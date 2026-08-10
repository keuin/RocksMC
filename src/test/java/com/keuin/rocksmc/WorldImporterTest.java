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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.zip.DeflaterOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the world importer, chiefly that parallelism changed nothing observable.
 *
 * <p>The importer writes real terrain, so a concurrency bug here is silent data
 * loss discovered by a player finding a hole in the world. The property worth
 * pinning is therefore equivalence: a parallel import and a sequential one must
 * produce byte-identical stored values and identical counters. Anything weaker
 * would pass while dropping chunks.
 *
 * <p>Region files are synthesised rather than fixtures, so the tests are
 * hermetic and can cover awkward shapes -- sparse regions, negative coordinates,
 * more region files than worker threads -- that a captured world may not contain.
 */
class WorldImporterTest {

    private static final int SECTOR = 4096;

    private static RocksMcConfig config() {
        Properties props = new Properties();
        props.setProperty("backend", "rocksdb");
        return RocksMcConfig.of(props);
    }

    // ------------------------------------------------------------------ fixtures

    /** NBT with enough content that compression and parsing are non-trivial. */
    private static NbtCompound chunkNbt(int x, int z) {
        NbtCompound level = new NbtCompound();
        level.putInt("xPos", x);
        level.putInt("zPos", z);
        level.putString("Status", "full");
        // A byte array large enough that the payload spans sectors, so the reader's
        // length and sector arithmetic is genuinely exercised.
        byte[] blocks = new byte[8192];
        for (int i = 0; i < blocks.length; i++) {
            blocks[i] = (byte) ((x * 31 + z * 17 + i) & 0xFF);
        }
        level.putByteArray("Blocks", blocks);

        NbtCompound root = new NbtCompound();
        root.putInt("DataVersion", 2586);
        root.put("Level", level);
        return root;
    }

    /**
     * Writes a region file containing the given chunk indices.
     *
     * <p>Deliberately a real Anvil file rather than a mock: the point is to exercise
     * {@link AnvilReader}'s own parsing under concurrency, so the bytes have to be
     * the genuine layout -- 4 KiB sector table, 4 KiB timestamps, then zlib payloads
     * prefixed by length and scheme.
     */
    private static void writeRegion(File file, int regionX, int regionZ,
            int[] localIndices) throws IOException {
        byte[] header = new byte[SECTOR * 2];
        List<byte[]> payloads = new ArrayList<>();
        int nextSector = 2;

        for (int index : localIndices) {
            int chunkX = regionX * 32 + (index % 32);
            int chunkZ = regionZ * 32 + (index / 32);

            ByteArrayOutputStream raw = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(raw)) {
                NbtIo.write(chunkNbt(chunkX, chunkZ), out);
            }
            ByteArrayOutputStream compressed = new ByteArrayOutputStream();
            try (DeflaterOutputStream deflater = new DeflaterOutputStream(compressed)) {
                deflater.write(raw.toByteArray());
            }
            byte[] payload = compressed.toByteArray();

            // 4-byte length (payload + scheme byte), 1-byte scheme, then payload.
            int framed = payload.length + 1;
            int sectors = (framed + 4 + SECTOR - 1) / SECTOR;
            byte[] block = new byte[sectors * SECTOR];
            block[0] = (byte) (framed >>> 24);
            block[1] = (byte) (framed >>> 16);
            block[2] = (byte) (framed >>> 8);
            block[3] = (byte) framed;
            block[4] = 2; // zlib
            System.arraycopy(payload, 0, block, 5, payload.length);
            payloads.add(block);

            int entry = (nextSector << 8) | sectors;
            header[index * 4] = (byte) (entry >>> 24);
            header[index * 4 + 1] = (byte) (entry >>> 16);
            header[index * 4 + 2] = (byte) (entry >>> 8);
            header[index * 4 + 3] = (byte) entry;
            nextSector += sectors;
        }

