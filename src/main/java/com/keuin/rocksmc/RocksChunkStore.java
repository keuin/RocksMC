package com.keuin.rocksmc;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.util.math.ChunkPos;
import org.rocksdb.BlockBasedTableConfig;
import org.rocksdb.BloomFilter;
import org.rocksdb.Checkpoint;
import org.rocksdb.CompressionType;
import org.rocksdb.FlushOptions;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.WriteOptions;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A {@link ChunkStore} backed by RocksDB with key-value separation.
 *
 * <h2>Design notes, with the measurements behind them</h2>
 *
 * <p><b>Blob files are on.</b> Real uncompressed chunk NBT averages ~51 KiB
 * (Phase 1a, measured from generated worlds). At that size leveled compaction
 * would repeatedly rewrite large values; key-value separation cut compaction
 * traffic by 316x in measurement, so blobs are not optional here.
 *
 * <p><b>NBT is stored uncompressed at the application layer</b> and compressed by
 * the engine instead. Honest caveat: this is <em>not</em> a compression win.
 * Vanilla's per-chunk DEFLATE achieves 14.56x on real chunks; the best RocksDB
 * configuration measured 14.07x, i.e. about 3.5% larger on disk. The reason to
 * do it anyway is bytes written: vanilla rewrites the entire 8 KiB region header
 * on every chunk save, which for a mean 3.5 KiB compressed chunk is roughly 70%
 * of its write volume. RocksDB measured 0.32x vanilla bytes written.
 *
 * <p><b>ZSTD, not LZ4.</b> LZ4 measured 9.25x against ZSTD's 13.93x on real
 * chunks -- about 57% more disk. An earlier plan to prefer LZ4 "for speed" was
 * based on synthetic data and was wrong.
 *
 * <p><b>Trained dictionaries are not configured.</b> RocksDB blob files ignore
 * them (Phase 0: byte-identical output with them on and off), and on 51 KiB
 * values intra-value redundancy dominates any cross-value sharing anyway.
 *
 * <h2>Key encoding</h2>
 *
 * <p>{@code dimension(4B) | mortonZ(x,z)(8B)}, big-endian. Interleaving the
 * chunk coordinates preserves 2D locality in RocksDB's 1D ordered keyspace, so a
 * player walking through the world produces near-sequential access and compaction
 * keeps neighbouring chunks together.
 *
 * <p>Not thread-safe, matching the seam contract: vanilla serialises all access
 * through one {@code TaskExecutor} per storage instance.
 */
public final class RocksChunkStore implements ChunkStore {

    static {
        RocksDB.loadLibrary();
    }

    /** Bumped if the key encoding or value framing ever changes. */
    private static final int FORMAT_VERSION = 1;

    private final RocksDB db;
    private final Options options;
    private final WriteOptions writeOptions;
    private final BloomFilter bloomFilter;
    private final File path;
    private final int dimensionId;

    private final AtomicLong reads = new AtomicLong();
    private final AtomicLong writes = new AtomicLong();
    private final AtomicLong bytesRead = new AtomicLong();
    private final AtomicLong bytesWritten = new AtomicLong();

    /**
     * @param path        database directory; created if absent
     * @param dimensionId distinguishes dimensions sharing one database
     * @param config      tuning; see {@link RocksMcConfig}
     */
    public RocksChunkStore(File path, int dimensionId, RocksMcConfig config) throws IOException {
        this.path = path;
        this.dimensionId = dimensionId;

        if (!path.exists() && !path.mkdirs()) {
            throw new IOException("Could not create RocksDB directory: " + path);
        }

        this.bloomFilter = new BloomFilter(10);
        BlockBasedTableConfig tableConfig = new BlockBasedTableConfig()
            .setFilterPolicy(this.bloomFilter)
            // Chunk reads are single-key point lookups, never scans, so bias the
            // index for lookup speed.
            .setCacheIndexAndFilterBlocks(true)
            .setPinL0FilterAndIndexBlocksInCache(true);

        this.options = new Options()
            .setCreateIfMissing(true)
            .setCompressionType(CompressionType.ZSTD_COMPRESSION)
            .setBottommostCompressionType(CompressionType.ZSTD_COMPRESSION)
            .setTableFormatConfig(tableConfig)
            // Key-value separation: the whole point. Without it, compaction
            // rewrites ~51 KiB values repeatedly.
            .setEnableBlobFiles(true)
            .setMinBlobSize(config.minBlobSize())
            .setBlobCompressionType(CompressionType.ZSTD_COMPRESSION)
            .setEnableBlobGarbageCollection(true)
            // Reclaim space from overwritten blobs rather than growing without
            // bound, which is precisely the failure mode Anvil's never-compacted
            // sector allocator has.
            .setBlobGarbageCollectionAgeCutoff(0.25)
            .setBlobGarbageCollectionForceThreshold(0.5);

        this.writeOptions = new WriteOptions().setSync(config.syncWrites());

        try {
            this.db = RocksDB.open(this.options, path.getAbsolutePath());
        } catch (RocksDBException e) {
            closeQuietly();
            throw new IOException("Failed to open RocksDB at " + path, e);
        }
    }

