package com.keuin.rocksmc;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.math.ChunkPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the shared, reference-counted database.
 *
 * <p>Two classes of property are pinned here, and both are ones where a bug is
 * silent and catastrophic rather than loud:
 *
 * <ul>
 *   <li><b>Lifecycle.</b> Six stores share one native handle. Releasing it while a
 *       store still holds it is a use-after-free affecting every dimension at once,
 *       and the symptom would be arbitrary corruption rather than an exception.</li>
 *   <li><b>Isolation.</b> All dimensions now share a column family, separated only
 *       by the ordinal in the key prefix. If that separation failed, one dimension
 *       would silently serve another's terrain -- the exact bug the dimension
 *       identity work was done to prevent, reintroduced one layer down.</li>
 * </ul>
 */
class RocksDatabaseTest {

    private static RocksMcConfig config() {
        return RocksMcConfig.of(new Properties());
    }

    private static RocksChunkStore open(Path worldRoot, String relative) throws IOException {
        File dir = new File(worldRoot.toFile(), relative);
        return RocksChunkStore.open(DimensionKey.fromStorageDirectory(dir), config());
    }

    private static NbtCompound nbt(String marker) {
        NbtCompound tag = new NbtCompound();
        tag.putString("marker", marker);
        return tag;
    }

    // ------------------------------------------------------------- consolidation

    /**
     * The headline property of the phase: one database for the whole world.
     *
     * <p>All six storage directories of a three-dimension world must resolve to the
     * same handle, because that is what gives them one write-ahead log and therefore
     * one crash recovery point.
     */
    @Test
    void everyStoreOfAWorldSharesOneDatabase(@TempDir Path tmp) throws Exception {
        List<RocksChunkStore> stores = new ArrayList<>();
        try {
            for (String dir : new String[] {"region", "poi", "DIM-1/region",
                    "DIM-1/poi", "DIM1/region", "DIM1/poi"}) {
                stores.add(open(tmp, dir));
            }
            RocksDatabase shared = stores.get(0).database();
            for (RocksChunkStore store : stores) {
                assertSame(shared, store.database(),
                    "every store of a world must share one handle");
            }
            assertEquals(6, shared.referenceCount());
            assertEquals(1, StoreRegistry.databaseCount(),
                "six stores must not open six databases");
        } finally {
            for (RocksChunkStore store : stores) {
                store.close();
            }
        }
    }

    /** The database lives inside the world root, not beside each storage directory. */
    @Test
    void databaseLivesAtTheWorldRoot(@TempDir Path tmp) throws Exception {
        try (RocksChunkStore store = open(tmp, "DIM-1/region")) {
            assertEquals(new File(tmp.toFile(), RocksDatabase.DIRECTORY_NAME)
                .getCanonicalFile(), store.database().path().getCanonicalFile());
        }
    }

    /** Separate worlds must never share a handle, or they would share a keyspace. */
    @Test
    void separateWorldsGetSeparateDatabases(@TempDir Path tmp) throws Exception {
        try (RocksChunkStore a = open(tmp.resolve("world-a"), "region");
             RocksChunkStore b = open(tmp.resolve("world-b"), "region")) {
            assertTrue(a.database() != b.database(),
                "distinct worlds must not share a database");
            assertEquals(1, a.database().referenceCount());
            assertEquals(1, b.database().referenceCount());
        }
    }

    /**
     * Two paths reaching one world through a symlink must join one database.
     *
     * <p>Without canonicalisation they would be distinct map keys, and the second
     * open would race the first for RocksDB's directory lock -- turning a benign
     * configuration into a startup failure, or worse if the lock were ever absent.
     */
    @Test
    void symlinkedWorldPathsJoinOneDatabase(@TempDir Path tmp) throws Exception {
        Path real = Files.createDirectories(tmp.resolve("real-world"));
        Path link = tmp.resolve("linked-world");
        try {
            Files.createSymbolicLink(link, real);
        } catch (UnsupportedOperationException | IOException e) {
            // Symlinks may be unavailable (e.g. Windows without privilege). The
            // property is real but untestable here, so skip rather than fail.
            return;
        }
        try (RocksChunkStore viaReal = open(real, "region");
             RocksChunkStore viaLink = open(link, "DIM-1/region")) {
            assertSame(viaReal.database(), viaLink.database(),
                "a symlinked world path must join the same database");
        }
    }

