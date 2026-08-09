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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Mod entrypoint. Loads configuration, reports the active backend, and owns the
 * telemetry side-channels.
 *
 * <p>All storage work happens in the mixin, which redirects
 * {@code RegionBasedStorage}'s four seam methods when RocksDB is enabled. What
 * lives here is everything an operator needs to see what the storage layer is
 * doing: periodic log lines and, optionally, a Prometheus endpoint.
 */
public final class RocksMc implements DedicatedServerModInitializer {

    public static final String MOD_ID = "rocksmc";
    private static final Logger LOGGER = LogManager.getLogger("rocksmc");

    private static RocksMcConfig config = RocksMcConfig.defaults();
    private static MetricsExporter metrics;
    private static ScheduledExecutorService statsLogger;

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
        // Vanilla's own durability flag is read by RegionFile, which we bypass
        // entirely, so an operator setting it would otherwise see no effect.
        LOGGER.warn("Note: server.properties sync-chunk-writes has NO effect with "
            + "the rocksdb backend. Durability is controlled by sync-writes in "
            + "config/rocksmc.properties.");
        if (config.verifyOnRead()) {
            LOGGER.warn("verify-on-read=true: every write is read back and compared. "
                + "This roughly halves write throughput; use for diagnosis only.");
        }

        if (config.metricsEnabled()) {
            metrics = MetricsExporter.start(config.metricsBind(), config.metricsPort());
        }
        startStatsLogger();
        Runtime.getRuntime().addShutdownHook(new Thread(RocksMc::shutdown, "rocksmc-shutdown"));
    }

    /**
     * Logs a periodic summary of every open store.
     *
     * <p>Kept even when Prometheus is enabled: a log line survives in an archive
     * after a crash, whereas a scrape only exists if something was collecting at the
     * time. For a beta the post-mortem record matters more.
     */
    private static void startStatsLogger() {
        int interval = config.statsLogIntervalSeconds();
        if (interval <= 0) {
            return;
        }
        statsLogger = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread t = new Thread(runnable, "rocksmc-stats");
            t.setDaemon(true);
            return t;
        });
        statsLogger.scheduleAtFixedRate(RocksMc::logStats, interval, interval,
            TimeUnit.SECONDS);
    }

    private static void logStats() {
        try {
            for (RocksChunkStore store : StoreRegistry.stores()) {
                RocksChunkStore.Snapshot s = store.snapshot();
                LOGGER.info("stats {} [{}]: reads={} writes={} readBytes={} writeBytes={} "
                        + "sst={} blob={} keys~{} pendingCompaction={} running={}c/{}f "
                        + "stalls={} stopped={}",
                    s.dimension, s.leaf, s.reads, s.writes, s.bytesRead, s.bytesWritten,
                    s.liveSstBytes, s.blobFileBytes, s.estimatedKeys,
                    s.pendingCompactionBytes, s.runningCompactions, s.runningFlushes,
                    s.delayedWriteRate, s.writeStopped);
                // Surface the two conditions that actually cause tick lag, rather than
                // leaving an operator to spot them in a wall of numbers.
                if (s.writeStopped > 0) {
                    LOGGER.error("rocksmc: writes are STOPPED on {} [{}] -- compaction "
                        + "cannot keep up. Raise max-background-jobs.", s.dimension, s.leaf);
                } else if (s.delayedWriteRate > 0) {
                    LOGGER.warn("rocksmc: writes are being throttled on {} [{}] to {} B/s.",
                        s.dimension, s.leaf, s.delayedWriteRate);
                }
                if (s.readFailures + s.writeFailures + s.verifyFailures > 0) {
                    LOGGER.error("rocksmc: failures on {} [{}]: read={} write={} verify={}",
                        s.dimension, s.leaf, s.readFailures, s.writeFailures,
                        s.verifyFailures);
                }
            }
        } catch (RuntimeException e) {
            LOGGER.warn("rocksmc: stats logging failed", e);
        }
    }

    private static void shutdown() {
        if (statsLogger != null) {
            statsLogger.shutdownNow();
        }
        if (metrics != null) {
            metrics.close();
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
            + "# Diagnostic only: re-read and compare the bytes of every write.\n"
            + "# Roughly halves write throughput. Worth enabling early in a beta to\n"
            + "# catch a corrupting bug where it happens rather than when a player\n"
            + "# notices missing terrain.\n"
            + "verify-on-read=false\n"
            + "\n"
            + "# Normally the mod refuses to start if the RocksDB store is empty but\n"
            + "# sibling .mca files contain chunks, because vanilla would regenerate\n"
            + "# terrain while playerdata still refers to the old world -- players keep\n"
            + "# their coordinates in a world that no longer matches. Set true only for\n"
            + "# a deliberately fresh start in a populated directory.\n"
            + "allow-blank-start=false\n"
            + "\n"
            + "# ---------------------------------------------------------------------\n"
            + "# Performance. Defaults target a technical server on fast storage: the\n"
            + "# scarce resource is CPU competing with the tick loop, so these favour\n"
            + "# draining background work quickly and spreading writeback thinly.\n"
            + "#\n"
            + "# NOTE: these values are reasoned, not measured. Use the metrics below\n"
            + "# to correct them for your workload.\n"
            + "# ---------------------------------------------------------------------\n"
            + "\n"
            + "# Background compaction and flush threads. RocksDB defaults to 2, sized\n"
            + "# for spinning disks. Unfinished compaction eventually stalls writes on\n"
            + "# the IO worker, so on NVMe/Optane it pays to drain it aggressively.\n"
            + "max-background-jobs=8\n"
            + "\n"
            + "# Splits a single large compaction across threads.\n"
            + "max-subcompactions=4\n"
            + "\n"
            + "# Memtable size. The dominant write pattern is the same hot chunks being\n"
            + "# saved repeatedly, so a larger memtable coalesces more of that before\n"
            + "# anything reaches disk. 64 MiB.\n"
            + "write-buffer-size=67108864\n"
            + "\n"
            + "# Memtables allowed before writes stall; absorbs autosave convoys.\n"
            + "max-write-buffer-number=4\n"
            + "\n"
            + "# Writeback increment for SST and WAL. Without this the OS accumulates\n"
            + "# dirty pages until file close and flushes them in one burst, which\n"
            + "# lands on the tick loop as a stall. 1 MiB.\n"
            + "bytes-per-sync=1048576\n"
            + "\n"
            + "# Block cache for index and filter blocks. Note this does NOT cache\n"
            + "# chunk values: those live in blob files, which RocksDB 10.x has no\n"
            + "# per-column-family blob cache for, so chunk reads rely on this plus the\n"
            + "# OS page cache. 256 MiB.\n"
            + "block-cache-size=268435456\n"
            + "\n"
            + "# L0 file count that begins throttling writes. Raised from RocksDB's\n"
            + "# default of 8: fast storage drains L0 quickly, so throttling early\n"
            + "# costs tick time for no benefit.\n"
            + "level0-slowdown-writes-trigger=20\n"
            + "\n"
            + "# ---------------------------------------------------------------------\n"
            + "# Telemetry\n"
            + "# ---------------------------------------------------------------------\n"
            + "\n"
            + "# Prometheus exporter on http://<bind>:<port>/metrics\n"
            + "# Metrics reveal world size and activity, so the default binds to\n"
            + "# loopback only. Point your scraper at it, or set 0.0.0.0 deliberately.\n"
            + "metrics-enabled=false\n"
            + "metrics-bind=127.0.0.1\n"
            + "metrics-port=9940\n"
            + "\n"
            + "# Seconds between periodic stats log lines; 0 disables. Kept alongside\n"
            + "# Prometheus because a log line survives in an archive after a crash,\n"
            + "# whereas a scrape only exists if something was collecting at the time.\n"
            + "stats-log-interval-seconds=300\n";
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, contents.getBytes("UTF-8"));
            LOGGER.info("Wrote default config to {}", file);
        } catch (IOException e) {
            LOGGER.error("Could not write default config to {}", file, e);
        }
    }
}
