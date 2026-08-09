package com.keuin.rocksmc;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtIo;
import net.minecraft.util.math.ChunkPos;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

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

    private static final int SECTOR = 4096;

    public static final class Stats {
        public int chunksFound;
        public int chunksVerified;
        public int mismatches;
        public int readFailures;
        /** Sum of compressed chunk payloads, excluding Anvil's sector padding. */
        public long compressedBytes;
        /** Actual size of the .mca files, including sector padding and headers. */
        public long anvilOnDisk;
        public long uncompressedBytes;
        public long rocksOnDisk;
        public final List<String> mismatchDetails = new ArrayList<>();

        public double deflateRatio() {
            return this.compressedBytes == 0 ? 0
                : this.uncompressedBytes / (double)this.compressedBytes;
        }

        public double rocksRatio() {
            return this.rocksOnDisk == 0 ? 0
                : this.uncompressedBytes / (double)this.rocksOnDisk;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("chunks=%d verified=%d mismatches=%d readFailures=%d%n",
                this.chunksFound, this.chunksVerified, this.mismatches, this.readFailures));
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
                    this.anvilOnDisk, this.uncompressedBytes / (double)this.anvilOnDisk,
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

    /** One chunk as stored by Anvil, already decompressed. */
    private static final class RawChunk {
        final ChunkPos pos;
        final int compressedLen;
        final NbtCompound nbt;
        final int uncompressedLen;

        RawChunk(ChunkPos pos, int compressedLen, NbtCompound nbt, int uncompressedLen) {
            this.pos = pos;
            this.compressedLen = compressedLen;
            this.nbt = nbt;
            this.uncompressedLen = uncompressedLen;
        }
    }

    /**
     * Runs the round trip over every region file under {@code regionDir}.
     *
     * @param regionDir  directory containing {@code r.X.Z.mca}
     * @param scratchDb  a fresh directory for the temporary RocksDB
     * @param limit      stop after this many chunks, or 0 for no limit
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
        DimensionKey dimension = DimensionKey.fromStorageDirectory(regionDir);
        try (RocksChunkStore store = new RocksChunkStore(scratchDb, dimension, config)) {
            outer:
            for (File region : regions) {
                for (RawChunk chunk : readRegion(region)) {
                    // Break the OUTER loop: continuing would call readRegion() on
                    // every remaining region file -- fully parsing and inflating
                    // every chunk in each -- only to discard the results. That made
                    // the earlier throughput figures meaningless.
                    if (limit > 0 && stats.chunksFound >= limit) {
                        break outer;
                    }
                    stats.chunksFound++;
                    stats.compressedBytes += chunk.compressedLen;
                    stats.uncompressedBytes += chunk.uncompressedLen;

                    store.write(chunk.pos, chunk.nbt);
                    NbtCompound readBack = store.read(chunk.pos);

                    if (readBack == null) {
                        stats.readFailures++;
                        stats.mismatchDetails.add(chunk.pos + ": read returned null");
                    } else if (!equivalent(chunk.nbt, readBack)) {
                        stats.mismatches++;
                        if (stats.mismatchDetails.size() < 20) {
                            stats.mismatchDetails.add(
                                chunk.pos + ": " + firstDifference(chunk.nbt, readBack, "root"));
                        }
                    } else {
                        stats.chunksVerified++;
                    }
                }
            }
            // Compact before measuring. sync() alone leaves un-merged L0 files and
            // obsolete blobs on disk, which inflates the footprint and would bias
            // the size comparison against RocksDB. The Phase 0/1a spikes all
            // compacted first, so this keeps the methodology consistent.
            store.sync();
            store.compact();
            stats.rocksOnDisk = directorySize(scratchDb, ".sst") + directorySize(scratchDb, ".blob");
        }
        return stats;
    }

    /**
     * Parses an Anvil region file directly.
     *
     * <p>Layout: 4096 bytes of packed sector entries (offset = value &gt;&gt;&gt; 8 in
     * sectors, size = value &amp; 0xFF), 4096 bytes of timestamps, then payloads
     * prefixed by a big-endian length and a one-byte compression scheme
     * (1 = gzip, 2 = zlib, 3 = none; high bit set means an external .mcc file).
     */
    private static List<RawChunk> readRegion(File file) throws IOException {
        List<RawChunk> out = new ArrayList<>();
        if (file.length() < SECTOR * 2L) {
            // Vanilla creates region files on demand and leaves them empty until a
            // chunk in that region is actually written. Not corruption.
            return out;
        }

        String[] parts = file.getName().split("\\.");
        int regionX = Integer.parseInt(parts[1]);
        int regionZ = Integer.parseInt(parts[2]);

        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            byte[] header = new byte[SECTOR];
            raf.readFully(header);
            for (int i = 0; i < 1024; i++) {
                int packed = ((header[i * 4] & 0xFF) << 24)
                    | ((header[i * 4 + 1] & 0xFF) << 16)
                    | ((header[i * 4 + 2] & 0xFF) << 8)
                    | (header[i * 4 + 3] & 0xFF);
                if (packed == 0) {
                    continue;
                }
                int offset = packed >>> 8;
                int sectors = packed & 0xFF;
                if (offset < 2 || sectors == 0 || (long)offset * SECTOR >= file.length()) {
                    continue;
                }

                raf.seek((long)offset * SECTOR);
                int declaredLen = raf.readInt();
                int scheme = raf.readUnsignedByte();
                if ((scheme & 0x80) != 0 || declaredLen <= 1) {
                    continue;
                }
                int payloadLen = declaredLen - 1;
                if (payloadLen > sectors * SECTOR) {
                    continue;
                }
                byte[] payload = new byte[payloadLen];
                raf.readFully(payload);

                NbtCompound nbt;
                try {
                    nbt = decode(payload, scheme & 0x7F);
                } catch (IOException e) {
                    continue;
                }
                if (nbt == null) {
                    continue;
                }

                int chunkX = regionX * 32 + (i % 32);
                int chunkZ = regionZ * 32 + (i / 32);
                out.add(new RawChunk(new ChunkPos(chunkX, chunkZ), payloadLen,
                    nbt, uncompressedSize(nbt)));
            }
        }
        return out;
    }

    private static NbtCompound decode(byte[] payload, int scheme) throws IOException {
        ByteArrayInputStream raw = new ByteArrayInputStream(payload);
        switch (scheme) {
            case 1:
                try (DataInputStream in = new DataInputStream(new GZIPInputStream(raw))) {
                    return NbtIo.read(in);
                }
            case 2:
                try (DataInputStream in = new DataInputStream(new InflaterInputStream(raw))) {
                    return NbtIo.read(in);
                }
            case 3:
                try (DataInputStream in = new DataInputStream(raw)) {
                    return NbtIo.read(in);
                }
            default:
                return null;
        }
    }

    private static int uncompressedSize(NbtCompound nbt) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(64 * 1024);
        try (DataOutputStream out = new DataOutputStream(buffer)) {
            NbtIo.write(nbt, out);
        }
        return buffer.size();
    }

    /**
     * NBT equality. {@code NbtCompound.equals} compares its entry map and the
     * array tags use {@code Arrays.equals}, so this is a genuine deep compare.
     */
    private static boolean equivalent(NbtElement a, NbtElement b) {
        return a == null ? b == null : a.equals(b);
    }

    /** Locates the first structural difference, for actionable failure output. */
    private static String firstDifference(NbtElement a, NbtElement b, String path) {
        if (a == null || b == null) {
            return path + ": one side null";
        }
        if (a.getType() != b.getType()) {
            return path + ": type " + a.getType() + " != " + b.getType();
        }
        if (a instanceof NbtCompound && b instanceof NbtCompound) {
            NbtCompound ca = (NbtCompound)a;
            NbtCompound cb = (NbtCompound)b;
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
