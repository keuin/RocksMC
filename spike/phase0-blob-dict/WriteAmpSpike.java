import org.rocksdb.*;

import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/**
 * Phase 0b: quantify the write-amplification cost of Branch B.
 *
 * BlobDictSpike proved blob files ignore ZSTD trained dictionaries, and that the
 * best compression came from config C -- blobs OFF, dictionary ON -- because
 * dictionaries only work on SST files. Branch B therefore proposes keeping ~8 KiB
 * chunk values in the LSM (min_blob_size above chunk size) to regain
 * cross-chunk compression scope.
 *
 * But key-value separation existed precisely to stop leveled compaction from
 * rewriting multi-KiB values over and over. So Branch B trades write
 * amplification for compression ratio, and we need the size of that trade.
 *
 * METHOD
 * ------
 * Write the corpus, overwrite every key several times (modelling repeated
 * autosaves of the same dirty chunks), then compare bytes actually written to
 * disk against logical bytes written by the application.
 *
 *   write amp = (flush bytes + compaction bytes + blob bytes) / logical bytes
 *
 * Vanilla Anvil's write amp is ~2x: it writes the chunk plus a full 8192-byte
 * header rewrite per chunk write.
 */
public final class WriteAmpSpike {

    private static final int VALUE_SIZE = 8192;
    private static final int NUM_VALUES = 8000;
    private static final int OVERWRITE_ROUNDS = 12;  // repeated autosaves of hot chunks
    private static final long SEED = 20260809L;

    public static void main(String[] args) throws Exception {
        RocksDB.loadLibrary();

        long logical = (long)NUM_VALUES * VALUE_SIZE * OVERWRITE_ROUNDS;
        System.out.printf("logical writes: %d values x %d bytes x %d rounds = %.1f MiB%n",
            NUM_VALUES, VALUE_SIZE, OVERWRITE_ROUNDS, logical / 1048576.0);
        System.out.println("NOTE: reporting RELATIVE bytes-written. Absolute 'write amp' vs");
        System.out.println("      uncompressed logical bytes is not meaningful when the engine");
        System.out.println("      compresses -- it conflates ratio with amplification.");
        System.out.println();

        Result blobOn = run("blobs=ON   dict=OFF (plan as written)", true);
        Result blobOff = run("blobs=OFF  dict=ON  (Branch B)      ", false);

        System.out.println();
        System.out.println("==================== RESULTS ====================");
        System.out.printf("%-38s %12s %12s %12s%n", "config", "flush", "compact", "total written");
        for (Result r : List.of(blobOn, blobOff)) {
            System.out.printf("%-38s %12d %12d %12d%n",
                r.label, r.flushBytes, r.compactBytes, r.totalWritten());
        }

        System.out.println();
        System.out.printf("%-38s %12s %12s%n", "config", "on-disk", "ratio");
        for (Result r : List.of(blobOn, blobOff)) {
            System.out.printf("%-38s %12d %11.2fx%n", r.label, r.onDisk,
                (logical / (double)OVERWRITE_ROUNDS) / (double)r.onDisk);
        }

        System.out.println();
        System.out.println("==================== VERDICT ====================");
        System.out.printf("compaction bytes  BlobDB: %d   Branch B: %d   (%.1fx more)%n",
            blobOn.compactBytes, blobOff.compactBytes,
            blobOff.compactBytes / (double)Math.max(1, blobOn.compactBytes));
        System.out.printf("total bytes written  Branch B / BlobDB = %.2fx%n",
            blobOff.totalWritten() / (double)blobOn.totalWritten());
        System.out.printf("on-disk size         Branch B / BlobDB = %.3fx (%+.1f%%)%n",
            blobOff.onDisk / (double)blobOn.onDisk,
            (blobOff.onDisk - blobOn.onDisk) * 100.0 / blobOn.onDisk);
    }

    private record Result(String label, long flushBytes, long compactBytes, long onDisk) {
        /**
         * FLUSH_WRITE_BYTES already accounts for blob files written during flush, so
         * BLOB_DB_BLOB_FILE_BYTES_WRITTEN must NOT be added -- doing so double-counts.
         */
        long totalWritten() {
            return flushBytes + compactBytes;
        }
    }

    private static Result run(String label, boolean blobs) throws Exception {
        Path dir = Files.createTempDirectory("writeamp-");
        try (Statistics stats = new Statistics();
             Options opts = new Options();
             CompressionOptions co = new CompressionOptions();
             CompressionOptions bco = new CompressionOptions()) {

            opts.setCreateIfMissing(true);
            opts.setStatistics(stats);
            opts.setCompressionType(CompressionType.ZSTD_COMPRESSION);
            opts.setBottommostCompressionType(CompressionType.ZSTD_COMPRESSION);

            if (!blobs) {
                // Branch B: dictionaries work on SSTs, so enable them there.
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
                opts.setMinBlobSize(1024);
                opts.setBlobCompressionType(CompressionType.ZSTD_COMPRESSION);
                opts.setEnableBlobGarbageCollection(true);
            } else {
                opts.setEnableBlobFiles(false);
            }

            Random rnd = new Random(SEED);
            try (RocksDB db = RocksDB.open(opts, dir.toString());
                 WriteOptions wo = new WriteOptions()) {
                wo.setDisableWAL(true);   // isolate flush+compaction from log traffic

                for (int round = 0; round < OVERWRITE_ROUNDS; round++) {
                    for (int i = 0; i < NUM_VALUES; i++) {
                        db.put(wo, key(i), value(rnd));
                    }
                    db.flush(new FlushOptions().setWaitForFlush(true));
                }
                db.compactRange();

                long flushed = stats.getTickerCount(TickerType.FLUSH_WRITE_BYTES);
                long compacted = stats.getTickerCount(TickerType.COMPACT_WRITE_BYTES);
                long blobWritten = stats.getTickerCount(TickerType.BLOB_DB_BLOB_FILE_BYTES_WRITTEN);

                long onDisk = sumByExtension(dir, ".sst") + sumByExtension(dir, ".blob");
                System.out.printf("%s  flush=%-11d compact=%-11d blob=%-11d onDisk=%d%n",
                    label, flushed, compacted, blobWritten, onDisk);
                return new Result(label, flushed, compacted, onDisk);
            }
        } finally {
            deleteRecursively(dir);
        }
    }

    private static byte[] key(int i) {
        byte[] k = new byte[12];
        for (int b = 0; b < 8; b++) {
            k[4 + b] = (byte)(i >>> (8 * (7 - b)));
        }
        return k;
    }

    /** Terrain-dominated chunk analogue: 15% unique tail, rest highly redundant. */
    private static byte[] value(Random rnd) {
        byte[] v = new byte[VALUE_SIZE];
        int structured = (int)(VALUE_SIZE * 0.85);
        int p = 0;
        String[] palette = {"minecraft:stone", "minecraft:dirt", "minecraft:water", "minecraft:deepslate"};
        for (int e = 0; e < 30 && p < structured - 40; e++) {
            String s = "Name" + palette[rnd.nextInt(palette.length)];
            for (int i = 0; i < s.length() && p < structured; i++) {
                v[p++] = (byte)s.charAt(i);
            }
        }
        while (p < structured) {
            v[p++] = (byte)0xFF;
        }
        while (p < VALUE_SIZE) {
            v[p++] = (byte)rnd.nextInt(256);
        }
        return v;
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
