import com.github.luben.zstd.Zstd;
import net.jpountz.lz4.LZ4Compressor;
import net.jpountz.lz4.LZ4Factory;
import net.jpountz.lz4.LZ4FastDecompressor;
import org.xerial.snappy.Snappy;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Phase 1b Experiment A: raw codec performance on REAL Minecraft chunk NBT.
 *
 * <h2>Why measure codecs directly</h2>
 *
 * <p>Every earlier phase measured compression <em>through</em> a storage engine,
 * which conflates three separate things: the codec itself, the engine's block or
 * blob framing, and per-value overhead. {@code rocksdbjni} exposes no raw codec
 * API, so those numbers could never isolate codec behaviour.
 *
 * <p>This harness feeds real chunk blobs straight into each codec. No database,
 * no framing. It also measures the axis no earlier phase touched at all:
 * <strong>encode and decode throughput</strong>. That matters because chunk
 * decode sits directly in the chunk-load path, so a codec trading a little ratio
 * for a lot of speed could easily be the better choice.
 *
 * <h2>Measured in Java on purpose</h2>
 *
 * <p>The mod runs on the JVM, so JVM-side timings are the relevant ones: JNI
 * transitions and array copies are part of the real cost. A Python cross-check
 * validates <em>ratios</em> only (same underlying C libraries produce identical
 * output sizes); any divergence indicates a harness bug rather than a real
 * difference.
 *
 * <h2>Timing methodology</h2>
 *
 * <ul>
 *   <li>Warmup passes before timing, so the JIT has compiled the hot paths.</li>
 *   <li>Multiple timed repetitions; the <em>median</em> is reported, because
 *       GC pauses and scheduling produce occasional large outliers that would
 *       skew a mean.</li>
 *   <li>Per-chunk latencies collected separately for p50/p99.</li>
 *   <li>Output buffers are pre-allocated outside the timed region where the API
 *       allows, so allocation cost does not masquerade as codec cost.</li>
 * </ul>
 */
public final class CodecSweep {

    private static final int WARMUP_PASSES = 2;
    private static final int TIMED_PASSES = 5;

    /** A codec at a specific setting. */
    private interface Codec {
        String name();

        /** @return compressed length written into {@code dst} */
        int compress(byte[] src, int srcLen, byte[] dst) throws Exception;

        /** @return decompressed length written into {@code dst} */
        int decompress(byte[] src, int srcLen, byte[] dst, int dstLen) throws Exception;

        /** Upper bound on compressed size, for buffer sizing. */
        int maxCompressedSize(int srcLen);
    }

    // ---------------------------------------------------------------- DEFLATE

    /** Vanilla's codec. Level 6 is the JDK default and what Minecraft actually uses. */
    private static final class DeflateCodec implements Codec {
        private final int level;
        private final Deflater deflater;
        private final Inflater inflater = new Inflater();

        DeflateCodec(int level) {
            this.level = level;
            this.deflater = new Deflater(level);
        }

        @Override
        public String name() {
            return "deflate-" + this.level;
        }

        @Override
        public int compress(byte[] src, int srcLen, byte[] dst) {
            this.deflater.reset();
            this.deflater.setInput(src, 0, srcLen);
            this.deflater.finish();
            int n = this.deflater.deflate(dst);
            // A full output buffer would silently truncate; dst is sized via
            // maxCompressedSize so this should never trigger.
            if (!this.deflater.finished()) {
                throw new IllegalStateException("deflate output buffer too small");
            }
            return n;
        }

        @Override
        public int decompress(byte[] src, int srcLen, byte[] dst, int dstLen) throws Exception {
            this.inflater.reset();
            this.inflater.setInput(src, 0, srcLen);
            return this.inflater.inflate(dst);
        }

        @Override
        public int maxCompressedSize(int srcLen) {
            // zlib worst case: input + 0.1% + 12 bytes, with headroom.
            return srcLen + (srcLen / 500) + 64;
        }
    }

    // ------------------------------------------------------------------- ZSTD

    private static final class ZstdCodec implements Codec {
        private final int level;

        ZstdCodec(int level) {
            this.level = level;
        }

        @Override
        public String name() {
            return "zstd-" + this.level;
        }

