package com.keuin.rocksmc;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.util.math.ChunkPos;
import org.rocksdb.ColumnFamilyDescriptor;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.DBOptions;
import org.rocksdb.ReadOptions;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
import org.rocksdb.Slice;
import org.rocksdb.Snapshot;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Exports a RocksDB world back into Anvil region files.
 *
 * <h2>Why this exists</h2>
 *
 * <p>{@code .mca} is the interchange format for the entire Minecraft ecosystem: Amulet,
 * Chunker, BlueMap, Dynmap, MCA Selector, every pregenerator and every world editor.
 * Storing chunks in RocksDB locks all of it out, and until now the only way back was to
 * keep the original {@code .mca} files forever. This turns that from a permanent
 * storage tax into an operation, and it is the rollback path that makes deleting them
 * safe.
 *
 * <h2>⚠️ Consistency: read-only, and from a snapshot</h2>
 *
 * <p>The database is opened <b>read-only</b>. That is the single most valuable property
 * for a tool pointed at a world someone cares about: it cannot modify the source, by
 * construction rather than by care.
 *
 * <p>The scan also runs against a RocksDB {@link Snapshot}, so every dimension and both
 * column families are read at one instant. Without it an iterator can observe writes
 * that land mid-scan, and the result would be an internally inconsistent world -- a
 * chunk from one tick beside its POI from another. That is exactly the incoherence the
 * one-database-per-world consolidation was done to eliminate, and it would be a shame
 * to reintroduce it on the way out.
 *
 * <p>A read-only open does <b>not</b> take RocksDB's exclusive lock, so this can run
 * while a server is up. Doing so is not recommended and the caller is warned: a
 * read-only handle sees the manifest and SST files as they are on disk and cannot see
 * the live server's memtables, so recent chunks may be missing. The supported route for
 * a running server is {@code /rocksmc checkpoint} followed by an export of that
 * checkpoint, which is consistent by construction -- checkpoints are why they exist.
 *
 * <h2>Grouping and parallelism</h2>
 *
 * <p>One task per region file, exactly as {@link WorldImporter} does, using the
 * key-range property proved in {@link ChunkKeyCodec}: a region's 1024 chunks are 1024
 * consecutive Morton codes, so each region is an exact, bounded key range that a worker
 * can scan alone. No sorting, no coordination, and one {@link AnvilWriter} per output
 * file -- which matters because each {@code RegionFile} owns an independent sector
 * allocator and two on one path would corrupt each other.
 *
 * <p>Which regions exist is discovered by one cheap pass over each dimension's keys
 * before any writing, so the work can be divided up front.
 *
 * <h2>Verification</h2>
 *
 * <p>Every written region is read back with {@link AnvilReader} and compared, because an
 * export that silently dropped terrain would only be discovered by whatever tool was
 * meant to consume it. The reader is an independent implementation, which is what makes
 * the check worth anything.
 */
public final class WorldExporter {

    /** Outcome for one dimension and leaf. */
    public static final class DimensionResult {
        public final String label;
        public final String dimension;
        public long elapsedMs;

        private final AtomicInteger regionCount = new AtomicInteger();
        private final AtomicInteger chunkCount = new AtomicInteger();
        private final AtomicInteger verifiedCount = new AtomicInteger();
        private final AtomicInteger externalCount = new AtomicInteger();
        private final AtomicInteger mismatchCount = new AtomicInteger();
        private final AtomicLong byteCount = new AtomicLong();
        private final AtomicInteger minDataVersion = new AtomicInteger(Integer.MAX_VALUE);
        private final AtomicInteger maxDataVersion = new AtomicInteger(Integer.MIN_VALUE);
        private final List<String> failureList =
            Collections.synchronizedList(new ArrayList<>());

        DimensionResult(String label, String dimension) {
            this.label = label;
            this.dimension = dimension;
        }

        public int regions() {
            return this.regionCount.get();
        }

        public int chunks() {
            return this.chunkCount.get();
        }

        public int verified() {
            return this.verifiedCount.get();
        }

        public int externalChunks() {
            return this.externalCount.get();
        }

