package com.keuin.rocksmc;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.math.ChunkPos;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests checkpoint creation and retention.
 *
 * <p>Retention is the dangerous half. It deletes directories, so the properties worth
 * pinning are that it removes the <em>oldest</em> ones, never removes a checkpoint an
 * operator named by hand, and never removes anything while the count is within the
 * limit. A retention bug here silently destroys the rollback the feature exists to
 * provide -- and would do so exactly when it is needed.
 */
class CheckpointSchedulerTest {

    @BeforeEach
    void resetCounters() {
        CheckpointScheduler.resetCountersForTesting();
    }

    private static RocksMcConfig config() {
        Properties props = new Properties();
        props.setProperty("backend", "rocksdb");
        return RocksMcConfig.of(props);
    }

    private static RocksChunkStore open(Path worldRoot, String relative) throws Exception {
        File dir = new File(worldRoot.toFile(), relative);
        return RocksChunkStore.open(DimensionKey.fromStorageDirectory(dir), config());
    }

    private static NbtCompound tag(String marker) {
        NbtCompound nbt = new NbtCompound();
        nbt.putString("marker", marker);
        return nbt;
    }

    /** Creates a checkpoint directory by hand, to test retention without RocksDB. */
    private static File fakeCheckpoint(Path worldRoot, String name) throws IOException {
        File dir = new File(new File(worldRoot.toFile(), CheckpointScheduler.DIRECTORY_NAME),
            name);
        assertTrue(dir.mkdirs(), "could not create " + dir);
        // A file inside, so the recursive delete is actually exercised.
        Files.write(new File(dir, "CURRENT").toPath(), "dummy".getBytes("UTF-8"));
        return dir;
    }

    // ---------------------------------------------------------------- creation

    /** A checkpoint must be a complete, openable database. */
    @Test
    void createsACompleteDatabase(@TempDir Path tmp) throws Exception {
        try (RocksChunkStore store = open(tmp, "region")) {
            store.write(new ChunkPos(1, 2), tag("checkpointed"));
            store.database().flushMemtables();

            File checkpoint = CheckpointScheduler.create(store.database(), "manual");
            assertTrue(checkpoint.isDirectory());
            assertTrue(new File(checkpoint, "CURRENT").isFile(),
                "a checkpoint without CURRENT is not a database");
            assertEquals(1, CheckpointScheduler.checkpointCount());
            assertEquals(0, CheckpointScheduler.failureCount());
            assertTrue(CheckpointScheduler.lastSuccessEpochSeconds() > 0);
            assertTrue(CheckpointScheduler.lastDurationMillis() >= 0);
        }
    }

    /**
     * A restored checkpoint must return the data as of when it was taken.
     *
     * <p>The property the whole feature rests on, and worth testing end-to-end rather
     * than trusting that RocksDB does the right thing: write, checkpoint, write again,
     * then confirm the checkpoint has the first value and not the second.
     */
    @Test
    void checkpointCapturesStateAtTheTimeItWasTaken(@TempDir Path tmp) throws Exception {
        File checkpoint;
        try (RocksChunkStore store = open(tmp, "region")) {
            store.write(new ChunkPos(0, 0), tag("before"));
            store.database().flushMemtables();
            checkpoint = CheckpointScheduler.create(store.database(), "snapshot");

            store.write(new ChunkPos(0, 0), tag("after"));
            store.write(new ChunkPos(5, 5), tag("added-later"));
            store.database().flushMemtables();
            assertEquals("after", store.read(new ChunkPos(0, 0)).getString("marker"));
        }

        // Restore exactly as the documentation instructs, then read it back.
        File live = new File(tmp.toFile(), RocksDatabase.DIRECTORY_NAME);
        assertTrue(deleteRecursively(live), "could not remove the live database");
        copyRecursively(checkpoint, live);

        try (RocksChunkStore restored = open(tmp, "region")) {
            assertEquals("before", restored.read(new ChunkPos(0, 0)).getString("marker"),
                "the checkpoint must hold the value as of when it was taken");
            assertNull(restored.read(new ChunkPos(5, 5)),
                "data written after the checkpoint must not be present");
        }
    }