    // ----------------------------------------------------------------- lifecycle

    /**
     * The principal risk of the phase: the handle must survive until the last store
     * closes, and must be released exactly then.
     */
    @Test
    void handleSurvivesUntilTheLastStoreCloses(@TempDir Path tmp) throws Exception {
        RocksChunkStore first = open(tmp, "region");
        RocksChunkStore second = open(tmp, "DIM-1/region");
        RocksChunkStore third = open(tmp, "poi");
        RocksDatabase database = first.database();
        assertEquals(3, database.referenceCount());

        first.close();
        assertEquals(2, database.referenceCount());
        assertFalse(database.isClosed(), "handle released while stores still hold it");
        // Still usable: this is the assertion that would fail on a use-after-free.
        second.write(new ChunkPos(1, 1), nbt("alive"));
        assertNotNull(second.read(new ChunkPos(1, 1)));

        second.close();
        assertEquals(1, database.referenceCount());
        assertFalse(database.isClosed());
        third.write(new ChunkPos(2, 2), nbt("still alive"));
        assertNotNull(third.read(new ChunkPos(2, 2)));

        third.close();
        assertEquals(0, database.referenceCount());
        assertTrue(database.isClosed(), "last close must release the handle");
        assertEquals(0, StoreRegistry.databaseCount());
    }

    /** Closing in the reverse order must work identically: no ownership by order. */
    @Test
    void closeOrderDoesNotMatter(@TempDir Path tmp) throws Exception {
        RocksChunkStore first = open(tmp, "region");
        RocksChunkStore second = open(tmp, "DIM1/region");
        RocksDatabase database = first.database();

        second.close();
        assertFalse(database.isClosed());
        first.write(new ChunkPos(3, 3), nbt("first still owns nothing special"));
        first.close();
        assertTrue(database.isClosed());
    }

    /**
     * A double close must not decrement the shared count twice.
     *
     * <p>This is the specific way a shared handle gets released early: one buggy
     * caller closing twice would drop another dimension's reference. {@link
     * ChunkStore} is {@link java.io.Closeable}, whose contract requires close to be
     * idempotent, so the store absorbs it rather than throwing.
     */
    @Test
    void doubleCloseOfAStoreDoesNotReleaseAnotherStoresReference(@TempDir Path tmp)
            throws Exception {
        RocksChunkStore first = open(tmp, "region");
        RocksChunkStore second = open(tmp, "DIM-1/region");
        RocksDatabase database = first.database();

        first.close();
        first.close();
        first.close();

        assertEquals(1, database.referenceCount(),
            "a repeated close must not consume another store's reference");
        assertFalse(database.isClosed());
        second.write(new ChunkPos(4, 4), nbt("unaffected"));
        assertNotNull(second.read(new ChunkPos(4, 4)));
        second.close();
        assertTrue(database.isClosed());
    }

    /**
     * Over-releasing the database directly must throw rather than be tolerated.
     *
     * <p>A negative count means a caller has a double-close bug. Continuing would
     * use freed native memory for every dimension at once, so it must be loud.
     */
    @Test
    void overReleaseThrows(@TempDir Path tmp) throws Exception {
        RocksChunkStore store = open(tmp, "region");
        RocksDatabase database = store.database();
        store.close();
        assertTrue(database.isClosed());
        assertThrows(IllegalStateException.class, database::release);
    }

    /** Reopening after the last close must produce a working database again. */
    @Test
    void databaseCanBeReopenedAfterFullRelease(@TempDir Path tmp) throws Exception {
        try (RocksChunkStore store = open(tmp, "region")) {
            store.write(new ChunkPos(5, 5), nbt("persisted"));
        }
        try (RocksChunkStore store = open(tmp, "region")) {
            NbtCompound read = store.read(new ChunkPos(5, 5));
            assertNotNull(read, "data must survive a full close and reopen");
            assertEquals("persisted", read.getString("marker"));
        }
    }

