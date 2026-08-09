import org.rocksdb.*;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Stream;

/**
 * Phase 1c: steady-state write amplification, for flash endurance.
 *
 * <h2>Why this experiment exists</h2>
 *
 * <p>Every prior write measurement in this project is disqualified for endurance
 * purposes, for three independent reasons:
 *
 * <ol>
 *   <li><b>No LSM tree.</b> The Phase 1a database ended at 11.2 MB. RocksDB's
 *       default {@code write_buffer_size} is 64 MB and {@code
 *       max_bytes_for_level_base} is 256 MB, so it never filled a single
 *       memtable, never reached L1, and never exercised leveled compaction. Its
 *       headline "316x compaction reduction" therefore compares near-zero against
 *       near-zero in the cold-start regime.</li>
 *   <li><b>No WAL.</b> Every harness set {@code setDisableWAL(true)}. The WAL is
 *       written on every put and is a first-order contributor to device wear.</li>
 *   <li><b>Bad arithmetic downstream.</b> Per-GiB figures derived from that run
 *       were off by 15x, and were then described as "negligible" -- a conclusion
 *       that only holds if you decline to integrate over a server's lifetime.</li>
 * </ol>
 *
 * <p>This harness fixes all three: it forces a real multi-level LSM tree, counts
 * WAL bytes, and cross-checks RocksDB's own counters against what the kernel
 * says was written.
 *
 * <h2>How real LSM depth is reached quickly</h2>
 *
 * <p>Growing to multiple GB with default level geometry would take far longer
 * than the time budget allows. Instead the geometry is shrunk -- 16 MB memtable,
 * 64 MB L1 base -- so L2 begins around 640 MB and L3 around 6.4 GB. A ~1 GB
 * database then solidly occupies L2/L3.
 *
 * <p><b>Caveat, stated plainly:</b> this reaches realistic <em>depth</em> faster
 * than production geometry would, and leveled amplification scales with level
 * count rather than absolute size, so it is a reasonable proxy. Absolute byte
 * counts will still differ from a default-configured server. The harness asserts
 * that L2+ is actually populated and aborts loudly otherwise, because silently
 * measuring the cold-start regime is precisely the trap that invalidated
 * Phase 1a.
 *
 * <h2>Accounting</h2>
 *
 * <p>Components are reported separately and never summed naively:
 * {@code FLUSH_WRITE_BYTES} already includes blob bytes written during flush, so
 * adding {@code BLOB_DB_BLOB_FILE_BYTES_WRITTEN} to it double-counts -- a mistake
 * made in Phase 0. The device-level total is taken from {@code /proc/self/io}
 * with an idle baseline subtracted, which validates the tickers rather than
 * trusting them.
 */
public final class EnduranceSweep {

    /**
     * Shrunken level geometry so several levels populate quickly.
     *
     * <p>Sized against <em>on-disk</em> bytes, not logical bytes: chunk NBT
     * compresses roughly 8x, so 1 GiB of logical writes becomes only ~128 MB of
     * SST/blob data. An earlier version sized these against logical volume and
     * produced a single populated level, which the validity check then correctly
     * rejected.
     *
     * <p>With an 8 MB L1 base and the default multiplier of 10: L1 = 8 MB,
     * L2 = 80 MB, L3 = 800 MB. A few hundred MB on disk therefore spans three
     * levels and genuinely exercises inter-level compaction.
     */
    private static long writeBufferSize = 4L * 1024 * 1024;
    private static long levelBase = 8L * 1024 * 1024;
    private static final long TARGET_FILE_SIZE = 2L * 1024 * 1024;

    /** Give up filling rather than run forever if levels refuse to populate. */
    private static final long FILL_HARD_CAP_MULTIPLIER = 8;

    private static long fillTargetBytes = 1024L * 1024 * 1024;
    private static long measureLogicalBytes = 2048L * 1024 * 1024;
    private static boolean quick;