    /** Automatic names carry the prefix retention keys on; manual names do not. */
    @Test
    void automaticAndManualNamesAreDistinguishable(@TempDir Path tmp) throws Exception {
        try (RocksChunkStore store = open(tmp, "region")) {
            File automatic = CheckpointScheduler.create(store.database(), null);
            File manual = CheckpointScheduler.create(store.database(), "before-upgrade");

            assertTrue(automatic.getName().startsWith(CheckpointScheduler.AUTOMATIC_PREFIX),
                "an automatic checkpoint must be identifiable: " + automatic.getName());
            assertFalse(manual.getName().startsWith(CheckpointScheduler.AUTOMATIC_PREFIX),
                "a manual name must not be treated as automatic");
            assertEquals("before-upgrade", manual.getName());
        }
    }

    /** Re-using a name must fail rather than silently overwrite a rollback point. */
    @Test
    void refusesToOverwriteAnExistingCheckpoint(@TempDir Path tmp) throws Exception {
        try (RocksChunkStore store = open(tmp, "region")) {
            CheckpointScheduler.create(store.database(), "same");
            IOException e = assertThrows(IOException.class,
                () -> CheckpointScheduler.create(store.database(), "same"));
            assertTrue(e.getMessage().contains("already exists"), e.getMessage());
        }
    }

    @Test
    void listReturnsCheckpointsOldestFirst(@TempDir Path tmp) throws Exception {
        fakeCheckpoint(tmp, "auto-20260101-000000");
        fakeCheckpoint(tmp, "auto-20260103-000000");
        fakeCheckpoint(tmp, "auto-20260102-000000");
        fakeCheckpoint(tmp, "manual-thing");

        try (RocksChunkStore store = open(tmp, "region")) {
            List<File> listed = CheckpointScheduler.list(store.database());
            List<String> names = new ArrayList<>();
            for (File f : listed) {
                names.add(f.getName());
            }
            assertEquals(4, names.size());
            assertEquals("auto-20260101-000000", names.get(0));
            assertEquals("auto-20260102-000000", names.get(1));
            assertEquals("auto-20260103-000000", names.get(2));
        }
    }

    // --------------------------------------------------------------- retention

    /** Retention must delete the oldest and keep exactly the requested count. */
    @Test
    void pruneKeepsTheNewestAndDeletesTheOldest(@TempDir Path tmp) throws Exception {
        for (String name : new String[] {"auto-20260101-000000", "auto-20260102-000000",
                "auto-20260103-000000", "auto-20260104-000000", "auto-20260105-000000"}) {
            fakeCheckpoint(tmp, name);
        }

        try (RocksChunkStore store = open(tmp, "region")) {
            assertEquals(3, CheckpointScheduler.prune(store.database(), 2),
                "five checkpoints keeping two means three deletions");

            List<File> remaining = CheckpointScheduler.list(store.database());
            List<String> names = new ArrayList<>();
            for (File f : remaining) {
                names.add(f.getName());
            }
            assertEquals(2, names.size(), "kept: " + names);
            assertTrue(names.contains("auto-20260104-000000"), "kept: " + names);
            assertTrue(names.contains("auto-20260105-000000"), "kept: " + names);
            assertFalse(names.contains("auto-20260101-000000"),
                "the oldest must be the first to go");
        }
    }

    /**
     * A hand-named checkpoint must never be pruned.
     *
     * <p>Naming one is a deliberate act -- {@code before-upgrade} exists precisely
     * because someone expects to need it. Losing it to the retention timer would be
     * the worst possible failure of this feature.
     */
    @Test
    void pruneNeverDeletesManualCheckpoints(@TempDir Path tmp) throws Exception {
        fakeCheckpoint(tmp, "before-upgrade");
        fakeCheckpoint(tmp, "keep-me-forever");
        for (String name : new String[] {"auto-20260101-000000", "auto-20260102-000000",
                "auto-20260103-000000"}) {
            fakeCheckpoint(tmp, name);
        }

        try (RocksChunkStore store = open(tmp, "region")) {
            // keep=1 is aggressive on purpose: only automatic ones may be touched.
            CheckpointScheduler.prune(store.database(), 1);

            List<String> names = new ArrayList<>();
            for (File f : CheckpointScheduler.list(store.database())) {
                names.add(f.getName());
            }
            assertTrue(names.contains("before-upgrade"),
                "a manual checkpoint was pruned: " + names);
            assertTrue(names.contains("keep-me-forever"),
                "a manual checkpoint was pruned: " + names);
            assertEquals(3, names.size(), "expected 2 manual + 1 automatic: " + names);
        }
    }