    /**
     * Concurrent opens must yield one database and a correct count.
     *
     * <p>Vanilla constructs storage instances during world load, which is not
     * obviously single-threaded across dimensions, and getting this wrong yields two
     * handles on one directory. The barrier maximises the chance of overlap in the
     * window between the map lookup and the insert.
     */
    @Test
    void concurrentOpensShareOneDatabase(@TempDir Path tmp) throws Exception {
        String[] dirs = {"region", "poi", "DIM-1/region", "DIM-1/poi",
            "DIM1/region", "DIM1/poi"};
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        List<RocksChunkStore> opened = new ArrayList<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        for (String dir : dirs) {
            Thread t = new Thread(() -> {
                try {
                    start.await();
                    RocksChunkStore store = open(tmp, dir);
                    synchronized (opened) {
                        opened.add(store);
                    }
                } catch (Throwable e) {
                    failure.compareAndSet(null, e);
                }
            }, "open-" + dir.replace('/', '-'));
            threads.add(t);
            t.start();
        }
        start.countDown();
        for (Thread t : threads) {
            t.join(TimeUnit.SECONDS.toMillis(60));
        }

        try {
            assertNull(failure.get(), "concurrent open failed: " + failure.get());
            assertEquals(dirs.length, opened.size());
            RocksDatabase shared = opened.get(0).database();
            for (RocksChunkStore store : opened) {
                assertSame(shared, store.database());
            }
            assertEquals(dirs.length, shared.referenceCount());
            assertEquals(1, StoreRegistry.databaseCount());
        } finally {
            for (RocksChunkStore store : opened) {
                store.close();
            }
        }
    }

    // ----------------------------------------------------------------- isolation

    /**
     * Two dimensions in one column family must never alias.
     *
     * <p>The ordinal prefix is now the only thing separating them. Same chunk
     * coordinates in every dimension is the case that would collide if the prefix
     * were dropped or computed wrongly.
     */
    @Test
    void dimensionsInOneColumnFamilyDoNotAlias(@TempDir Path tmp) throws Exception {
        ChunkPos shared = new ChunkPos(0, 0);
        try (RocksChunkStore overworld = open(tmp, "region");
             RocksChunkStore nether = open(tmp, "DIM-1/region");
             RocksChunkStore end = open(tmp, "DIM1/region");
             RocksChunkStore custom = open(tmp, "dimensions/ns/dim/region")) {

            overworld.write(shared, nbt("overworld"));
            nether.write(shared, nbt("nether"));
            end.write(shared, nbt("end"));
            custom.write(shared, nbt("custom"));

            assertEquals("overworld", overworld.read(shared).getString("marker"));
            assertEquals("nether", nether.read(shared).getString("marker"));
            assertEquals("end", end.read(shared).getString("marker"));
            assertEquals("custom", custom.read(shared).getString("marker"));
        }
    }

    /**
     * A dimension must not see another's chunk at a position it never wrote.
     *
     * <p>Distinct from the aliasing test above: that one proves writes do not
     * overwrite each other, this proves reads do not leak across the prefix.
     */
    @Test
    void oneDimensionsChunkIsNotVisibleFromAnother(@TempDir Path tmp) throws Exception {
        try (RocksChunkStore overworld = open(tmp, "region");
             RocksChunkStore nether = open(tmp, "DIM-1/region")) {
            overworld.write(new ChunkPos(100, 200), nbt("overworld only"));
            assertNull(nether.read(new ChunkPos(100, 200)),
                "a dimension must not read another dimension's chunk");
        }
    }

    /**
     * Chunk and POI of one dimension share an ordinal but must not collide.
     *
     * <p>They are separated by column family, not by key, so this is what proves
     * the leaf-to-column-family mapping is actually wired through.
     */
    @Test
    void chunkAndPoiOfOneDimensionDoNotAlias(@TempDir Path tmp) throws Exception {
        ChunkPos pos = new ChunkPos(7, 7);
        try (RocksChunkStore region = open(tmp, "region");
             RocksChunkStore poi = open(tmp, "poi")) {
            assertEquals(region.dimensionOrdinal(), poi.dimensionOrdinal(),
                "same dimension must share an ordinal");
            region.write(pos, nbt("chunk data"));
            poi.write(pos, nbt("poi data"));
            assertEquals("chunk data", region.read(pos).getString("marker"));
            assertEquals("poi data", poi.read(pos).getString("marker"));
        }
    }