        @Override
        public int compress(byte[] src, int srcLen, byte[] dst) {
            long n = Zstd.compressByteArray(dst, 0, dst.length, src, 0, srcLen, this.level);
            if (Zstd.isError(n)) {
                throw new IllegalStateException("zstd: " + Zstd.getErrorName(n));
            }
            return (int)n;
        }

        @Override
        public int decompress(byte[] src, int srcLen, byte[] dst, int dstLen) {
            long n = Zstd.decompressByteArray(dst, 0, dstLen, src, 0, srcLen);
            if (Zstd.isError(n)) {
                throw new IllegalStateException("zstd: " + Zstd.getErrorName(n));
            }
            return (int)n;
        }

        @Override
        public int maxCompressedSize(int srcLen) {
            return (int)Zstd.compressBound(srcLen);
        }
    }

    // -------------------------------------------------------------------- LZ4

    private static final class Lz4Codec implements Codec {
        private final boolean highCompression;
        private final LZ4Compressor compressor;
        private final LZ4FastDecompressor decompressor;

        Lz4Codec(boolean highCompression) {
            this.highCompression = highCompression;
            LZ4Factory factory = LZ4Factory.fastestInstance();
            this.compressor = highCompression
                ? factory.highCompressor()
                : factory.fastCompressor();
            this.decompressor = factory.fastDecompressor();
        }

        @Override
        public String name() {
            return this.highCompression ? "lz4hc" : "lz4";
        }

        @Override
        public int compress(byte[] src, int srcLen, byte[] dst) {
            return this.compressor.compress(src, 0, srcLen, dst, 0, dst.length);
        }

        @Override
        public int decompress(byte[] src, int srcLen, byte[] dst, int dstLen) {
            // LZ4 block format stores no length, so the caller supplies it. This is
            // how RocksDB and Minecraft-style framing would use it too.
            this.decompressor.decompress(src, 0, dst, 0, dstLen);
            return dstLen;
        }

        @Override
        public int maxCompressedSize(int srcLen) {
            return this.compressor.maxCompressedLength(srcLen);
        }
    }

    // ----------------------------------------------------------------- SNAPPY

    private static final class SnappyCodec implements Codec {
        @Override
        public String name() {
            return "snappy";
        }

        @Override
        public int compress(byte[] src, int srcLen, byte[] dst) throws IOException {
            return Snappy.compress(src, 0, srcLen, dst, 0);
        }

        @Override
        public int decompress(byte[] src, int srcLen, byte[] dst, int dstLen) throws IOException {
            return Snappy.uncompress(src, 0, srcLen, dst, 0);
        }

        @Override
        public int maxCompressedSize(int srcLen) {
            return Snappy.maxCompressedLength(srcLen);
        }
    }

    // ----------------------------------------------------------------- RESULT

    private static final class Result {
        final String codec;
        final long logical;
        final long compressed;
        final double encodeMbps;
        final double decodeMbps;
        final long encodeP50Ns;
        final long encodeP99Ns;
        final long decodeP50Ns;
        final long decodeP99Ns;
        final boolean verified;

        Result(String codec, long logical, long compressed, double encodeMbps, double decodeMbps,
                long encodeP50Ns, long encodeP99Ns, long decodeP50Ns, long decodeP99Ns,
                boolean verified) {
            this.codec = codec;
            this.logical = logical;
            this.compressed = compressed;
            this.encodeMbps = encodeMbps;
            this.decodeMbps = decodeMbps;
            this.encodeP50Ns = encodeP50Ns;
            this.encodeP99Ns = encodeP99Ns;
            this.decodeP50Ns = decodeP50Ns;
            this.decodeP99Ns = decodeP99Ns;
            this.verified = verified;
        }

        double ratio() {
            return this.compressed == 0 ? 0 : this.logical / (double)this.compressed;
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: CodecSweep <corpus-dir> [stratum ...]");
            System.exit(2);
        }
        File dir = new File(args[0]);
        List<String> strata = new ArrayList<>();
        if (args.length > 1) {
            strata.addAll(Arrays.asList(args).subList(1, args.length));
        } else {
            File[] bins = dir.listFiles((d, n) -> n.endsWith(".bin"));
            if (bins == null) {
                System.err.println("no .bin files in " + dir);
                System.exit(1);
            }
            Arrays.sort(bins);
            for (File f : bins) {
                strata.add(f.getName().replace(".bin", ""));
            }
        }