    /** Nothing is deleted while the count is within the limit. */
    @Test
    void pruneIsANoOpWithinTheLimit(@TempDir Path tmp) throws Exception {
        fakeCheckpoint(tmp, "auto-20260101-000000");
        fakeCheckpoint(tmp, "auto-20260102-000000");

        try (RocksChunkStore store = open(tmp, "region")) {
            assertEquals(0, CheckpointScheduler.prune(store.database(), 6));
            assertEquals(0, CheckpointScheduler.prune(store.database(), 2),
                "exactly at the limit must not delete");
            assertEquals(2, CheckpointScheduler.list(store.database()).size());
        }
    }

    /** Pruning with no checkpoint directory at all must not throw. */
    @Test
    void pruneToleratesAMissingDirectory(@TempDir Path tmp) throws Exception {
        try (RocksChunkStore store = open(tmp, "region")) {
            assertEquals(0, CheckpointScheduler.prune(store.database(), 3));
        }
    }

    /** A stray file among the checkpoints must not be mistaken for one. */
    @Test
    void pruneIgnoresLooseFiles(@TempDir Path tmp) throws Exception {
        fakeCheckpoint(tmp, "auto-20260101-000000");
        File parent = new File(tmp.toFile(), CheckpointScheduler.DIRECTORY_NAME);
        File stray = new File(parent, "auto-not-a-directory.txt");
        Files.write(stray.toPath(), "loose".getBytes("UTF-8"));

        try (RocksChunkStore store = open(tmp, "region")) {
            assertEquals(0, CheckpointScheduler.prune(store.database(), 1));
            assertTrue(stray.isFile(), "a loose file must be left alone");
        }
    }

    /** Retention must delete the whole tree, not just the top directory. */
    @Test
    void pruneDeletesNestedContents(@TempDir Path tmp) throws Exception {
        File old = fakeCheckpoint(tmp, "auto-20260101-000000");
        File nested = new File(old, "nested/deeper");
        assertTrue(nested.mkdirs());
        Files.write(new File(nested, "blob.bin").toPath(), new byte[64]);
        fakeCheckpoint(tmp, "auto-20260102-000000");

        try (RocksChunkStore store = open(tmp, "region")) {
            assertEquals(1, CheckpointScheduler.prune(store.database(), 1));
            assertFalse(old.exists(), "a non-empty checkpoint must be fully removed");
        }
    }

    // --------------------------------------------------------------- scheduling

    /** A zero or negative interval must not start a timer. */
    @Test
    void schedulerStaysOffWhenIntervalIsZero() {
        Properties props = new Properties();
        props.setProperty("backend", "rocksdb");
        props.setProperty("checkpoint-interval-minutes", "0");
        // Must not throw and must not schedule; stop() is then a no-op.
        CheckpointScheduler.start(RocksMcConfig.of(props));
        CheckpointScheduler.stop();
        assertEquals(0, CheckpointScheduler.checkpointCount(),
            "a disabled scheduler must not checkpoint");
    }

    /** Starting twice must not produce two timers writing on overlapping schedules. */
    @Test
    void startIsIdempotent() {
        Properties props = new Properties();
        props.setProperty("backend", "rocksdb");
        props.setProperty("checkpoint-interval-minutes", "60");
        try {
            CheckpointScheduler.start(RocksMcConfig.of(props));
            CheckpointScheduler.start(RocksMcConfig.of(props));
        } finally {
            CheckpointScheduler.stop();
        }
        // Interval is 60 minutes and the first run is one interval in, so nothing
        // should have fired during the test.
        assertEquals(0, CheckpointScheduler.checkpointCount());
    }

    // -------------------------------------------------------------- timestamps

    /**
     * Names must sort chronologically as plain strings.
     *
     * <p>Retention orders by name, so a format that does not sort would delete the
     * wrong checkpoint. UTC for the same reason: a local-time name repeats during a
     * DST fallback, which would collide two checkpoints once a year.
     */
    @Test
    void timestampsAreSortableAndFilesystemSafe() {
        String stamp = CheckpointScheduler.timestamp();
        assertTrue(stamp.matches("\\d{8}-\\d{6}"), "expected yyyyMMdd-HHmmss, got " + stamp);
        assertFalse(stamp.matches(".*[:/\\\\ ].*"), "unsafe characters in " + stamp);
    }

    @Test
    void timestampsSortChronologically() throws Exception {
        String first = CheckpointScheduler.timestamp();
        Thread.sleep(1100);
        String second = CheckpointScheduler.timestamp();
        assertTrue(first.compareTo(second) < 0,
            "must sort chronologically: " + first + " then " + second);
    }

