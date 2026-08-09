package com.keuin.rocksmc;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.util.math.ChunkPos;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * Reads chunks straight out of Anvil region files.
 *
 * <h2>Why this parses the format itself</h2>
 *
 * <p>Vanilla's {@code RegionFile} cannot be used for this. Once the RocksDB
 * backend is active the mixin has redirected {@code RegionBasedStorage}, so
 * asking vanilla to read Anvil would read RocksDB right back. An independent
 * parser is also a genuinely separate source of truth for the fidelity harness,
 * which is worth more than reusing code here.
 *
 * <h2>Format</h2>
 *
 * <p>As implemented by {@code RegionFile} in 1.16.5:
 *
 * <pre>
 *   bytes 0..4095      1024 x uint32 packed sector entries
 *                      offset = value &gt;&gt;&gt; 8   (in 4096-byte sectors)
 *                      size   = value &amp; 0xFF   (sector count)
 *   bytes 4096..8191   1024 x uint32 timestamps (unused here)
 *   at offset*4096     uint32 length, uint8 scheme, then payload
 *                      scheme 1 = gzip, 2 = zlib/deflate, 3 = uncompressed
 *                      high bit of scheme set =&gt; payload lives in an external
 *                      c.X.Z.mcc file alongside the region file
 * </pre>
 *
 * <h2>Oversized chunks</h2>
 *
 * <p>A chunk needing 256 or more sectors (1 MiB) is written to an external
 * {@code c.X.Z.mcc} file instead, with only a 5-byte stub left in the region
 * file. Earlier code in this project skipped those entries, which is tolerable
 * for a sampling harness but would <em>silently drop terrain</em> during an
 * import. They are read properly here.
 */
public final class AnvilReader {

    private static final int SECTOR = 4096;
    private static final int HEADER_SECTORS = 2;

    /** A chunk as stored by Anvil, decompressed and parsed. */
    public static final class Entry {
        private final ChunkPos pos;
        private final NbtCompound nbt;
        private final int compressedLength;
        private final boolean external;

        Entry(ChunkPos pos, NbtCompound nbt, int compressedLength, boolean external) {
            this.pos = pos;
            this.nbt = nbt;
            this.compressedLength = compressedLength;
            this.external = external;
        }

        public ChunkPos pos() {
            return this.pos;
        }

        public NbtCompound nbt() {
            return this.nbt;
        }

        /** Bytes the payload occupied on disk, before decompression. */
        public int compressedLength() {
            return this.compressedLength;
        }

        /** Whether this chunk came from an external {@code .mcc} file. */
        public boolean external() {
            return this.external;
        }
    }

    /** Problems encountered while reading, for reporting rather than throwing. */
    public static final class Report {
        public int emptyRegionFiles;
        public int invalidSectorEntries;
        public int truncatedHeaders;
        public int decompressFailures;
        public int missingExternalFiles;
        public int unknownSchemes;

        public int total() {
            return this.emptyRegionFiles + this.invalidSectorEntries + this.truncatedHeaders
                + this.decompressFailures + this.missingExternalFiles + this.unknownSchemes;
        }

        @Override
        public String toString() {
            if (total() == 0) {
                return "no anomalies";
            }
            StringBuilder sb = new StringBuilder();
            append(sb, "emptyRegionFiles", this.emptyRegionFiles);
            append(sb, "invalidSectorEntries", this.invalidSectorEntries);
            append(sb, "truncatedHeaders", this.truncatedHeaders);
            append(sb, "decompressFailures", this.decompressFailures);
            append(sb, "missingExternalFiles", this.missingExternalFiles);
            append(sb, "unknownSchemes", this.unknownSchemes);
            return sb.toString();
        }

        private static void append(StringBuilder sb, String name, int value) {
            if (value > 0) {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(name).append('=').append(value);
            }
        }
    }

    /** Lists region files in a directory, in stable order. */
    public static List<File> regionFiles(File regionDir) {
        File[] files = regionDir.listFiles((d, n) -> n.endsWith(".mca"));
        List<File> out = new ArrayList<>();
        if (files != null) {
            java.util.Arrays.sort(files);
            out.addAll(java.util.Arrays.asList(files));
        }
        return out;
    }

    /**
     * Reads every chunk in one region file.
     *
     * <p>Anomalies are recorded in {@code report} rather than thrown: a single
     * corrupt entry in a large world should not abort an import, but it must be
     * counted and surfaced.
     */
    public static List<Entry> read(File regionFile, Report report) throws IOException {
        List<Entry> out = new ArrayList<>();
        long length = regionFile.length();
        if (length == 0) {
            // Vanilla creates region files on demand and leaves them empty until a
            // chunk in that region is written. Not corruption.
            report.emptyRegionFiles++;
            return out;
        }
        if (length < (long)SECTOR * HEADER_SECTORS) {
            report.truncatedHeaders++;
            return out;
        }

        String[] parts = regionFile.getName().split("\\.");
        int regionX = Integer.parseInt(parts[1]);
        int regionZ = Integer.parseInt(parts[2]);

        try (RandomAccessFile raf = new RandomAccessFile(regionFile, "r")) {
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
                if (offset < HEADER_SECTORS || sectors == 0
                        || (long)offset * SECTOR >= length) {
                    report.invalidSectorEntries++;
                    continue;
                }

                raf.seek((long)offset * SECTOR);
                int declaredLength = raf.readInt();
                int scheme = raf.readUnsignedByte();
                boolean external = (scheme & 0x80) != 0;
                scheme &= 0x7F;

                ChunkPos pos = new ChunkPos(regionX * 32 + (i % 32), regionZ * 32 + (i / 32));

                byte[] payload;
                if (external) {
                    // Oversized chunk: the region file holds only a stub and the
                    // real payload lives beside it. Skipping these would silently
                    // drop the largest chunks in the world.
                    File mcc = new File(regionFile.getParentFile(),
                        "c." + pos.x + "." + pos.z + ".mcc");
                    if (!mcc.isFile()) {
                        report.missingExternalFiles++;
                        continue;
                    }
                    payload = Files.readAllBytes(mcc.toPath());
                } else {
                    if (declaredLength <= 1) {
                        report.invalidSectorEntries++;
                        continue;
                    }
                    int payloadLength = declaredLength - 1;
                    if (payloadLength > sectors * SECTOR) {
                        report.invalidSectorEntries++;
                        continue;
                    }
                    payload = new byte[payloadLength];
                    raf.readFully(payload);
                }

                NbtCompound nbt;
                try {
                    nbt = decode(payload, scheme, report);
                } catch (IOException e) {
                    report.decompressFailures++;
                    continue;
                }
                if (nbt != null) {
                    out.add(new Entry(pos, nbt, payload.length, external));
                }
            }
        }
        return out;
    }

    private static NbtCompound decode(byte[] payload, int scheme, Report report)
            throws IOException {
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
                report.unknownSchemes++;
                return null;
        }
    }

    private AnvilReader() {
    }
}
