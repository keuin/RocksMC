package com.keuin.rocksmc;

import net.fabricmc.api.DedicatedServerModInitializer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/**
 * Mod entrypoint. Loads configuration and reports which backend is active.
 *
 * <p>Deliberately minimal: all real work happens in the mixin, which redirects
 * {@code RegionBasedStorage}'s four seam methods when RocksDB is enabled.
 */
public final class RocksMc implements DedicatedServerModInitializer {

    public static final String MOD_ID = "rocksmc";
    private static final Logger LOGGER = LogManager.getLogger("rocksmc");

    private static RocksMcConfig config = RocksMcConfig.defaults();

    public static RocksMcConfig config() {
        return config;
    }

    public static Logger logger() {
        return LOGGER;
    }

    @Override
    public void onInitializeServer() {
        config = loadConfig();
        LOGGER.info("rocksmc loaded: {}", config);

        if (!config.rocksEnabled()) {
            LOGGER.info("Backend is 'anvil' (vanilla). Set backend=rocksdb in "
                + "config/rocksmc.properties to enable the RocksDB backend.");
            return;
        }

        LOGGER.warn("RocksDB backend ENABLED. This is experimental and the world "
            + "format is NOT .mca -- third-party tools will not read it. "
            + "Back up before use.");
        if (!config.syncWrites()) {
            LOGGER.warn("sync-writes=false: relying on WAL group commit, so a crash "
                + "may lose the last few ms of writes. Set sync-writes=true for "
                + "strict parity with vanilla's sync-chunk-writes.");
        }
    }

    private static RocksMcConfig loadConfig() {
        Path file = Paths.get("config", MOD_ID + ".properties");
        if (!Files.isRegularFile(file)) {
            writeDefaultConfig(file);
            return RocksMcConfig.defaults();
        }
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            props.load(in);
        } catch (IOException e) {
            LOGGER.error("Could not read {}, using defaults", file, e);
            return RocksMcConfig.defaults();
        }
        return RocksMcConfig.of(props);
    }

    private static void writeDefaultConfig(Path file) {
        String contents = "# rocksmc configuration\n"
            + "#\n"
            + "# backend: anvil | rocksdb\n"
            + "#   anvil   - vanilla region files (default, no behaviour change)\n"
            + "#   rocksdb - RocksDB with key-value separation\n"
            + "#\n"
            + "# WARNING: rocksdb does not write .mca files. Amulet, Chunker,\n"
            + "# BlueMap/Dynmap, pregenerators and world editors will not read the\n"
            + "# result. Keep backups.\n"
            + "backend=anvil\n"
            + "\n"
            + "# Values at or above this size are stored in blob files rather than\n"
            + "# the LSM tree. Real chunk NBT averages ~19-51 KiB uncompressed, so at\n"
            + "# 1024 essentially every chunk goes to a blob file.\n"
            + "#\n"
            + "# Measured at real LSM depth, this is a near-symmetric trade rather\n"
            + "# than a clear win: blob files write ~8.5%% fewer bytes but store\n"
            + "# ~8.6%% more on disk, because they ignore the compression level and\n"
            + "# dictionary settings so the LSM arm compresses better. Raise this\n"
            + "# above chunk size (e.g. 1048576) to favour storage over endurance.\n"
            + "min-blob-size=1024\n"
            + "\n"
            + "# true  - fsync every write (parity with vanilla sync-chunk-writes)\n"
            + "# false - WAL with group commit; a crash may lose the last few ms,\n"
            + "#         but the WAL is checksummed and replayable, unlike Anvil's\n"
            + "#         silent torn-header failure mode\n"
            + "#\n"
            + "# THIS IS THE HIGHEST-IMPACT SETTING FOR SSD LIFETIME. Measured, an\n"
            + "# fsync per write multiplied kernel-observed writes by 3.65x while\n"
            + "# barely moving RocksDB's own counters, because partial 4 KiB blocks\n"
            + "# and filesystem metadata get forced to disk. Over five years that is\n"
            + "# roughly 22x the entire blob-versus-LSM difference.\n"
            + "sync-writes=false\n"
            + "\n"
            + "# Diagnostic only: re-read and compare every write. Very slow.\n"
            + "verify-on-read=false\n";
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, contents.getBytes("UTF-8"));
            LOGGER.info("Wrote default config to {}", file);
        } catch (IOException e) {
            LOGGER.error("Could not write default config to {}", file, e);
        }
    }
}