        System.out.println("Phase 1b Experiment A: raw codec on real chunk NBT");
        System.out.printf("warmup=%d timed=%d passes, median reported%n%n",
            WARMUP_PASSES, TIMED_PASSES);

        for (String stratum : strata) {
            File f = new File(dir, stratum + ".bin");
            if (!f.isFile()) {
                System.out.println("skip " + stratum + " (missing)");
                continue;
            }
            List<byte[]> corpus = load(f);
            if (corpus.isEmpty()) {
                continue;
            }
            runStratum(stratum, corpus);
        }
    }

    private static void runStratum(String stratum, List<byte[]> corpus) {
        long logical = 0;
        int maxLen = 0;
        for (byte[] b : corpus) {
            logical += b.length;
            maxLen = Math.max(maxLen, b.length);
        }

        System.out.println("=".repeat(104));
        System.out.printf("%s: %,d chunks, %s logical, mean %s, max %s%n", stratum,
            corpus.size(), human(logical), human(logical / corpus.size()), human(maxLen));
        System.out.println("=".repeat(104));

        List<Codec> codecs = new ArrayList<>();
        codecs.add(new DeflateCodec(1));
        codecs.add(new DeflateCodec(6));   // vanilla's actual setting
        codecs.add(new DeflateCodec(9));
        codecs.add(new ZstdCodec(1));
        codecs.add(new ZstdCodec(3));      // zstd default
        codecs.add(new ZstdCodec(9));
        codecs.add(new ZstdCodec(19));
        codecs.add(new Lz4Codec(false));
        codecs.add(new Lz4Codec(true));
        codecs.add(new SnappyCodec());

        System.out.printf("%-12s %8s %11s %11s %10s %10s %10s %10s  %s%n",
            "codec", "ratio", "enc MB/s", "dec MB/s", "encP50", "encP99",
            "decP50", "decP99", "ok");

        List<Result> results = new ArrayList<>();
        for (Codec codec : codecs) {
            try {
                Result r = measure(codec, corpus, logical, maxLen);
                results.add(r);
                System.out.printf("%-12s %7.2fx %11.0f %11.0f %9s %9s %9s %9s  %s%n",
                    r.codec, r.ratio(), r.encodeMbps, r.decodeMbps,
                    us(r.encodeP50Ns), us(r.encodeP99Ns),
                    us(r.decodeP50Ns), us(r.decodeP99Ns),
                    r.verified ? "yes" : "FAIL");
            } catch (Throwable t) {
                System.out.printf("%-12s  FAILED: %s%n", codec.name(), t);
            }
        }

        // Pareto frontier: a codec is dominated if another is both smaller and
        // faster to decode. Decode is the relevant speed axis because it sits in
        // the chunk load path, whereas encode happens off-thread at autosave.
        System.out.println();
        System.out.println("Pareto-optimal (ratio vs decode speed):");
        results.sort(Comparator.comparingDouble(Result::ratio).reversed());
        double bestDecode = -1;
        for (Result r : results) {
            if (r.decodeMbps > bestDecode) {
                bestDecode = r.decodeMbps;
                System.out.printf("  %-12s %6.2fx  %,.0f MB/s decode%n",
                    r.codec, r.ratio(), r.decodeMbps);
            }
        }

        Result vanilla = results.stream().filter(r -> r.codec.equals("deflate-6"))
            .findFirst().orElse(null);
        if (vanilla != null) {
            System.out.println();
            System.out.printf("vs vanilla (deflate-6, %.2fx, %,.0f MB/s decode):%n",
                vanilla.ratio(), vanilla.decodeMbps);
            for (Result r : results) {
                if (r.codec.equals("deflate-6")) {
                    continue;
                }
                double sizeDelta = (vanilla.compressed - r.compressed) * 100.0 / vanilla.compressed;
                double decodeSpeedup = r.decodeMbps / vanilla.decodeMbps;
                System.out.printf("  %-12s size %+6.1f%%   decode %5.2fx%n",
                    r.codec, -sizeDelta, decodeSpeedup);
            }
        }
        System.out.println();
    }