        public int mismatches() {
            return this.mismatchCount.get();
        }

        public long bytes() {
            return this.byteCount.get();
        }

        /**
         * Lowest and highest {@code DataVersion} written, or {@code null} if none.
         *
         * <p>Reported because the import path is byte-transparent -- no DataFixer runs
         * below the seam -- so a database imported from an older world can legitimately
         * hold chunks at mixed versions. Vanilla copes; third-party tools vary. Saying
         * so in one line of output turns a mystery bug report into a known condition.
         */
        public int[] dataVersionRange() {
            int min = this.minDataVersion.get();
            int max = this.maxDataVersion.get();
            return min == Integer.MAX_VALUE ? null : new int[] {min, max};
        }

        public List<String> failures() {
            synchronized (this.failureList) {
                return new ArrayList<>(this.failureList);
            }
        }

        void addFailure(String failure) {
            synchronized (this.failureList) {
                if (this.failureList.size() < 20) {
                    this.failureList.add(failure);
                }
            }
        }

        public boolean clean() {
            return mismatches() == 0 && this.failureList.isEmpty()
                && verified() == chunks();
        }
    }

    public static final class Result {
        public final List<DimensionResult> dimensions = new ArrayList<>();

        public int totalChunks() {
            int n = 0;
            for (DimensionResult d : this.dimensions) {
                n += d.chunks();
            }
            return n;
        }

        public boolean clean() {
            for (DimensionResult d : this.dimensions) {
                if (!d.clean()) {
                    return false;
                }
            }
            return true;
        }
    }

    public interface Progress {
        void update(String label, int chunksDone, int regionsDone, int regionsTotal);
    }

    /**
     * Exports every dimension in a database to Anvil files under {@code outputDir}.
     *
     * @param databaseDir the RocksDB directory: normally {@code <world>/rocksmc.db},
     *                    or a checkpoint, which is a complete database in its own right
     * @param outputDir   the world directory to write; {@code region/} and {@code poi/}
     *                    are created under it in vanilla's own layout
     * @param overwrite   write into an output directory that already holds {@code .mca}
     * @param threads     parallel region workers, at least 1
     */
    public static Result exportWorld(File databaseDir, File outputDir, boolean overwrite,
            Progress progress, int threads) throws IOException {
        Result result = new Result();

        if (!new File(databaseDir, "CURRENT").isFile()) {
            throw new IOException("not a RocksDB database: " + databaseDir
                + " (expected a CURRENT file). For a world, point at "
                + "<world>/" + RocksDatabase.DIRECTORY_NAME + "; for a checkpoint, at "
                + "the checkpoint directory itself.");
        }
        if (!overwrite && containsRegionFiles(outputDir)) {
            throw new IOException("refusing to write into " + outputDir
                + ": it already contains .mca files. Vanilla's writer merges into an "
                + "existing region file rather than replacing it, so a partial export "
                + "over a different world would silently interleave two worlds. Pass "
                + "--overwrite if merging is what you want, or choose an empty "
                + "directory.");
        }

        try (ExportSource source = ExportSource.open(databaseDir)) {
            Map<String, Integer> dimensions = new TreeMap<>(source.dimensionOrdinals());
            if (dimensions.isEmpty()) {
                throw new IOException("no dimensions registered in " + databaseDir
                    + " -- is this a rocksmc database?");
            }

            int workers = Math.max(1, threads);
            java.util.concurrent.ExecutorService pool = workers == 1 ? null
                : java.util.concurrent.Executors.newFixedThreadPool(workers,
                    runnable -> {
                        Thread t = new Thread(runnable, "rocksmc-export");
                        t.setDaemon(true);
                        return t;
                    });
            try {
                for (Map.Entry<String, Integer> entry : dimensions.entrySet()) {
                    for (String leaf : new String[] {"region", "poi"}) {
                        result.dimensions.add(exportOne(source, entry.getKey(),
                            entry.getValue(), leaf, outputDir, progress, pool));
                    }
                }
            } finally {
                if (pool != null) {
                    pool.shutdownNow();
                }
            }
        }
        return result;
    }

