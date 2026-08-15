package com.keuin.rocksmc;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.math.ChunkPos;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Compares two Anvil world directories chunk for chunk.
 *
 * <p>This exists because the export verifies itself by reading back what it just wrote,
 * which cannot detect a fault shared by both the write and the read. The question an
 * operator actually needs answered before deleting a world's {@code .mca} files is
 * different: does the exported world hold the same chunks as the original? That needs an
 * independent comparison against the source, which is this.
 *
 * <p>Compares parsed NBT rather than file bytes, deliberately. Region files legitimately
 * differ byte-for-byte while holding identical chunks -- sector allocation depends on
 * write order, timestamps are wall clock, compression level is a choice, and vanilla
 * leaves padding in place. Comparing bytes would report differences that do not exist.
 *
 * <pre>
 *   ./gradlew compareWorlds -Pa=/path/to/original -Pb=/path/to/exported
 * </pre>
 */
public final class CompareMain {

    public static void main(String[] args) throws Exception {
        File left = null;
        File right = null;
        int threads = Runtime.getRuntime().availableProcessors();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--a":
                    left = new File(requireValue(args, ++i, "--a"));
                    break;
                case "--b":
                    right = new File(requireValue(args, ++i, "--b"));
                    break;
                case "--threads":
                    threads = Math.max(1, Integer.parseInt(
                        requireValue(args, ++i, "--threads")));
                    break;
                default:
                    System.err.println("unknown argument: " + args[i]);
                    usage();
                    System.exit(2);
            }
        }
        if (left == null || right == null) {
            usage();
            System.exit(2);
            return;
        }

        System.out.println("comparing");
        System.out.println("  a: " + left);
        System.out.println("  b: " + right);
        System.out.println();

        // Union of the leaves present on either side, so a directory that exists in one
        // world and not the other is reported rather than skipped.
        Set<String> leaves = new TreeSet<>();
        leaves.addAll(regionLeaves(left));
        leaves.addAll(regionLeaves(right));
        if (leaves.isEmpty()) {
            System.err.println("neither directory contains any .mca files");
            System.exit(1);
        }

        ExecutorService pool = Executors.newFixedThreadPool(threads, runnable -> {
            Thread t = new Thread(runnable, "rocksmc-compare");
            t.setDaemon(true);
            return t;
        });
        long start = System.nanoTime();
        AtomicInteger totalChunks = new AtomicInteger();
        List<String> problems = new ArrayList<>();
        try {
            for (String leaf : leaves) {
                problems.addAll(compareLeaf(new File(left, leaf), new File(right, leaf),
                    leaf, pool, totalChunks));
            }
        } finally {
            pool.shutdownNow();
        }

        System.out.println(line());
        System.out.printf("compared %,d chunks in %,d ms%n", totalChunks.get(),
            (System.nanoTime() - start) / 1_000_000L);
        if (problems.isEmpty()) {
            System.out.println("RESULT: IDENTICAL -- every chunk matches, "
                + "and neither world holds a chunk the other lacks.");
        } else {
            System.out.println("RESULT: DIFFERENT -- " + problems.size()
                + " problem(s):");
            int shown = 0;
            for (String problem : problems) {
                if (shown++ == 40) {
                    System.out.println("  ... and " + (problems.size() - 40) + " more");
                    break;
                }
                System.out.println("  " + problem);
            }
            System.exit(1);
        }
    }

    /** Compares one leaf directory, one region file per worker. */
    private static List<String> compareLeaf(File left, File right, String leaf,
            ExecutorService pool, AtomicInteger totalChunks) throws Exception {
        Set<String> names = new TreeSet<>();
        names.addAll(regionFileNames(left));
        names.addAll(regionFileNames(right));
        if (names.isEmpty()) {
            return new ArrayList<>();
        }

        List<Future<List<String>>> futures = new ArrayList<>();
        AtomicLong chunks = new AtomicLong();
        for (String name : names) {
            File a = new File(left, name);
            File b = new File(right, name);
            futures.add(pool.submit((Callable<List<String>>) () -> {
                List<String> found = new ArrayList<>();
                // An .mca present on one side only is a real difference, unless it holds
                // no chunks: vanilla and this exporter both leave empty region files
                // behind in normal operation, so an empty one is not a discrepancy.
                Map<ChunkPos, NbtCompound> chunksA = a.isFile() ? read(a, found) : null;
                Map<ChunkPos, NbtCompound> chunksB = b.isFile() ? read(b, found) : null;
                if (chunksA == null) {
                    if (chunksB != null && !chunksB.isEmpty()) {
                        found.add(leaf + "/" + name + ": only in b, with "
                            + chunksB.size() + " chunks");
                    }
                    return found;
                }
                if (chunksB == null) {
                    if (!chunksA.isEmpty()) {
                        found.add(leaf + "/" + name + ": only in a, with "
                            + chunksA.size() + " chunks");
                    }
                    return found;
                }
                for (Map.Entry<ChunkPos, NbtCompound> entry : chunksA.entrySet()) {
                    NbtCompound other = chunksB.get(entry.getKey());
                    if (other == null) {
                        found.add(leaf + "/" + name + " " + entry.getKey()
                            + ": missing from b");
                    } else if (!entry.getValue().equals(other)) {
                        found.add(leaf + "/" + name + " " + entry.getKey()
                            + ": NBT differs");
                    }
                }
                for (ChunkPos pos : chunksB.keySet()) {
                    if (!chunksA.containsKey(pos)) {
                        found.add(leaf + "/" + name + " " + pos + ": extra in b");
                    }
                }
                chunks.addAndGet(chunksA.size());
                return found;
            }));
        }

        List<String> problems = new ArrayList<>();
        for (Future<List<String>> future : futures) {
            problems.addAll(future.get());
        }
        totalChunks.addAndGet((int) chunks.get());
        System.out.printf("%-28s %,10d chunks  %4d region files  %s%n", leaf,
            chunks.get(), names.size(),
            problems.isEmpty() ? "match" : problems.size() + " PROBLEM(S)");
        return problems;
    }

    private static Map<ChunkPos, NbtCompound> read(File file, List<String> problems)
            throws IOException {
        Map<ChunkPos, NbtCompound> chunks = new HashMap<>();
        AnvilReader.Report report = new AnvilReader.Report();
        AnvilReader.stream(file, report, entry -> chunks.put(entry.pos(), entry.nbt()));
        // corruption() rather than total(): both worlds legitimately contain empty
        // region files, which vanilla creates on demand whether or not a chunk is ever
        // generated there. Counting those as problems reported 563 differences against
        // an export that was in fact identical.
        if (report.corruption() > 0) {
            problems.add(file.getName() + ": unreadable chunks: " + report);
        }
        return chunks;
    }

    /** Every directory holding {@code .mca} files, relative to the world root. */
    private static Set<String> regionLeaves(File world) {
        Set<String> leaves = new TreeSet<>();
        for (String dimension : new String[] {"", "DIM-1", "DIM1"}) {
            for (String leaf : new String[] {"region", "poi", "entities"}) {
                String relative = dimension.isEmpty() ? leaf : dimension + "/" + leaf;
                if (!regionFileNames(new File(world, relative)).isEmpty()) {
                    leaves.add(relative);
                }
            }
        }
        // Custom dimensions, which live under dimensions/<namespace>/<path>/.
        File custom = new File(world, "dimensions");
        File[] namespaces = custom.listFiles(File::isDirectory);
        if (namespaces != null) {
            for (File namespace : namespaces) {
                File[] paths = namespace.listFiles(File::isDirectory);
                if (paths == null) {
                    continue;
                }
                for (File path : paths) {
                    for (String leaf : new String[] {"region", "poi", "entities"}) {
                        if (!regionFileNames(new File(path, leaf)).isEmpty()) {
                            leaves.add("dimensions/" + namespace.getName() + "/"
                                + path.getName() + "/" + leaf);
                        }
                    }
                }
            }
        }
        return leaves;
    }

    private static Set<String> regionFileNames(File dir) {
        Set<String> names = new TreeSet<>();
        String[] listed = dir.list((d, name) -> name.endsWith(".mca"));
        if (listed != null) {
            for (String name : listed) {
                names.add(name);
            }
        }
        return names;
    }

    private static String requireValue(String[] args, int index, String flag) {
        if (index >= args.length) {
            System.err.println(flag + " needs a value");
            usage();
            System.exit(2);
        }
        return args[index];
    }

    private static String line() {
        return "--------------------------------------------------------------------------";
    }

    private static void usage() {
        System.err.println("Compares two Anvil world directories chunk for chunk.");
        System.err.println();
        System.err.println("  --a <dir>        the first world directory");
        System.err.println("  --b <dir>        the second world directory");
        System.err.println("  --threads <n>    parallel region workers");
        System.err.println();
        System.err.println("Compares parsed NBT, not bytes: region files differ");
        System.err.println("byte-for-byte while holding identical chunks.");
    }

    private CompareMain() {
    }
}
