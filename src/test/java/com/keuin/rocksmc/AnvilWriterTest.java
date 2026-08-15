package com.keuin.rocksmc;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.math.ChunkPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the Anvil writer by reading back what it produced.
 *
 * <p>{@link AnvilReader} is the oracle, which gives genuine two-implementation
 * coverage: vanilla's {@code RegionFile} writes, this project's independent parser
 * reads. Every assertion below is "a separate implementation agrees", not "the writer
 * agrees with itself".
 *
 * <p>The cases are chosen for the failure modes that produce a file which passes a
 * casual check and is then rejected by a real tool: the ≥256-sector spill to an
 * external {@code .mcc} file (which neither pre-existing fixture in this project ever
 * generated), sector alignment of the finished file, and negative coordinates.
 */
class AnvilWriterTest {

    private static final int SECTOR = 4096;

    /** Chunk NBT of roughly a given serialised size. */
    private static NbtCompound nbt(String marker, int payloadBytes) {
        NbtCompound level = new NbtCompound();
        level.putString("Status", "full");
        byte[] payload = new byte[payloadBytes];
        // Pseudo-random content so it does not compress to nothing, which would
        // defeat the point of the oversized case.
        int seed = marker.hashCode();
        for (int i = 0; i < payload.length; i++) {
            seed = seed * 1103515245 + 12345;
            payload[i] = (byte) (seed >>> 16);
        }
        level.putByteArray("Blocks", payload);

        NbtCompound root = new NbtCompound();
        root.putInt("DataVersion", 2586);
        root.putString("marker", marker);
        root.put("Level", level);
        return root;
    }

    private static Map<ChunkPos, NbtCompound> readBack(File region) throws Exception {
        Map<ChunkPos, NbtCompound> out = new HashMap<>();
        AnvilReader.Report report = new AnvilReader.Report();
        AnvilReader.stream(region, report, entry -> out.put(entry.pos(), entry.nbt()));
        assertEquals(0, report.total(), "reading back what we wrote found anomalies: "
            + report);
        return out;
    }

    // ------------------------------------------------------------------ basics

    /** A written chunk must read back with identical NBT. */
    @Test
    void writtenChunkReadsBackIdentically(@TempDir Path tmp) throws Exception {
        File dir = new File(tmp.toFile(), "region");
        File region = new File(dir, "r.0.0.mca");
        NbtCompound written = nbt("basic", 4096);

        try (AnvilWriter writer = new AnvilWriter(region, dir)) {
            writer.write(new ChunkPos(0, 0), written);
            assertEquals(1, writer.chunksWritten());
        }

        Map<ChunkPos, NbtCompound> read = readBack(region);
        assertEquals(1, read.size());
        assertEquals(written, read.get(new ChunkPos(0, 0)),
            "NBT must survive the round trip through a real region file");
    }

    /** Every slot of a region must be addressable, including the last. */
    @Test
    void allSlotsIncludingTheLastAreAddressable(@TempDir Path tmp) throws Exception {
        File dir = new File(tmp.toFile(), "region");
        File region = new File(dir, "r.0.0.mca");
        int[] locals = {0, 1, 31, 32, 512, 1022, 1023};

        Map<ChunkPos, NbtCompound> expected = new HashMap<>();
        try (AnvilWriter writer = new AnvilWriter(region, dir)) {
            for (int local : locals) {
                ChunkPos pos = new ChunkPos(local % 32, local / 32);
                NbtCompound tag = nbt("slot-" + local, 1024);
                expected.put(pos, tag);
                writer.write(pos, tag);
            }
        }
        assertEquals(expected, readBack(region));
    }

    /**
     * Negative region coordinates, which are the normal case in Minecraft.
     *
     * <p>The slot index is {@code (x & 31) + (z & 31) * 32}; a writer using {@code %}
     * instead of {@code &} would place negative coordinates in the wrong slot, or a
     * negative one.
     */
    @Test
    void negativeCoordinatesLandInTheRightSlots(@TempDir Path tmp) throws Exception {
        File dir = new File(tmp.toFile(), "region");
        File region = new File(dir, "r.-2.-3.mca");

        Map<ChunkPos, NbtCompound> expected = new HashMap<>();
        try (AnvilWriter writer = new AnvilWriter(region, dir)) {
            for (int dx = 0; dx < 32; dx += 11) {
                for (int dz = 0; dz < 32; dz += 13) {
                    ChunkPos pos = new ChunkPos(-2 * 32 + dx, -3 * 32 + dz);
                    NbtCompound tag = nbt("neg-" + dx + "-" + dz, 512);
                    expected.put(pos, tag);
                    writer.write(pos, tag);
                }
            }
        }
        assertEquals(expected, readBack(region));
    }

