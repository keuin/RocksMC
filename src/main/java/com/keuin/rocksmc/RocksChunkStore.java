package com.keuin.rocksmc;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.util.math.ChunkPos;
import org.rocksdb.BlockBasedTableConfig;
import org.rocksdb.BloomFilter;
import org.rocksdb.Cache;
import org.rocksdb.Checkpoint;
import org.rocksdb.ColumnFamilyDescriptor;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.ColumnFamilyOptions;
import org.rocksdb.CompressionType;
import org.rocksdb.DBOptions;
import org.rocksdb.FlushOptions;
import org.rocksdb.LRUCache;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.WriteOptions;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A {@link ChunkStore} backed by RocksDB with key-value separation.
 *
 * <h2>Design notes, with the measurements behind them</h2>
 *
 * <p><b>Blob files are on; the setting is a near-symmetric tradeoff.</b> Real
 * uncompressed chunk NBT averages ~19-51 KiB. Measured at real LSM depth with the
 * WAL counted (Phase 1c), key-value separation writes <b>8.5% fewer bytes</b> but
 * stores <b>8.6% more on disk</b> than keeping values in the LSM -- because blob
 * files ignore the compression level and dictionary settings entirely, so the LSM
 * arm compresses better. Either choice is defensible: keep blobs on where SSD
 * endurance matters, raise {@code min_blob_size} above chunk size where storage
 * does.
 *
 * <p>An earlier comment here cited a 316x compaction reduction. That was measured
 * on an 11.2 MB database with no LSM levels populated and was wrong by more than
 * two orders of magnitude; the real ratio is 1.35-1.51x. See
 * spike/phase1c-endurance/RESULTS.md.
 *
 * <p><b>The dominant endurance factor is not this setting.</b> {@code sync-writes}
 * moves kernel-observed writes by 3.65x while barely changing RocksDB's own
 * counters, because an fsync per write forces partial blocks and filesystem
 * metadata to disk. Over five years that flag costs roughly 22x the entire
 * blob-versus-LSM difference.
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

    /**
     * Meta-CF key holding the on-disk format version.
     *
     * <p>Prefixed with NUL so it can never collide with a dimension identity, which
     * is always a printable namespaced string.
     */
    private static final String FORMAT_VERSION_KEY = "\u0000format";

    private final RocksDB db;
    private final DBOptions dbOptions;
    private final ColumnFamilyOptions defaultCfOptions;
    private final ColumnFamilyOptions metaCfOptions;
    private final List<ColumnFamilyHandle> cfHandles = new ArrayList<>();
    private final WriteOptions writeOptions;
    private final BloomFilter bloomFilter;
    private final Cache blockCache;
    private final File path;
    private final DimensionRegistry dimensionRegistry;
    private final String dimensionIdentity;
    private final String leaf;
    private final int dimensionId;
    private final boolean verifyOnRead;

    private final AtomicLong reads = new AtomicLong();
    private final AtomicLong writes = new AtomicLong();
    private final AtomicLong bytesRead = new AtomicLong();
    private final AtomicLong bytesWritten = new AtomicLong();
    private final AtomicLong readFailures = new AtomicLong();
    private final AtomicLong writeFailures = new AtomicLong();
    private final AtomicLong verifyFailures = new AtomicLong();

    /**
     * @param path     database directory; created if absent
     * @param dimension the dimension whose chunks this store holds, derived from
     *                  the save directory rather than guessed from a path prefix
     * @param config   tuning; see {@link RocksMcConfig}
     */
    public RocksChunkStore(File path, DimensionKey dimension, RocksMcConfig config)
            throws IOException {
        this.path = path;
        this.dimensionIdentity = dimension.identity();
        this.leaf = dimension.leaf();
        this.verifyOnRead = config.verifyOnRead();

        if (!path.exists() && !path.mkdirs()) {
            throw new IOException("Could not create RocksDB directory: " + path);
        }

        this.bloomFilter = new BloomFilter(10);
        this.blockCache = new LRUCache(config.blockCacheSize());
        BlockBasedTableConfig tableConfig = new BlockBasedTableConfig()
            .setFilterPolicy(this.bloomFilter)
            .setBlockCache(this.blockCache)
            // Chunk reads are single-key point lookups, never scans, so bias the
            // index for lookup speed.
            .setCacheIndexAndFilterBlocks(true)
            .setPinL0FilterAndIndexBlocksInCache(true);

        this.defaultCfOptions = new ColumnFamilyOptions()
            .setCompressionType(CompressionType.ZSTD_COMPRESSION)
            .setBottommostCompressionType(CompressionType.ZSTD_COMPRESSION)
            .setTableFormatConfig(tableConfig)
            // Key-value separation. Chunk values are tens of KiB, so without this
            // leveled compaction would rewrite them repeatedly.
            .setEnableBlobFiles(true)
            .setMinBlobSize(config.minBlobSize())
            .setBlobCompressionType(CompressionType.ZSTD_COMPRESSION)
            .setEnableBlobGarbageCollection(true)
            // Reclaim space from overwritten blobs rather than growing without
            // bound, which is precisely the failure mode Anvil's never-compacted
            // sector allocator has.
            .setBlobGarbageCollectionAgeCutoff(0.25)
            .setBlobGarbageCollectionForceThreshold(0.5)
            // Larger memtables coalesce more repeated saves of the same hot chunk
            // before any of it reaches disk, which is the dominant write pattern on
            // a technical server.
            .setWriteBufferSize(config.writeBufferSize())
            .setMaxWriteBufferNumber(config.maxWriteBufferNumber())
            // Raised from the default 8: fast storage drains L0 quickly, so
            // throttling writes early costs tick time for no benefit.
            .setLevel0SlowdownWritesTrigger(config.level0SlowdownTrigger());

        // The dimension registry holds a handful of tiny entries. Blob files and
        // heavy compression would be pure overhead there.
        this.metaCfOptions = new ColumnFamilyOptions()
            .setCompressionType(CompressionType.NO_COMPRESSION);

        this.dbOptions = new DBOptions()
            .setCreateIfMissing(true)
            .setCreateMissingColumnFamilies(true)
            .setMaxBackgroundJobs(config.maxBackgroundJobs())
            .setMaxSubcompactions(config.maxSubcompactions())
            // Trickle writeback out in small increments instead of letting it pile
            // up until file close, which otherwise shows up as a tick stall.
            .setBytesPerSync(config.bytesPerSync())
            .setWalBytesPerSync(config.bytesPerSync());

        this.writeOptions = new WriteOptions().setSync(config.syncWrites());

        List<ColumnFamilyDescriptor> descriptors = new ArrayList<>();
        descriptors.add(new ColumnFamilyDescriptor(
            RocksDB.DEFAULT_COLUMN_FAMILY, this.defaultCfOptions));
        descriptors.add(new ColumnFamilyDescriptor(
            DimensionRegistry.CF_NAME.getBytes(StandardCharsets.UTF_8), this.metaCfOptions));

        try {
            this.db = RocksDB.open(this.dbOptions, path.getAbsolutePath(),
                descriptors, this.cfHandles);
        } catch (RocksDBException e) {
            closeQuietly();
            throw new IOException("Failed to open RocksDB at " + path, e);
        }

        try {
            this.dimensionRegistry = new DimensionRegistry(this.db, this.cfHandles.get(1));
            // Before anything is read or written, confirm the on-disk layout is one
            // this build understands. Without this an encoding change would silently
            // reinterpret existing keys, which is unrecoverable.
            checkFormatVersion();
            this.dimensionId = this.dimensionRegistry.ordinalFor(this.dimensionIdentity);
        } catch (IOException e) {
            // An unusable registry means keys cannot be interpreted, so refuse to
            // serve rather than write chunks under an unknown dimension id.
            this.db.close();
            closeQuietly();
            throw e;
        }

        StoreRegistry.register(this);
    }

    /**
     * Reads, or on first open writes, the on-disk format version.
     *
     * <p>{@link #FORMAT_VERSION} covers the key encoding and value framing. A
     * database written by a different version cannot be read safely, so a mismatch
     * aborts rather than guessing. There is no migration: the mod is alpha and
     * worlds are expected to be rebuilt.
     */
    private void checkFormatVersion() throws IOException {
        byte[] key = FORMAT_VERSION_KEY.getBytes(StandardCharsets.UTF_8);
        ColumnFamilyHandle meta = this.cfHandles.get(1);
        try {
            byte[] stored = this.db.get(meta, key);
            if (stored == null) {
                this.db.put(meta, key, String.valueOf(FORMAT_VERSION)
                    .getBytes(StandardCharsets.UTF_8));
                return;
            }
            int found = Integer.parseInt(new String(stored, StandardCharsets.UTF_8).trim());
            if (found != FORMAT_VERSION) {
                throw new IOException("rocksmc: database at " + this.path
                    + " was written with on-disk format version " + found
                    + ", but this build only understands version " + FORMAT_VERSION
                    + ". There is no migration path; the world must be re-imported.");
            }
        } catch (RocksDBException e) {
            throw new IOException("failed to read format version at " + this.path, e);
        } catch (NumberFormatException e) {
            throw new IOException("corrupt format version marker at " + this.path, e);
        }
    }

    /** The column family holding chunk data. */
    private ColumnFamilyHandle dataCf() {
        return this.cfHandles.get(0);
    }

    @Override
    public NbtCompound read(ChunkPos pos) throws IOException {
        byte[] value;
        try {
            value = this.db.get(dataCf(), key(this.dimensionId, pos));
        } catch (RocksDBException e) {
            this.readFailures.incrementAndGet();
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
        byte[] chunkKey = key(this.dimensionId, pos);

        try {
            this.db.put(dataCf(), this.writeOptions, chunkKey, value);
        } catch (RocksDBException e) {
            this.writeFailures.incrementAndGet();
            throw new IOException("RocksDB write failed for " + pos, e);
        }
        this.writes.incrementAndGet();
        this.bytesWritten.addAndGet(value.length);

        if (this.verifyOnRead) {
            verifyRoundTrip(pos, chunkKey, value);
        }
    }

    /**
     * Diagnostic: read the value straight back and compare bytes.
     *
     * <p>Roughly halves write throughput, so it is off by default. Its purpose is
     * to catch a corrupting bug close to where it happens rather than hours later
     * when a player reports missing terrain, which makes it worth having during a
     * beta.
     *
     * <p>Compares the serialised bytes rather than parsed NBT: this is checking the
     * storage layer, so an exact byte match is the right assertion here even though
     * the fidelity harness deliberately uses semantic equality instead.
     */
    private void verifyRoundTrip(ChunkPos pos, byte[] chunkKey, byte[] expected)
            throws IOException {
        byte[] actual;
        try {
            actual = this.db.get(dataCf(), chunkKey);
        } catch (RocksDBException e) {
            this.verifyFailures.incrementAndGet();
            throw new IOException("verify-on-read: could not re-read " + pos, e);
        }
        if (actual == null) {
            this.verifyFailures.incrementAndGet();
            throw new IOException("verify-on-read: " + pos + " vanished immediately "
                + "after being written");
        }
        if (!java.util.Arrays.equals(expected, actual)) {
            this.verifyFailures.incrementAndGet();
            throw new IOException("verify-on-read: " + pos + " read back "
                + actual.length + " bytes, expected " + expected.length
                + " -- storage layer is corrupting data");
        }
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
     * Compacts the whole keyspace, merging L0 files and collecting obsolete blobs.
     *
     * <p>Not something the server needs -- RocksDB compacts in the background --
     * but measurement harnesses must call it before sizing the database. Without
     * it, un-merged L0 files and unreferenced blobs remain on disk and inflate the
     * apparent footprint.
     */
    public void compact() throws IOException {
        try {
            this.db.compactRange();
        } catch (RocksDBException e) {
            throw new IOException("RocksDB compaction failed", e);
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
            "rocksmc[%s %s ord=%d]: reads=%d (%d B), writes=%d (%d B), formatVersion=%d",
            this.path.getName(), this.dimensionIdentity, this.dimensionId,
            this.reads.get(), this.bytesRead.get(),
            this.writes.get(), this.bytesWritten.get(), FORMAT_VERSION);
    }

    /** The dimension this store holds, e.g. {@code twilightforest:twilight_forest}. */
    public String dimensionIdentity() {
        return this.dimensionIdentity;
    }

    /** The ordinal assigned to {@link #dimensionIdentity()} by the registry. */
    public int dimensionOrdinal() {
        return this.dimensionId;
    }

    /** Visible for tests: the persisted identity-to-ordinal mapping. */
    public DimensionRegistry dimensionRegistry() {
        return this.dimensionRegistry;
    }

    public long liveDataSize() {
        return longProperty("rocksdb.live-sst-files-size");
    }

    /**
     * A point-in-time view of this store, for logging and metrics.
     *
     * <p>Read under no lock: the counters are atomics and the RocksDB properties
     * are already approximate, so a scrape never blocks the IO worker. Values may
     * be very slightly inconsistent with each other, which is the right trade for
     * observability.
     */
    public Snapshot snapshot() {
        return new Snapshot(
            this.path.getName(),
            this.dimensionIdentity,
            this.leaf,
            this.dimensionId,
            this.reads.get(),
            this.writes.get(),
            this.bytesRead.get(),
            this.bytesWritten.get(),
            this.readFailures.get(),
            this.writeFailures.get(),
            this.verifyFailures.get(),
            longProperty("rocksdb.live-sst-files-size"),
            longProperty("rocksdb.total-sst-files-size"),
            longProperty("rocksdb.estimate-num-keys"),
            longProperty("rocksdb.estimate-pending-compaction-bytes"),
            longProperty("rocksdb.num-running-compactions"),
            longProperty("rocksdb.num-running-flushes"),
            longProperty("rocksdb.mem-table-flush-pending"),
            longProperty("rocksdb.compaction-pending"),
            longProperty("rocksdb.actual-delayed-write-rate"),
            longProperty("rocksdb.is-write-stopped"),
            longProperty("rocksdb.block-cache-usage"),
            longProperty("rocksdb.size-all-mem-tables"),
            blobFileBytes());
    }

    private long longProperty(String name) {
        try {
            return this.db.getLongProperty(name);
        } catch (RocksDBException e) {
            return -1L;
        }
    }

    /**
     * Total size of blob files on disk.
     *
     * <p>RocksDB exposes no property for this, and blob storage is where nearly all
     * chunk bytes live, so it is measured from the filesystem. Cheap: a handful of
     * large files per store.
     */
    private long blobFileBytes() {
        File[] files = this.path.listFiles((d, n) -> n.endsWith(".blob"));
        if (files == null) {
            return -1L;
        }
        long total = 0L;
        for (File f : files) {
            total += f.length();
        }
        return total;
    }

    /** Immutable metrics view. Field names map directly onto metric names. */
    public static final class Snapshot {
        public final String database;
        public final String dimension;
        public final String leaf;
        public final int dimensionOrdinal;
        public final long reads;
        public final long writes;
        public final long bytesRead;
        public final long bytesWritten;
        public final long readFailures;
        public final long writeFailures;
        public final long verifyFailures;
        public final long liveSstBytes;
        public final long totalSstBytes;
        public final long estimatedKeys;
        public final long pendingCompactionBytes;
        public final long runningCompactions;
        public final long runningFlushes;
        public final long memtableFlushPending;
        public final long compactionPending;
        public final long delayedWriteRate;
        public final long writeStopped;
        public final long blockCacheBytes;
        public final long memtableBytes;
        public final long blobFileBytes;

        Snapshot(String database, String dimension, String leaf, int dimensionOrdinal,
                long reads, long writes, long bytesRead, long bytesWritten,
                long readFailures, long writeFailures, long verifyFailures,
                long liveSstBytes, long totalSstBytes, long estimatedKeys,
                long pendingCompactionBytes, long runningCompactions, long runningFlushes,
                long memtableFlushPending, long compactionPending, long delayedWriteRate,
                long writeStopped, long blockCacheBytes, long memtableBytes,
                long blobFileBytes) {
            this.database = database;
            this.dimension = dimension;
            this.leaf = leaf;
            this.dimensionOrdinal = dimensionOrdinal;
            this.reads = reads;
            this.writes = writes;
            this.bytesRead = bytesRead;
            this.bytesWritten = bytesWritten;
            this.readFailures = readFailures;
            this.writeFailures = writeFailures;
            this.verifyFailures = verifyFailures;
            this.liveSstBytes = liveSstBytes;
            this.totalSstBytes = totalSstBytes;
            this.estimatedKeys = estimatedKeys;
            this.pendingCompactionBytes = pendingCompactionBytes;
            this.runningCompactions = runningCompactions;
            this.runningFlushes = runningFlushes;
            this.memtableFlushPending = memtableFlushPending;
            this.compactionPending = compactionPending;
            this.delayedWriteRate = delayedWriteRate;
            this.writeStopped = writeStopped;
            this.blockCacheBytes = blockCacheBytes;
            this.memtableBytes = memtableBytes;
            this.blobFileBytes = blobFileBytes;
        }
    }

    @Override
    public void close() throws IOException {
        StoreRegistry.deregister(this);
        IOException failure = null;
        try {
            sync();
        } catch (IOException e) {
            failure = e;
        }
        // Column family handles must be released before the database itself.
        for (ColumnFamilyHandle handle : this.cfHandles) {
            handle.close();
        }
        this.cfHandles.clear();
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
        if (this.dbOptions != null) {
            this.dbOptions.close();
        }
        if (this.defaultCfOptions != null) {
            this.defaultCfOptions.close();
        }
        if (this.metaCfOptions != null) {
            this.metaCfOptions.close();
        }
        if (this.bloomFilter != null) {
            this.bloomFilter.close();
        }
        // Released after the options that reference it, or RocksDB may touch a
        // freed cache during teardown.
        if (this.blockCache != null) {
            this.blockCache.close();
        }
    }
}
