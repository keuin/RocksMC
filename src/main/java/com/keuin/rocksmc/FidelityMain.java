package com.keuin.rocksmc;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Standalone entry point for {@link FidelityHarness}.
 *
 * <p>Runs outside the game so it can be pointed at any world directory --
 * including a real server backup -- without booting a server or touching the
 * original save. Region files are only ever opened for reading; all writes go to
 * a scratch RocksDB in a temporary directory that is deleted afterwards.
 *
 * <pre>
 *   java -cp ... com.keuin.rocksmc.FidelityMain &lt;world-dir&gt; [--limit N] [--keep]
 * </pre>
 *
 * <p>Discovers {@code region/}, {@code DIM-1/region/}, {@code DIM1/region/} and
 * the matching {@code poi/} directories automatically, and reports per-dimension
 * plus aggregate results.
 */
public final class FidelityMain {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: FidelityMain <world-dir> [--limit N] [--keep]");
            System.exit(2);
        }

        File world = new File(args[0]);
        int limit = 0;
        boolean keep = false;
        for (int i = 1; i < args.length; i++) {
            if (args[i].startsWith("--limit")) {
                limit = args[i].contains("=")
                    ? Integer.parseInt(args[i].split("=", 2)[1])
                    : Integer.parseInt(args[++i]);
            } else if (args[i].equals("--keep")) {
                keep = true;
            }
        }

        if (!world.isDirectory()) {
            System.err.println("not a directory: " + world);
            System.exit(1);
        }

        List<File> regionDirs = findRegionDirs(world);
        if (regionDirs.isEmpty()) {
            System.err.println("no region directories found under " + world);
            System.exit(1);
        }

        System.out.println("world: " + world.getAbsolutePath());
        System.out.println("region directories: " + regionDirs.size());
        if (limit > 0) {
            System.out.println("limit: " + limit + " chunks per directory");
        }

        FidelityHarness.Stats total = new FidelityHarness.Stats();
        int failures = 0;

        for (File regionDir : regionDirs) {
            String label = relative(world, regionDir);
            System.out.println();
            System.out.println("=== " + label + " ===");

            Path scratch = Files.createTempDirectory("rocksmc-fidelity-");
            try {
                long start = System.nanoTime();
                FidelityHarness.Stats stats = FidelityHarness.run(
                    regionDir, scratch.toFile(), limit);
                long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

                if (stats.chunksFound == 0) {
                    System.out.println("  (no chunks)");
                    continue;
                }

                System.out.print(indent(stats.toString()));
                System.out.printf("  elapsed %,d ms (%.1f chunks/s)%n", elapsedMs,
                    elapsedMs == 0 ? 0.0 : stats.chunksFound * 1000.0 / elapsedMs);

                if (stats.mismatches > 0 || stats.readFailures > 0) {
                    failures++;
                    System.out.println("  FAILURES:");
                    for (String detail : stats.mismatchDetails) {
                        System.out.println("    " + detail);
                    }
                }

                total.chunksFound += stats.chunksFound;
                total.chunksVerified += stats.chunksVerified;
                total.mismatches += stats.mismatches;
                total.readFailures += stats.readFailures;
                total.compressedBytes += stats.compressedBytes;
                total.anvilOnDisk += stats.anvilOnDisk;
                total.uncompressedBytes += stats.uncompressedBytes;
                total.rocksOnDisk += stats.rocksOnDisk;
            } finally {
                if (keep) {
                    System.out.println("  scratch db kept at " + scratch);
                } else {
                    deleteRecursively(scratch);
                }
            }
        }

        System.out.println();
        System.out.println("======================= TOTAL =======================");
        System.out.print(total);

        boolean clean = total.mismatches == 0 && total.readFailures == 0
            && total.chunksVerified == total.chunksFound;
        System.out.println();
        if (total.chunksFound == 0) {
            System.out.println("RESULT: no chunks examined");
            System.exit(1);
        } else if (clean) {
            System.out.printf("RESULT: PASS -- all %,d chunks round-tripped with "
                + "equivalent NBT%n", total.chunksVerified);
        } else {
            System.out.printf("RESULT: FAIL -- %d mismatches, %d read failures across "
                + "%,d chunks%n", total.mismatches, total.readFailures, total.chunksFound);
            System.exit(1);
        }
    }

    /**
     * Finds every directory containing .mca files, in stable order.
     */
    private static List<File> findRegionDirs(File world) {
        List<File> found = new ArrayList<>();
        collect(world, found);
        found.sort(Comparator.comparing(File::getAbsolutePath));
        return found;
    }

    private static void collect(File dir, List<File> found) {
        File[] entries = dir.listFiles();
        if (entries == null) {
            return;
        }
        boolean hasMca = false;
        for (File entry : entries) {
            if (entry.isFile() && entry.getName().endsWith(".mca")) {
                hasMca = true;
            }
        }
        if (hasMca) {
            found.add(dir);
        }
        for (File entry : entries) {
            // Skip our own scratch output so repeat runs stay clean.
            if (entry.isDirectory() && !entry.getName().endsWith(".rocksdb")) {
                collect(entry, found);
            }
        }
    }

    private static String relative(File base, File child) {
        String b = base.getAbsolutePath();
        String c = child.getAbsolutePath();
        return c.startsWith(b) ? c.substring(b.length()).replaceFirst("^/", "") : c;
    }

    private static String indent(String s) {
        StringBuilder sb = new StringBuilder();
        for (String line : s.split("\n")) {
            sb.append("  ").append(line).append('\n');
        }
        return sb.toString();
    }

    private static void deleteRecursively(Path path) {
        try (Stream<Path> paths = Files.walk(path)) {
            paths
                .sorted(Comparator.reverseOrder())
                .forEach(p -> {
                    try {
                        Files.delete(p);
                    } catch (Exception ignored) {
                    }
                });
        } catch (Exception ignored) {
        }
    }

    private FidelityMain() {
    }
}
