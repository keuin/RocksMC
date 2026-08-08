import org.rocksdb.*;

import java.io.DataInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/**
 * Phase 1a: validate the RocksDB configuration against REAL chunk NBT.
 *
 * WHY
 * ---
 * Phase 0 and 0c used a synthetic corpus of 8 KiB values, on the assumption that
 * RegionFile.ChunkBuffer's 8096-byte initial allocation reflected Mojang's size
 * expectation. Reading a real world showed that number tracks the *compressed*
 * size. Real uncompressed chunk NBT in a freshly generated world averages
 * ~51 KiB -- roughly 6x the synthetic value -- and vanilla's per-chunk DEFLATE
 * achieves 14.56x, far above the 4.76x the synthetic corpus suggested.
 *
 * Chunk NBT is therefore much more redundant than modelled. That invalidates two
 * decisions taken on the old numbers:
 *
 *   1. min_blob_size = 1 KiB was chosen so 8 KiB values would reach blob files.
 *      Is blob storage still right for 51 KiB values?
 *   2. The design stores NBT uncompressed and lets RocksDB compress it. Does
 *      ZSTD on large blobs actually beat vanilla's 14.56x DEFLATE? If not, the
 *      remaining compression rationale collapses.
 *
 * This harness answers both using byte-identical real chunk data, extracted from
 * .mca files by extract_corpus.py.
 *
 * The comparison that matters is against VANILLA, not against other RocksDB
 * configs: vanilla is the incumbent, and if it wins on size the honest
 * conclusion is that the swap costs disk space.
 */
public final class RealCorpusSpike {

