package com.keuin.rocksmc;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtIo;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Round-trip fidelity harness: Anvil -&gt; RocksDB -&gt; compare.
 *
 * <p>Reads every chunk directly out of {@code .mca} region files, writes each one
 * through {@link RocksChunkStore}, reads it back, and asserts the NBT is
 * equivalent. Also reports the size and compression statistics that earlier
 * phases could only estimate from synthetic corpora.
 *
 * <p>Region parsing is done here rather than through vanilla's
 * {@code RegionFile} because the mixin has already redirected that class -- with
 * the RocksDB backend active, asking vanilla to read Anvil would just read
 * RocksDB back. Parsing independently also means the comparison has a genuinely
 * separate source of truth.
 *
 * <p>Equality uses NBT's own {@code equals}, which is deep: {@code NbtCompound}
 * compares its entry map, and {@code NbtByteArray}/{@code NbtIntArray} use
 * {@code Arrays.equals}. A byte-level comparison of re-serialised output would be
 * stricter than necessary and would fail on legitimate map-ordering differences.
 */
public final class FidelityHarness {

    public static final class Stats {
        public int chunksFound;
        public int chunksVerified;
        public int mismatches;
        public int readFailures;
        /**
         * Sum of compressed chunk payloads, excluding Anvil's sector padding.
         */
        public long compressedBytes;
        /**
         * Actual size of the .mca files, including sector padding and headers.
         */
        public long anvilOnDisk;
        public long uncompressedBytes;
        public long rocksOnDisk;
        /** Chunks that lived in an external .mcc file. */
        public int externalChunks;
        /** Anvil-side anomalies, counted rather than thrown. */
        public final AnvilReader.Report anomalies = new AnvilReader.Report();
        public final List<String> mismatchDetails = new ArrayList<>();

        public double deflateRatio() {
            return this.compressedBytes == 0 ? 0
                : this.uncompressedBytes / (double) this.compressedBytes;
        }

