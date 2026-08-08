import org.rocksdb.*;

import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/**
 * Phase 0 spike: does RocksDB's integrated BlobDB honour ZSTD trained
 * dictionaries?
 *
 * WHY THIS MATTERS
 * ----------------
 * Minecraft chunk payloads are ~8 KiB NBT blobs that are highly redundant
 * *across* chunks: block-state palette strings ("minecraft:stone" plus a
 * Properties compound) are re-encoded verbatim in every chunk, biome arrays
 * are often identical between neighbours, and light data is long runs of
 * repeated nibbles. Vanilla's per-chunk DEFLATE cannot see any of that, since
 * each chunk is compressed in isolation.
 *
 * A ZSTD trained dictionary would capture that cross-chunk redundancy. But
 * chunks are large enough to live in blob files, and RocksDB's Java API exposes
 * `setBlobCompressionType` with NO blob-specific compression-options setter --
 * dictionary settings only exist on the column-family-level
 * `setCompressionOptions`, which nominally governs SST block compression.
 *
 * If blob files ignore dictionaries, each chunk compresses independently, which
 * is exactly the per-chunk scope Anvil already has, and the compression
 * argument for switching engines collapses.
 *
 * EXPERIMENT (2x2, with a positive control)
 * -----------------------------------------
 *   A: blobs ON,  dict ON     \_ A vs B: do dictionaries help BLOB files?
 *   B: blobs ON,  dict OFF    /
 *   C: blobs OFF, dict ON     \_ C vs D: do dictionaries help SST files?
 *   D: blobs OFF, dict OFF    /          (positive control -- validates method)
 *
 * C vs D is the load-bearing part of the design. If C shows a clear win but A
 * does not, the conclusion "blob files ignore dictionaries" is sound. If
 * neither shows a win, the harness itself is broken and proves nothing.
 *
 * Same synthetic corpus, same seed, same write order for all four runs.
 */
public final class BlobDictSpike {

    private static final int VALUE_SIZE = 8192;   // ~ vanilla's 8096-byte ChunkBuffer hint
    private static final int NUM_VALUES = 4000;   // ~32 MiB logical
    private static final long SEED = 20260809L;

    /** Block-state names, re-encoded in every chunk by vanilla -> cross-value redundancy. */
    private static final String[] PALETTE = {
        "minecraft:stone", "minecraft:granite", "minecraft:diorite", "minecraft:andesite",
        "minecraft:dirt", "minecraft:grass_block", "minecraft:sand", "minecraft:gravel",
        "minecraft:water", "minecraft:lava", "minecraft:bedrock", "minecraft:deepslate",
        "minecraft:oak_log", "minecraft:oak_leaves", "minecraft:birch_log", "minecraft:cobblestone",
        "minecraft:coal_ore", "minecraft:iron_ore", "minecraft:gold_ore", "minecraft:diamond_ore",
        "minecraft:redstone_ore", "minecraft:lapis_ore", "minecraft:emerald_ore", "minecraft:copper_ore",
    };

    private static final String[] PROPERTY_KV = {
        "axis=y", "axis=x", "axis=z", "waterlogged=false", "waterlogged=true",
        "snowy=false", "persistent=false", "distance=7", "level=0", "facing=north",
    };

