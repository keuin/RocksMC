package com.keuin.rocksmc;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.util.math.ChunkPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.zip.DeflaterOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the Anvil export, and above all the round trip: {@code .mca} imported into
 * RocksDB and written back out must yield the same chunks.
 *
 * <p>The source region files here are assembled from raw bytes rather than with
 * {@link AnvilWriter}, deliberately. If the writer built both the input and the output,
 * a systematic bug in it would corrupt both sides identically and the comparison would
 * still pass. Hand-rolled input keeps the writer on one side only.
 */
class WorldExporterTest {

    private static final int SECTOR = 4096;

    private static RocksMcConfig config(Path dir) {
        Properties props = new Properties();
        props.setProperty("backend", "rocksdb");
        return RocksMcConfig.of(props);
    }

    private static final WorldImporter.Progress NO_IMPORT_PROGRESS = (a, b, c) -> { };
    private static final WorldExporter.Progress NO_PROGRESS = (a, b, c, d) -> { };

    // ------------------------------------------------------------------ fixtures

    /** Chunk NBT with a payload big enough that compression and sectors are real. */
    private static NbtCompound chunkNbt(int x, int z, int payloadBytes) {
        NbtCompound level = new NbtCompound();
        level.putInt("xPos", x);
        level.putInt("zPos", z);
        level.putString("Status", "full");
        // Deliberately mixed types: an export that dropped or reordered a tag type
        // would pass a test that only stored byte arrays.
        level.putLong("LastUpdate", 123456789L + x);
        level.putString("Note", "chunk " + x + "," + z);
        byte[] blocks = new byte[payloadBytes];
        for (int i = 0; i < blocks.length; i++) {
            blocks[i] = (byte) ((x * 31 + z * 17 + i) & 0xFF);
        }
        level.putByteArray("Blocks", blocks);

        NbtCompound root = new NbtCompound();
        root.putInt("DataVersion", 2586);
        root.put("Level", level);
        return root;
    }

    private static NbtCompound chunkNbt(int x, int z) {
        return chunkNbt(x, z, 8192);
    }

    /**
     * Writes a genuine Anvil region file: 4 KiB sector table, 4 KiB timestamps, then
     * each payload framed by a 4-byte length and a 1-byte compression scheme.
     */
    private static void writeRegion(File file, Map<ChunkPos, NbtCompound> chunks)
            throws IOException {
        byte[] header = new byte[SECTOR * 2];
        List<byte[]> blocks = new ArrayList<>();
        int nextSector = 2;

        for (Map.Entry<ChunkPos, NbtCompound> entry : chunks.entrySet()) {
            ChunkPos pos = entry.getKey();
            ByteArrayOutputStream raw = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(raw)) {
                NbtIo.write(entry.getValue(), out);
            }
            ByteArrayOutputStream deflated = new ByteArrayOutputStream();
            try (DeflaterOutputStream deflater = new DeflaterOutputStream(deflated)) {
                deflater.write(raw.toByteArray());
            }
            byte[] payload = deflated.toByteArray();

            int framed = payload.length + 1;
            int sectors = (framed + 4 + SECTOR - 1) / SECTOR;
            byte[] block = new byte[sectors * SECTOR];
            block[0] = (byte) (framed >>> 24);
            block[1] = (byte) (framed >>> 16);
            block[2] = (byte) (framed >>> 8);
            block[3] = (byte) framed;
            block[4] = 2; // zlib
            System.arraycopy(payload, 0, block, 5, payload.length);
            blocks.add(block);

            int index = (pos.x & 31) + (pos.z & 31) * 32;
            // The sector count is one byte, so a chunk of 256 sectors or more cannot be
            // described here at all; those must live in an external .mcc file.
            if (sectors > 255) {
                throw new IllegalArgumentException(
                    "fixture chunk needs " + sectors + " sectors; use an external file");
            }
            header[index * 4] = (byte) (nextSector >>> 16);
            header[index * 4 + 1] = (byte) (nextSector >>> 8);
            header[index * 4 + 2] = (byte) nextSector;
            header[index * 4 + 3] = (byte) sectors;
            header[SECTOR + index * 4 + 3] = 1; // non-zero timestamp
            nextSector += sectors;
        }