    /** Exports one dimension's one leaf. */
    private static DimensionResult exportOne(ExportSource source, String identity,
            int ordinal, String leaf, File outputDir, Progress progress,
            java.util.concurrent.ExecutorService pool) throws IOException {
        String label = relativeLeafPath(identity, leaf);
        DimensionResult out = new DimensionResult(label, identity);
        long start = System.nanoTime();

        File leafDir = new File(outputDir, label);
        ColumnFamilyHandle cf = source.columnFamily(leaf);

        // One cheap pass to learn which regions exist, so the work can be divided
        // before anything is written.
        List<long[]> regions = source.regionsOf(cf, ordinal);
        out.regionCount.set(regions.size());
        if (regions.isEmpty()) {
            out.elapsedMs = (System.nanoTime() - start) / 1_000_000L;
            return out;
        }

        AtomicInteger regionsDone = new AtomicInteger();
        RegionTask task = region -> {
            exportRegion(source, cf, ordinal, (int) region[0], (int) region[1],
                leafDir, out);
            if (progress != null) {
                progress.update(label, out.chunks(), regionsDone.incrementAndGet(),
                    regions.size());
            }
        };

        if (pool == null) {
            for (long[] region : regions) {
                task.run(region);
            }
        } else {
            List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
            for (long[] region : regions) {
                futures.add(pool.submit(() -> {
                    task.run(region);
                    return null;
                }));
            }
            IOException failure = null;
            for (java.util.concurrent.Future<?> future : futures) {
                try {
                    future.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("export interrupted", e);
                } catch (java.util.concurrent.ExecutionException e) {
                    Throwable cause = e.getCause();
                    if (failure == null) {
                        failure = cause instanceof IOException
                            ? (IOException) cause
                            : new IOException("region export failed", cause);
                    } else {
                        failure.addSuppressed(cause);
                    }
                }
            }
            if (failure != null) {
                throw failure;
            }
        }

        out.elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        return out;
    }

    /**
     * Writes one region file, then reads it back and compares.
     *
     * <p>The verify pass is in the same task as the write so a region is proved before
     * the worker moves on, and so the comparison reads the file as it was closed --
     * after the sector padding and the force to disk that {@code close} performs.
     */
    private static void exportRegion(ExportSource source, ColumnFamilyHandle cf,
            int ordinal, int regionX, int regionZ, File leafDir, DimensionResult out)
            throws IOException {
        File regionFile = new File(leafDir, "r." + regionX + "." + regionZ + ".mca");
        // Position to hash of the serialised NBT, not the NBT itself. Holding a
        // region's parsed chunks would be roughly 150 MB per worker at 1024 chunks,
        // which across a full thread pool is gigabytes -- the same trap the importer
        // avoids by streaming. A hash is 32 bytes and the comparison is byte-exact.
        Map<ChunkPos, String> writtenHashes = new TreeMap<>(
            (a, b) -> a.x != b.x ? Integer.compare(a.x, b.x) : Integer.compare(a.z, b.z));

        try (AnvilWriter writer = new AnvilWriter(regionFile, leafDir)) {
            byte[][] range = ChunkKeyCodec.regionKeyRange(ordinal, regionX, regionZ);
            source.forEachInRange(cf, range[0], range[1], (key, value) -> {
                ChunkPos pos = ChunkKeyCodec.position(key);
                NbtCompound nbt;
                try (DataInputStream in =
                        new DataInputStream(new ByteArrayInputStream(value))) {
                    nbt = NbtIo.read(in);
                }

                // The key and the NBT's own coordinates are two independent statements
                // of position. A disagreement means the chunk lands in the wrong slot
                // consistently, so terrain shows up shifted in a tool while a naive
                // round trip passes. Reported rather than silently trusted.
                NbtCompound level = nbt.getCompound("Level");
                if (level.contains("xPos") && level.contains("zPos")) {
                    int nbtX = level.getInt("xPos");
                    int nbtZ = level.getInt("zPos");
                    if (nbtX != pos.x || nbtZ != pos.z) {
                        out.addFailure(pos + ": stored NBT claims " + nbtX + "," + nbtZ);
                        out.mismatchCount.incrementAndGet();
                    }
                }
                recordDataVersion(nbt, out);

                // Serialise once: the bytes go to the file and their hash is what
                // verification compares against.
                byte[] serialised = AnvilWriter.serialise(nbt);
                writer.write(pos, serialised);
                writtenHashes.put(pos, hash(serialised));
                out.chunkCount.incrementAndGet();
                out.byteCount.addAndGet(value.length);
            });
            out.externalCount.addAndGet(writer.externalChunks());
        }

        if (writtenHashes.isEmpty()) {
            // Nothing to write means no file should exist. RegionFile creates a
            // zero-length one on open, which would litter the output.
            if (regionFile.isFile() && regionFile.length() == 0 && !regionFile.delete()) {
                out.addFailure(regionFile.getName() + ": could not remove empty file");
            }
            return;
        }

        verifyRegion(regionFile, writtenHashes, out);
    }