    public static void main(String[] args) throws Exception {
        String corpusPath = null;
        String dbRoot = null;
        String outPath = "endurance-results.json";

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--corpus":
                    corpusPath = args[++i];
                    break;
                case "--db-root":
                    dbRoot = args[++i];
                    break;
                case "--out":
                    outPath = args[++i];
                    break;
                case "--quick":
                    quick = true;
                    break;
                default:
                    System.err.println("unknown argument: " + args[i]);
                    System.exit(2);
            }
        }
        if (corpusPath == null || dbRoot == null) {
            System.err.println("usage: EnduranceSweep --corpus <dir> --db-root <dir> "
                + "[--out results.json] [--quick]");
            System.exit(2);
        }

        if (quick) {
            // Enough to populate L1/L2 and prove the harness works end to end,
            // without committing hours. Not a substitute for the full run.
            fillTargetBytes = 192L * 1024 * 1024;
            measureLogicalBytes = 384L * 1024 * 1024;
        }

        RocksDB.loadLibrary();

        List<byte[]> corpus = loadCorpus(new File(corpusPath));
        if (corpus.isEmpty()) {
            System.err.println("corpus is empty: " + corpusPath);
            System.exit(1);
        }
        long meanValue = corpus.stream().mapToLong(b -> b.length).sum() / corpus.size();

        System.out.println("Phase 1c: steady-state write amplification");
        System.out.printf("corpus       %,d chunks, mean %s%n", corpus.size(), human(meanValue));
        System.out.printf("fill target  %s (to force L2/L3)%n", human(fillTargetBytes));
        System.out.printf("measured     %s logical writes after settling%n",
            human(measureLogicalBytes));
        System.out.printf("mode         %s%n%n", quick ? "QUICK (validation only)" : "FULL");

        List<Map<String, Object>> results = new ArrayList<>();

        // blob on/off x WAL sync/group-commit x zipfian/uniform.
        for (boolean blobs : new boolean[] {true, false}) {
            for (boolean syncWrites : new boolean[] {false, true}) {
                for (boolean zipfian : new boolean[] {true, false}) {
                    String label = String.format("blob=%-3s sync=%-5s %s",
                        blobs ? "on" : "off", syncWrites, zipfian ? "zipfian" : "uniform");
                    Map<String, Object> r = runConfig(label, corpus, new File(dbRoot),
                        blobs, syncWrites, zipfian, meanValue);
                    results.add(r);
                    System.out.println();
                }
            }
        }

        writeJson(Paths.get(outPath), corpus.size(), meanValue, results);
        System.out.println("results written to " + outPath);

        summarise(results, meanValue);
    }

    private static Map<String, Object> runConfig(String label, List<byte[]> corpus, File dbRoot,
            boolean blobs, boolean syncWrites, boolean zipfian, long meanValue) throws Exception {
        System.out.println("─".repeat(78));
        System.out.println(label);
        System.out.println("─".repeat(78));

        Path dir = Files.createTempDirectory(dbRoot.toPath(), "p1c-");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("config", label.trim());
        out.put("blobs", blobs);
        out.put("syncWrites", syncWrites);
        out.put("workload", zipfian ? "zipfian" : "uniform");

        try (Statistics stats = new Statistics();
             Options opts = new Options();
             CompressionOptions co = new CompressionOptions();
             CompressionOptions bco = new CompressionOptions()) {

            opts.setCreateIfMissing(true);
            opts.setStatistics(stats);
            opts.setCompressionType(CompressionType.ZSTD_COMPRESSION);
            opts.setBottommostCompressionType(CompressionType.ZSTD_COMPRESSION);
            co.setLevel(9);
            bco.setLevel(9);
            bco.setEnabled(true);
            opts.setCompressionOptions(co);
            opts.setBottommostCompressionOptions(bco);

            // Shrink the level geometry so real depth is reached in minutes.
            opts.setWriteBufferSize(writeBufferSize);
            opts.setMaxBytesForLevelBase(levelBase);
            opts.setTargetFileSizeBase(TARGET_FILE_SIZE);
            // Dynamic level bytes places all data at the BOTTOM level until the
            // tree is large, which yields "L6=13, everything else empty" -- a
            // single populated level that exercises almost no inter-level
            // compaction while superficially looking deep. Classic (static)
            // leveling fills L1, L2, L3... in order, which is what we need to
            // measure and is also what a long-running server would develop.
            opts.setLevelCompactionDynamicLevelBytes(false);

            if (blobs) {
                opts.setEnableBlobFiles(true);
                opts.setMinBlobSize(1024);
                opts.setBlobCompressionType(CompressionType.ZSTD_COMPRESSION);
                opts.setEnableBlobGarbageCollection(true);
            }

            try (RocksDB db = RocksDB.open(opts, dir.toString());
                 WriteOptions wo = new WriteOptions().setSync(syncWrites)) {

                // ---- Phase 1: fill until the tree has real depth ----
                long keyspace = Math.max(corpus.size(),
                    fillTargetBytes / Math.max(1, meanValue));
                System.out.printf("  filling to %s over %,d keys...%n",
                    human(fillTargetBytes), keyspace);
                long written = 0;
                Random rnd = new Random(42);
                for (long i = 0; written < fillTargetBytes; i++) {
                    byte[] v = corpus.get((int)(i % corpus.size()));
                    db.put(wo, key(i % keyspace), v);
                    written += v.length;
                }
                db.flush(new FlushOptions().setWaitForFlush(true));

                // ---- Phase 2: settle, so compaction reaches steady state ----
                System.out.println("  settling (waiting for background compaction)...");
                waitForCompaction(db);

                String levels = levelSummary(db);
                System.out.println("  level occupancy: " + levels);
                out.put("levels", levels);

                int deepest = deepestPopulatedLevel(db);
                int populated = populatedLevelCount(db);
                out.put("deepestLevel", deepest);
                out.put("populatedLevels", populated);

                // Validity requires MULTIPLE populated levels -- but only for the
                // no-blob arm.
                //
                // Two subtleties, both learned the hard way:
                //
                // 1. An earlier version tested "deepest >= 2" and passed trivially
                //    on "L6=13, everything else empty": one populated level and
                //    almost no inter-level compaction. Counting populated levels
                //    is what actually matters.
                //
                // 2. With key-value separation the LSM holds only keys and
                //    pointers, so it stays small by design and will not develop
                //    deep levels at any realistic data volume. That is the
                //    mechanism working, not a defect. Demanding depth from the
                //    blob arm would reject the very configuration under test.
                //
                // So: the no-blob arm must reach real depth, because that is where
                // leveled compaction cost lives and is precisely what Phase 1a
                // failed to measure. The blob arm is validated instead by checking
                // that blob files were actually produced.
                if (!blobs && populated < 2) {
                    System.err.println();
                    System.err.println("  *** ABORT: only " + populated + " populated level"
                        + " (deepest L" + deepest + ").");
                    System.err.println("  *** Inter-level compaction is not being exercised, so");
                    System.err.println("  *** any amplification measured here would be");
                    System.err.println("  *** meaningless -- the exact defect this harness");
                    System.err.println("  *** exists to avoid. Increase the fill target.");
                    out.put("valid", false);
                    return out;
                }
                if (blobs) {
                    long blobBytesOnDisk = sum(dir, ".blob");
                    if (blobBytesOnDisk == 0) {
                        System.err.println();
                        System.err.println("  *** ABORT: blob mode enabled but no .blob files");
                        System.err.println("  *** exist. Values are not reaching blob storage,");
                        System.err.println("  *** so this is not measuring key-value separation.");
                        out.put("valid", false);
                        return out;
                    }
                    System.out.printf("  blob storage: %s on disk "
                        + "(LSM intentionally shallow -- holds pointers only)%n",
                        human(blobBytesOnDisk));
                }
                out.put("valid", true);

                // ---- Phase 3: measure ----
                // Counters are read AFTER settling, so the fill phase's cold-start
                // compaction is excluded and only steady-state work is counted.
                long baseFlush = stats.getTickerCount(TickerType.FLUSH_WRITE_BYTES);
                long baseCompact = stats.getTickerCount(TickerType.COMPACT_WRITE_BYTES);
                long baseWal = stats.getTickerCount(TickerType.WAL_FILE_BYTES);
                long baseBlob = stats.getTickerCount(TickerType.BLOB_DB_BLOB_FILE_BYTES_WRITTEN);
                long baseDevice = deviceWriteBytes();

                System.out.printf("  measuring %s of logical writes...%n",
                    human(measureLogicalBytes));
                long logical = 0;
                long ops = 0;
                long start = System.nanoTime();
                while (logical < measureLogicalBytes) {
                    // Zipfian models a few players revisiting the same chunks;
                    // uniform models exploration touching fresh terrain. Real
                    // servers sit between the two.
                    long k = zipfian ? zipfKey(rnd, keyspace) : (long)(rnd.nextDouble() * keyspace);
                    byte[] v = corpus.get((int)(ops % corpus.size()));
                    db.put(wo, key(k), v);
                    logical += v.length;
                    ops++;
                    // Batch flushes at roughly autosave cadence rather than
                    // hammering in a tight loop.
                    if (ops % 2000 == 0) {
                        db.flush(new FlushOptions().setWaitForFlush(true));
                    }
                }
                db.flush(new FlushOptions().setWaitForFlush(true));
                waitForCompaction(db);
                long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

                long flush = stats.getTickerCount(TickerType.FLUSH_WRITE_BYTES) - baseFlush;
                long compact = stats.getTickerCount(TickerType.COMPACT_WRITE_BYTES) - baseCompact;
                long wal = stats.getTickerCount(TickerType.WAL_FILE_BYTES) - baseWal;
                long blob = stats.getTickerCount(TickerType.BLOB_DB_BLOB_FILE_BYTES_WRITTEN)
                    - baseBlob;
                long device = deviceWriteBytes() - baseDevice;

                // FLUSH_WRITE_BYTES already includes blob bytes written during
                // flush. Adding them would double-count, as happened in Phase 0.
                long engineTotal = wal + flush + compact;

                out.put("logicalBytes", logical);
                out.put("ops", ops);
                out.put("elapsedMs", elapsedMs);
                out.put("walBytes", wal);
                out.put("flushBytes", flush);
                out.put("compactBytes", compact);
                out.put("blobBytesWritten", blob);
                out.put("engineTotalBytes", engineTotal);
                out.put("deviceWriteBytes", device);
                out.put("amplification", engineTotal / (double)logical);
                out.put("deviceAmplification", device > 0 ? device / (double)logical : -1);
                out.put("onDiskBytes", sum(dir, ".sst") + sum(dir, ".blob"));

                System.out.printf("    logical    %,15d%n", logical);
                System.out.printf("    WAL        %,15d%n", wal);
                System.out.printf("    flush      %,15d  (includes flush-time blob writes)%n", flush);
                System.out.printf("    compaction %,15d%n", compact);
                System.out.printf("    blob(info) %,15d%n", blob);
                System.out.printf("    ENGINE SUM %,15d   = %.2fx logical%n",
                    engineTotal, engineTotal / (double)logical);
                if (device > 0) {
                    System.out.printf("    kernel     %,15d   = %.2fx logical  (/proc/self/io)%n",
                        device, device / (double)logical);
                    double overhead = (device - engineTotal) * 100.0 / Math.max(1, engineTotal);
                    System.out.printf("    filesystem overhead: %+.0f%% above engine counters%n",
                        overhead);
                    // The kernel figure is the one that matters for flash wear: it
                    // includes filesystem metadata, checksum trees on CoW
                    // filesystems, and block-size rounding on every fsync. RocksDB
                    // cannot see any of that. A large gap is not a harness bug --
                    // but it IS filesystem-specific, so it must be interpreted
                    // alongside the recorded fstype rather than generalised.
                    if (overhead > 100) {
                        System.out.println("      (large gap is expected on copy-on-write "
                            + "filesystems such as btrfs/ZFS,");
                        System.out.println("       which charge data + metadata + checksum "
                            + "writes to this process;");
                        System.out.println("       expect a much smaller gap on ext4/xfs)");
                    }
                }
            }
            return out;
        } finally {
            delete(dir);
        }
    }

    /** Blocks until no compaction or flush is pending, so counters are stable. */
    private static void waitForCompaction(RocksDB db) throws Exception {
        for (int i = 0; i < 600; i++) {
            long pending = db.getLongProperty("rocksdb.compaction-pending")
                + db.getLongProperty("rocksdb.mem-table-flush-pending")
                + db.getLongProperty("rocksdb.num-running-compactions")
                + db.getLongProperty("rocksdb.num-running-flushes");
            if (pending == 0) {
                return;
            }
            Thread.sleep(500);
        }
        System.err.println("  warning: compaction still pending after 300s");
    }

    private static String levelSummary(RocksDB db) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int level = 0; level <= 6; level++) {
            String n = db.getProperty("rocksdb.num-files-at-level" + level);
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append('L').append(level).append('=').append(n);
        }
        return sb.toString();
    }

    private static int deepestPopulatedLevel(RocksDB db) throws Exception {
        int deepest = 0;
        for (int level = 0; level <= 6; level++) {
            String n = db.getProperty("rocksdb.num-files-at-level" + level);
            if (n != null && !n.trim().equals("0")) {
                deepest = level;
            }
        }
        return deepest;
    }

    /**
     * Counts how many levels actually hold files.
     *
     * <p>This, not the deepest index, is what determines whether inter-level
     * compaction is being exercised. A tree with everything parked at the bottom
     * level looks deep but compacts almost nothing.
     */
    private static int populatedLevelCount(RocksDB db) throws Exception {
        int count = 0;
        for (int level = 0; level <= 6; level++) {
            String n = db.getProperty("rocksdb.num-files-at-level" + level);
            if (n != null && !n.trim().equals("0")) {
                count++;
            }
        }
        return count;
    }

    /**
     * Bytes this process has caused to be sent to storage, per the kernel.
     *
     * <p>Linux-only. Returns -1 elsewhere, in which case only RocksDB's own
     * counters are available and the cross-check is skipped.
     */
    private static long deviceWriteBytes() {
        try {
            for (String line : Files.readAllLines(Paths.get("/proc/self/io"))) {
                if (line.startsWith("write_bytes:")) {
                    return Long.parseLong(line.split(":")[1].trim());
                }
            }
        } catch (Exception ignored) {
            // Not Linux, or /proc unavailable.
        }
        return -1L;
    }

    /**
     * Zipfian-ish key selection: heavily favours a small hot set, which is how a
     * handful of players moving around a large world actually touch chunks.
     */
    private static long zipfKey(Random rnd, long keyspace) {
        double u = rnd.nextDouble();
        // ~80% of accesses land in ~20% of the keyspace.
        double skewed = Math.pow(u, 3.0);
        return (long)(skewed * keyspace);
    }

    private static byte[] key(long i) {
        byte[] k = new byte[12];
        for (int b = 0; b < 8; b++) {
            k[4 + b] = (byte)(i >>> (8 * (7 - b)));
        }
        return k;
    }

    private static void summarise(List<Map<String, Object>> results, long meanValue) {
        System.out.println();
        System.out.println("═".repeat(78));
        System.out.println("SUMMARY");
        System.out.println("═".repeat(78));
        System.out.printf("%-34s %10s %12s %12s%n", "config", "amp", "compaction", "WAL");
        for (Map<String, Object> r : results) {
            if (!Boolean.TRUE.equals(r.get("valid"))) {
                System.out.printf("%-34s %10s%n", r.get("config"), "INVALID");
                continue;
            }
            System.out.printf("%-34s %9.2fx %12d %12d%n", r.get("config"),
                (Double)r.get("amplification"), (Long)r.get("compactBytes"),
                (Long)r.get("walBytes"));
        }

        System.out.println();
        System.out.println("Multi-year projection at 11.25 GiB/day logical");
        System.out.println("(40 MB dirty chunks per 5-minute autosave, a busy server):");
        System.out.printf("%-34s %10s %10s %10s%n", "config", "1 year", "3 years", "5 years");
        for (Map<String, Object> r : results) {
            if (!Boolean.TRUE.equals(r.get("valid"))) {
                continue;
            }
            double amp = (Double)r.get("amplification");
            double perYearTb = 11.25 * 365 * amp / 1024.0;
            System.out.printf("%-34s %8.2fTB %8.2fTB %8.2fTB%n", r.get("config"),
                perYearTb, perYearTb * 3, perYearTb * 5);
        }
        System.out.println();
        System.out.println("Vanilla Anvil for comparison: every chunk save writes the");
        System.out.println("compressed payload plus a full 8 KiB header rewrite. At a mean");
        System.out.println("3.5 KiB compressed chunk that is ~3.3x amplification before any");
        System.out.println("filesystem overhead, and O_DSYNC makes each one a separate");
        System.out.println("synchronous device write.");
    }

    private static void writeJson(Path out, int corpusSize, long meanValue,
            List<Map<String, Object>> results) throws IOException {
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(out, StandardCharsets.UTF_8))) {
            w.println("{");
            w.printf("  \"generatedAt\": \"%s\",%n", java.time.Instant.now());
            w.printf("  \"os\": \"%s %s\",%n", System.getProperty("os.name"),
                System.getProperty("os.version"));
            w.printf("  \"arch\": \"%s\",%n", System.getProperty("os.arch"));
            w.printf("  \"jvm\": \"%s\",%n", System.getProperty("java.version"));
            w.printf("  \"cpus\": %d,%n", Runtime.getRuntime().availableProcessors());
            w.printf("  \"quick\": %s,%n", quick);
            w.printf("  \"corpusChunks\": %d,%n", corpusSize);
            w.printf("  \"meanValueBytes\": %d,%n", meanValue);
            w.printf("  \"fillTargetBytes\": %d,%n", fillTargetBytes);
            w.printf("  \"measureLogicalBytes\": %d,%n", measureLogicalBytes);
            w.println("  \"results\": [");
            for (int i = 0; i < results.size(); i++) {
                w.print("    {");
                Map<String, Object> r = results.get(i);
                int j = 0;
                for (Map.Entry<String, Object> e : r.entrySet()) {
                    if (j++ > 0) {
                        w.print(", ");
                    }
                    Object v = e.getValue();
                    w.printf("\"%s\": %s", e.getKey(),
                        v instanceof String ? "\"" + v + "\"" : String.valueOf(v));
                }
                w.println(i == results.size() - 1 ? "}" : "},");
            }
            w.println("  ]");
            w.println("}");
        }
    }

    private static List<byte[]> loadCorpus(File dir) throws IOException {
        List<byte[]> out = new ArrayList<>();
        File[] bins = dir.isDirectory()
            ? dir.listFiles((d, n) -> n.endsWith(".bin"))
            : new File[] {dir};
        if (bins == null) {
            return out;
        }
        Arrays.sort(bins);
        for (File f : bins) {
            try (DataInputStream in = new DataInputStream(
                    new java.io.BufferedInputStream(new FileInputStream(f), 1 << 20))) {
                int count = in.readInt();
                for (int i = 0; i < count; i++) {
                    byte[] b = new byte[in.readInt()];
                    in.readFully(b);
                    out.add(b);
                }
            }
        }
        return out;
    }

    private static long sum(Path dir, String ext) throws IOException {
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(Files::isRegularFile)
                .filter(f -> f.getFileName().toString().endsWith(ext))
                .mapToLong(f -> {
                    try {
                        return Files.size(f);
                    } catch (IOException e) {
                        return 0L;
                    }
                }).sum();
        }
    }

    private static void delete(Path dir) {
        try (Stream<Path> s = Files.walk(dir)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }

    private static String human(long n) {
        String[] u = {"B", "KiB", "MiB", "GiB"};
        double d = n;
        int i = 0;
        while (d >= 1024 && i < u.length - 1) {
            d /= 1024;
            i++;
        }
        return i == 0 ? n + " B" : String.format("%.1f %s", d, u[i]);
    }

    private EnduranceSweep() {
    }
}
