import org.rocksdb.*;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Phase 1b Experiment B: engine-level configurations on real chunk NBT.
 *
 * <p>Experiment A measured codecs in isolation. This measures what a deployment
 * actually gets, which differs because the engine adds block or blob framing,
 * per-value metadata, and an index. Comparing the two explains why RocksDB's
 * whole-database ratio (7.14x on the real world) sits below the raw codec ratio
 * for the same data.
 *
 * <p>Sweeps the shortlist from Experiment A across blob thresholds and
 * dictionary settings, and re-tests dictionaries at genuine value sizes rather
 * than assuming the Phase 0 null result transfers from an 8 KiB synthetic corpus
 * to real ~30 KiB chunks.
 *
 * <p>Every configuration is compacted before measurement. Without that, un-merged
 * L0 files and unreferenced blobs inflate the footprint -- a mistake that
 * initially skewed the Step 6 numbers.
 */
public final class EngineSweep {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: EngineSweep <corpus-dir> [stratum ...]");
            System.exit(2);
        }
        RocksDB.loadLibrary();

        File dir = new File(args[0]);
        List<String> strata = new ArrayList<>();
        if (args.length > 1) {
            for (int i = 1; i < args.length; i++) {
                strata.add(args[i]);
            }
        } else {
            File[] bins = dir.listFiles((d, n) -> n.endsWith(".bin"));
            if (bins != null) {
                java.util.Arrays.sort(bins);
                for (File f : bins) {
                    strata.add(f.getName().replace(".bin", ""));
                }
            }
        }

        System.out.println("Phase 1b Experiment B: RocksDB configs on real chunk NBT");
        System.out.println();

        for (String stratum : strata) {
            File f = new File(dir, stratum + ".bin");
            if (!f.isFile()) {
                continue;
            }
            List<byte[]> corpus = load(f);
            if (corpus.isEmpty()) {
                continue;
            }
            runStratum(stratum, corpus);
        }
    }

    private static void runStratum(String stratum, List<byte[]> corpus) throws Exception {
        long logical = corpus.stream().mapToLong(b -> b.length).sum();

        // Vanilla's own codec and level: the baseline any replacement must beat.
        long vanilla = 0;
        java.util.zip.Deflater deflater = new java.util.zip.Deflater(6);
        byte[] buf = new byte[2 << 20];
        for (byte[] b : corpus) {
            deflater.reset();
            deflater.setInput(b);
            deflater.finish();
            vanilla += deflater.deflate(buf);
        }
        deflater.end();

        System.out.println("=".repeat(96));
        System.out.printf("%s: %,d chunks, %s logical, mean %s%n", stratum, corpus.size(),
            human(logical), human(logical / corpus.size()));
        System.out.printf("vanilla anvil payload (deflate-6): %,d bytes (%.2fx)%n",
            vanilla, logical / (double)vanilla);
        System.out.println("=".repeat(96));
        System.out.printf("%-34s %13s %13s %10s %9s%n",
            "config", "sst", "blob", "total", "ratio");

        List<Result> results = new ArrayList<>();
        // Blob threshold sweep at the engine's default level, then explicit levels.
        // RocksDB defaults zstd to level 3, which Experiment A shows LOSES to
        // vanilla deflate-6 on ratio -- so the level must be set explicitly.
        results.add(run("blob=1KiB   zstd(def) dict=off", corpus, 1024, CompressionType.ZSTD_COMPRESSION, false, 0));
        results.add(run("blob=1KiB   zstd-9    dict=off", corpus, 1024, CompressionType.ZSTD_COMPRESSION, false, 9));
        results.add(run("blob=1KiB   zstd-19   dict=off", corpus, 1024, CompressionType.ZSTD_COMPRESSION, false, 19));
        results.add(run("blob=off    zstd(def) dict=off", corpus, -1, CompressionType.ZSTD_COMPRESSION, false, 0));
        results.add(run("blob=off    zstd-9    dict=off", corpus, -1, CompressionType.ZSTD_COMPRESSION, false, 9));
        results.add(run("blob=off    zstd(def) dict=ON ", corpus, -1, CompressionType.ZSTD_COMPRESSION, true, 0));
        results.add(run("blob=off    zstd-9    dict=ON ", corpus, -1, CompressionType.ZSTD_COMPRESSION, true, 9));
        results.add(run("blob=1KiB   zstd-9    dict=ON ", corpus, 1024, CompressionType.ZSTD_COMPRESSION, true, 9));
        results.add(run("blob=1KiB   lz4       dict=off", corpus, 1024, CompressionType.LZ4_COMPRESSION, false, 0));

        for (Result r : results) {
            if (r == null) {
                continue;
            }
            System.out.printf("%-34s %13d %13d %10d %8.2fx%n",
                r.label, r.sst, r.blob, r.total(), logical / (double)r.total());
        }

        Result best = results.stream().filter(r -> r != null && r.total() > 0)
            .min(Comparator.comparingLong(Result::total)).orElse(null);
        if (best != null) {
            System.out.println();
            System.out.printf("best engine config: %s -> %.2fx%n",
                best.label.trim(), logical / (double)best.total());
            System.out.printf("vs vanilla payload: %+.1f%%%n",
                (best.total() - vanilla) * 100.0 / vanilla);

            // The gap between raw-codec ratio and engine ratio is the engine's
            // framing and metadata overhead, which is what Experiment A cannot see.
            System.out.printf("note: engine overhead is the gap between this and the "
                + "raw zstd ratio in Experiment A%n");
        }
        System.out.println();
    }

    private record Result(String label, long sst, long blob) {
        long total() {
            return this.sst + this.blob;
        }
    }

    private static Result run(String label, List<byte[]> corpus, int minBlobSize,
            CompressionType compression, boolean dict, int level) {
        Path dir = null;
        try {
            dir = Files.createTempDirectory("p1b-");
            try (Options opts = new Options();
                 CompressionOptions co = new CompressionOptions();
                 CompressionOptions bco = new CompressionOptions()) {

                opts.setCreateIfMissing(true);
                opts.setCompressionType(compression);
                opts.setBottommostCompressionType(compression);
                // level 0 means "leave the library default" (zstd 3 for RocksDB).
                if (level != 0) {
                    co.setLevel(level);
                    bco.setLevel(level);
                }
                if (dict) {
                    co.setMaxDictBytes(64 * 1024);
                    co.setZStdMaxTrainBytes(1024 * 1024);
                    bco.setMaxDictBytes(64 * 1024);
                    bco.setZStdMaxTrainBytes(1024 * 1024);
                }
                // Bottommost options are only honoured when explicitly enabled.
                bco.setEnabled(true);
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
                    try (FlushOptions fo = new FlushOptions().setWaitForFlush(true)) {
                        db.flush(fo);
                    }
                    // Mandatory before sizing: otherwise L0 files and obsolete blobs
                    // are counted and the footprint is overstated.
                    db.compactRange();
                }
                return new Result(label, sum(dir, ".sst"), sum(dir, ".blob"));
            }
        } catch (Exception e) {
            System.out.printf("%-34s FAILED: %s%n", label, e);
            return null;
        } finally {
            if (dir != null) {
                delete(dir);
            }
        }
    }

    private static byte[] key(int i) {
        byte[] k = new byte[12];
        for (int b = 0; b < 8; b++) {
            k[4 + b] = (byte)(i >>> (8 * (7 - b)));
        }
        return k;
    }

    private static List<byte[]> load(File f) throws IOException {
        List<byte[]> out = new ArrayList<>();
        try (DataInputStream in = new DataInputStream(
                new java.io.BufferedInputStream(new FileInputStream(f), 1 << 20))) {
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

    private EngineSweep() {
    }
}