        public double rocksRatio() {
            return this.rocksOnDisk == 0 ? 0
                : this.uncompressedBytes / (double) this.rocksOnDisk;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("chunks=%d verified=%d mismatches=%d readFailures=%d%n",
                this.chunksFound, this.chunksVerified, this.mismatches, this.readFailures));
            if (this.externalChunks > 0) {
                // Reported because these were silently skipped before the harness
                // shared AnvilReader's parser, which biased every figure below
                // against the largest chunks in the world.
                sb.append(String.format("external (.mcc)    = %,d chunks%n",
                    this.externalChunks));
            }
            if (this.anomalies.total() > 0) {
                sb.append(String.format("anvil anomalies    = %s%n", this.anomalies));
            }
            sb.append(String.format("uncompressed NBT   = %,d bytes (mean %,d/chunk)%n",
                this.uncompressedBytes,
                this.chunksFound == 0 ? 0 : this.uncompressedBytes / this.chunksFound));
            sb.append(String.format("anvil payload      = %,d bytes (%.2fx, mean %,d/chunk)%n",
                this.compressedBytes, deflateRatio(),
                this.chunksFound == 0 ? 0 : this.compressedBytes / this.chunksFound));
            if (this.anvilOnDisk > 0) {
                // The fair comparison. Anvil allocates in whole 4 KiB sectors, so
                // the payload sum understates what it actually consumes; comparing
                // RocksDB's real files against Anvil's payload penalises RocksDB
                // for space Anvil is also using.
                sb.append(String.format("anvil ON-DISK      = %,d bytes (%.2fx) "
                        + "[+%.1f%% sector padding]%n",
                    this.anvilOnDisk, this.uncompressedBytes / (double) this.anvilOnDisk,
                    (this.anvilOnDisk - this.compressedBytes) * 100.0 / this.compressedBytes));
            }
            if (this.rocksOnDisk > 0) {
                sb.append(String.format("rocksdb ON-DISK    = %,d bytes (%.2fx)%n",
                    this.rocksOnDisk, rocksRatio()));
                sb.append(String.format("  vs anvil payload : %+.1f%%%n",
                    (this.rocksOnDisk - this.compressedBytes) * 100.0 / this.compressedBytes));
                if (this.anvilOnDisk > 0) {
                    sb.append(String.format("  vs anvil ON-DISK : %+.1f%%   <-- the fair "
                            + "file-to-file comparison%n",
                        (this.rocksOnDisk - this.anvilOnDisk) * 100.0 / this.anvilOnDisk));
                }
            }
            return sb.toString();
        }
    }

    /**
     * Runs the round trip over every region file under {@code regionDir}.
     *
     * @param regionDir directory containing {@code r.X.Z.mca}
     * @param scratchDb a fresh directory to hold the temporary database
     * @param limit     stop after this many chunks, or 0 for no limit
     */
    public static Stats run(File regionDir, File scratchDb, int limit) throws IOException {
        Stats stats = new Stats();
        File[] regions = regionDir.listFiles((d, n) -> n.endsWith(".mca"));
        if (regions == null || regions.length == 0) {
            return stats;
        }
        Arrays.sort(regions);

        // Record what Anvil actually consumes on disk, not just its payload sum.
        // Anvil allocates in whole 4 KiB sectors and carries an 8 KiB header per
        // file, so the payload total understates real usage -- on a real world by
        // over 60%. Comparing RocksDB's real files against Anvil's payload would
        // charge RocksDB for padding Anvil is also paying for.
        //
        // When a limit is set the .mca total covers the whole directory while only
        // some chunks are processed, so it is left unset to avoid a bogus ratio.
        if (limit <= 0) {
            for (File region : regions) {
                stats.anvilOnDisk += region.length();
            }
        }

        RocksMcConfig config = RocksMcConfig.of(new java.util.Properties());
        // Parse the dimension from the real directory rather than hardcoding an
        // ordinal, so the harness exercises the same identity path the server does
        // -- including for custom dimensions.
        DimensionKey source = DimensionKey.fromStorageDirectory(regionDir);
        // Redirect the database into the scratch directory rather than writing it
        // beside the world being measured. The world may be a read-only mirror, and
        // the harness must not leave anything behind in it.
        DimensionKey dimension = source.withRoot(scratchDb);

        // One report for the whole run: Anvil anomalies are counted rather than
        // thrown, and a nonzero total is reported alongside the fidelity verdict.
        AnvilReader.Report anomalies = new AnvilReader.Report();

        try (RocksChunkStore store = RocksChunkStore.open(dimension, config)) {
            for (File region : regions) {
                if (limit > 0 && stats.chunksFound >= limit) {
                    // Stop before opening the next file. Continuing would parse and
                    // inflate every chunk in every remaining region only to discard
                    // the results, which is what made earlier throughput figures
                    // meaningless.
                    break;
                }
                try {
                    AnvilReader.stream(region, anomalies, entry -> {
                        if (limit > 0 && stats.chunksFound >= limit) {
                            return;
                        }
                        verifyOne(store, entry, stats);
                    });
                } catch (IOException e) {
                    // Recorded rather than swallowed: a region that cannot be read at
                    // all is a fidelity result, not a detail to drop.
                    stats.readFailures++;
                    if (stats.mismatchDetails.size() < 20) {
                        stats.mismatchDetails.add(
                            region.getName() + ": could not read: " + e);
                    }
                }
            }
            stats.anomalies.merge(anomalies);
            // Compact before measuring. A WAL sync alone leaves everything in
            // memtables, and flushing alone leaves un-merged L0 files and obsolete
            // blobs on disk, which inflates the footprint and would bias the size
            // comparison against RocksDB. The Phase 0/1a spikes all compacted
            // first, so this keeps the methodology consistent.
            store.database().flushMemtables();
            store.database().compact();
            stats.rocksOnDisk = directorySize(store.database().path(), ".sst")
                + directorySize(store.database().path(), ".blob");
        }
        return stats;
    }

    /**
     * Writes one chunk, reads it back, and records the comparison.
     *
     * <p>Region parsing is delegated to {@link AnvilReader} rather than duplicated
     * here. It used to be a private parser in this class, which silently skipped
     * chunks stored in external {@code .mcc} files -- so the harness measured a
     * different chunk population than the importer, and every published fidelity and
     * size figure excluded the very largest chunks in the world. Sharing one parser
     * makes that class of divergence impossible.
     *
     * <p>Equality is NBT's own deep {@code equals}, not a byte comparison of
     * re-serialised output: the latter is stricter than the contract and would fail on
     * legitimate map-ordering differences.
     */
    private static void verifyOne(RocksChunkStore store, AnvilReader.Entry entry,
            Stats stats) throws IOException {
        stats.chunksFound++;
        stats.compressedBytes += entry.compressedLength();
        if (entry.external()) {
            stats.externalChunks++;
        }

        NbtCompound nbt = entry.nbt();
        stats.uncompressedBytes += uncompressedSize(nbt);

        store.write(entry.pos(), nbt);
        NbtCompound readBack = store.read(entry.pos());

        if (readBack == null) {
            stats.readFailures++;
            if (stats.mismatchDetails.size() < 20) {
                stats.mismatchDetails.add(entry.pos() + ": read returned null");
            }
        } else if (!Objects.equals(nbt, readBack)) {
            stats.mismatches++;
            if (stats.mismatchDetails.size() < 20) {
                stats.mismatchDetails.add(
                    entry.pos() + ": " + firstDifference(nbt, readBack, "root"));
            }
        } else {
            stats.chunksVerified++;
        }
    }

    /** Serialised size of the NBT as this mod stores it: uncompressed. */
    private static int uncompressedSize(NbtCompound nbt) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(64 * 1024);
        try (DataOutputStream out = new DataOutputStream(buffer)) {
            NbtIo.write(nbt, out);
        }
        return buffer.size();
    }

    /**
     * Locates the first structural difference, for actionable failure output.
     */
    private static String firstDifference(NbtElement a, NbtElement b, String path) {
        if (a == null || b == null) {
            return path + ": one side null";
        }
        if (a.getType() != b.getType()) {
            return path + ": type " + a.getType() + " != " + b.getType();
        }
        if (a instanceof NbtCompound && b instanceof NbtCompound) {
            NbtCompound ca = (NbtCompound) a;
            NbtCompound cb = (NbtCompound) b;
            for (String key : ca.getKeys()) {
                if (!cb.contains(key)) {
                    return path + "." + key + ": missing after round trip";
                }
                NbtElement ea = ca.get(key);
                NbtElement eb = cb.get(key);
                if (ea != null && !ea.equals(eb)) {
                    return firstDifference(ea, eb, path + "." + key);
                }
            }
            for (String key : cb.getKeys()) {
                if (!ca.contains(key)) {
                    return path + "." + key + ": unexpected after round trip";
                }
            }
            return path + ": compounds differ but no differing key found";
        }
        return path + ": values differ (" + a.getType() + ")";
    }

    private static long directorySize(File dir, String suffix) {
        File[] files = dir.listFiles();
        if (files == null) {
            return 0L;
        }
        long total = 0L;
        for (File f : files) {
            if (f.isDirectory()) {
                total += directorySize(f, suffix);
            } else if (f.getName().endsWith(suffix)) {
                total += f.length();
            }
        }
        return total;
    }

    private FidelityHarness() {
    }
}