    /**
     * A content hash of serialised NBT.
     *
     * <p>SHA-256 rather than a cheaper checksum because this is the only thing standing
     * between a corrupted export and a world someone loads; a collision here would
     * report success on wrong data.
     */
    private static String hash(byte[] bytes) throws IOException {
        try {
            java.security.MessageDigest digest =
                java.security.MessageDigest.getInstance("SHA-256");
            byte[] out = digest.digest(bytes);
            StringBuilder sb = new StringBuilder(out.length * 2);
            for (byte b : out) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the platform; if it is absent something is very
            // wrong and silently skipping verification would be worse.
            throw new IOException("SHA-256 unavailable, cannot verify the export", e);
        }
    }

    /**
     * Reads a written region back with the independent parser and compares hashes.
     *
     * <p>Byte-exact, and that is sound rather than over-strict here. Both sides are
     * re-serialisations of a parse of the same byte sequence, and {@code NbtCompound}
     * is backed by a {@code HashMap} whose iteration order is deterministic for an
     * identical key set -- so the order is preserved through the round trip. A test
     * pins that property, because if it ever stopped holding this check would fail
     * loudly rather than silently weaken.
     */
    private static void verifyRegion(File regionFile,
            Map<ChunkPos, String> expected, DimensionResult out) throws IOException {
        AnvilReader.Report report = new AnvilReader.Report();
        List<ChunkPos> seen = new ArrayList<>();
        AnvilReader.stream(regionFile, report, entry -> {
            String original = expected.get(entry.pos());
            seen.add(entry.pos());
            if (original == null) {
                out.mismatchCount.incrementAndGet();
                out.addFailure(entry.pos() + ": present in the file but never written");
            } else if (!original.equals(hash(AnvilWriter.serialise(entry.nbt())))) {
                out.mismatchCount.incrementAndGet();
                out.addFailure(entry.pos() + ": NBT differs after write");
            } else {
                out.verifiedCount.incrementAndGet();
            }
        });
        if (report.total() > 0) {
            out.addFailure(regionFile.getName() + ": reading back the file we just "
                + "wrote reported " + report);
            out.mismatchCount.incrementAndGet();
        }
        for (ChunkPos pos : expected.keySet()) {
            if (!seen.contains(pos)) {
                out.mismatchCount.incrementAndGet();
                out.addFailure(pos + ": written but missing when read back");
            }
        }
    }

    private static void recordDataVersion(NbtCompound nbt, DimensionResult out) {
        if (!nbt.contains("DataVersion")) {
            return;
        }
        int version = nbt.getInt("DataVersion");
        out.minDataVersion.getAndUpdate(current -> Math.min(current, version));
        out.maxDataVersion.getAndUpdate(current -> Math.max(current, version));
    }

