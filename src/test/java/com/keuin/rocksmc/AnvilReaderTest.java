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
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests Anvil region parsing.
 *
 * <p>Previously covered only indirectly, through importer fixtures that emitted
 * zlib and nothing else. The untested paths were the ones that matter most for an
 * import: gzip and uncompressed chunks, oversized chunks in external {@code .mcc}
 * files, and every malformed shape that has to be counted rather than thrown. A
 * parser that silently skips an entry loses terrain, and the class javadoc calls
 * out {@code .mcc} specifically as the bug that "would silently drop terrain".
 */
class AnvilReaderTest {

    private static final int SECTOR = 4096;

    private static final int SCHEME_GZIP = 1;
    private static final int SCHEME_ZLIB = 2;
    private static final int SCHEME_NONE = 3;

    // ------------------------------------------------------------------ fixtures

    private static NbtCompound nbt(String marker) {
        NbtCompound tag = new NbtCompound();
        tag.putString("marker", marker);
        tag.putInt("DataVersion", 2586);
        return tag;
    }

    private static byte[] serialise(NbtCompound tag) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (DataOutputStream data = new DataOutputStream(out)) {
            NbtIo.write(tag, data);
        }
        return out.toByteArray();
    }

    private static byte[] compress(byte[] raw, int scheme) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (scheme == SCHEME_GZIP) {
            try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
                gzip.write(raw);
            }
        } else if (scheme == SCHEME_ZLIB) {
            try (DeflaterOutputStream deflate = new DeflaterOutputStream(out)) {
                deflate.write(raw);
            }
        } else {
            return raw;
        }
        return out.toByteArray();
    }

    /** One chunk slot to write into a region file. */
    private static final class Slot {
        final int index;
        final int scheme;
        final boolean external;
        final String marker;

        Slot(int index, int scheme, boolean external, String marker) {
            this.index = index;
            this.scheme = scheme;
            this.external = external;
            this.marker = marker;
        }
    }

    /**
     * Writes a genuine Anvil region file.
     *
     * <p>Real bytes rather than a mock, because the point is to exercise the sector
     * arithmetic and framing that a mock would paper over.
     */
    private static void writeRegion(File file, int regionX, int regionZ, List<Slot> slots)
            throws IOException {
        byte[] header = new byte[SECTOR * 2];
        List<byte[]> blocks = new ArrayList<>();
        int nextSector = 2;

        for (Slot slot : slots) {
            byte[] block;
            if (slot.external) {
                // An oversized chunk leaves a 5-byte stub with the high bit set and
                // puts the payload in c.X.Z.mcc beside the region file.
                int chunkX = regionX * 32 + (slot.index % 32);
                int chunkZ = regionZ * 32 + (slot.index / 32);
                byte[] payload = compress(serialise(nbt(slot.marker)), slot.scheme);
                Files.write(new File(file.getParentFile(),
                    "c." + chunkX + "." + chunkZ + ".mcc").toPath(), payload);
                block = new byte[SECTOR];
                writeInt(block, 0, 1);
                block[4] = (byte) (slot.scheme | 0x80);
            } else {
                byte[] payload = compress(serialise(nbt(slot.marker)), slot.scheme);
                int framed = payload.length + 1;
                int sectors = (framed + 4 + SECTOR - 1) / SECTOR;
                block = new byte[sectors * SECTOR];
                writeInt(block, 0, framed);
                block[4] = (byte) slot.scheme;
                System.arraycopy(payload, 0, block, 5, payload.length);
            }
            blocks.add(block);
            writeInt(header, slot.index * 4, (nextSector << 8) | (block.length / SECTOR));
            nextSector += block.length / SECTOR;
        }

        try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
            raf.write(header);
            for (byte[] block : blocks) {
                raf.write(block);
            }
        }
    }

    private static void writeInt(byte[] target, int offset, int value) {
        target[offset] = (byte) (value >>> 24);
        target[offset + 1] = (byte) (value >>> 16);
        target[offset + 2] = (byte) (value >>> 8);
        target[offset + 3] = (byte) value;
    }

    private static List<AnvilReader.Entry> readAll(File region, AnvilReader.Report report)
            throws IOException {
        List<AnvilReader.Entry> out = new ArrayList<>();
        AnvilReader.stream(region, report, out::add);
        return out;
    }

    // ---------------------------------------------------------------- schemes

    /**
     * All three compression schemes must decode.
     *
     * <p>Only zlib was previously exercised. Vanilla writes zlib by default but reads
     * all three, and a world touched by an external tool can legitimately contain gzip
     * or uncompressed chunks -- which the importer would otherwise count as
     * decompression failures and silently drop.
     */
    @Test
    void decodesAllThreeCompressionSchemes(@TempDir Path tmp) throws Exception {
        File region = new File(tmp.toFile(), "r.0.0.mca");
        List<Slot> slots = new ArrayList<>();
        slots.add(new Slot(0, SCHEME_GZIP, false, "gzip"));
        slots.add(new Slot(1, SCHEME_ZLIB, false, "zlib"));
        slots.add(new Slot(2, SCHEME_NONE, false, "uncompressed"));
        writeRegion(region, 0, 0, slots);

        AnvilReader.Report report = new AnvilReader.Report();
        List<AnvilReader.Entry> entries = readAll(region, report);

        assertEquals(3, entries.size(), "every scheme must decode");
        assertEquals(0, report.total(), "no anomalies expected: " + report);
        assertEquals("gzip", markerAt(entries, 0, 0));
        assertEquals("zlib", markerAt(entries, 1, 0));
        assertEquals("uncompressed", markerAt(entries, 2, 0));
    }

    /** An unrecognised scheme is counted, not thrown, and the chunk is skipped. */
    @Test
    void unknownSchemeIsCountedAndSkipped(@TempDir Path tmp) throws Exception {
        File region = new File(tmp.toFile(), "r.0.0.mca");
        writeRegion(region, 0, 0, listOf(new Slot(0, SCHEME_ZLIB, false, "good")));
        // Corrupt the scheme byte of the first payload in place.
        try (RandomAccessFile raf = new RandomAccessFile(region, "rw")) {
            raf.seek(2L * SECTOR + 4);
            raf.write(9);
        }

        AnvilReader.Report report = new AnvilReader.Report();
        assertTrue(readAll(region, report).isEmpty());
        assertEquals(1, report.unknownSchemes);
    }

    /** A payload that is not valid compressed data is counted as a failure. */
    @Test
    void corruptPayloadIsCountedNotThrown(@TempDir Path tmp) throws Exception {
        File region = new File(tmp.toFile(), "r.0.0.mca");
        writeRegion(region, 0, 0, listOf(new Slot(0, SCHEME_ZLIB, false, "good")));
        try (RandomAccessFile raf = new RandomAccessFile(region, "rw")) {
            raf.seek(2L * SECTOR + 5);
            raf.write(new byte[] {0, 1, 2, 3, 4, 5, 6, 7});
        }

        AnvilReader.Report report = new AnvilReader.Report();
        assertTrue(readAll(region, report).isEmpty());
        assertEquals(1, report.decompressFailures, report.toString());
    }

    // ------------------------------------------------------- external chunks

    /**
     * Oversized chunks live in a sibling {@code .mcc} file and must be read.
     *
     * <p>This is the case the class javadoc singles out: earlier tooling skipped these,
     * which is tolerable for a sampling harness but drops the largest chunks in the
     * world during an import.
     */
    @Test
    void readsExternalMccChunks(@TempDir Path tmp) throws Exception {
        File region = new File(tmp.toFile(), "r.0.0.mca");
        List<Slot> slots = new ArrayList<>();
        slots.add(new Slot(0, SCHEME_ZLIB, false, "inline"));
        slots.add(new Slot(1, SCHEME_ZLIB, true, "oversized"));
        writeRegion(region, 0, 0, slots);

        AnvilReader.Report report = new AnvilReader.Report();
        List<AnvilReader.Entry> entries = readAll(region, report);

        assertEquals(2, entries.size(), "the external chunk must be read: " + report);
        assertEquals(0, report.total(), report.toString());

        AnvilReader.Entry external = entryAt(entries, 1, 0);
        assertNotNull(external);
        assertTrue(external.external(), "the entry must be flagged as external");
        assertEquals("oversized", external.nbt().getString("marker"));

        assertFalse(entryAt(entries, 0, 0).external(), "inline chunk must not be flagged");
    }

    /** A stub whose .mcc file is missing is counted rather than throwing. */
    @Test
    void missingExternalFileIsCounted(@TempDir Path tmp) throws Exception {
        File region = new File(tmp.toFile(), "r.0.0.mca");
        writeRegion(region, 0, 0, listOf(new Slot(0, SCHEME_ZLIB, true, "gone")));
        assertTrue(new File(tmp.toFile(), "c.0.0.mcc").delete());

        AnvilReader.Report report = new AnvilReader.Report();
        assertTrue(readAll(region, report).isEmpty());
        assertEquals(1, report.missingExternalFiles);
    }

    // ------------------------------------------------------------ malformed

    /** A zero-length region file is normal: vanilla creates them on demand. */
    @Test
    void emptyRegionFileIsNotCorruption(@TempDir Path tmp) throws Exception {
        File region = new File(tmp.toFile(), "r.0.0.mca");
        assertTrue(region.createNewFile());

        AnvilReader.Report report = new AnvilReader.Report();
        assertTrue(readAll(region, report).isEmpty());
        assertEquals(1, report.emptyRegionFiles);
        assertEquals(1, report.total(), "an empty file is one specific anomaly");
    }

    /** A file too short to hold the two header sectors is truncated, not empty. */
    @Test
    void truncatedHeaderIsCounted(@TempDir Path tmp) throws Exception {
        File region = new File(tmp.toFile(), "r.0.0.mca");
        Files.write(region.toPath(), new byte[SECTOR]);

        AnvilReader.Report report = new AnvilReader.Report();
        assertTrue(readAll(region, report).isEmpty());
        assertEquals(1, report.truncatedHeaders);
    }

    /** A sector pointer past the end of the file must be rejected, not read. */
    @Test
    void sectorOffsetBeyondEndOfFileIsCounted(@TempDir Path tmp) throws Exception {
        File region = new File(tmp.toFile(), "r.0.0.mca");
        byte[] header = new byte[SECTOR * 2];
        // Offset 9999 sectors into a 2-sector file.
        writeInt(header, 0, (9999 << 8) | 1);
        Files.write(region.toPath(), header);

        AnvilReader.Report report = new AnvilReader.Report();
        assertTrue(readAll(region, report).isEmpty());
        assertEquals(1, report.invalidSectorEntries);
    }

    /** A pointer into the header itself is invalid: data starts at sector 2. */
    @Test
    void sectorOffsetInsideHeaderIsCounted(@TempDir Path tmp) throws Exception {
        File region = new File(tmp.toFile(), "r.0.0.mca");
        byte[] header = new byte[SECTOR * 3];
        writeInt(header, 0, (1 << 8) | 1);
        Files.write(region.toPath(), header);

        AnvilReader.Report report = new AnvilReader.Report();
        assertTrue(readAll(region, report).isEmpty());
        assertEquals(1, report.invalidSectorEntries);
    }

    /** Empty header slots are simply absent chunks, not anomalies. */
    @Test
    void emptyHeaderSlotsAreNotAnomalies(@TempDir Path tmp) throws Exception {
        File region = new File(tmp.toFile(), "r.0.0.mca");
        writeRegion(region, 0, 0, listOf(new Slot(5, SCHEME_ZLIB, false, "only")));

        AnvilReader.Report report = new AnvilReader.Report();
        assertEquals(1, readAll(region, report).size());
        assertEquals(0, report.total(), "1023 empty slots are normal: " + report);
    }

    // ------------------------------------------------------------ coordinates

    /**
     * Chunk coordinates must derive from the region name and the slot index.
     *
     * <p>Negative region coordinates are the normal case in Minecraft, and an
     * off-by-one here would store terrain at the wrong position -- which round-trips
     * consistently and so would pass a naive fidelity check.
     */
    @Test
    void chunkCoordinatesAreDerivedCorrectly(@TempDir Path tmp) throws Exception {
        File region = new File(tmp.toFile(), "r.-2.-3.mca");
        List<Slot> slots = new ArrayList<>();
        slots.add(new Slot(0, SCHEME_ZLIB, false, "first"));
        slots.add(new Slot(1, SCHEME_ZLIB, false, "second"));
        slots.add(new Slot(32, SCHEME_ZLIB, false, "nextRow"));
        slots.add(new Slot(1023, SCHEME_ZLIB, false, "last"));
        writeRegion(region, -2, -3, slots);

        AnvilReader.Report report = new AnvilReader.Report();
        List<AnvilReader.Entry> entries = readAll(region, report);
        assertEquals(4, entries.size());

        // region -2 => chunk x base -64; region -3 => chunk z base -96
        assertEquals("first", markerAt(entries, -64, -96));
        assertEquals("second", markerAt(entries, -63, -96));
        assertEquals("nextRow", markerAt(entries, -64, -95));
        assertEquals("last", markerAt(entries, -33, -65));
    }

    // ------------------------------------------------------------ report merge

    /** Merging must sum every field, since parallel imports rely on it. */
    @Test
    void reportMergeSumsEveryField() {
        AnvilReader.Report a = new AnvilReader.Report();
        a.emptyRegionFiles = 1;
        a.invalidSectorEntries = 2;
        a.truncatedHeaders = 3;
        a.decompressFailures = 4;
        a.missingExternalFiles = 5;
        a.unknownSchemes = 6;

        AnvilReader.Report b = new AnvilReader.Report();
        b.emptyRegionFiles = 10;
        b.invalidSectorEntries = 20;
        b.truncatedHeaders = 30;
        b.decompressFailures = 40;
        b.missingExternalFiles = 50;
        b.unknownSchemes = 60;

        a.merge(b);
        assertEquals(11, a.emptyRegionFiles);
        assertEquals(22, a.invalidSectorEntries);
        assertEquals(33, a.truncatedHeaders);
        assertEquals(44, a.decompressFailures);
        assertEquals(55, a.missingExternalFiles);
        assertEquals(66, a.unknownSchemes);
        assertEquals(21 + 210, a.total());
    }

    /**
     * Concurrent merges must not lose counts.
     *
     * <p>The parallel importer merges from many worker threads as they finish, so a
     * non-atomic merge would silently undercount anomalies.
     */
    @Test
    void concurrentMergesDoNotLoseCounts() throws Exception {
        AnvilReader.Report target = new AnvilReader.Report();
        int threads = 8;
        int perThread = 500;
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> workers = new ArrayList<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        for (int t = 0; t < threads; t++) {
            Thread worker = new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        AnvilReader.Report one = new AnvilReader.Report();
                        one.decompressFailures = 1;
                        target.merge(one);
                    }
                } catch (Throwable e) {
                    failure.compareAndSet(null, e);
                }
            });
            workers.add(worker);
            worker.start();
        }
        start.countDown();
        for (Thread worker : workers) {
            worker.join(TimeUnit.SECONDS.toMillis(30));
        }

        assertNull(failure.get());
        assertEquals(threads * perThread, target.decompressFailures,
            "merges raced and lost counts");
    }

    @Test
    void reportToStringIsReadable() {
        AnvilReader.Report report = new AnvilReader.Report();
        assertEquals("no anomalies", report.toString());
        report.emptyRegionFiles = 2;
        assertTrue(report.toString().contains("emptyRegionFiles=2"), report.toString());
    }

    // --------------------------------------------------------------- listing

    /** Region listing must be stable, so parallel and sequential agree on order. */
    @Test
    void regionFilesAreListedInStableOrder(@TempDir Path tmp) throws Exception {
        for (String name : new String[] {"r.1.0.mca", "r.-1.0.mca", "r.0.0.mca"}) {
            assertTrue(new File(tmp.toFile(), name).createNewFile());
        }
        assertTrue(new File(tmp.toFile(), "notes.txt").createNewFile());

        List<File> first = AnvilReader.regionFiles(tmp.toFile());
        List<File> second = AnvilReader.regionFiles(tmp.toFile());
        assertEquals(3, first.size(), "only .mca files: " + first);
        assertEquals(first, second, "listing order must be stable across calls");
    }

    @Test
    void regionFilesOfMissingDirectoryIsEmpty(@TempDir Path tmp) {
        assertTrue(AnvilReader.regionFiles(new File(tmp.toFile(), "absent")).isEmpty());
    }

    // ----------------------------------------------------------------- helpers

    private static List<Slot> listOf(Slot slot) {
        List<Slot> slots = new ArrayList<>();
        slots.add(slot);
        return slots;
    }

    private static AnvilReader.Entry entryAt(List<AnvilReader.Entry> entries, int x, int z) {
        for (AnvilReader.Entry entry : entries) {
            if (entry.pos().equals(new ChunkPos(x, z))) {
                return entry;
            }
        }
        return null;
    }

    private static String markerAt(List<AnvilReader.Entry> entries, int x, int z) {
        AnvilReader.Entry entry = entryAt(entries, x, z);
        assertNotNull(entry, "no chunk at " + x + ", " + z + " in " + entries.size()
            + " entries");
        return entry.nbt().getString("marker");
    }
}