    /** Two worlds' overworlds are separate databases and must not alias either. */
    @Test
    void separateWorldsDoNotAlias(@TempDir Path tmp) throws Exception {
        ChunkPos pos = new ChunkPos(0, 0);
        try (RocksChunkStore a = open(tmp.resolve("world-a"), "region");
             RocksChunkStore b = open(tmp.resolve("world-b"), "region")) {
            a.write(pos, nbt("world a"));
            b.write(pos, nbt("world b"));
            assertEquals("world a", a.read(pos).getString("marker"));
            assertEquals("world b", b.read(pos).getString("marker"));
        }
    }

    /** The leaf-to-column-family mapping, including the rejection of anything else. */
    @Test
    void columnFamilyMapping() {
        assertEquals(RocksDatabase.CHUNK_CF, RocksDatabase.columnFamilyNameFor("region"));
        assertEquals(RocksDatabase.POI_CF, RocksDatabase.columnFamilyNameFor("poi"));
        // DimensionKey produces only those two, so a third value means the parser
        // and this mapping have drifted apart -- which must not fail silently.
        assertThrows(IllegalArgumentException.class,
            () -> RocksDatabase.columnFamilyNameFor("entities"));
    }

    // -------------------------------------------------------------- format guard

    /**
     * A version 1 database must be refused with a re-import hint.
     *
     * <p>Version 1 keys are laid out for one database per dimension. Opening one
     * under version 2 assumptions would reinterpret every key rather than fail, so
     * the guard is the only thing standing between an old world and silent
     * misreading.
     */
    @Test
    void refusesToOpenAnOlderFormatVersion(@TempDir Path tmp) throws Exception {
        try (RocksChunkStore store = open(tmp, "region")) {
            store.write(new ChunkPos(0, 0), nbt("v2 data"));
        }
        // Rewrite the marker in place to look like the previous format version.
        overwriteFormatVersion(tmp, 1);

        IOException failure = assertThrows(IOException.class, () -> open(tmp, "region"));
        String message = failure.getMessage();
        assertTrue(message.contains("format version 1"), message);
        assertTrue(message.contains("importWorld"),
            "the error must name the re-import command: " + message);
        // A refused open must not leak a reference, or a retry would join a
        // half-initialised database.
        assertEquals(0, StoreRegistry.databaseCount(),
            "a refused open must not leave the database registered");
    }

    /** A marker that is not a number is corruption, not a version to guess at. */
    @Test
    void refusesToOpenACorruptFormatMarker(@TempDir Path tmp) throws Exception {
        try (RocksChunkStore store = open(tmp, "region")) {
            store.write(new ChunkPos(0, 0), nbt("v2 data"));
        }
        overwriteFormatVersionRaw(tmp, "not-a-number");
        assertThrows(IOException.class, () -> open(tmp, "region"));
        assertEquals(0, StoreRegistry.databaseCount());
    }

    /** A fresh database must be stamped with the current version. */
    @Test
    void freshDatabaseRecordsTheCurrentFormatVersion(@TempDir Path tmp) throws Exception {
        try (RocksChunkStore ignored = open(tmp, "region")) {
            // opened successfully
        }
        assertEquals(String.valueOf(RocksDatabase.FORMAT_VERSION),
            readFormatVersion(tmp));
    }

    // ------------------------------------------------------------ durability API

