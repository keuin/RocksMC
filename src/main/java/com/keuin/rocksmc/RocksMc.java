package com.keuin.rocksmc;

import net.fabricmc.api.DedicatedServerModInitializer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
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
    private static ScheduledExecutorService diskWatchdog;

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

        // Before the backend check, deliberately. With backend=anvil the commands
        // still answer -- reporting that no database is open -- which is more useful
        // than a missing command an operator has to guess about, and it means
        // /rocksmc stats can confirm which backend is actually live.
        RocksMcCommand.registerCallback();

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
        startDiskWatchdog();
        CheckpointScheduler.start(config);
        Runtime.getRuntime().addShutdownHook(new Thread(RocksMc::shutdown, "rocksmc-shutdown"));
    }

    /**
     * Logs a periodic summary of every open store and database.
     *
     * <p>Kept even when Prometheus is enabled: a log line survives in an archive
     * after a crash, whereas a scrape only exists if something was collecting at the
     * time. For a beta the post-mortem record matters more.
     *
     * <p>Two kinds of line, matching the two scopes. Per-store lines carry IO the
     * store owns; per-database lines carry the shared state. Merging them would
     * repeat a world's compaction figures once per dimension and invite exactly the
     * overcounting the metric split exists to avoid.
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

    /**
     * Warns when free space on the world's volume runs low.
     *
     * <p>Deliberately its own timer rather than part of the stats logger. The stats
     * interval is operator-tunable and can be set to 0 -- the beta server runs it that
     * way -- and disabling periodic statistics should not also disable the warning
     * that precedes an unrecoverable disk-full state. Fixed at one minute because this
     * is a safety net, not a metric.
     */
    private static void startDiskWatchdog() {
        if (config.diskSpaceWarningBytes() <= 0) {
            return;
        }
        diskWatchdog = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread t = new Thread(runnable, "rocksmc-disk");
            t.setDaemon(true);
            return t;
        });
        diskWatchdog.scheduleAtFixedRate(RocksMc::checkDiskSpace, 60, 60, TimeUnit.SECONDS);
    }

    private static void checkDiskSpace() {
        try {
            long threshold = config.diskSpaceWarningBytes();
            for (RocksDatabase database : StoreRegistry.databases()) {
                long usable = database.path().getUsableSpace();
                // 0 means the path is unavailable or the query failed; do not read
                // that as "disk full" and cry wolf.
                if (usable > 0 && usable < threshold) {
                    FailureReporter.report(FailureReporter.Kind.DISK_LOW,
                        database.name() + ": only "
                            + CheckpointScheduler.formatBytes(usable)
                            + " free. If this reaches zero RocksDB latches a "
                            + "background error and refuses ALL further writes, and "
                            + "freeing space does NOT recover it -- the server must be "
                            + "restarted. Free space now.");
                }
            }
        } catch (Throwable t) {
            LOGGER.warn("rocksmc: disk space check failed", t);
        }
    }

    private static void logStats() {
        try {
            for (RocksChunkStore store : StoreRegistry.stores()) {
                RocksChunkStore.Snapshot s = store.snapshot();
                LOGGER.info("stats {} {} [{}]: reads={} writes={} readBytes={} "
                        + "writeBytes={} ord={}",
                    s.database, s.dimension, s.leaf, s.reads, s.writes,
                    s.bytesRead, s.bytesWritten, s.dimensionOrdinal);
                if (s.readFailures + s.writeFailures + s.verifyFailures > 0) {
                    // A running total, alongside the immediate per-failure alerts
                    // raised from the store itself. Kept because a cumulative count
                    // is what tells an operator whether a fault is ongoing.
                    LOGGER.error("rocksmc: cumulative failures on {} [{}]: read={} "
                        + "write={} verify={}", s.dimension, s.leaf, s.readFailures,
                        s.writeFailures, s.verifyFailures);
                }
            }

            for (RocksDatabase database : StoreRegistry.databases()) {
                RocksDatabase.Snapshot d = database.snapshot();
                StringBuilder cfs = new StringBuilder();
                for (RocksDatabase.ColumnFamilySnapshot cf : d.columnFamilies) {
                    cfs.append(' ').append(cf.columnFamily)
                        .append("[sst=").append(cf.liveSstBytes)
                        .append(" keys~").append(cf.estimatedKeys)
                        .append(" memtable=").append(cf.memtableBytes)
                        .append(" pendingCompaction=").append(cf.pendingCompactionBytes)
                        .append(']');
                }
                LOGGER.info("stats db {}: stores={} blob={} blockCache={} "
                        + "running={}c/{}f stalls={} stopped={}{}",
                    d.database, d.openStores, d.blobFileBytes, d.blockCacheBytes,
                    d.runningCompactions, d.runningFlushes, d.delayedWriteRate,
                    d.writeStopped, cfs);

                // Surface the two conditions that actually cause tick lag, rather
                // than leaving an operator to spot them in a wall of numbers.
                if (d.writeStopped > 0) {
                    FailureReporter.report(FailureReporter.Kind.WRITE_STOPPED,
                        d.database + ": writes are STOPPED -- compaction cannot keep "
                            + "up. Raise max-background-jobs. Chunks are not being "
                            + "saved.");
                } else if (d.delayedWriteRate > 0) {
                    FailureReporter.report(FailureReporter.Kind.WRITE_THROTTLED,
                        d.database + ": writes throttled to " + d.delayedWriteRate
                            + " B/s -- compaction is falling behind.");
                }
            }
        } catch (Throwable t) {
            // Throwable, not RuntimeException. scheduleAtFixedRate cancels a task
            // permanently and silently on any escaping throwable, so an Error here --
            // an OutOfMemoryError during a spike, a LinkageError on a lazily loaded
            // log4j path -- would stop stats for the rest of the server's life with
            // nothing to mark the transition. CheckpointScheduler already gets this
            // right; this did not.
            LOGGER.warn("rocksmc: stats logging failed", t);
        }
    }

    private static void shutdown() {
        // Before the exporter, so a checkpoint in flight is not abandoned mid-write by
        // a JVM that has already torn down its telemetry.
        CheckpointScheduler.stop();
        if (statsLogger != null) {
            statsLogger.shutdownNow();
        }
        if (diskWatchdog != null) {
            diskWatchdog.shutdownNow();
        }
        if (metrics != null) {
            metrics.close();
        }
    }

    /**
     * Loads the configuration, refusing to guess when the stakes are high.
     *
     * <p>An unreadable config used to fall back to {@link RocksMcConfig#defaults()},
     * which means {@code backend=anvil}. On a world already migrated to RocksDB that
     * is silent data loss in slow motion: vanilla reads the {@code .mca} files, which
     * are frozen at the moment of the import, and every session since then is
     * invisible. So if a database is present and the config cannot be read, this
     * aborts instead.
     *
     * <p>A missing config file is different and stays benign -- it means a fresh
     * install, so the default is written and {@code anvil} is genuinely correct.
     */
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
            if (databasePresent()) {
                throw new RuntimeException("rocksmc: cannot read " + file
                    + ", and a RocksDB database exists in this world. Refusing to "
                    + "start: falling back to the anvil backend would serve the .mca "
                    + "files as they were at import time and silently discard "
                    + "everything played since. Fix or delete the config file.", e);
            }
            LOGGER.error("Could not read {}, using defaults", file, e);
            return RocksMcConfig.defaults();
        }
        return RocksMcConfig.of(props);
    }

    /**
     * Whether any world beside the server directory already holds a database.
     *
     * <p>Deliberately a shallow filesystem check rather than anything that needs the
     * config: it runs precisely when the config is unusable. The level name is not
     * known here either, so this looks for {@code rocksmc.db} one level down, which
     * covers the standard layout ({@code world/rocksmc.db}) without opening anything.
     */
    private static boolean databasePresent() {
        File[] candidates = new File(".").listFiles();
        if (candidates == null) {
            return false;
        }
        for (File candidate : candidates) {
            if (candidate.isDirectory()
                    && new File(candidate, RocksDatabase.DIRECTORY_NAME).isDirectory()) {
                return true;
            }
        }
        return false;
    }

    private static void writeDefaultConfig(Path file) {
        String contents = "# rocksmc configuration\n"
            + "#\n"
            + "# backend: anvil | rocksdb\n"
            + "#   anvil   - vanilla region files (default, no behaviour change)\n"
            + "#   rocksdb - one RocksDB per world, with key-value separation\n"
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
            + "# than a clear win: blob files write ~8.5% fewer bytes but store\n"
            + "# ~8.6% more on disk, because they ignore the compression level and\n"
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
            + "# These are per-WORLD, not per-dimension. One database serves every\n"
            + "# dimension, so the memory figures below mean what they say. Before\n"
            + "# that consolidation each of a world's six stores allocated its own,\n"
            + "# silently multiplying every figure by six.\n"
            + "#\n"
            + "# NOTE: these values are reasoned, not measured. Use the metrics below\n"
            + "# to correct them for your workload.\n"
            + "# ---------------------------------------------------------------------\n"
            + "\n"
            + "# Background compaction and flush threads, per world. RocksDB defaults\n"
            + "# to 2, sized for spinning disks. Unfinished compaction eventually\n"
            + "# stalls writes on the IO worker, so on NVMe/Optane it pays to drain it\n"
            + "# aggressively.\n"
            + "max-background-jobs=8\n"
            + "\n"
            + "# Splits a single large compaction across threads.\n"
            + "max-subcompactions=4\n"
            + "\n"
            + "# Memtable size, per column family (chunk and poi). The dominant write\n"
            + "# pattern is the same hot chunks being saved repeatedly, so a larger\n"
            + "# memtable coalesces more of that before anything reaches disk. All\n"
            + "# dimensions share a column family, so worst case is this x\n"
            + "# max-write-buffer-number x 2. 128 MiB.\n"
            + "write-buffer-size=134217728\n"
            + "\n"
            + "# Memtables allowed before writes stall; absorbs autosave convoys.\n"
            + "max-write-buffer-number=4\n"
            + "\n"
            + "# Writeback increment for SST and WAL. Without this the OS accumulates\n"
            + "# dirty pages until file close and flushes them in one burst, which\n"
            + "# lands on the tick loop as a stall. 1 MiB.\n"
            + "bytes-per-sync=1048576\n"
            + "\n"
            + "# Block cache for index and filter blocks, one per world. Note this\n"
            + "# does NOT cache chunk values: those live in blob files, which RocksDB\n"
            + "# 10.x has no per-column-family blob cache for, so chunk reads rely on\n"
            + "# this plus the OS page cache. 512 MiB.\n"
            + "block-cache-size=536870912\n"
            + "\n"
            + "# L0 file count that begins throttling writes. Raised from RocksDB's\n"
            + "# default of 8: fast storage drains L0 quickly, so throttling early\n"
            + "# costs tick time for no benefit.\n"
            + "level0-slowdown-writes-trigger=20\n"
            + "\n"
            + "# L0 file count at which writes stop entirely. Must stay at or above\n"
            + "# the slowdown trigger, or writes stall without being throttled first;\n"
            + "# the mod corrects that combination and warns rather than allowing it.\n"
            + "level0-stop-writes-trigger=36\n"
            + "\n"
            + "# Cap on simultaneously open SST and blob files. -1 is RocksDB's own\n"
            + "# default (unlimited), which is fine on a dedicated host but can drift\n"
            + "# into the process file-descriptor limit on a shared one, failing days\n"
            + "# later with 'too many open files'. Set a bound if ulimit -n is low.\n"
            + "max-open-files=-1\n"
            + "\n"
            + "# Total write-ahead-log bytes before a memtable flush is forced.\n"
            + "# 0 leaves RocksDB's default. With sync-writes=false the WAL IS the\n"
            + "# durability mechanism, so bounding it bounds recovery time and the\n"
            + "# disk a mostly-idle world can hold.\n"
            + "max-total-wal-size=0\n"
            + "\n"
            + "# Hard cap on SST bytes; 0 means no cap. THIS IS THE ONLY PRE-EMPTIVE\n"
            + "# DEFENCE AGAINST A FULL DISK, and it matters more than it looks: when\n"
            + "# RocksDB hits ENOSPC it latches a background error and refuses every\n"
            + "# further write, and the Java API exposes no DB::Resume(), so freeing\n"
            + "# space does NOT recover it -- the database stays effectively read-only\n"
            + "# until the server is restarted, while the server keeps running and\n"
            + "# silently persists nothing. Set this below the volume size so writes\n"
            + "# fail while there is still room to compact and react.\n"
            + "max-allowed-space-bytes=0\n"
            + "\n"
            + "# Warn when free space on the world's volume drops below this. 0\n"
            + "# disables. Checked once a minute on its own timer, so it keeps working\n"
            + "# with stats-log-interval-seconds=0. 2 GiB.\n"
            + "disk-space-warning-bytes=2147483648\n"
            + "\n"
            + "# Rotation for RocksDB's own LOG file inside the database directory.\n"
            + "# RocksDB defaults to never rotating by size, so on a server that does\n"
            + "# not restart the file grows without bound (~2.5 MB/day with the default\n"
            + "# stats dump interval) and is invisible to every size metric here.\n"
            + "max-log-file-size=67108864\n"
            + "keep-log-file-num=4\n"
            + "\n"
            + "# ---------------------------------------------------------------------\n"
            + "# Checkpoints (rollback safety net)\n"
            + "#\n"
            + "# Hard-link based, so near-instant and almost free: measured at 0 ms on\n"
            + "# a real 1.1 GB database. Unlike a filesystem snapshot of a live Anvil\n"
            + "# world, a checkpoint is consistent by construction and needs no pause.\n"
            + "#\n"
            + "# WARNING: the links share blocks with the live database, so this\n"
            + "# protects against logical corruption and bad deploys, NOT against\n"
            + "# losing the drive. It is not an off-device backup.\n"
            + "#\n"
            + "# Retention matters: a checkpoint pins the files it references, so\n"
            + "# obsolete SSTs and blobs cannot be reclaimed while it exists.\n"
            + "# Keeping them forever turns 'free' into unbounded growth.\n"
            + "# ---------------------------------------------------------------------\n"
            + "\n"
            + "# Minutes between automatic checkpoints; 0 disables. Manual ones are\n"
            + "# always available via /rocksmc checkpoint.\n"
            + "checkpoint-interval-minutes=0\n"
            + "\n"
            + "# How many automatic checkpoints to keep; the oldest are pruned.\n"
            + "checkpoint-keep=6\n"
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