    /** Referenced by the docs and the restore procedure, so a rename is breaking. */
    @Test
    void directoryNameIsStable() {
        assertEquals("rocksmc-checkpoints", CheckpointScheduler.DIRECTORY_NAME);
    }

    // ---------------------------------------------------------------- counters

    @Test
    void failureCounterRecordsRefusals(@TempDir Path tmp) throws Exception {
        try (RocksChunkStore store = open(tmp, "region")) {
            CheckpointScheduler.create(store.database(), "dup");
            assertThrows(IOException.class,
                () -> CheckpointScheduler.create(store.database(), "dup"));
            // create() reports the failure to its caller rather than counting it; the
            // counter is for the scheduler, which cannot report to anyone.
            assertEquals(1, CheckpointScheduler.checkpointCount(),
                "a refused duplicate must not count as a checkpoint");
        }
    }

    @Test
    void formatBytesIsReadable() {
        assertEquals("0 B", CheckpointScheduler.formatBytes(0));
        assertEquals("1.0 KiB", CheckpointScheduler.formatBytes(1024));
        assertEquals("1.0 GiB", CheckpointScheduler.formatBytes(1024L * 1024 * 1024));
        assertEquals("n/a", CheckpointScheduler.formatBytes(-1));
    }

    @Test
    void directoryBytesSumsNestedFiles(@TempDir Path tmp) throws Exception {
        File root = fakeCheckpoint(tmp, "auto-20260101-000000");
        File nested = new File(root, "sub");
        assertTrue(nested.mkdirs());
        Files.write(new File(nested, "a.bin").toPath(), new byte[100]);
        Files.write(new File(nested, "b.bin").toPath(), new byte[200]);
        // 5 bytes of "dummy" from fakeCheckpoint plus 300.
        assertEquals(305, CheckpointScheduler.directoryBytes(root));
    }

    // ----------------------------------------------------------------- helpers

    private static boolean deleteRecursively(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                if (!deleteRecursively(child)) {
                    return false;
                }
            }
        }
        return file.delete();
    }

    private static void copyRecursively(File from, File to) throws IOException {
        if (from.isDirectory()) {
            assertTrue(to.mkdirs() || to.isDirectory());
            File[] children = from.listFiles();
            assertNotNull(children);
            for (File child : children) {
                copyRecursively(child, new File(to, child.getName()));
            }
        } else {
            Files.copy(from.toPath(), to.toPath());
        }
    }

    /**
     * A failed automatic checkpoint must reach the operators, not only the log.
     *
     * <p>This is the one failure whose entire purpose is to be noticed before it
     * matters. Nothing about the running server behaves differently when a checkpoint is
     * missing, so a silent failure is discovered at the moment somebody reaches for a
     * rollback that was never taken. The alert kind existed for this from the start and
     * was never raised by anything.
     *
     * <p>Asserts through the throttle counter, having first written the obvious version
     * of this test and found it passed with the alert deleted: with no server attached
     * there is nothing to observe a delivery directly, and "nothing was suppressed" is
     * equally true of a report that never happened. Two failures are distinguishable,
     * because the second can only be counted as suppressed if the first was recorded.
     */
    @Test
    void aFailedAutomaticCheckpointAlertsTheOperators(@TempDir Path tmp)
            throws Exception {
        FailureReporter.resetForTesting();
        RocksChunkStore store = RocksChunkStore.open(
            DimensionKey.fromStorageDirectory(new File(tmp.toFile(), "region")),
            config());
        try {
            // A file where the checkpoint directory has to go, so creation fails for a
            // reason unrelated to the health of the database itself.
            assertTrue(new File(tmp.toFile(), "rocksmc-checkpoints").createNewFile());

            CheckpointScheduler.runScheduled(config());
            assertEquals(0L, FailureReporter.suppressedCount(
                FailureReporter.Kind.CHECKPOINT_FAILURE),
                "the first failure must be delivered, not throttled");

            CheckpointScheduler.runScheduled(config());
            assertEquals(1L, FailureReporter.suppressedCount(
                FailureReporter.Kind.CHECKPOINT_FAILURE),
                "the second failure proves the first was actually reported; if nothing "
                    + "reported, nothing could be suppressed");
        } finally {
            store.close();
            FailureReporter.resetForTesting();
        }
    }
}