    /**
     * The directory a dimension's leaf belongs in, inverting
     * {@code DimensionType.getSaveDirectory}.
     *
     * <p>Overworld at the world root, nether under {@code DIM-1}, end under
     * {@code DIM1}, anything else under {@code dimensions/<namespace>/<path>} -- the
     * same four layouts {@link DimensionKey} parses on the way in, which is what makes
     * an exported world loadable by vanilla.
     */
    static String relativeLeafPath(String identity, String leaf) {
        if (DimensionKey.OVERWORLD.equals(identity)) {
            return leaf;
        }
        if (DimensionKey.THE_NETHER.equals(identity)) {
            return "DIM-1/" + leaf;
        }
        if (DimensionKey.THE_END.equals(identity)) {
            return "DIM1/" + leaf;
        }
        int colon = identity.indexOf(':');
        if (colon <= 0 || colon == identity.length() - 1) {
            throw new IllegalArgumentException("not a namespaced dimension identity: "
                + identity);
        }
        return "dimensions/" + identity.substring(0, colon) + '/'
            + identity.substring(colon + 1) + '/' + leaf;
    }

    private static boolean containsRegionFiles(File dir) {
        File[] entries = dir.listFiles();
        if (entries == null) {
            return false;
        }
        for (File entry : entries) {
            if (entry.isFile() && entry.getName().endsWith(".mca")) {
                return true;
            }
            if (entry.isDirectory() && containsRegionFiles(entry)) {
                return true;
            }
        }
        return false;
    }

    private interface RegionTask {
        void run(long[] region) throws IOException;
    }

    /** Receives one stored key and value during a scan. */
    private interface EntryConsumer {
        void accept(byte[] key, byte[] value) throws IOException;
    }

    /**
     * A read-only, snapshot-pinned view of a database.
     *
     * <p>Separate from {@link RocksDatabase} on purpose. That class owns the read-write
     * handle a running server depends on, is reference counted, and is deliberately not
     * asked to open arbitrary directories; an export needs the opposite -- a
     * throwaway, read-only handle that may point at a checkpoint. Keeping them apart
     * means the exporter cannot affect the production path at all.
     */
    private static final class ExportSource implements AutoCloseable {
        private final RocksDB db;
        private final DBOptions options;
        private final Map<String, ColumnFamilyHandle> columnFamilies;
        private final Snapshot snapshot;
        private final Map<String, Integer> ordinals;

        private ExportSource(RocksDB db, DBOptions options,
                Map<String, ColumnFamilyHandle> columnFamilies, Snapshot snapshot,
                Map<String, Integer> ordinals) {
            this.db = db;
            this.options = options;
            this.columnFamilies = columnFamilies;
            this.snapshot = snapshot;
            this.ordinals = ordinals;
        }

        static ExportSource open(File databaseDir) throws IOException {
            RocksDB.loadLibrary();
            DBOptions options = new DBOptions()
                .setCreateIfMissing(false)
                .setCreateMissingColumnFamilies(false);
            List<ColumnFamilyDescriptor> descriptors = Arrays.asList(
                new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY),
                new ColumnFamilyDescriptor(
                    RocksDatabase.CHUNK_CF.getBytes(StandardCharsets.UTF_8)),
                new ColumnFamilyDescriptor(
                    RocksDatabase.POI_CF.getBytes(StandardCharsets.UTF_8)));
            List<ColumnFamilyHandle> handles = new ArrayList<>();
            RocksDB db;
            try {
                // Read-only: the export cannot modify the source, by construction.
                // Note this does not take RocksDB's exclusive lock, so it also works on
                // a checkpoint while the live database is open -- which is the supported
                // route for a running server.
                db = RocksDB.openReadOnly(options, databaseDir.getAbsolutePath(),
                    descriptors, handles);
            } catch (RocksDBException e) {
                options.close();
                throw new IOException("could not open " + databaseDir
                    + " read-only: " + e.getMessage(), e);
            }

            Map<String, ColumnFamilyHandle> byName = new java.util.HashMap<>();
            byName.put("default", handles.get(0));
            byName.put(RocksDatabase.CHUNK_CF, handles.get(1));
            byName.put(RocksDatabase.POI_CF, handles.get(2));

            Snapshot snapshot = db.getSnapshot();
            Map<String, Integer> ordinals;
            try {
                ordinals = readRegistry(db, handles.get(0), snapshot);
            } catch (IOException | RuntimeException e) {
                db.releaseSnapshot(snapshot);
                for (ColumnFamilyHandle handle : handles) {
                    handle.close();
                }
                db.close();
                options.close();
                throw e;
            }
            return new ExportSource(db, options, byName, snapshot, ordinals);
        }