    private static Result measure(Codec codec, List<byte[]> corpus, long logical, int maxLen)
            throws Exception {
        byte[] compressBuf = new byte[codec.maxCompressedSize(maxLen)];
        byte[] decompressBuf = new byte[maxLen];

        // Compress once to record sizes and validate round-trip correctness. A
        // codec that lost data would otherwise look like a compression winner.
        int[] compressedLens = new int[corpus.size()];
        long compressedTotal = 0;
        boolean verified = true;
        for (int i = 0; i < corpus.size(); i++) {
            byte[] src = corpus.get(i);
            int n = codec.compress(src, src.length, compressBuf);
            compressedLens[i] = n;
            compressedTotal += n;
            int m = codec.decompress(compressBuf, n, decompressBuf, src.length);
            if (m != src.length || !regionEquals(src, decompressBuf, src.length)) {
                verified = false;
            }
        }

        // Pre-compress everything so decode timing is not polluted by encode work.
        List<byte[]> compressed = new ArrayList<>(corpus.size());
        for (int i = 0; i < corpus.size(); i++) {
            byte[] src = corpus.get(i);
            int n = codec.compress(src, src.length, compressBuf);
            compressed.add(Arrays.copyOf(compressBuf, n));
        }

        for (int pass = 0; pass < WARMUP_PASSES; pass++) {
            encodePass(codec, corpus, compressBuf, null);
            decodePass(codec, corpus, compressed, decompressBuf, null);
        }

        long[] encodeTotals = new long[TIMED_PASSES];
        long[] decodeTotals = new long[TIMED_PASSES];
        long[] encodeLatencies = new long[corpus.size()];
        long[] decodeLatencies = new long[corpus.size()];
        for (int pass = 0; pass < TIMED_PASSES; pass++) {
            encodeTotals[pass] = encodePass(codec, corpus, compressBuf,
                pass == 0 ? encodeLatencies : null);
            decodeTotals[pass] = decodePass(codec, corpus, compressed, decompressBuf,
                pass == 0 ? decodeLatencies : null);
        }

        double mib = logical / (1024.0 * 1024.0);
        double encodeMbps = mib / (median(encodeTotals) / 1e9);
        double decodeMbps = mib / (median(decodeTotals) / 1e9);

        Arrays.sort(encodeLatencies);
        Arrays.sort(decodeLatencies);
        return new Result(codec.name(), logical, compressedTotal, encodeMbps, decodeMbps,
            percentile(encodeLatencies, 0.50), percentile(encodeLatencies, 0.99),
            percentile(decodeLatencies, 0.50), percentile(decodeLatencies, 0.99),
            verified);
    }

    private static long encodePass(Codec codec, List<byte[]> corpus, byte[] buf,
            long[] latencies) throws Exception {
        long total = 0;
        for (int i = 0; i < corpus.size(); i++) {
            byte[] src = corpus.get(i);
            long t0 = System.nanoTime();
            codec.compress(src, src.length, buf);
            long dt = System.nanoTime() - t0;
            total += dt;
            if (latencies != null) {
                latencies[i] = dt;
            }
        }
        return total;
    }

    private static long decodePass(Codec codec, List<byte[]> corpus, List<byte[]> compressed,
            byte[] buf, long[] latencies) throws Exception {
        long total = 0;
        for (int i = 0; i < compressed.size(); i++) {
            byte[] src = compressed.get(i);
            int originalLen = corpus.get(i).length;
            long t0 = System.nanoTime();
            codec.decompress(src, src.length, buf, originalLen);
            long dt = System.nanoTime() - t0;
            total += dt;
            if (latencies != null) {
                latencies[i] = dt;
            }
        }
        return total;
    }

    private static boolean regionEquals(byte[] a, byte[] b, int len) {
        for (int i = 0; i < len; i++) {
            if (a[i] != b[i]) {
                return false;
            }
        }
        return true;
    }

    private static long median(long[] values) {
        long[] copy = values.clone();
        Arrays.sort(copy);
        return copy[copy.length / 2];
    }

    private static long percentile(long[] sorted, double q) {
        if (sorted.length == 0) {
            return 0;
        }
        int idx = (int)Math.min(sorted.length - 1, Math.round(q * (sorted.length - 1)));
        return sorted[idx];
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

    private static String us(long ns) {
        return String.format("%.1fus", ns / 1000.0);
    }

    private CodecSweep() {
    }
}