        try (RandomAccessFile out = new RandomAccessFile(file, "rw")) {
            out.write(header);
            for (byte[] block : blocks) {
                out.write(block);
            }
        }
    }

    /** Reads every chunk out of a region directory, keyed by position. */
    private static Map<ChunkPos, NbtCompound> readAll(File dir) throws IOException {
        Map<ChunkPos, NbtCompound> found = new HashMap<>();
        File[] files = dir.listFiles((d, name) -> name.endsWith(".mca"));
        assertTrue(files != null, "no region directory at " + dir);
        for (File file : files) {
            AnvilReader.Report report = new AnvilReader.Report();
            AnvilReader.stream(file, report, entry ->
                found.put(entry.pos(), entry.nbt()));
            assertEquals(0, report.total(),
                "reading back " + file.getName() + " reported problems: " + report);
        }
        return found;
    }

    private static File importInto(Path worldDir, Map<ChunkPos, NbtCompound> chunks,
            String dimensionLeaf) throws IOException {
        File region = new File(worldDir.toFile(), dimensionLeaf);
        assertTrue(region.mkdirs() || region.isDirectory());
        // Group by region file, so multi-region worlds are covered too.
        Map<String, Map<ChunkPos, NbtCompound>> byFile = new HashMap<>();
        for (Map.Entry<ChunkPos, NbtCompound> entry : chunks.entrySet()) {
            String name = "r." + (entry.getKey().x >> 5) + "."
                + (entry.getKey().z >> 5) + ".mca";
            byFile.computeIfAbsent(name, k -> new HashMap<>())
                .put(entry.getKey(), entry.getValue());
        }
        for (Map.Entry<String, Map<ChunkPos, NbtCompound>> entry : byFile.entrySet()) {
            writeRegion(new File(region, entry.getKey()), entry.getValue());
        }
        return region;
    }

    // ------------------------------------------------------------------ the property

    /**
     * The export verifies itself by comparing a hash of serialised NBT against a hash
     * of the NBT it reads back. That is only sound if serialising a parse of some bytes
     * reproduces those bytes -- otherwise verification would report a mismatch on a
     * perfectly good export, or worse, be quietly meaningless.
     *
     * <p>It holds because {@code NbtCompound} is {@code HashMap}-backed: bucket order
     * depends on the key hashes and capacity, both identical for the same key set, and
     * within a bucket the chain follows insertion order -- which on the second pass is
     * the first pass's iteration order, already grouped by bucket. So one round trip
     * reaches a fixed point. This test pins it, because it is an assumption about
     * someone else's data structure rather than something the exporter controls.
     */
    @Test
    void serialisingAParseIsAFixedPoint() throws Exception {
        for (int i = 0; i < 8; i++) {
            byte[] once = AnvilWriter.serialise(chunkNbt(i, i * 3, 4096 + i * 517));
            byte[] twice = AnvilWriter.serialise(AnvilWriter.parse(once));
            assertArrayEquals(once, twice,
                "serialise(parse(x)) must equal x, or hash verification is unsound");
            // And it stays put, rather than merely alternating between two orders.
            assertArrayEquals(once,
                AnvilWriter.serialise(AnvilWriter.parse(twice)));
        }
    }

    // ------------------------------------------------------------------ round trip

    @Test
    void everyChunkSurvivesTheRoundTripThroughTheDatabase(@TempDir Path tmp)
            throws Exception {
        Map<ChunkPos, NbtCompound> expected = new HashMap<>();
        // Two region files, and positions that are not merely 0..n: negative
        // coordinates exercise the sign handling in both the key codec and the
        // region-file naming.
        for (int x = -33; x <= 33; x += 11) {
            for (int z = -33; z <= 33; z += 11) {
                expected.put(new ChunkPos(x, z), chunkNbt(x, z));
            }
        }
        Path world = tmp.resolve("world");
        assertTrue(world.toFile().mkdirs());
        importInto(world, expected, "region");

        WorldImporter.Result imported = WorldImporter.importWorld(world.toFile(),
            config(world), false, NO_IMPORT_PROGRESS, 4);
        assertEquals(expected.size(), imported.totalChunks(),
            "import should have taken every chunk");

        File out = tmp.resolve("exported").toFile();
        WorldExporter.Result result = WorldExporter.exportWorld(
            new File(world.toFile(), RocksDatabase.DIRECTORY_NAME), out, false,
            NO_PROGRESS, 4);

        assertTrue(result.clean(), "export reported problems: " + result.failures());
        assertEquals(expected.size(), result.totalChunks());

        Map<ChunkPos, NbtCompound> actual = readAll(new File(out, "region"));
        assertEquals(expected.keySet(), actual.keySet(),
            "the exported world must hold exactly the chunks that went in");
        for (Map.Entry<ChunkPos, NbtCompound> entry : expected.entrySet()) {
            assertEquals(entry.getValue(), actual.get(entry.getKey()),
                "chunk " + entry.getKey() + " differs after the round trip");
        }
    }

    @Test
    void everyDimensionLandsInItsOwnVanillaDirectory(@TempDir Path tmp)
            throws Exception {
        Path world = tmp.resolve("world");
        assertTrue(world.toFile().mkdirs());
        Map<ChunkPos, NbtCompound> overworld = new HashMap<>();
        overworld.put(new ChunkPos(1, 1), chunkNbt(1, 1));
        Map<ChunkPos, NbtCompound> nether = new HashMap<>();
        nether.put(new ChunkPos(2, 2), chunkNbt(2, 2));
        Map<ChunkPos, NbtCompound> end = new HashMap<>();
        end.put(new ChunkPos(3, 3), chunkNbt(3, 3));
        importInto(world, overworld, "region");
        importInto(world, nether, "DIM-1/region");
        importInto(world, end, "DIM1/region");

        WorldImporter.importWorld(world.toFile(), config(world), false,
            NO_IMPORT_PROGRESS, 2);
        File out = tmp.resolve("exported").toFile();
        WorldExporter.Result result = WorldExporter.exportWorld(
            new File(world.toFile(), RocksDatabase.DIRECTORY_NAME), out, false,
            NO_PROGRESS, 2);
        assertTrue(result.clean(), "export reported problems: " + result.failures());

        // The layout has to be vanilla's, or nothing else can read the result -- which
        // is the entire point of exporting.
        assertEquals(overworld.keySet(), readAll(new File(out, "region")).keySet());
        assertEquals(nether.keySet(), readAll(new File(out, "DIM-1/region")).keySet());
        assertEquals(end.keySet(), readAll(new File(out, "DIM1/region")).keySet());
    }

    @Test
    void aChunkTooLargeForTheSectorTableSurvivesAsAnExternalFile(@TempDir Path tmp)
            throws Exception {
        // Over 1 MiB once compressed, so it cannot be described by the one-byte sector
        // count and has to spill to a .mcc file. Incompressible content, because
        // zlib would otherwise shrink a repetitive payload back under the limit.
        int size = 3 * 1024 * 1024;
        byte[] noise = new byte[size];
        new java.util.Random(20260816L).nextBytes(noise);
        NbtCompound level = new NbtCompound();
        level.putInt("xPos", 5);
        level.putInt("zPos", 6);
        level.putByteArray("Blocks", noise);
        NbtCompound big = new NbtCompound();
        big.putInt("DataVersion", 2586);
        big.put("Level", level);

        // Written straight into the database, since the fixture writer cannot express a
        // chunk this large -- which is exactly why the external-file path exists.
        Path world = tmp.resolve("world");
        assertTrue(new File(world.toFile(), "region").mkdirs());
        Map<ChunkPos, NbtCompound> small = new HashMap<>();
        small.put(new ChunkPos(0, 0), chunkNbt(0, 0));
        importInto(world, small, "region");
        WorldImporter.importWorld(world.toFile(), config(world), false,
            NO_IMPORT_PROGRESS, 1);

        ChunkPos bigPos = new ChunkPos(5, 6);
        RocksChunkStore store = RocksChunkStore.open(
            DimensionKey.fromStorageDirectory(new File(world.toFile(), "region")),
            config(world));
        try {
            store.write(bigPos, big);
            store.sync();
        } finally {
            store.close();
        }

        File out = tmp.resolve("exported").toFile();
        WorldExporter.Result result = WorldExporter.exportWorld(
            new File(world.toFile(), RocksDatabase.DIRECTORY_NAME), out, false,
            NO_PROGRESS, 1);
        assertTrue(result.clean(), "export reported problems: " + result.failures());

        File region = new File(out, "region");
        assertTrue(new File(region, "c.5.6.mcc").isFile(),
            "an oversized chunk must spill to a .mcc file: "
                + java.util.Arrays.toString(region.list()));
        Map<ChunkPos, NbtCompound> actual = readAll(region);
        assertEquals(big, actual.get(bigPos), "the oversized chunk must survive intact");
        assertEquals(2, actual.size());
    }

    // ------------------------------------------------------------------ refusals

    @Test
    void refusesADirectoryThatIsNotADatabase(@TempDir Path tmp) {
        IOException error = assertThrows(IOException.class, () ->
            WorldExporter.exportWorld(tmp.toFile(), tmp.resolve("out").toFile(),
                false, NO_PROGRESS, 1));
        assertTrue(error.getMessage().contains("not a RocksDB database"),
            error.getMessage());
    }

    @Test
    void refusesToMergeIntoAWorldThatAlreadyHasRegionFiles(@TempDir Path tmp)
            throws Exception {
        Path world = tmp.resolve("world");
        assertTrue(world.toFile().mkdirs());
        Map<ChunkPos, NbtCompound> chunks = new HashMap<>();
        chunks.put(new ChunkPos(0, 0), chunkNbt(0, 0));
        importInto(world, chunks, "region");
        WorldImporter.importWorld(world.toFile(), config(world), false,
            NO_IMPORT_PROGRESS, 1);
        File database = new File(world.toFile(), RocksDatabase.DIRECTORY_NAME);

        // Vanilla's writer merges into an existing region file rather than truncating
        // it, so exporting over another world would interleave the two of them.
        File out = tmp.resolve("out").toFile();
        assertTrue(new File(out, "region").mkdirs());
        writeRegion(new File(out, "region/r.0.0.mca"), chunks);

        IOException error = assertThrows(IOException.class, () ->
            WorldExporter.exportWorld(database, out, false, NO_PROGRESS, 1));
        assertTrue(error.getMessage().contains("already contains .mca"),
            error.getMessage());

        // ...and goes ahead when the caller says merging is intended.
        WorldExporter.Result result =
            WorldExporter.exportWorld(database, out, true, NO_PROGRESS, 1);
        assertTrue(result.clean(), "export reported problems: " + result.failures());
    }

    @Test
    void doesNotModifyTheDatabaseItReads(@TempDir Path tmp) throws Exception {
        Path world = tmp.resolve("world");
        assertTrue(world.toFile().mkdirs());
        Map<ChunkPos, NbtCompound> chunks = new HashMap<>();
        for (int i = 0; i < 6; i++) {
            chunks.put(new ChunkPos(i, i), chunkNbt(i, i));
        }
        importInto(world, chunks, "region");
        WorldImporter.importWorld(world.toFile(), config(world), false,
            NO_IMPORT_PROGRESS, 2);
        File database = new File(world.toFile(), RocksDatabase.DIRECTORY_NAME);

        // A read-only open must not so much as create a log file, because the supported
        // way to export a running server is to point this at its live database or a
        // checkpoint, and touching either would be a surprise.
        Map<String, Long> before = fileSizes(database);
        WorldExporter.exportWorld(database, tmp.resolve("out").toFile(), false,
            NO_PROGRESS, 2);
        assertEquals(before, fileSizes(database),
            "exporting must leave the source database byte-for-byte unchanged");
    }

    private static Map<String, Long> fileSizes(File dir) {
        Map<String, Long> sizes = new java.util.TreeMap<>();
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                sizes.put(file.getName(), file.isDirectory() ? -1L : file.length());
            }
        }
        return sizes;
    }

    @Test
    void reportsTheDataVersionItSaw(@TempDir Path tmp) throws Exception {
        Path world = tmp.resolve("world");
        assertTrue(world.toFile().mkdirs());
        Map<ChunkPos, NbtCompound> chunks = new HashMap<>();
        chunks.put(new ChunkPos(0, 0), chunkNbt(0, 0));
        importInto(world, chunks, "region");
        WorldImporter.importWorld(world.toFile(), config(world), false,
            NO_IMPORT_PROGRESS, 1);

        WorldExporter.Result result = WorldExporter.exportWorld(
            new File(world.toFile(), RocksDatabase.DIRECTORY_NAME),
            tmp.resolve("out").toFile(), false, NO_PROGRESS, 1);
        assertTrue(result.clean(), "export reported problems: " + result.failures());

        // Whoever loads the result needs to know which version it is for; a mixed range
        // means the world was never fully upgraded, which is worth surfacing.
        boolean sawVersion = false;
        for (WorldExporter.DimensionResult dimension : result.dimensions) {
            int[] range = dimension.dataVersionRange();
            if (range != null) {
                assertEquals(2586, range[0]);
                assertEquals(2586, range[1]);
                sawVersion = true;
            }
        }
        assertTrue(sawVersion, "the export should report the DataVersion it saw");
    }

    @Test
    void anEmptyDimensionProducesNoRegionFile(@TempDir Path tmp) throws Exception {
        Path world = tmp.resolve("world");
        assertTrue(world.toFile().mkdirs());
        Map<ChunkPos, NbtCompound> chunks = new HashMap<>();
        chunks.put(new ChunkPos(0, 0), chunkNbt(0, 0));
        importInto(world, chunks, "region");
        WorldImporter.importWorld(world.toFile(), config(world), false,
            NO_IMPORT_PROGRESS, 1);

        File out = tmp.resolve("out").toFile();
        WorldExporter.exportWorld(new File(world.toFile(),
            RocksDatabase.DIRECTORY_NAME), out, false, NO_PROGRESS, 1);

        // An opened-but-unwritten region file is 4 KiB of zeroed header at best and
        // 0 bytes at worst; either way it is a file vanilla never made, and world
        // tools treat a present-but-empty region as a generated-and-empty area.
        File poi = new File(out, "poi");
        assertFalse(poi.isDirectory() && poi.list() != null && poi.list().length > 0,
            "an empty POI leaf should not produce files: "
                + java.util.Arrays.toString(poi.list()));
    }
}