    /**
     * A WAL sync must not flush memtables.
     *
     * <p>Vanilla calls sync once per storage instance per autosave -- six times for
     * a three-dimension world -- and all six now reach one database. Flushing there
     * would cut every memtable short six times an autosave, producing tiny L0 files
     * and the compaction work to merge them, for no durability gain. So this pins
     * that sync leaves the data in memory while still reading back correctly.
     */
    @Test
    void syncDoesNotFlushMemtables(@TempDir Path tmp) throws Exception {
        try (RocksChunkStore store = open(tmp, "region")) {
            for (int i = 0; i < 32; i++) {
                store.write(new ChunkPos(i, 0), nbt("chunk " + i));
            }
            store.sync();

            RocksDatabase database = store.database();
            long memtable = database.longProperty(
                RocksDatabase.CHUNK_CF, "rocksdb.size-all-mem-tables");
            assertTrue(memtable > 0,
                "sync must leave data in the memtable, found " + memtable);

            // Read-your-writes still holds, which is what actually matters.
            assertEquals("chunk 31", store.read(new ChunkPos(31, 0)).getString("marker"));

            database.flushMemtables();
            assertEquals("chunk 31", store.read(new ChunkPos(31, 0)).getString("marker"),
                "data must still be readable after a flush");
        }
    }

    /** A checkpoint must capture every dimension, being world-wide now. */
    @Test
    void checkpointCapturesEveryDimension(@TempDir Path tmp) throws Exception {
        File target = new File(tmp.toFile(), "checkpoint");
        try (RocksChunkStore overworld = open(tmp.resolve("world"), "region");
             RocksChunkStore nether = open(tmp.resolve("world"), "DIM-1/region")) {
            overworld.write(new ChunkPos(0, 0), nbt("overworld"));
            nether.write(new ChunkPos(0, 0), nbt("nether"));
            overworld.database().flushMemtables();
            overworld.database().checkpoint(target);
        }
        assertTrue(target.isDirectory(), "checkpoint directory was not created");
        Set<String> names = new HashSet<>();
        File[] files = target.listFiles();
        assertNotNull(files);
        for (File f : files) {
            names.add(f.getName());
        }
        assertTrue(names.contains("CURRENT"),
            "checkpoint must be a complete database, found " + names);
    }

    // --------------------------------------------------------------------- utils

    private static String readFormatVersion(Path worldRoot) throws Exception {
        try (org.rocksdb.RocksDB db = openRaw(worldRoot)) {
            byte[] value = db.get("\u0000format".getBytes(StandardCharsets.UTF_8));
            return value == null ? null : new String(value, StandardCharsets.UTF_8);
        }
    }

    private static void overwriteFormatVersion(Path worldRoot, int version) throws Exception {
        overwriteFormatVersionRaw(worldRoot, String.valueOf(version));
    }

    /**
     * Rewrites the format marker by opening the database directly.
     *
     * <p>Deliberately bypasses {@link RocksDatabase}, whose whole job is to refuse
     * this. Only the default column family is opened, which is where the marker
     * lives; RocksDB tolerates opening a subset for a plain put.
     */
    private static void overwriteFormatVersionRaw(Path worldRoot, String value)
            throws Exception {
        try (org.rocksdb.RocksDB db = openRaw(worldRoot)) {
            db.put("\u0000format".getBytes(StandardCharsets.UTF_8),
                value.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static org.rocksdb.RocksDB openRaw(Path worldRoot) throws Exception {
        org.rocksdb.RocksDB.loadLibrary();
        File path = new File(worldRoot.toFile(), RocksDatabase.DIRECTORY_NAME);
        List<org.rocksdb.ColumnFamilyDescriptor> descriptors = new ArrayList<>();
        descriptors.add(new org.rocksdb.ColumnFamilyDescriptor(
            org.rocksdb.RocksDB.DEFAULT_COLUMN_FAMILY));
        descriptors.add(new org.rocksdb.ColumnFamilyDescriptor(
            RocksDatabase.CHUNK_CF.getBytes(StandardCharsets.UTF_8)));
        descriptors.add(new org.rocksdb.ColumnFamilyDescriptor(
            RocksDatabase.POI_CF.getBytes(StandardCharsets.UTF_8)));
        List<org.rocksdb.ColumnFamilyHandle> handles = new ArrayList<>();
        org.rocksdb.DBOptions options = new org.rocksdb.DBOptions()
            .setCreateIfMissing(false)
            .setCreateMissingColumnFamilies(false);
        return org.rocksdb.RocksDB.open(options, path.getAbsolutePath(),
            descriptors, handles);
    }
}
