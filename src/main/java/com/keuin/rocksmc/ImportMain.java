package com.keuin.rocksmc;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Properties;

/**
 * Command-line entry point for {@link WorldImporter}.
 *
 * <pre>
 *   ./gradlew importWorld -Pworld=/path/to/world [-Poverwrite] [-Pconfig=path]
 *                         [-Pthreads=n]
 * </pre>
 *
 * <p>Runs outside the game deliberately: an import against a copy of a production
 * world should happen before any server touches it. Region files are opened
 * read-only throughout.
 *
 * <p>Imports one region file per worker thread, defaulting to one per core. Pass
 * {@code -Pthreads=1} for the sequential path, which is worth having when
 * establishing whether a problem is concurrency-related.
 */
public final class ImportMain {

    /**
     * Separator. A literal because String.repeat is Java 11+ and this
     * module compiles with options.release = 8.
     */
    private static final String RULE =
        "--------------------------------------------------------------------------";

    public static void main(String[] args) throws Exception {
        String worldPath = null;
        String configPath = null;
        boolean overwrite = false;
        int threads = WorldImporter.defaultThreads();

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--world":
                    worldPath = args[++i];
                    break;
                case "--config":
                    configPath = args[++i];
                    break;
                case "--overwrite":
                    overwrite = true;
                    break;
                case "--threads":
                    threads = parseThreads(args[++i]);
                    break;
                default:
                    System.err.println("unknown argument: " + args[i]);
                    usage();
                    System.exit(2);
            }
        }
        if (worldPath == null) {
            usage();
            System.exit(2);
        }

        File world = new File(worldPath);
        if (!world.isDirectory()) {
            System.err.println("not a directory: " + world);
            System.exit(1);
        }

        RocksMcConfig config = loadConfig(configPath);

        System.out.println("rocksmc world import");
        System.out.println("  world:     " + world.getAbsolutePath());
        System.out.println("  overwrite: " + overwrite);
        System.out.println("  threads:   " + threads
            + (threads == 1 ? " (sequential)" : " of " + Runtime.getRuntime()
                .availableProcessors() + " cores"));
        System.out.println("  settings:  minBlobSize=" + config.minBlobSize()
            + " writeBufferSize=" + config.writeBufferSize()
            + " maxBackgroundJobs=" + config.maxBackgroundJobs());
        System.out.println();
        System.out.println("Region files are opened READ-ONLY. Existing .mca data is");
        System.out.println("never modified, so this is safe to run against a copy and");
        System.out.println("reversible by deleting the rocksmc.db directory.");
        System.out.println();

        long start = System.nanoTime();
        WorldImporter.Result result = WorldImporter.importWorld(world, config, overwrite,
            ImportMain::reportProgress, threads);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

        System.out.println();
        System.out.println(RULE);
        for (WorldImporter.DirectoryResult d : result.directories) {
            System.out.printf("%-28s %s%n", d.label, d.dimension);
            System.out.printf("    chunks read %,d  written %,d  external(.mcc) %,d%n",
                d.chunksRead(), d.chunksWritten(), d.externalChunks());
            System.out.printf("    payload %,d bytes   elapsed %,d ms%n",
                d.uncompressedBytes(), d.elapsedMs);
            if (d.anomalies.total() > 0) {
                System.out.println("    anvil anomalies: " + d.anomalies);
            }
            if (d.verifyFailures() > 0) {
                System.out.printf("    VERIFY FAILURES: %,d%n", d.verifyFailures());
            }
            for (String failure : d.failures()) {
                System.out.println("    ! " + failure);
            }
            System.out.println();
        }

        System.out.println(RULE);
        System.out.printf("total chunks imported: %,d in %,d ms%n",
            result.totalChunks(), elapsedMs);
        if (result.clean()) {
            System.out.println("RESULT: OK -- every chunk was written and read back.");
            System.out.println();
            System.out.println("Next: set backend=rocksdb in config/rocksmc.properties,");
            System.out.println("then start the server. Keep the .mca files until you are");
            System.out.println("satisfied; reverting is just backend=anvil.");
        } else {
            System.out.println("RESULT: FAILED -- see the failures listed above.");
            System.out.println("Do NOT start a server against this database.");
            System.exit(1);
        }
    }

    private static long lastReport;

    /**
     * Throttled progress output.
     *
     * <p>{@code synchronized} because the importer calls this from its worker
     * threads: without it two workers could interleave inside {@code printf} and
     * produce a garbled line, and the throttle itself would race.
     */
    private static synchronized void reportProgress(String label, int done, int total) {
        // Throttle so a large world does not produce thousands of lines.
        long now = System.currentTimeMillis();
        if (now - lastReport < 2000) {
            return;
        }
        lastReport = now;
        System.out.printf("  %-24s %,d chunks%n", label, done);
    }

    /** Parses {@code --threads}, rejecting values that cannot be a worker count. */
    private static int parseThreads(String value) {
        int n;
        try {
            n = Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            System.err.println("--threads must be a positive integer: " + value);
            System.exit(2);
            return 1;
        }
        if (n < 1) {
            System.err.println("--threads must be at least 1, got " + n);
            System.exit(2);
        }
        return n;
    }

    private static RocksMcConfig loadConfig(String path) throws Exception {
        Properties props = new Properties();
        File file = path != null
            ? new File(path)
            : new File("config/rocksmc.properties");
        if (file.isFile()) {
            try (InputStream in = Files.newInputStream(file.toPath())) {
                props.load(in);
            }
            System.out.println("  config:    " + file.getAbsolutePath());
        } else if (path != null) {
            throw new IllegalArgumentException("config file not found: " + file);
        }
        // The import writes its own database from scratch, so the backend setting in
        // the config file is irrelevant here; only the tuning values matter.
        props.setProperty("backend", "rocksdb");
        props.setProperty("verify-on-read", "false");
        return RocksMcConfig.of(props);
    }

    private static void usage() {
        System.err.println("usage: ImportMain --world <dir> [--overwrite] "
            + "[--config <file>] [--threads <n>]");
        System.err.println();
        System.err.println("  --world      world directory containing region/");
        System.err.println("  --overwrite  import even if the database already has data");
        System.err.println("  --config     rocksmc.properties to take tuning from");
        System.err.println("  --threads    parallel region workers (default: one per "
            + "core, " + WorldImporter.defaultThreads() + " here; 1 = sequential)");
    }

    private ImportMain() {
    }
}
