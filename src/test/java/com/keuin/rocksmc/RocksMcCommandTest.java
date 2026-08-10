package com.keuin.rocksmc;

import net.minecraft.util.math.ChunkPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the pieces of the command surface that can be exercised without a server.
 *
 * <p>Brigadier dispatch itself needs a live {@code ServerCommandSource}, so what is
 * pinned here is everything underneath it: that the operations the commands expose are
 * reachable and correct, that checkpoint naming is sane, and that the byte formatting
 * an operator reads is right. The dispatch wiring is verified on a real dev server
 * instead.
 */
class RocksMcCommandTest {

    private static RocksMcConfig config() {
        Properties props = new Properties();
        props.setProperty("backend", "rocksdb");
        return RocksMcConfig.of(props);
    }

    private static RocksChunkStore open(Path worldRoot, String relative) throws Exception {
        File dir = new File(worldRoot.toFile(), relative);
        return RocksChunkStore.open(DimensionKey.fromStorageDirectory(dir), config());
    }

    // ------------------------------------------------------------ reachability

    /**
     * The whole point of the command surface: these were unreachable before.
     *
     * <p>{@code checkpoint} in particular is the one capability Anvil structurally
     * cannot offer, and it was written, tested and callable by nothing.
     */
    @Test
    void everyOperationTheCommandsExposeIsReachable(@TempDir Path tmp) throws Exception {
        try (RocksChunkStore store = open(tmp, "region")) {
            store.write(new ChunkPos(0, 0), tag("reachable"));
            RocksDatabase database = store.database();

            // flush
            database.flushMemtables();
            assertEquals("reachable",
                store.read(new ChunkPos(0, 0)).getString("marker"));

            // compact
            database.compact();
            assertEquals("reachable",
                store.read(new ChunkPos(0, 0)).getString("marker"));

            // checkpoint
            File target = new File(tmp.toFile(), "cp");
            database.checkpoint(target);
            assertTrue(new File(target, "CURRENT").isFile(),
                "a checkpoint must be a complete database");

            // stats + dimensions read paths
            assertEquals(1, database.snapshot().openStores);
            assertFalse(database.dimensionRegistry().snapshot().isEmpty());
        }
    }

    @Test
    void subcommandsAreTheDocumentedSet() {
        List<String> subcommands = RocksMcCommand.subcommands();
        assertTrue(subcommands.contains("stats"));
        assertTrue(subcommands.contains("dimensions"));
        assertTrue(subcommands.contains("flush"));
        assertTrue(subcommands.contains("compact"));
        assertTrue(subcommands.contains("checkpoint"));
        assertEquals(5, subcommands.size(), "update the docs if this changes");
    }

    // --------------------------------------------------------------- formatting

    /**
     * Byte counts an operator reads in chat.
     *
     * <p>A raw count of blob bytes is unreadable, and getting the unit boundaries wrong
     * would misreport disk usage by a factor of 1024 -- the kind of error that led to
     * the retracted figures elsewhere in this project.
     */
    @Test
    void byteFormattingIsCorrectAtUnitBoundaries() {
        assertEquals("0 B", RocksMcCommand.bytes(0));
        assertEquals("1023 B", RocksMcCommand.bytes(1023));
        assertEquals("1.0 KiB", RocksMcCommand.bytes(1024));
        assertEquals("1.5 KiB", RocksMcCommand.bytes(1536));
        assertEquals("1.0 MiB", RocksMcCommand.bytes(1024L * 1024));
        assertEquals("1.0 GiB", RocksMcCommand.bytes(1024L * 1024 * 1024));
        assertEquals("1.1 GiB", RocksMcCommand.bytes(1181116006L));
        assertEquals("1.0 TiB", RocksMcCommand.bytes(1024L * 1024 * 1024 * 1024));
    }

    /**
     * A RocksDB property that is unavailable comes back as -1 and must not be shown
     * as a negative size.
     */
    @Test
    void unavailableValuesRenderAsNotApplicable() {
        assertEquals("n/a", RocksMcCommand.bytes(-1));
    }

    // --------------------------------------------------------------- timestamps

    /**
     * Checkpoint names must sort chronologically as plain strings.
     *
     * <p>Retention prunes the oldest by name, so a format that does not sort would
     * delete the wrong one. UTC for the same reason: a local-time name repeats during
     * a DST fallback, which would make two checkpoints collide once a year.
     */
    @Test
    void checkpointTimestampsAreSortableAndFilesystemSafe() {
        String stamp = RocksMcCommand.timestamp();
        assertTrue(stamp.matches("\\d{8}-\\d{6}"),
            "expected yyyyMMdd-HHmmss, got " + stamp);
        // No characters that need quoting or that Windows rejects in a filename.
        assertFalse(stamp.matches(".*[:/\\\\ ].*"), stamp);
    }

    @Test
    void checkpointTimestampsSortLexicographically() throws Exception {
        // Two stamps a second apart must order correctly as strings.
        String first = RocksMcCommand.timestamp();
        Thread.sleep(1100);
        String second = RocksMcCommand.timestamp();
        assertNotEquals(first, second);
        assertTrue(first.compareTo(second) < 0,
            "timestamps must sort chronologically: " + first + " vs " + second);
    }

    @Test
    void checkpointDirectoryNameIsStable() {
        // Referenced by docs and by the retention logic, so a rename is a breaking
        // change rather than a detail.
        assertEquals("rocksmc-checkpoints", RocksMcCommand.CHECKPOINT_DIR_NAME);
    }

    private static net.minecraft.nbt.NbtCompound tag(String marker) {
        net.minecraft.nbt.NbtCompound nbt = new net.minecraft.nbt.NbtCompound();
        nbt.putString("marker", marker);
        return nbt;
    }
}