        try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
            raf.write(header);
            for (byte[] block : payloads) {
                raf.write(block);
            }
        }
    }

    /** Storage directories the fixture creates, in the order the importer visits them. */
    private static final String[] FIXTURE_DIRS = {"region", "poi", "DIM-1/region", "DIM1/region"};

    /** Chunk slots populated per region file by the fixture. */
    private static final int[] FIXTURE_INDICES = {0, 1, 33, 100, 511, 1023};

    /** Total entries a fixture world holds, counting POI, which the importer also stores. */
    private static int expectedEntries(int regionsPerDim) {
        return FIXTURE_DIRS.length * regionsPerDim * FIXTURE_INDICES.length;
    }

    /**
     * Builds a world with several region files per dimension.
     *
     * @return every chunk position written to a {@code region} directory. POI
     *         positions are deliberately excluded because the returned set is used to
     *         probe the chunk column family; {@link #expectedEntries} counts POI too,
     *         since the importer stores those as well.
     */
    private static Set<ChunkPos> buildWorld(Path worldRoot, int regionsPerDim)
            throws IOException {
        Set<ChunkPos> written = new HashSet<>();
        for (String dir : FIXTURE_DIRS) {
            File storage = new File(worldRoot.toFile(), dir);
            assertTrue(storage.mkdirs() || storage.isDirectory());
            for (int r = 0; r < regionsPerDim; r++) {
                // Negative region coordinates too: they are entirely normal in
                // Minecraft and exercise the Morton bias.
                int regionX = r - 1;
                int regionZ = -r;
                // A sparse spread rather than all 1024, to keep the fixture small
                // while still crossing rows of the sector table.
                writeRegion(new File(storage, "r." + regionX + "." + regionZ + ".mca"),
                    regionX, regionZ, FIXTURE_INDICES);
                if (!dir.equals("poi")) {
                    for (int index : FIXTURE_INDICES) {
                        written.add(new ChunkPos(regionX * 32 + (index % 32),
                            regionZ * 32 + (index / 32)));
                    }
                }
            }
        }
        return written;
    }

    /** Every stored value in a column family, keyed by hex key. */
    private static Map<String, String> dumpDatabase(Path worldRoot) throws Exception {
        Map<String, String> out = new HashMap<>();
        RocksDatabase db = RocksDatabase.open(worldRoot.toFile(), config());
        try {
            for (String leaf : new String[] {"region", "poi"}) {
                try (org.rocksdb.RocksIterator it =
                        db.handle().newIterator(db.columnFamilyFor(leaf))) {
                    for (it.seekToFirst(); it.isValid(); it.next()) {
                        out.put(leaf + ':' + hex(it.key()), hex(sha256(it.value())));
                    }
                }
            }
        } finally {
            db.release();
        }
        return out;
    }

    private static byte[] sha256(byte[] data) throws Exception {
        return java.security.MessageDigest.getInstance("SHA-256").digest(data);
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    // -------------------------------------------------------------- equivalence

    /**
     * The property that matters: parallel and sequential imports agree exactly.
     *
     * <p>Compares every stored key and a hash of every stored value, not just
     * counts, because a race that wrote a chunk under the wrong key or truncated a
     * value would keep the counts right while corrupting the world.
     */
    @Test
    void parallelImportMatchesSequentialExactly(@TempDir Path tmp) throws Exception {
        Path sequential = tmp.resolve("seq");
        Path parallel = tmp.resolve("par");
        Set<ChunkPos> expected = buildWorld(sequential, 3);
        buildWorld(parallel, 3);

        WorldImporter.Result seqResult =
            WorldImporter.importWorld(sequential.toFile(), config(), false, null, 1);
        WorldImporter.Result parResult =
            WorldImporter.importWorld(parallel.toFile(), config(), false, null, 8);

        assertTrue(seqResult.clean(), "sequential import failed: " + failures(seqResult));
        assertTrue(parResult.clean(), "parallel import failed: " + failures(parResult));

        assertEquals(seqResult.totalChunks(), parResult.totalChunks(),
            "parallel import wrote a different number of chunks");
        assertTrue(seqResult.totalChunks() > 0, "fixture produced no chunks");

        Map<String, String> seqDump = dumpDatabase(sequential);
        Map<String, String> parDump = dumpDatabase(parallel);
        assertEquals(seqDump.keySet(), parDump.keySet(),
            "parallel import stored a different key set");
        assertEquals(seqDump, parDump,
            "parallel import stored different values under the same keys");

        // And the chunk count lines up with the fixture, so neither path silently
        // dropped a whole region. Counts POI as well as chunks, since the importer
        // stores both.
        assertEquals(expectedEntries(3), seqResult.totalChunks(),
            "every fixture entry, POI included, must be imported");
        // The expected set covers only the chunk column family, so it must be a
        // strict subset of the total.
        assertTrue(expected.size() < seqResult.totalChunks());
    }

    /** Per-directory counters must be identical too, not merely the total. */
    @Test
    void perDirectoryCountersMatchSequential(@TempDir Path tmp) throws Exception {
        Path sequential = tmp.resolve("seq");
        Path parallel = tmp.resolve("par");
        buildWorld(sequential, 2);
        buildWorld(parallel, 2);

        WorldImporter.Result seq =
            WorldImporter.importWorld(sequential.toFile(), config(), false, null, 1);
        WorldImporter.Result par =
            WorldImporter.importWorld(parallel.toFile(), config(), false, null, 16);

        assertEquals(seq.directories.size(), par.directories.size());
        for (int i = 0; i < seq.directories.size(); i++) {
            WorldImporter.DirectoryResult a = seq.directories.get(i);
            WorldImporter.DirectoryResult b = par.directories.get(i);
            assertEquals(a.label, b.label, "directories must be visited in one order");
            assertEquals(a.chunksRead(), b.chunksRead(), a.label + ": chunksRead");
            assertEquals(a.chunksWritten(), b.chunksWritten(), a.label + ": chunksWritten");
            assertEquals(a.externalChunks(), b.externalChunks(), a.label + ": externalChunks");
            assertEquals(a.uncompressedBytes(), b.uncompressedBytes(),
                a.label + ": uncompressedBytes");
            assertEquals(a.verifyFailures(), b.verifyFailures(), a.label + ": verifyFailures");
            assertEquals(a.anomalies.total(), b.anomalies.total(), a.label + ": anomalies");
        }
    }

    /**
     * More workers than region files must not deadlock, drop work, or double-count.
     *
     * <p>The boundary case where several workers find nothing to do.
     */
    @Test
    void moreThreadsThanRegionsIsSafe(@TempDir Path tmp) throws Exception {
        buildWorld(tmp, 1);
        WorldImporter.Result result =
            WorldImporter.importWorld(tmp.toFile(), config(), false, null, 32);
        assertTrue(result.clean(), "import failed: " + failures(result));
        assertEquals(expectedEntries(1), result.totalChunks(),
            "idle workers must not cause work to be skipped");
    }

    // ------------------------------------------------------------- data fidelity

    /** Every chunk in the fixture must be individually readable afterwards. */
    @Test
    void everyFixtureChunkIsReadableAfterParallelImport(@TempDir Path tmp) throws Exception {
        Set<ChunkPos> expected = buildWorld(tmp, 2);
        WorldImporter.Result result =
            WorldImporter.importWorld(tmp.toFile(), config(), false, null, 8);
        assertTrue(result.clean(), "import failed: " + failures(result));

        DimensionKey overworld = DimensionKey.fromStorageDirectory(
            new File(tmp.toFile(), "region"));
        try (RocksChunkStore store = RocksChunkStore.open(overworld, config())) {
            for (ChunkPos pos : expected) {
                NbtCompound nbt = store.read(pos);
                assertNotNull(nbt, "missing chunk after import: " + pos);
                // Round-trip fidelity, not just presence: the coordinates inside the
                // NBT must match the key it was stored under, which is what a
                // misassigned write would break.
                NbtCompound level = nbt.getCompound("Level");
                assertEquals(pos.x, level.getInt("xPos"), "wrong chunk at " + pos);
                assertEquals(pos.z, level.getInt("zPos"), "wrong chunk at " + pos);
                assertEquals(2586, nbt.getInt("DataVersion"));
            }
        }
    }

    /** Dimensions must not bleed into each other when imported concurrently. */
    @Test
    void dimensionsRemainSeparateAfterParallelImport(@TempDir Path tmp) throws Exception {
        buildWorld(tmp, 1);
        WorldImporter.Result result =
            WorldImporter.importWorld(tmp.toFile(), config(), false, null, 8);
        assertTrue(result.clean(), "import failed: " + failures(result));

        // The overworld and DIM1 fixtures use the same region coordinates, so a
        // broken ordinal prefix would have them overwrite one another.
        try (RocksChunkStore overworld = RocksChunkStore.open(
                DimensionKey.fromStorageDirectory(new File(tmp.toFile(), "region")),
                config());
             RocksChunkStore end = RocksChunkStore.open(
                DimensionKey.fromStorageDirectory(new File(tmp.toFile(), "DIM1/region")),
                config())) {
            assertEquals(0, overworld.dimensionOrdinal());
            assertEquals(2, end.dimensionOrdinal());
            ChunkPos pos = new ChunkPos(-32, 0);
            assertNotNull(overworld.read(pos), "overworld chunk missing");
            assertNotNull(end.read(pos), "end chunk missing");
        }
    }

    // -------------------------------------------------------------------- guards

    /** An existing database must be refused once for the world, not per directory. */
    @Test
    void refusesToOverwriteWithoutTheFlag(@TempDir Path tmp) throws Exception {
        buildWorld(tmp, 1);
        assertTrue(WorldImporter.importWorld(tmp.toFile(), config(), false, null, 4).clean());

        WorldImporter.Result second =
            WorldImporter.importWorld(tmp.toFile(), config(), false, null, 4);
        assertFalse(second.clean(), "a populated database must not be imported into");
        assertEquals(1, second.directories.size(),
            "one refusal for the world, since all dimensions share the database");
        assertTrue(second.directories.get(0).failures().get(0).contains("--overwrite"),
            "the refusal must name the flag that overrides it");

        assertTrue(WorldImporter.importWorld(tmp.toFile(), config(), true, null, 4).clean(),
            "--overwrite must permit a re-import");
    }

    /** Progress callbacks arrive from worker threads and must not be lost or racy. */
    @Test
    void progressIsReportedFromWorkers(@TempDir Path tmp) throws Exception {
        buildWorld(tmp, 2);
        List<String> labels = java.util.Collections.synchronizedList(new ArrayList<>());
        java.util.concurrent.atomic.AtomicInteger calls =
            new java.util.concurrent.atomic.AtomicInteger();

        WorldImporter.Result result = WorldImporter.importWorld(tmp.toFile(), config(),
            false, (label, done, total) -> {
                labels.add(label);
                calls.incrementAndGet();
            }, 8);

        assertTrue(result.clean(), "import failed: " + failures(result));
        assertTrue(calls.get() > 0, "progress was never reported");
        assertFalse(labels.isEmpty());
    }

    /** An empty world is not an error; it simply imports nothing. */
    @Test
    void emptyWorldImportsNothing(@TempDir Path tmp) throws Exception {
        WorldImporter.Result result =
            WorldImporter.importWorld(tmp.toFile(), config(), false, null, 4);
        assertEquals(0, result.totalChunks());
        assertTrue(result.clean());
        assertTrue(result.directories.isEmpty());
    }

    /**
     * A zero-length region file is normal, not corruption.
     *
     * <p>Vanilla creates them on demand. Under concurrency each worker keeps its own
     * report, so this also checks the merge preserved the count.
     */
    @Test
    void emptyRegionFilesAreCountedNotFatal(@TempDir Path tmp) throws Exception {
        File storage = new File(tmp.toFile(), "region");
        assertTrue(storage.mkdirs());
        writeRegion(new File(storage, "r.0.0.mca"), 0, 0, new int[] {0, 5});
        // Three empty files, so the merged report must show exactly three.
        for (int i = 1; i <= 3; i++) {
            assertTrue(new File(storage, "r." + i + ".0.mca").createNewFile());
        }

        WorldImporter.Result result =
            WorldImporter.importWorld(tmp.toFile(), config(), false, null, 8);
        assertTrue(result.clean(), "import failed: " + failures(result));
        assertEquals(2, result.totalChunks());
        assertEquals(3, result.directories.get(0).anomalies.emptyRegionFiles,
            "per-worker reports must merge without losing counts");
    }

    /** The default worker count must be sane on any machine. */
    @Test
    void defaultThreadCountIsPositive() {
        assertTrue(WorldImporter.defaultThreads() >= 1);
        assertTrue(WorldImporter.defaultThreads()
            <= Runtime.getRuntime().availableProcessors());
    }

    private static String failures(WorldImporter.Result result) {
        StringBuilder sb = new StringBuilder();
        for (WorldImporter.DirectoryResult d : result.directories) {
            for (String f : d.failures()) {
                sb.append('\n').append(d.label).append(": ").append(f);
            }
            if (d.chunksWritten() != d.chunksRead()) {
                sb.append('\n').append(d.label).append(": read ").append(d.chunksRead())
                    .append(" but wrote ").append(d.chunksWritten());
            }
        }
        return sb.length() == 0 ? "(no detail)" : sb.toString();
    }
}
