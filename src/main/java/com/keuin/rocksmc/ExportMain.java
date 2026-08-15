package com.keuin.rocksmc;

import java.io.File;

/**
 * Command-line entry point for {@link WorldExporter}.
 *
 * <pre>
 *   ./gradlew exportWorld -Pworld=/path/to/world -Pout=/path/to/output
 *                         [-Pdatabase=dir] [-Poverwrite] [-Pthreads=n]
 * </pre>
 *
 * <p>Runs outside the game, like the importer and the fidelity harness. That is not
 * incidental: no Fabric Loader means no mixins, so vanilla's {@code RegionFile} is stock
 * bytecode and writing Anvil actually writes Anvil rather than being redirected back
 * into RocksDB.
 *
 * <p>The database is opened <b>read-only</b>, so the source world cannot be modified.
 *
 * <p>For a <b>running server</b>, do not point this at the live database: a read-only
 * handle cannot see the server's memtables, so recent chunks would be missing. Take a
 * checkpoint first and export that, which is consistent by construction:
 *
 * <pre>
 *   /rocksmc checkpoint before-export
 *   ./gradlew exportWorld -Pdatabase=&lt;world&gt;/rocksmc-checkpoints/before-export \
 *                         -Pout=/tmp/exported
 * </pre>
 */
public final class ExportMain {

    private static final String RULE =
        "--------------------------------------------------------------------------";

    public static void main(String[] args) throws Exception {
        String worldPath = null;
        String databasePath = null;
        String outputPath = null;
        boolean overwrite = false;
        int threads = WorldImporter.defaultThreads();

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--world":
                    worldPath = requireValue(args, ++i, "--world");
                    break;
                case "--database":
                    databasePath = requireValue(args, ++i, "--database");
                    break;
                case "--out":
                    outputPath = requireValue(args, ++i, "--out");
                    break;
                case "--overwrite":
                    overwrite = true;
                    break;
                case "--threads":
                    threads = parseThreads(requireValue(args, ++i, "--threads"));
                    break;
                default:
                    System.err.println("unknown argument: " + args[i]);
                    usage();
                    System.exit(2);
            }
        }

        if (outputPath == null || (worldPath == null && databasePath == null)) {
            usage();
            System.exit(2);
        }

        File database = databasePath != null
            ? new File(databasePath)
            : new File(new File(worldPath), RocksDatabase.DIRECTORY_NAME);
        File output = new File(outputPath);

        if (!database.isDirectory()) {
            System.err.println("not a directory: " + database);
            System.exit(1);
        }
        if (output.equals(database) || output.getAbsolutePath()
                .startsWith(database.getAbsolutePath() + File.separator)) {
            // Writing into the database being read would be a spectacular way to lose a
            // world, and the read-only handle would not prevent it.
            System.err.println("refusing to write inside the source database: " + output);
            System.exit(1);
        }

        System.out.println("rocksmc world export");
        System.out.println("  database:  " + database.getAbsolutePath() + "  (READ-ONLY)");
        System.out.println("  output:    " + output.getAbsolutePath());
        System.out.println("  overwrite: " + overwrite);
        System.out.println("  threads:   " + threads
            + (threads == 1 ? " (sequential)" : " of "
                + Runtime.getRuntime().availableProcessors() + " cores"));
        System.out.println();
        System.out.println("The database is opened read-only and read through a single");
        System.out.println("snapshot, so the source cannot be modified and every");
        System.out.println("dimension is read at one instant.");
        System.out.println();
        System.out.println("If a server is currently running against this database, stop");
        System.out.println("and use a checkpoint instead -- a read-only handle cannot see");
        System.out.println("the server's memtables and recent chunks would be missing.");
        System.out.println();

        long start = System.nanoTime();
        WorldExporter.Result result = WorldExporter.exportWorld(database, output,
            overwrite, ExportMain::reportProgress, threads);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

        System.out.println();
        System.out.println(RULE);
        for (WorldExporter.DimensionResult d : result.dimensions) {
            if (d.chunks() == 0 && d.failures().isEmpty()) {
                continue;
            }
            System.out.printf("%-40s %s%n", d.label, d.dimension);
            System.out.printf("    regions %,d  chunks %,d  verified %,d%n",
                d.regions(), d.chunks(), d.verified());
            System.out.printf("    payload %,d bytes   elapsed %,d ms%n",
                d.bytes(), d.elapsedMs);
            if (d.externalChunks() > 0) {
                // Called out because .mcc support is the least uniform part of the
                // format across third-party tools.
                System.out.printf("    oversized (.mcc) %,d -- check your tool supports "
                    + "external chunks%n", d.externalChunks());
            }
            int[] versions = d.dataVersionRange();
            if (versions != null && versions[0] != versions[1]) {
                System.out.printf("    DataVersion %d..%d (mixed; the import path is "
                    + "byte-transparent)%n", versions[0], versions[1]);
            }
            if (d.mismatches() > 0) {
                System.out.printf("    MISMATCHES: %,d%n", d.mismatches());
            }
            for (String failure : d.failures()) {
                System.out.println("    ! " + failure);
            }
            System.out.println();
        }

        System.out.println(RULE);
        System.out.printf("total chunks exported: %,d in %,d ms%n",
            result.totalChunks(), elapsedMs);
        if (result.clean()) {
            System.out.println("RESULT: OK -- every chunk was written and read back.");
            System.out.println();
            System.out.println("The output is a vanilla world directory. Note that");
            System.out.println("level.dat, playerdata/, data/ and advancements/ are NOT");
            System.out.println("in the database and were not exported; copy them from the");
            System.out.println("source world if you need a world a server can load.");
        } else {
            System.out.println("RESULT: FAILED -- see the failures listed above.");
            System.out.println("Do NOT rely on this output.");
            System.exit(1);
        }
    }

    private static long lastReport;

    /** Throttled progress. Synchronised because workers call it concurrently. */
    private static synchronized void reportProgress(String label, int chunks,
            int regionsDone, int regionsTotal) {
        long now = System.currentTimeMillis();
        if (now - lastReport < 2000) {
            return;
        }
        lastReport = now;
        System.out.printf("  %-40s %,d chunks  (%d/%d regions)%n",
            label, chunks, regionsDone, regionsTotal);
    }

    private static String requireValue(String[] args, int index, String flag) {
        if (index >= args.length) {
            System.err.println(flag + " requires a value");
            usage();
            System.exit(2);
        }
        return args[index];
    }

    private static int parseThreads(String value) {
        try {
            int n = Integer.parseInt(value.trim());
            if (n < 1) {
                System.err.println("--threads must be at least 1, got " + n);
                System.exit(2);
            }
            return n;
        } catch (NumberFormatException e) {
            System.err.println("--threads must be a positive integer: " + value);
            System.exit(2);
            return 1;
        }
    }

    private static void usage() {
        System.err.println("usage: ExportMain (--world <dir> | --database <dir>) "
            + "--out <dir> [--overwrite] [--threads <n>]");
        System.err.println();
        System.err.println("  --world      world directory containing "
            + RocksDatabase.DIRECTORY_NAME);
        System.err.println("  --database   the RocksDB directory directly, e.g. a "
            + "checkpoint under rocksmc-checkpoints/");
        System.err.println("  --out        world directory to write .mca files into");
        System.err.println("  --overwrite  write into an output that already has .mca");
        System.err.println("  --threads    parallel region workers (default: one per "
            + "core, " + WorldImporter.defaultThreads() + " here)");
    }

    private ExportMain() {
    }
}
