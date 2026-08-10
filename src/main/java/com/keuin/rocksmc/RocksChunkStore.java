package com.keuin.rocksmc;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.util.math.ChunkPos;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.RocksDBException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A {@link ChunkStore} view over one dimension's slice of one column family.
 *
 * <p>Owns no native resources. The database handle, options, block cache, thread
 * pool and dimension registry all belong to {@link RocksDatabase}, which is shared
 * by every dimension and both leaves of a world -- see that class for why
 * consolidation was necessary. This class contributes the key prefix, the NBT
 * framing and its own IO counters, and nothing else.
 *
 * <p>Six of these exist for a three-dimension world, all pointing at one database.
 * {@link #close()} therefore releases a reference rather than closing anything;
 * only the last one closes the handle.
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
 * of its write volume.
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
 * <p>{@code dimension(4B) | mortonZ(x,z)(8B)}, big-endian. The dimension prefix is
 * load-bearing now that dimensions share a column family: it is the only thing
 * separating one dimension's chunks from another's. Putting it first also keeps
 * each dimension in a contiguous key range, which is what would make a future
 * per-dimension {@code DeleteRange} or bulk export cheap.
 *
 * <p>Interleaving the chunk coordinates preserves 2D locality in RocksDB's 1D
 * ordered keyspace, so a player walking through the world produces
 * near-sequential access and compaction keeps neighbouring chunks together.
 *
 * <p>Not thread-safe, matching the seam contract: vanilla serialises all access
 * through one {@code TaskExecutor} per storage instance.
 */
public final class RocksChunkStore implements ChunkStore {

    private final RocksDatabase database;
    private final ColumnFamilyHandle columnFamily;
    private final String columnFamilyName;
    private final String dimensionIdentity;
    private final String leaf;
    private final int dimensionId;
    private final boolean verifyOnRead;
    private boolean closed;

    private final AtomicLong reads = new AtomicLong();
    private final AtomicLong writes = new AtomicLong();
    private final AtomicLong bytesRead = new AtomicLong();
    private final AtomicLong bytesWritten = new AtomicLong();
    private final AtomicLong readFailures = new AtomicLong();
    private final AtomicLong writeFailures = new AtomicLong();
    private final AtomicLong verifyFailures = new AtomicLong();

    /**
     * Opens a view onto the world's shared database, opening that database if this
     * is the first store to need it.
     *
     * <p>This is the only constructor callers should use: taking the reference and
     * building the view together means the reference cannot be leaked by a failure
     * in between.
     *
     * @param dimension which dimension and leaf this store serves, and which world
     *                  it belongs to -- all derived from the save directory rather
     *                  than guessed from a path prefix
     */
    public static RocksChunkStore open(DimensionKey dimension, RocksMcConfig config)
            throws IOException {
        RocksDatabase database = RocksDatabase.open(dimension.root(), config);
        try {
            return new RocksChunkStore(database, dimension, config);
        } catch (IOException | RuntimeException e) {
            // The reference was taken above, so it must be given back or the
            // database would never close.
            try {
                database.release();
            } catch (IOException suppressed) {
                e.addSuppressed(suppressed);
            }
            throw e;
        }
    }

    private RocksChunkStore(RocksDatabase database, DimensionKey dimension,
            RocksMcConfig config) throws IOException {
        this.database = database;
        this.columnFamilyName = RocksDatabase.columnFamilyNameFor(dimension.leaf());
        this.columnFamily = database.columnFamilyFor(dimension.leaf());
        this.dimensionIdentity = dimension.identity();
        this.leaf = dimension.leaf();
        this.verifyOnRead = config.verifyOnRead();
        this.dimensionId = database.dimensionRegistry().ordinalFor(this.dimensionIdentity);
        StoreRegistry.register(this);
    }

    @Override
    public NbtCompound read(ChunkPos pos) throws IOException {
        byte[] value;
        try {
            value = this.database.handle().get(this.columnFamily, key(this.dimensionId, pos));
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
            this.database.handle().put(this.columnFamily, this.database.writeOptions(),
                chunkKey, value);
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
            actual = this.database.handle().get(this.columnFamily, chunkKey);
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

    /**
     * Makes prior writes durable, by syncing the shared WAL.
     *
     * <p>Vanilla calls this once per storage instance per autosave -- six times for
     * a three-dimension world -- and all six now reach the same WAL. It does not
     * flush memtables; see {@link RocksDatabase#syncWal()} for why that would cost
     * compaction work for no durability gain.
     */
    @Override
    public void sync() throws IOException {
        this.database.syncWal();
    }

    /** The shared database this store is a view onto. */
    public RocksDatabase database() {
        return this.database;
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
        k[0] = (byte) (dimensionId >>> 24);
        k[1] = (byte) (dimensionId >>> 16);
        k[2] = (byte) (dimensionId >>> 8);
        k[3] = (byte) dimensionId;
        for (int i = 0; i < 8; i++) {
            k[4 + i] = (byte) (morton >>> (8 * (7 - i)));
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
            "rocksmc[%s %s/%s ord=%d]: reads=%d (%d B), writes=%d (%d B)",
            this.database.name(), this.dimensionIdentity, this.columnFamilyName,
            this.dimensionId, this.reads.get(), this.bytesRead.get(),
            this.writes.get(), this.bytesWritten.get());
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
        return this.database.dimensionRegistry();
    }

    /**
     * A point-in-time view of this store's own IO, for logging and metrics.
     *
     * <p>Only counters this store genuinely owns. Everything shared -- SST sizes,
     * key estimates, compaction state, the block cache -- belongs to the column
     * family or the database and is reported by {@link RocksDatabase#snapshot()}
     * instead. Reporting those here would emit the same number once per store and
     * make any aggregate six times too large.
     *
     * <p>Read under no lock: the counters are atomics, so a scrape never blocks the
     * IO worker.
     */
    public Snapshot snapshot() {
        return new Snapshot(
            this.database.name(),
            this.dimensionIdentity,
            this.leaf,
            this.columnFamilyName,
            this.dimensionId,
            this.reads.get(),
            this.writes.get(),
            this.bytesRead.get(),
            this.bytesWritten.get(),
            this.readFailures.get(),
            this.writeFailures.get(),
            this.verifyFailures.get());
    }

    /** Immutable per-store metrics view. Field names map onto metric names. */
    public static final class Snapshot {
        public final String database;
        public final String dimension;
        public final String leaf;
        public final String columnFamily;
        public final int dimensionOrdinal;
        public final long reads;
        public final long writes;
        public final long bytesRead;
        public final long bytesWritten;
        public final long readFailures;
        public final long writeFailures;
        public final long verifyFailures;

        Snapshot(String database, String dimension, String leaf, String columnFamily,
                int dimensionOrdinal, long reads, long writes, long bytesRead,
                long bytesWritten, long readFailures, long writeFailures,
                long verifyFailures) {
            this.database = database;
            this.dimension = dimension;
            this.leaf = leaf;
            this.columnFamily = columnFamily;
            this.dimensionOrdinal = dimensionOrdinal;
            this.reads = reads;
            this.writes = writes;
            this.bytesRead = bytesRead;
            this.bytesWritten = bytesWritten;
            this.readFailures = readFailures;
            this.writeFailures = writeFailures;
            this.verifyFailures = verifyFailures;
        }
    }

    /**
     * Deregisters this view and drops its reference to the shared database.
     *
     * <p>Does not close the database unless this was the last store using it. The
     * guard against a second close is what keeps a double-close from decrementing
     * the shared count twice and releasing the handle while other dimensions are
     * still serving reads.
     */
    @Override
    public void close() throws IOException {
        if (this.closed) {
            return;
        }
        this.closed = true;
        StoreRegistry.deregister(this);
        this.database.release();
    }
}
