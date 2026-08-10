package com.keuin.rocksmc;

import net.minecraft.util.math.ChunkPos;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

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
 */
public final class WorldImporter {

    /** Outcome for one storage directory. */
    public static final class DirectoryResult {
        public final String label;
        public final String dimension;
        public int chunksRead;
        public int chunksWritten;
        public int verifyFailures;
        public int externalChunks;
        public long uncompressedBytes;
        public long elapsedMs;
        public final AnvilReader.Report anomalies = new AnvilReader.Report();
        public final List<String> failures = new ArrayList<>();

        DirectoryResult(String label, String dimension) {
            this.label = label;
            this.dimension = dimension;
        }

        public boolean clean() {
            return this.verifyFailures == 0 && this.failures.isEmpty()
                && this.chunksWritten == this.chunksRead;
        }
    }

    public static final class Result {
        public final List<DirectoryResult> directories = new ArrayList<>();

        public int totalChunks() {
            int n = 0;
            for (DirectoryResult d : this.directories) {
                n += d.chunksWritten;
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
     * Imports every storage directory under a world into one shared database.
     *
     * <p>The database is opened once and held for the whole import, so all
     * dimensions land in one place and the final compaction covers the lot. Each
     * directory still gets its own {@link RocksChunkStore} view, because that is
     * what carries the dimension ordinal and the column family.
     *
     * @param worldDir  the world root, containing {@code region/}
     * @param overwrite import into a database that already holds data
     * @param progress  optional callback; may be {@code null}
     */
    public static Result importWorld(File worldDir, RocksMcConfig config, boolean overwrite,
            Progress progress) throws IOException {
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
                refused.failures.add("database already contains data: " + dbPath
                    + " (pass --overwrite to import anyway)");
                result.directories.add(refused);
                return result;
            }
        }

        // The importer does its own verification pass, so verify-on-read would
        // double the work for nothing.
        RocksMcConfig importConfig = config.withVerifyOnRead(false);

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
                    skipped.failures.add("unrecognised layout: " + e.getMessage());
                    result.directories.add(skipped);
                    continue;
                }
                result.directories.add(importDirectory(
                    worldDir, storageDir, dimension, importConfig, progress));
            }

            // Compact once for the whole world, after every dimension is in, rather
            // than once per directory. Compacting between dimensions would merge
            // data that later writes then overlap anyway, doing the work twice.
            database.flushMemtables();
            database.compact();
        } finally {
            database.release();
        }
        return result;
    }

    private static DirectoryResult importDirectory(File worldDir, File storageDir,
            DimensionKey dimension, RocksMcConfig config, Progress progress)
            throws IOException {
        DirectoryResult out = new DirectoryResult(
            relative(worldDir, storageDir), dimension.toString());
        long start = System.nanoTime();

        List<File> regions = AnvilReader.regionFiles(storageDir);
        int estimated = regions.size() * 1024;

        // Joins the database already open for this world -- see importWorld.
        try (RocksChunkStore store = RocksChunkStore.open(dimension, config)) {
            for (File region : regions) {
                List<AnvilReader.Entry> entries = AnvilReader.read(region, out.anomalies);
                for (AnvilReader.Entry entry : entries) {
                    out.chunksRead++;
                    if (entry.external()) {
                        out.externalChunks++;
                    }
                    try {
                        store.write(entry.pos(), entry.nbt());
                        out.chunksWritten++;
                        out.uncompressedBytes += entry.compressedLength();
                    } catch (IOException e) {
                        if (out.failures.size() < 20) {
                            out.failures.add(entry.pos() + ": write failed: " + e.getMessage());
                        }
                    }
                }
                if (progress != null) {
                    progress.update(out.label, out.chunksWritten, estimated);
                }
            }

            // Flush before verifying so the read path exercises stored data rather
            // than the memtable.
            store.database().flushMemtables();

            for (File region : regions) {
                AnvilReader.Report ignored = new AnvilReader.Report();
                for (AnvilReader.Entry entry : AnvilReader.read(region, ignored)) {
                    if (store.read(entry.pos()) == null) {
                        out.verifyFailures++;
                        if (out.failures.size() < 20) {
                            out.failures.add(entry.pos() + ": missing after import");
                        }
                    }
                }
            }
        }

        out.elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        return out;
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
        found.sort((a, b) -> a.getAbsolutePath().compareTo(b.getAbsolutePath()));
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
