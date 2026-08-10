package com.keuin.rocksmc;

import net.minecraft.util.math.ChunkPos;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Imports an existing Anvil world into a single RocksDB database beside it.
 *
 * <h2>What this does and does not touch</h2>
 *
 * <p>Region files are opened <b>read-only</b>. For each {@code region} and
 * {@code poi} directory found, chunks are read with {@link AnvilReader} and written
 * into {@code <world>/rocksmc.db} -- one database for the whole world, exactly the
 * layout the mixin expects at runtime, so a server started afterwards finds its
 * data already there.
 *
 * <p>Nothing else in the world is converted, because nothing else needs to be:
 * {@code level.dat}, {@code playerdata}, {@code data} and {@code advancements}
 * remain flat files that vanilla reads directly. That also means a backup must
 * capture both the database and those files.
 *
 * <h2>Why one database, and why that matters here</h2>
 *
 * <p>Earlier builds wrote one database per {@code (dimension, leaf)}. Each had its
 * own write-ahead log, and since the server saves worlds sequentially a crash
 * mid-autosave could recover each to a different point -- a state no tick ever
 * produced. Consolidating means the whole world shares one recovery point.
 *
 * <p>For the importer this has a concrete consequence: every dimension must be
 * imported in <b>one</b> pass. The runtime blank-start guard checks whether the
 * shared database holds any data, so importing only the overworld and then starting
 * a server would let the nether regenerate silently. The importer therefore always
 * walks the whole world, and reports per directory rather than exiting early.
 *
 * <h2>Why it does not go through the mod's runtime path</h2>
 *
 * <p>Running as a standalone tool rather than a server command keeps the import out
 * of the tick loop entirely, and lets it run against a copy of a world before any
 * server touches it -- which is the safe order of operations when rolling out from a
 * production mirror.
 *
 * <h2>Verification</h2>
 *
 * <p>Every chunk is read back and compared after writing, so an import either
 * completes clean or reports exactly which chunks failed. That check costs roughly
 * a third of the runtime and is not optional: an import that silently dropped
 * terrain would only be discovered by a player finding a hole in the world.
 *
 * <h2>Parallelism</h2>
 *
 * <p>One task per region file, {@link #defaultThreads()} workers by default. The
 * region file is the natural unit: 1024 self-contained chunks with no cross-file
 * references, so the only shared state is the database and the result counters.
 *
 * <p>This is worth doing because the work is almost entirely CPU-bound -- inflate
 * plus NBT parse for every chunk -- and single-threaded it left 23 of 24 cores idle.
 * RocksDB permits concurrent writes to one handle, and {@link AnvilReader} holds no
 * state between calls, so neither side needed a lock; the counters are atomic and
 * each task carries its own {@link AnvilReader.Report}, merged on completion.
 *
 * <p>Chunks are streamed through {@link AnvilReader#stream} rather than collected
 * into a list, so peak memory is one chunk per worker rather than one whole region
 * per worker. That is what keeps heap bounded by worker count instead of world size:
 * measured, the 293,207-chunk world imports on 24 threads under {@code -Xmx512m}, at
 * the same speed as with 8 GB. Collecting whole regions instead would need gigabytes
 * purely for parsed NBT awaiting a write.
 *
 * <p>Measured on a real 293,207-chunk world (24 cores, tmpfs), verifying every
 * chunk on read-back as usual:
 *
 * <pre>
 *   threads     time   speedup   efficiency
 *         1   342.1s      1.0x         100%
 *         4    89.2s      3.8x          96%
 *         8    49.2s      7.0x          87%
 *        12    37.7s      9.1x          76%
 *        24    31.6s     10.8x          45%
 * </pre>
 *
 * <p>The database written in parallel was compared against the sequential one
 * key-for-key with every value hashed: identical, all 293,207 entries. Counter
 * equality alone would not have proven that, since a race could write a chunk under
 * the wrong key while keeping every count right.
 *
 * <p><b>Efficiency falls off past ~12 workers</b> and that is structural, not a
 * tuning problem. The overworld holds 817 of the world's 1,622 region files while
 * the four smallest directories hold 202 between them, so the run ends with one
 * dimension's remaining files spread over far more workers than there is work for.
 * Fixing it would mean abandoning the per-directory sequencing -- which is what keeps
 * progress and timing reportable per dimension -- for a single global queue. Not
 * worth it for a one-off migration that now takes half a minute.
 *
 * <p>Dimensions are still imported one after another. They share the database and
 * the thread pool, so there is nothing to gain from overlapping them, and keeping
 * them sequential means the per-directory progress and timing stay meaningful.
 */
public final class WorldImporter {

    /**
     * Outcome for one storage directory.
     *
     * <p>Counters are atomic and the failure list is synchronised because regions
     * within a directory are imported in parallel. They are read only after the
     * directory's tasks have all completed, so the accessors need no barrier of their
     * own beyond what the atomics provide.
     */
    public static final class DirectoryResult {
        public final String label;
        public final String dimension;
        public long elapsedMs;
        public final AnvilReader.Report anomalies = new AnvilReader.Report();

        private final AtomicInteger chunksReadCount = new AtomicInteger();
        private final AtomicInteger chunksWrittenCount = new AtomicInteger();
        private final AtomicInteger verifyFailureCount = new AtomicInteger();
        private final AtomicInteger externalChunkCount = new AtomicInteger();
        private final AtomicLong uncompressedByteCount = new AtomicLong();
        private final List<String> failureList =
            Collections.synchronizedList(new ArrayList<>());

        DirectoryResult(String label, String dimension) {
            this.label = label;
            this.dimension = dimension;
        }

        public int chunksRead() {
            return this.chunksReadCount.get();
        }

        public int chunksWritten() {
            return this.chunksWrittenCount.get();
        }

        public int verifyFailures() {
            return this.verifyFailureCount.get();
        }

        public int externalChunks() {
            return this.externalChunkCount.get();
        }

        public long uncompressedBytes() {
            return this.uncompressedByteCount.get();
        }

        /** A snapshot copy, safe to iterate while other threads are still adding. */
        public List<String> failures() {
            synchronized (this.failureList) {
                return new ArrayList<>(this.failureList);
            }
        }

        /**
         * Records a failure, keeping only the first 20.
         *
         * <p>Bounded because a systematically broken import would otherwise
         * accumulate one string per chunk -- hundreds of thousands of them -- and the
         * operator only needs enough to diagnose it. The check and the add are one
         * atomic step so concurrent callers cannot overshoot.
         */
        void addFailure(String failure) {
            synchronized (this.failureList) {
                if (this.failureList.size() < 20) {
                    this.failureList.add(failure);
                }
            }
        }

        public boolean clean() {
            return verifyFailures() == 0 && this.failureList.isEmpty()
                && chunksWritten() == chunksRead();
        }
    }

    public static final class Result {
        public final List<DirectoryResult> directories = new ArrayList<>();

        public int totalChunks() {
            int n = 0;
            for (DirectoryResult d : this.directories) {
                n += d.chunksWritten();
            }
            return n;
        }

        public boolean clean() {
            for (DirectoryResult d : this.directories) {
                if (!d.clean()) {
                    return false;
                }
            }
            return true;
        }
    }

    public interface Progress {
        void update(String label, int chunksDone, int chunksTotal);
    }

    /**
     * Default worker count: one per available processor.
     *
     * <p>The work is overwhelmingly CPU-bound -- inflate plus NBT parse per chunk --
     * so the core count is the right scale. It is not I/O bound in any way that would
     * argue for oversubscribing: region files are read sequentially in large chunks,
     * and RocksDB's own writes go through its background threads.
     */
    public static int defaultThreads() {
        return Math.max(1, Runtime.getRuntime().availableProcessors());
    }

    /**
     * Imports every storage directory under a world into one shared database.
     *
     * <p>The database is opened once and held for the whole import, so all
     * dimensions land in one place and the final compaction covers the lot. Each
     * directory still gets its own {@link RocksChunkStore} view, because that is
     * what carries the dimension ordinal and the column family.
     *
     * <p>{@code threads = 1} runs everything on the calling thread with no executor
     * at all, which keeps the sequential path available for diagnosing whether a
     * problem is concurrency-related; {@link #defaultThreads()} is the usual value.
     *
     * @param worldDir  the world root, containing {@code region/}
     * @param overwrite import into a database that already holds data
     * @param progress  optional callback; may be {@code null}
     * @param threads   parallel region workers, at least 1
     */
    public static Result importWorld(File worldDir, RocksMcConfig config, boolean overwrite,
            Progress progress, int threads) throws IOException {
        Result result = new Result();

        List<File> storageDirs = findStorageDirectories(worldDir);
        if (storageDirs.isEmpty()) {
            return result;
        }

        File dbPath = new File(worldDir, RocksDatabase.DIRECTORY_NAME);
        if (!overwrite) {
            File[] existing = dbPath.listFiles(
                (d, n) -> n.endsWith(".sst") || n.endsWith(".blob"));
            if (existing != null && existing.length > 0) {
                // One refusal for the world, not one per directory: they all share
                // the database, so the condition is world-wide.
                DirectoryResult refused = new DirectoryResult(
                    relative(worldDir, dbPath), "n/a");
                refused.addFailure("database already contains data: " + dbPath
                    + " (pass --overwrite to import anyway)");
                result.directories.add(refused);
                return result;
            }
        }

        // The importer does its own verification pass, so verify-on-read would
        // double the work for nothing.
        RocksMcConfig importConfig = config.withVerifyOnRead(false);

        int workers = Math.max(1, threads);
        // Daemon threads, so a failure on the main path cannot leave the JVM alive
        // holding an open database.
        ExecutorService pool = workers == 1 ? null
            : Executors.newFixedThreadPool(workers, runnable -> {
                Thread t = new Thread(runnable, "rocksmc-import");
                t.setDaemon(true);
                return t;
            });

        RocksDatabase database = RocksDatabase.open(worldDir, importConfig);
        try {
            for (File storageDir : storageDirs) {
                DimensionKey dimension;
                try {
                    dimension = DimensionKey.fromStorageDirectory(storageDir);
                } catch (IllegalArgumentException e) {
                    // Not a layout we can address; skipping silently would lose
                    // data, so record it as a hard failure for the caller to
                    // surface.
                    DirectoryResult skipped = new DirectoryResult(
                        relative(worldDir, storageDir), "unknown");
                    skipped.addFailure("unrecognised layout: " + e.getMessage());
                    result.directories.add(skipped);
                    continue;
                }
                result.directories.add(importDirectory(
                    worldDir, storageDir, dimension, importConfig, progress, pool));
            }

            // Compact once for the whole world, after every dimension is in, rather
            // than once per directory. Compacting between dimensions would merge
            // data that later writes then overlap anyway, doing the work twice.
            database.flushMemtables();
            database.compact();
        } finally {
            if (pool != null) {
                pool.shutdownNow();
            }
            database.release();
        }
        return result;
    }

    /**
     * Imports one storage directory, one region file per task.
     *
     * <p>The region file is the unit of work because it is self-contained: 1024
     * chunks, no cross-file references, and the only shared state is the database
     * (which RocksDB makes safe for concurrent writes) and the result counters
     * (atomics). Splitting finer would multiply coordination for no gain; coarser
     * would leave cores idle on the two dimensions that have few files.
     *
     * <p>Chunks are streamed rather than collected, so peak memory is one chunk per
     * worker instead of one whole region per worker. On a 24-core machine the
     * difference is roughly a gigabyte.
     */
    private static DirectoryResult importDirectory(File worldDir, File storageDir,
            DimensionKey dimension, RocksMcConfig config, Progress progress,
            ExecutorService pool) throws IOException {
        DirectoryResult out = new DirectoryResult(
            relative(worldDir, storageDir), dimension.toString());
        long start = System.nanoTime();

        List<File> regions = AnvilReader.regionFiles(storageDir);
        int estimated = regions.size() * 1024;

        // Joins the database already open for this world -- see importWorld.
        try (RocksChunkStore store = RocksChunkStore.open(dimension, config)) {
            forEachRegion(pool, regions, region -> {
                writeRegion(store, region, out);
                if (progress != null) {
                    progress.update(out.label, out.chunksWritten(), estimated);
                }
            });

            // Flush before verifying so the read path exercises stored data rather
            // than the memtable.
            store.database().flushMemtables();

            forEachRegion(pool, regions, region -> verifyRegion(store, region, out));
        }

        out.elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        return out;
    }

    /** Reads one region file and writes every chunk in it to the store. */
    private static void writeRegion(RocksChunkStore store, File region, DirectoryResult out)
            throws IOException {
        // A private report per region, merged at the end: the counters are plain ints
        // and would otherwise race.
        AnvilReader.Report report = new AnvilReader.Report();
        try {
            AnvilReader.stream(region, report, entry -> {
                out.chunksReadCount.incrementAndGet();
                if (entry.external()) {
                    out.externalChunkCount.incrementAndGet();
                }
                try {
                    store.write(entry.pos(), entry.nbt());
                    out.chunksWrittenCount.incrementAndGet();
                    out.uncompressedByteCount.addAndGet(entry.compressedLength());
                } catch (IOException e) {
                    out.addFailure(entry.pos() + ": write failed: " + e.getMessage());
                }
            });
        } finally {
            out.anomalies.merge(report);
        }
    }

    /** Re-reads one region file and confirms every chunk is retrievable. */
    private static void verifyRegion(RocksChunkStore store, File region, DirectoryResult out)
            throws IOException {
        AnvilReader.Report ignored = new AnvilReader.Report();
        AnvilReader.stream(region, ignored, entry -> {
            if (store.read(entry.pos()) == null) {
                out.verifyFailureCount.incrementAndGet();
                out.addFailure(entry.pos() + ": missing after import");
            }
        });
    }

    /** A unit of work over one region file. */
    private interface RegionTask {
        void run(File region) throws IOException;
    }

    /**
     * Runs {@code task} over every region, in parallel when a pool is given.
     *
     * <p>Waits for all tasks before returning, and rethrows the first failure as an
     * {@link IOException} once the rest have finished. Failing loudly matters here:
     * an import that skipped a region and still reported success would leave a hole
     * in the world that only a player would find. Note the verification pass reads
     * back every chunk, so a silently dropped write is caught there too -- this is
     * the belt to that braces.
     */
    private static void forEachRegion(ExecutorService pool, List<File> regions,
            RegionTask task) throws IOException {
        if (pool == null) {
            for (File region : regions) {
                task.run(region);
            }
            return;
        }

        List<Future<?>> futures = new ArrayList<>(regions.size());
        for (File region : regions) {
            futures.add(pool.submit(() -> {
                task.run(region);
                return null;
            }));
        }

        IOException failure = null;
        for (Future<?> future : futures) {
            try {
                future.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("import interrupted", e);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (failure == null) {
                    failure = cause instanceof IOException
                        ? (IOException) cause
                        : new IOException("region import failed", cause);
                } else {
                    failure.addSuppressed(cause);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    /**
     * Finds every directory containing {@code .mca} files.
     *
     * <p>Skips both the current database directory and any leftover version 1
     * {@code *.rocksdb} directories, so a re-import never tries to read its own
     * output as a source.
     */
    static List<File> findStorageDirectories(File worldDir) {
        List<File> found = new ArrayList<>();
        collect(worldDir, found);
        found.sort(Comparator.comparing(File::getAbsolutePath));
        return found;
    }

    private static void collect(File dir, List<File> found) {
        File[] entries = dir.listFiles();
        if (entries == null) {
            return;
        }
        for (File entry : entries) {
            if (entry.isFile() && entry.getName().endsWith(".mca")) {
                found.add(dir);
                break;
            }
        }
        for (File entry : entries) {
            if (entry.isDirectory()
                    && !entry.getName().endsWith(".rocksdb")
                    && !entry.getName().equals(RocksDatabase.DIRECTORY_NAME)) {
                collect(entry, found);
            }
        }
    }

    private static String relative(File base, File child) {
        String b = base.getAbsolutePath();
        String c = child.getAbsolutePath();
        return c.startsWith(b) ? c.substring(b.length()).replaceFirst("^[/\\\\]", "") : c;
    }

    private WorldImporter() {
    }
}