    public static void main(String[] args) throws Exception {
        RocksDB.loadLibrary();
        System.out.println("RocksDB native library loaded.");

        // Fraction of each value that is unique incompressible noise (entity data
        // etc.). The first run used ~0.5, which turned out to dominate the result
        // and understate the dictionary prize. Pass a smaller value to model
        // terrain-dominated chunks, which are the common case.
        double tailFraction = args.length > 0 ? Double.parseDouble(args[0]) : 0.5;

        System.out.printf("corpus: %d values x %d bytes = %.1f MiB logical, tailFraction=%.2f%n%n",
            NUM_VALUES, VALUE_SIZE, (NUM_VALUES * (long)VALUE_SIZE) / 1048576.0, tailFraction);

        List<byte[]> corpus = buildCorpus(tailFraction);

        Result a = run("A  blobs=ON   dict=ON ", corpus, true, true);
        Result b = run("B  blobs=ON   dict=OFF", corpus, true, false);
        Result c = run("C  blobs=OFF  dict=ON ", corpus, false, true);
        Result d = run("D  blobs=OFF  dict=OFF", corpus, false, false);

        long logical = NUM_VALUES * (long)VALUE_SIZE;

        System.out.println();
        System.out.println("=================== RESULTS ===================");
        System.out.printf("%-22s %12s %12s %12s %8s%n", "config", "sst bytes", "blob bytes", "total", "ratio");
        for (Result r : List.of(a, b, c, d)) {
            System.out.printf("%-22s %12d %12d %12d %7.2fx%n",
                r.label, r.sstBytes, r.blobBytes, r.total(), logical / (double)r.total());
        }

        System.out.println();
        System.out.println("=================== VERDICT ===================");

        // Positive control first: if dictionaries do not help SSTs, the harness is invalid.
        double sstGain = pctSmaller(d.total(), c.total());
        System.out.printf("CONTROL  C vs D (SST,  dict on vs off): %+.1f%% size change%n", -sstGain);
        boolean harnessValid = sstGain > 2.0;
        if (!harnessValid) {
            System.out.println("  !! Dictionaries did NOT measurably help SST files either.");
            System.out.println("  !! HARNESS SUSPECT -- this run proves nothing about blob files.");
        } else {
            System.out.println("  -> Dictionaries demonstrably work in this harness. Method validated.");
        }

        double blobGain = pctSmaller(b.total(), a.total());
        System.out.printf("TEST     A vs B (blob, dict on vs off): %+.1f%% size change%n", -blobGain);

        System.out.println();
        if (harnessValid && blobGain > 2.0) {
            System.out.println("SUPPORTED: blob files honour trained dictionaries.");
            System.out.println("  => Proceed with plan as written: BlobDB + ZSTD trained dictionary.");
        } else if (harnessValid) {
            System.out.println("NOT SUPPORTED: blob files ignore trained dictionaries.");
            System.out.println("  => Design must branch. Options:");
            System.out.println("     A) app-level shared dictionary (we own the lifecycle)");
            System.out.println("     B) raise min_blob_size above chunk size, keep chunks in the LSM");
            System.out.println("     C) per-chunk ZSTD for chunks, dictionaries only for small CFs");
        } else {
            System.out.println("INCONCLUSIVE -- fix the harness before drawing any conclusion.");
        }

        // Report whether blobs were actually exercised; a zero here invalidates A/B.
        System.out.println();
        if (a.blobBytes == 0 || b.blobBytes == 0) {
            System.out.println("WARNING: blob-enabled runs produced 0 blob bytes -- values did not reach");
            System.out.println("         blob files, so the A/B comparison is meaningless.");
        }
        if (c.blobBytes != 0 || d.blobBytes != 0) {
            System.out.println("WARNING: blob-disabled runs produced blob bytes -- config leak.");
        }
    }

    private record Result(String label, long sstBytes, long blobBytes) {
        long total() {
            return sstBytes + blobBytes;
        }
    }

    private static double pctSmaller(long baseline, long candidate) {
        return (baseline - candidate) * 100.0 / baseline;
    }

    private static Result run(String label, List<byte[]> corpus, boolean blobs, boolean dict) throws Exception {
        Path dir = Files.createTempDirectory("blobdict-");
        try (Options opts = new Options();
             CompressionOptions co = new CompressionOptions();
             CompressionOptions bco = new CompressionOptions()) {

            opts.setCreateIfMissing(true);
            opts.setCompressionType(CompressionType.ZSTD_COMPRESSION);
            opts.setBottommostCompressionType(CompressionType.ZSTD_COMPRESSION);

            // Dictionary settings live only at CF level; there is no blob-specific
            // equivalent. Whether blob files consult these is precisely the question.
            if (dict) {
                co.setMaxDictBytes(64 * 1024);
                co.setZStdMaxTrainBytes(1024 * 1024);
                bco.setMaxDictBytes(64 * 1024);
                bco.setZStdMaxTrainBytes(1024 * 1024);
                bco.setEnabled(true);
            }
            opts.setCompressionOptions(co);
            opts.setBottommostCompressionOptions(bco);

            if (blobs) {
                opts.setEnableBlobFiles(true);
                opts.setMinBlobSize(1024);          // 8 KiB values -> blob files
                opts.setBlobCompressionType(CompressionType.ZSTD_COMPRESSION);
                opts.setEnableBlobGarbageCollection(true);
            } else {
                opts.setEnableBlobFiles(false);     // values stay in the LSM
            }

            try (RocksDB db = RocksDB.open(opts, dir.toString());
                 WriteOptions wo = new WriteOptions()) {
                wo.setDisableWAL(true);             // measure stored form, not the log
                for (int i = 0; i < corpus.size(); i++) {
                    db.put(wo, key(i), corpus.get(i));
                }
                db.flush(new FlushOptions().setWaitForFlush(true));
                // Force compaction: dictionaries are trained during flush/compaction.
                db.compactRange();
            }

            long sst = sumByExtension(dir, ".sst");
            long blob = sumByExtension(dir, ".blob");
            System.out.printf("%s  sst=%-11d blob=%-11d total=%d%n", label, sst, blob, sst + blob);
            return new Result(label, sst, blob);
        } finally {
            deleteRecursively(dir);
        }
    }

