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
        assertTrue(subcommands.contains("checkpoints"));
        assertEquals(6, subcommands.size(), "update the docs if this changes");
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

    /**
     * Shutdown must be safe with no worker ever started, and repeatable.
     *
     * <p>The common case: a server that never ran a maintenance command still runs the
     * shutdown hook. It must not throw or block. Repeatability matters because the hook
     * can race other teardown and a second call must be a no-op rather than an error.
     */
    @Test
    void shutdownWithNoWorkerIsSafeAndRepeatable() {
        RocksMcCommand.shutdown();
        RocksMcCommand.shutdown();
        assertTrue(true, "reached without throwing or hanging");
    }

    private static net.minecraft.nbt.NbtCompound tag(String marker) {
        net.minecraft.nbt.NbtCompound nbt = new net.minecraft.nbt.NbtCompound();
        nbt.putString("marker", marker);
        return nbt;
    }
}
