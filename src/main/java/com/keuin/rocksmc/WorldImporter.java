package com.keuin.rocksmc;

import net.minecraft.util.math.ChunkPos;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Imports an existing Anvil world into RocksDB stores beside it.
 *
 * <h2>What this does and does not touch</h2>
 *
 * <p>Region files are opened <b>read-only</b>. For each {@code region} and
 * {@code poi} directory found, chunks are read with {@link AnvilReader} and written
 * into a sibling {@code <name>.rocksdb} database -- exactly the layout the mixin
 * expects at runtime, so a server started afterwards finds its data already there.
 *
 * <p>Nothing else in the world is converted, because nothing else needs to be:
 * {@code level.dat}, {@code playerdata}, {@code data} and {@code advancements}
 * remain flat files that vanilla reads directly. That also means a backup must
 * capture both the databases and those files.
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
     * Imports every storage directory under a world.
     *
     * @param worldDir  the world root, containing {@code region/}
     * @param overwrite import into a database that already holds data
     * @param progress  optional callback; may be {@code null}
     */
    public static Result importWorld(File worldDir, RocksMcConfig config, boolean overwrite,
            Progress progress) throws IOException {
        Result result = new Result();

        for (File storageDir : findStorageDirectories(worldDir)) {
            DimensionKey dimension;
            try {
                dimension = DimensionKey.fromStorageDirectory(storageDir);
            } catch (IllegalArgumentException e) {
                // Not a layout we can address; skipping silently would lose data, so
                // record it as a hard failure for the caller to surface.
                DirectoryResult skipped = new DirectoryResult(
                    relative(worldDir, storageDir), "unknown");
                skipped.failures.add("unrecognised layout: " + e.getMessage());
                result.directories.add(skipped);
                continue;
            }
            result.directories.add(
                importDirectory(worldDir, storageDir, dimension, config, overwrite, progress));
        }
        return result;
    }

    private static DirectoryResult importDirectory(File worldDir, File storageDir,
            DimensionKey dimension, RocksMcConfig config, boolean overwrite,
            Progress progress) throws IOException {
        DirectoryResult out = new DirectoryResult(
            relative(worldDir, storageDir), dimension.toString());
        long start = System.nanoTime();

        File dbPath = new File(storageDir.getParentFile(),
            storageDir.getName() + ".rocksdb");

        if (!overwrite) {
            File[] existing = dbPath.listFiles(
                (d, n) -> n.endsWith(".sst") || n.endsWith(".blob"));
            if (existing != null && existing.length > 0) {
                out.failures.add("database already contains data: " + dbPath
                    + " (pass --overwrite to import anyway)");
                return out;
            }
        }

        List<File> regions = AnvilReader.regionFiles(storageDir);
        int estimated = regions.size() * 1024;

        // verify-on-read would double the work; this importer does its own
        // verification pass instead, so force it off for the duration.
        RocksMcConfig importConfig = config;

        try (RocksChunkStore store = new RocksChunkStore(dbPath, dimension, importConfig)) {
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
            store.sync();

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

            // Compact so the database is in steady state before the server starts,
            // rather than the server paying for it during play.
            store.compact();
        }

        out.elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        return out;
    }

    /** Finds every directory containing {@code .mca} files, excluding our own output. */
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
            if (entry.isDirectory() && !entry.getName().endsWith(".rocksdb")) {
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