    @Override
    public NbtCompound read(ChunkPos pos) throws IOException {
        byte[] value;
        try {
            value = this.db.get(key(this.dimensionId, pos));
        } catch (RocksDBException e) {
            throw new IOException("RocksDB read failed for " + pos, e);
        }
        if (value == null) {
            return null;
        }
        this.reads.incrementAndGet();
        this.bytesRead.addAndGet(value.length);

        // NbtIo.read expects an uncompressed NBT stream, which is exactly what we
        // stored: the engine handles compression underneath.
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(value))) {
            return NbtIo.read(in);
        }
    }

    @Override
    public void write(ChunkPos pos, NbtCompound nbt) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(64 * 1024);
        try (DataOutputStream out = new DataOutputStream(buffer)) {
            NbtIo.write(nbt, out);
        }
        byte[] value = buffer.toByteArray();

        try {
            this.db.put(this.writeOptions, key(this.dimensionId, pos), value);
        } catch (RocksDBException e) {
            throw new IOException("RocksDB write failed for " + pos, e);
        }
        this.writes.incrementAndGet();
        this.bytesWritten.addAndGet(value.length);
    }

    @Override
    public void sync() throws IOException {
        try (FlushOptions flushOptions = new FlushOptions().setWaitForFlush(true)) {
            this.db.flush(flushOptions);
            // Flush alone leaves the WAL unsynced when setSync(false) is in use.
            this.db.flushWal(true);
        } catch (RocksDBException e) {
            throw new IOException("RocksDB flush failed", e);
        }
    }

    /**
     * Creates a consistent, application-level snapshot via hard links.
     *
     * <p>This is the capability Anvil cannot offer at all. A filesystem snapshot
     * of a live Anvil world is only crash-consistent and may capture a torn 8 KiB
     * header, which -- with no write-ahead log -- is silently unrecoverable. A
     * checkpoint here is consistent by construction and needs no server pause.
     *
     * @param target destination directory; must not already exist
     */
    public void checkpoint(File target) throws IOException {
        try (Checkpoint cp = Checkpoint.create(this.db)) {
            cp.createCheckpoint(target.getAbsolutePath());
        } catch (RocksDBException e) {
            throw new IOException("Checkpoint to " + target + " failed", e);
        }
    }

    /**
     * Key layout: {@code dimension(4B) | morton(x,z)(8B)}, big-endian.
     *
     * <p>Big-endian keeps RocksDB's lexicographic byte ordering consistent with
     * numeric ordering, and Morton interleaving preserves spatial locality.
     */
    static byte[] key(int dimensionId, ChunkPos pos) {
        long morton = morton(pos.x, pos.z);
        byte[] k = new byte[12];
        k[0] = (byte)(dimensionId >>> 24);
        k[1] = (byte)(dimensionId >>> 16);
        k[2] = (byte)(dimensionId >>> 8);
        k[3] = (byte)dimensionId;
        for (int i = 0; i < 8; i++) {
            k[4 + i] = (byte)(morton >>> (8 * (7 - i)));
        }
        return k;
    }

    /**
     * Interleaves the low 32 bits of x and z into a 64-bit Morton (Z-order) code.
     *
     * <p>Coordinates are biased by {@link Integer#MIN_VALUE} first so that
     * negative chunk coordinates -- which are entirely normal in Minecraft -- sort
     * before positive ones under unsigned byte comparison.
     */
    static long morton(int x, int z) {
        return (spread(x ^ Integer.MIN_VALUE) << 1) | spread(z ^ Integer.MIN_VALUE);
    }

    /** Spreads the 32 bits of {@code v} into the even bit positions of a long. */
    private static long spread(int v) {
        long r = v & 0xFFFFFFFFL;
        r = (r | (r << 16)) & 0x0000FFFF0000FFFFL;
        r = (r | (r << 8)) & 0x00FF00FF00FF00FFL;
        r = (r | (r << 4)) & 0x0F0F0F0F0F0F0F0FL;
        r = (r | (r << 2)) & 0x3333333333333333L;
        r = (r | (r << 1)) & 0x5555555555555555L;
        return r;
    }

    public String statsSummary() {
        return String.format(
            "rocksmc[%s dim=%d]: reads=%d (%d B), writes=%d (%d B), formatVersion=%d",
            this.path.getName(), this.dimensionId, this.reads.get(), this.bytesRead.get(),
            this.writes.get(), this.bytesWritten.get(), FORMAT_VERSION);
    }

    public long liveDataSize() {
        try {
            return this.db.getLongProperty("rocksdb.live-sst-files-size");
        } catch (RocksDBException e) {
            return -1L;
        }
    }

    @Override
    public void close() throws IOException {
        IOException failure = null;
        try {
            sync();
        } catch (IOException e) {
            failure = e;
        }
        if (this.db != null) {
            this.db.close();
        }
        closeQuietly();
        if (failure != null) {
            throw failure;
        }
    }

    private void closeQuietly() {
        if (this.writeOptions != null) {
            this.writeOptions.close();
        }
        if (this.options != null) {
            this.options.close();
        }
        if (this.bloomFilter != null) {
            this.bloomFilter.close();
        }
    }
}