    // ---------------------------------------------------------- oversized chunks

    /**
     * The ≥256-sector spill, which no pre-existing fixture in this project generated.
     *
     * <p>This is the highest-severity latent bug in a hand-rolled writer: the header's
     * size field is 8 bits, so storing 256 writes {@code 256 & 0xFF == 0}, a "size 0"
     * entry that vanilla zeroes on open with only a warning — silently dropping the
     * largest chunks in the world. Delegating to vanilla's writer avoids it, and this
     * proves the delegation actually handles the case rather than merely being expected
     * to.
     */
    @Test
    void oversizedChunkSpillsToAnExternalFileAndReadsBack(@TempDir Path tmp)
            throws Exception {
        File dir = new File(tmp.toFile(), "region");
        File region = new File(dir, "r.0.0.mca");
        // Comfortably past 256 sectors (1 MiB) of incompressible payload.
        NbtCompound huge = nbt("oversized", 3 * 1024 * 1024);

        try (AnvilWriter writer = new AnvilWriter(region, dir)) {
            writer.write(new ChunkPos(5, 7), huge);
            assertTrue(writer.externalChunks() >= 1,
                "an oversized chunk should be reported as external");
        }

        // Vanilla names the sidecar by absolute chunk coordinates.
        File mcc = new File(dir, "c.5.7.mcc");
        assertTrue(mcc.isFile(), "expected an external .mcc file, found: "
            + String.join(", ", dir.list()));
        assertTrue(mcc.length() > 1024 * 1024,
            "the payload should live in the .mcc, size was " + mcc.length());

        Map<ChunkPos, NbtCompound> read = readBack(region);
        assertEquals(1, read.size(), "the oversized chunk must still be discoverable");
        assertEquals(huge, read.get(new ChunkPos(5, 7)),
            "an oversized chunk must round trip through the external file");
    }

    /** A mix of oversized and ordinary chunks in one region must all survive. */
    @Test
    void oversizedAndOrdinaryChunksCoexist(@TempDir Path tmp) throws Exception {
        File dir = new File(tmp.toFile(), "region");
        File region = new File(dir, "r.0.0.mca");

        Map<ChunkPos, NbtCompound> expected = new HashMap<>();
        try (AnvilWriter writer = new AnvilWriter(region, dir)) {
            for (int i = 0; i < 4; i++) {
                ChunkPos pos = new ChunkPos(i, 0);
                NbtCompound tag = i == 2
                    ? nbt("big-" + i, 2 * 1024 * 1024)
                    : nbt("small-" + i, 2048);
                expected.put(pos, tag);
                writer.write(pos, tag);
            }
        }
        assertEquals(expected, readBack(region));
    }

    // ------------------------------------------------------------- file structure

    /**
     * The finished file must be a whole number of sectors.
     *
     * <p>An unpadded final sector round-trips fine through this project's reader, which
     * tolerates a short tail, and is rejected by stricter parsers that trust the header
     * and read {@code size * 4096} bytes. Exactly the case where local verification
     * passes and the real consumer fails, so it is asserted on the bytes.
     */
    @Test
    void fileLengthIsSectorAligned(@TempDir Path tmp) throws Exception {
        File dir = new File(tmp.toFile(), "region");
        File region = new File(dir, "r.0.0.mca");
        try (AnvilWriter writer = new AnvilWriter(region, dir)) {
            // A size that is deliberately not a sector multiple.
            writer.write(new ChunkPos(1, 1), nbt("odd", 5000));
        }
        long length = region.length();
        assertTrue(length > 0, "the file should not be empty");
        assertEquals(0, length % SECTOR,
            "file length " + length + " is not a multiple of " + SECTOR
                + "; a strict reader would hit EOF on the last chunk");
        assertTrue(length >= 2L * SECTOR, "the two header sectors must be present");
    }