    private static byte[] key(int i) {
        // 12-byte key, loosely mirroring (dimension, morton) chunk addressing.
        byte[] k = new byte[12];
        k[0] = 0;
        for (int b = 0; b < 8; b++) {
            k[4 + b] = (byte)(i >>> (8 * (7 - b)));
        }
        return k;
    }

    /**
     * Synthesises chunk-like payloads: a shared-vocabulary palette section, long
     * runs standing in for light nibble arrays, a near-uniform biome array, and a
     * small unique tail for entity/tile-entity data. Deterministic.
     */
    private static List<byte[]> buildCorpus(double tailFraction) {
        Random rnd = new Random(SEED);
        int tailBytes = (int)(VALUE_SIZE * tailFraction);
        List<byte[]> out = new ArrayList<>(NUM_VALUES);
        for (int i = 0; i < NUM_VALUES; i++) {
            byte[] v = new byte[VALUE_SIZE];
            int p = 0;
            int structuredLimit = VALUE_SIZE - tailBytes;

            // --- palette: strings repeated verbatim across every value ---
            int paletteEntries = 24 + rnd.nextInt(12);
            for (int e = 0; e < paletteEntries && p < structuredLimit - 64; e++) {
                p = putAscii(v, p, "Name");
                p = putAscii(v, p, PALETTE[rnd.nextInt(PALETTE.length)]);
                if (rnd.nextInt(3) == 0) {
                    p = putAscii(v, p, "Properties");
                    p = putAscii(v, p, PROPERTY_KV[rnd.nextInt(PROPERTY_KV.length)]);
                }
            }

            // --- light-array analogue: long runs of one nibble pattern ---
            int lightBudget = (int)((structuredLimit - p) * 0.6);
            byte skyFill = (byte)0xFF;
            for (int j = 0; j < lightBudget && p < structuredLimit; j++, p++) {
                v[p] = (j % 97 == 0) ? (byte)rnd.nextInt(256) : skyFill;
            }

            // --- biome-array analogue: near-uniform int array ---
            byte biome = (byte)(1 + rnd.nextInt(4));
            while (p < structuredLimit) {
                v[p++] = biome;
            }

            // --- unique tail: incompressible, stands in for entity data ---
            while (p < VALUE_SIZE) {
                v[p++] = (byte)rnd.nextInt(256);
            }
            out.add(v);
        }
        return out;
    }

    private static int putAscii(byte[] dst, int pos, String s) {
        for (int i = 0; i < s.length() && pos < dst.length; i++) {
            dst[pos++] = (byte)s.charAt(i);
        }
        return pos;
    }

    private static long sumByExtension(Path dir, String ext) throws Exception {
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(Files::isRegularFile)
                .filter(f -> f.getFileName().toString().endsWith(ext))
                .mapToLong(f -> {
                    try {
                        return Files.size(f);
                    } catch (Exception e) {
                        return 0L;
                    }
                })
                .sum();
        }
    }

    private static void deleteRecursively(Path dir) throws Exception {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> s = Files.walk(dir)) {
            s.sorted(Comparator.reverseOrder()).forEach(f -> {
                try {
                    Files.delete(f);
                } catch (Exception ignored) {
                }
            });
        }
    }
}