    private static final int OVERWRITE_ROUNDS = 6;

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: RealCorpusSpike <corpus.bin> [vanillaBytes]");
            System.exit(2);
        }
        RocksDB.loadLibrary();

        List<byte[]> corpus = loadCorpus(args[0]);
        long logical = corpus.stream().mapToLong(b -> b.length).sum();
        long vanilla = args.length > 1 ? Long.parseLong(args[1]) : -1;

        int[] sizes = corpus.stream().mapToInt(b -> b.length).sorted().toArray();
        System.out.printf("real corpus: %d chunks, %s total, mean %s, p50 %s, max %s%n",
            corpus.size(), human(logical), human(logical / corpus.size()),
            human(sizes[sizes.length / 2]), human(sizes[sizes.length - 1]));
        if (vanilla > 0) {
            System.out.printf("vanilla Anvil (per-chunk DEFLATE): %s  ratio %.2fx%n",
                human(vanilla), logical / (double)vanilla);
        }
        System.out.println();

        // ---- Experiment 1: which configuration compresses real chunks best? ----
        System.out.println("=== Experiment 1: compression of real chunk NBT ===");
        List<Result> results = new ArrayList<>();
        results.add(run("A  blob(min=1KiB)  ZSTD", corpus, 1024, CompressionType.ZSTD_COMPRESSION, false));
        results.add(run("B  blob(min=1KiB)  LZ4 ", corpus, 1024, CompressionType.LZ4_COMPRESSION, false));
        results.add(run("C  blob(min=64KiB) ZSTD", corpus, 65536, CompressionType.ZSTD_COMPRESSION, false));
        results.add(run("D  no blobs, ZSTD+dict", corpus, -1, CompressionType.ZSTD_COMPRESSION, true));
        results.add(run("E  no blobs, ZSTD     ", corpus, -1, CompressionType.ZSTD_COMPRESSION, false));

        System.out.println();
        System.out.printf("%-26s %13s %13s %9s%n", "config", "sst", "blob", "ratio");
        for (Result r : results) {
            System.out.printf("%-26s %13d %13d %8.2fx%n",
                r.label, r.sst, r.blob, logical / (double)r.total());
        }

        Result best = results.stream().filter(r -> r.total() > 0)
            .min(Comparator.comparingLong(Result::total)).orElseThrow();
        System.out.println();
        System.out.printf("best RocksDB config: %s at %.2fx (%s)%n",
            best.label.trim(), logical / (double)best.total(), human(best.total()));
        if (vanilla > 0) {
            double delta = (best.total() - vanilla) * 100.0 / vanilla;
            System.out.printf("vs vanilla Anvil: %+.1f%% on-disk  ->  %s%n", delta,
                delta < 0 ? "RocksDB SMALLER" : "VANILLA SMALLER");
        }

        // ---- Experiment 2: bytes written under repeated overwrite ----
        System.out.println();
        System.out.printf("=== Experiment 2: bytes written, %d overwrite rounds ===%n",
            OVERWRITE_ROUNDS);
        Result w1 = runOverwrite("blob(min=1KiB) ZSTD", corpus, 1024);
        Result w2 = runOverwrite("no blobs       ZSTD", corpus, -1);

        long logicalW = logical * OVERWRITE_ROUNDS;
        System.out.println();
        System.out.printf("logical writes: %s%n", human(logicalW));
        System.out.printf("%-26s %13s %13s %13s%n", "config", "flush", "compact", "total");
        for (Result r : List.of(w1, w2)) {
            System.out.printf("%-26s %13d %13d %13d%n",
                r.label, r.flush, r.compact, r.flush + r.compact);
        }
        System.out.println();
        System.out.printf("compaction traffic: blobs=%d  noBlobs=%d  (%.1fx more without blobs)%n",
            w1.compact, w2.compact, w2.compact / (double)Math.max(1, w1.compact));

        // Vanilla's write cost per chunk save: the compressed payload plus a full
        // 8 KiB header rewrite (RegionFile.java:298-301).
        if (vanilla > 0) {
            long vanillaWrite = (vanilla + 8192L * corpus.size()) * OVERWRITE_ROUNDS;
            System.out.printf("vanilla Anvil equivalent: %d (payload + 8 KiB header per write)%n",
                vanillaWrite);
            System.out.printf("  RocksDB/vanilla bytes written = %.2fx%n",
                (w1.flush + w1.compact) / (double)vanillaWrite);
        }
    }

    private record Result(String label, long sst, long blob, long flush, long compact) {
        long total() {
            return sst + blob;
        }
    }

    /**
     * @param minBlobSize blob threshold in bytes, or -1 to disable blob files
     * @param dict        enable ZSTD trained dictionaries (only affects SST data)
     */
    private static Result run(String label, List<byte[]> corpus, int minBlobSize,
            CompressionType compression, boolean dict) throws Exception {
        Path dir = Files.createTempDirectory("p1a-");
        try (Options opts = new Options();
             CompressionOptions co = new CompressionOptions();
             CompressionOptions bco = new CompressionOptions()) {

            opts.setCreateIfMissing(true);
            opts.setCompressionType(compression);
            opts.setBottommostCompressionType(compression);
            if (dict) {
                co.setMaxDictBytes(64 * 1024);
                co.setZStdMaxTrainBytes(1024 * 1024);
                bco.setMaxDictBytes(64 * 1024);
                bco.setZStdMaxTrainBytes(1024 * 1024);
                bco.setEnabled(true);
            }
            opts.setCompressionOptions(co);
            opts.setBottommostCompressionOptions(bco);

            if (minBlobSize > 0) {
                opts.setEnableBlobFiles(true);
                opts.setMinBlobSize(minBlobSize);
                opts.setBlobCompressionType(compression);
                opts.setEnableBlobGarbageCollection(true);
            }

            try (RocksDB db = RocksDB.open(opts, dir.toString());
                 WriteOptions wo = new WriteOptions()) {
                wo.setDisableWAL(true);
                for (int i = 0; i < corpus.size(); i++) {
                    db.put(wo, key(i), corpus.get(i));
                }
                db.flush(new FlushOptions().setWaitForFlush(true));
                db.compactRange();
            }
            long sst = sum(dir, ".sst");
            long blob = sum(dir, ".blob");
            System.out.printf("%s  sst=%-11d blob=%-11d%n", label, sst, blob);
            return new Result(label, sst, blob, 0, 0);
        } finally {
            delete(dir);
        }
    }

    private static Result runOverwrite(String label, List<byte[]> corpus, int minBlobSize)
            throws Exception {
        Path dir = Files.createTempDirectory("p1aw-");
        try (Statistics stats = new Statistics();
             Options opts = new Options()) {
            opts.setCreateIfMissing(true);
            opts.setStatistics(stats);
            opts.setCompressionType(CompressionType.ZSTD_COMPRESSION);
            opts.setBottommostCompressionType(CompressionType.ZSTD_COMPRESSION);
            if (minBlobSize > 0) {
                opts.setEnableBlobFiles(true);
                opts.setMinBlobSize(minBlobSize);
                opts.setBlobCompressionType(CompressionType.ZSTD_COMPRESSION);
                opts.setEnableBlobGarbageCollection(true);
            }

            try (RocksDB db = RocksDB.open(opts, dir.toString());
                 WriteOptions wo = new WriteOptions()) {
                wo.setDisableWAL(true);
                Random rnd = new Random(1234);
                for (int round = 0; round < OVERWRITE_ROUNDS; round++) {
                    for (int i = 0; i < corpus.size(); i++) {
                        // Perturb a few bytes so each round writes genuinely
                        // different data, as real chunk saves would.
                        byte[] v = corpus.get(i).clone();
                        for (int k = 0; k < 16 && v.length > 32; k++) {
                            v[rnd.nextInt(v.length)] = (byte)rnd.nextInt(256);
                        }
                        db.put(wo, key(i), v);
                    }
                    db.flush(new FlushOptions().setWaitForFlush(true));
                }
                db.compactRange();

                long flush = stats.getTickerCount(TickerType.FLUSH_WRITE_BYTES);
                long compact = stats.getTickerCount(TickerType.COMPACT_WRITE_BYTES);
                System.out.printf("%s  flush=%-12d compact=%-12d onDisk=%d%n",
                    label, flush, compact, sum(dir, ".sst") + sum(dir, ".blob"));
                return new Result(label, sum(dir, ".sst"), sum(dir, ".blob"), flush, compact);
            }
        } finally {
            delete(dir);
        }
    }

    private static byte[] key(int i) {
        byte[] k = new byte[12];
        for (int b = 0; b < 8; b++) {
            k[4 + b] = (byte)(i >>> (8 * (7 - b)));
        }
        return k;
    }

    private static List<byte[]> loadCorpus(String path) throws IOException {
        List<byte[]> out = new ArrayList<>();
        try (DataInputStream in = new DataInputStream(
                new java.io.BufferedInputStream(new FileInputStream(path), 1 << 20))) {
            int count = in.readInt();
            for (int i = 0; i < count; i++) {
                byte[] b = new byte[in.readInt()];
                in.readFully(b);
                out.add(b);
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

    private static void delete(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> s = Files.walk(dir)) {
            s.sorted(Comparator.reverseOrder()).forEach(f -> {
                try {
                    Files.delete(f);
                } catch (IOException ignored) {
                }
            });
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
}