    /**
     * A writer that wrote nothing leaves a zero-length file, which is normal.
     *
     * <p>{@code RegionFile} only flushes its 8 KiB header when the first chunk is
     * written, so a region with no chunks is 0 bytes on disk — exactly vanilla's own
     * convention of creating region files on demand and leaving them empty. That is why
     * {@link AnvilReader} counts it as {@code emptyRegionFiles} rather than as
     * corruption, and it is asserted here so the distinction stays deliberate.
     *
     * <p>Consequence for the exporter: it should only open a region file when it has
     * chunks for that region, otherwise it litters the output with zero-length files.
     */
    @Test
    void emptyRegionFileIsZeroLengthAndReportedAsEmpty(@TempDir Path tmp) throws Exception {
        File dir = new File(tmp.toFile(), "region");
        File region = new File(dir, "r.0.0.mca");
        try (AnvilWriter writer = new AnvilWriter(region, dir)) {
            assertEquals(0, writer.chunksWritten());
        }
        assertTrue(region.isFile(), "the file should exist even with no chunks");
        assertEquals(0L, region.length(),
            "vanilla writes the header lazily, so an empty region is 0 bytes");

        AnvilReader.Report report = new AnvilReader.Report();
        List<ChunkPos> found = new ArrayList<>();
        AnvilReader.stream(region, report, entry -> found.add(entry.pos()));
        assertTrue(found.isEmpty());
        assertEquals(1, report.emptyRegionFiles,
            "an empty region file is a counted anomaly, not corruption");
        assertEquals(1, report.total(), "and nothing else should be reported");
    }

    /** Overwriting a chunk must replace it, not leave the old copy discoverable. */
    @Test
    void rewritingAChunkReplacesIt(@TempDir Path tmp) throws Exception {
        File dir = new File(tmp.toFile(), "region");
        File region = new File(dir, "r.0.0.mca");
        NbtCompound second = nbt("second", 3000);

        try (AnvilWriter writer = new AnvilWriter(region, dir)) {
            writer.write(new ChunkPos(3, 3), nbt("first", 1500));
            writer.write(new ChunkPos(3, 3), second);
        }

        Map<ChunkPos, NbtCompound> read = readBack(region);
        assertEquals(1, read.size());
        assertEquals(second, read.get(new ChunkPos(3, 3)),
            "the second write must win");
    }

    /** Reopening an existing file must preserve what is already in it. */
    @Test
    void reopeningPreservesExistingChunks(@TempDir Path tmp) throws Exception {
        File dir = new File(tmp.toFile(), "region");
        File region = new File(dir, "r.0.0.mca");

        NbtCompound first = nbt("first", 2000);
        try (AnvilWriter writer = new AnvilWriter(region, dir)) {
            writer.write(new ChunkPos(0, 0), first);
        }
        NbtCompound second = nbt("second", 2000);
        try (AnvilWriter writer = new AnvilWriter(region, dir)) {
            writer.write(new ChunkPos(1, 0), second);
        }

        Map<ChunkPos, NbtCompound> read = readBack(region);
        assertEquals(2, read.size(), "reopening must not truncate: " + read.keySet());
        assertEquals(first, read.get(new ChunkPos(0, 0)));
        assertEquals(second, read.get(new ChunkPos(1, 0)));
    }

    /** The writer must create the output directory rather than failing on it. */
    @Test
    void missingDirectoryIsCreated(@TempDir Path tmp) throws Exception {
        File dir = new File(tmp.toFile(), "deep/nested/region");
        assertFalse(dir.exists());
        File region = new File(dir, "r.0.0.mca");
        try (AnvilWriter writer = new AnvilWriter(region, dir)) {
            writer.write(new ChunkPos(0, 0), nbt("created", 100));
        }
        assertTrue(region.isFile());
        assertNotNull(readBack(region).get(new ChunkPos(0, 0)));
    }

    /** Many chunks in one file, to exercise sector allocation over a realistic count. */
    @Test
    void manyChunksInOneRegion(@TempDir Path tmp) throws Exception {
        File dir = new File(tmp.toFile(), "region");
        File region = new File(dir, "r.0.0.mca");

        List<ChunkPos> positions = new ArrayList<>();
        Map<ChunkPos, NbtCompound> expected = new HashMap<>();
        try (AnvilWriter writer = new AnvilWriter(region, dir)) {
            for (int i = 0; i < 256; i++) {
                ChunkPos pos = new ChunkPos(i % 32, i / 32);
                NbtCompound tag = nbt("many-" + i, 1000 + (i * 37) % 9000);
                positions.add(pos);
                expected.put(pos, tag);
                writer.write(pos, tag);
            }
            assertEquals(256, writer.chunksWritten());
        }

        Map<ChunkPos, NbtCompound> read = readBack(region);
        assertEquals(expected.size(), read.size());
        for (ChunkPos pos : positions) {
            assertEquals(expected.get(pos), read.get(pos), "mismatch at " + pos);
        }
        assertEquals(0, region.length() % SECTOR);
    }
}