        /**
         * Reads identity to ordinal straight from the metadata column family.
         *
         * <p>Not via {@link DimensionRegistry}, which allocates a synced
         * {@code WriteOptions} and can assign new ordinals -- neither of which belongs
         * anywhere near a read-only handle. The format is two lines of it: NUL-prefixed
         * keys are internal bookkeeping, everything else is a UTF-8 identity mapped to
         * a big-endian int.
         */
        private static Map<String, Integer> readRegistry(RocksDB db,
                ColumnFamilyHandle metaCf, Snapshot snapshot) throws IOException {
            Map<String, Integer> out = new TreeMap<>();
            try (ReadOptions readOptions = new ReadOptions().setSnapshot(snapshot);
                 RocksIterator it = db.newIterator(metaCf, readOptions)) {
                for (it.seekToFirst(); it.isValid(); it.next()) {
                    byte[] key = it.key();
                    if (key.length == 0 || key[0] == 0) {
                        continue;
                    }
                    byte[] value = it.value();
                    if (value.length < 4) {
                        throw new IOException("corrupt dimension ordinal for "
                            + new String(key, StandardCharsets.UTF_8));
                    }
                    int ordinal = ((value[0] & 0xFF) << 24) | ((value[1] & 0xFF) << 16)
                        | ((value[2] & 0xFF) << 8) | (value[3] & 0xFF);
                    out.put(new String(key, StandardCharsets.UTF_8), ordinal);
                }
            }
            return out;
        }

        Map<String, Integer> dimensionOrdinals() {
            return this.ordinals;
        }

        ColumnFamilyHandle columnFamily(String leaf) {
            return this.columnFamilies.get(RocksDatabase.columnFamilyNameFor(leaf));
        }

        /**
         * The distinct regions holding chunks for one dimension.
         *
         * <p>One forward scan, relying on {@code morton >>> 10} identifying the region:
         * chunks arrive already grouped, so this only has to notice the block changing.
         */
        List<long[]> regionsOf(ColumnFamilyHandle cf, int ordinal) throws IOException {
            List<long[]> regions = new ArrayList<>();
            byte[] prefix = ChunkKeyCodec.dimensionPrefix(ordinal);
            long lastBlock = Long.MIN_VALUE;
            try (ReadOptions readOptions = new ReadOptions().setSnapshot(this.snapshot);
                 RocksIterator it = this.db.newIterator(cf, readOptions)) {
                for (it.seek(prefix); it.isValid(); it.next()) {
                    byte[] key = it.key();
                    if (!ChunkKeyCodec.belongsTo(key, ordinal)) {
                        break;
                    }
                    ChunkPos pos = ChunkKeyCodec.position(key);
                    long block = ChunkKeyCodec.morton(pos.x, pos.z) >>> 10;
                    if (block != lastBlock) {
                        regions.add(new long[] {pos.x >> 5, pos.z >> 5});
                        lastBlock = block;
                    }
                }
            }
            return regions;
        }

        /** Scans a bounded key range, snapshot-pinned. */
        void forEachInRange(ColumnFamilyHandle cf, byte[] lower, byte[] upper,
                EntryConsumer consumer) throws IOException {
            try (Slice lowerSlice = new Slice(lower);
                 Slice upperSlice = new Slice(upper);
                 ReadOptions readOptions = new ReadOptions()
                     .setSnapshot(this.snapshot)
                     .setIterateLowerBound(lowerSlice)
                     .setIterateUpperBound(upperSlice);
                 RocksIterator it = this.db.newIterator(cf, readOptions)) {
                for (it.seekToFirst(); it.isValid(); it.next()) {
                    consumer.accept(it.key(), it.value());
                }
            }
        }

        @Override
        public void close() {
            this.db.releaseSnapshot(this.snapshot);
            for (ColumnFamilyHandle handle : this.columnFamilies.values()) {
                handle.close();
            }
            this.db.close();
            this.options.close();
        }
    }

    private WorldExporter() {
    }
}
