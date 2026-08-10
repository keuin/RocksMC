package com.keuin.rocksmc;

import java.util.Locale;
import java.util.Properties;

/**
 * Configuration, read from {@code config/rocksmc.properties}.
 *
 * <p>Defaults are deliberately conservative: the backend is {@code anvil}, so
 * installing the mod changes nothing until explicitly enabled. Vanilla therefore
 * remains the live reference implementation for A/B comparison.
 *
 * <p>The RocksDB tuning defaults below are chosen for a game server rather than a
 * database server: the scarce resource is CPU competing with the tick loop, so
 * they favour draining background work quickly and spreading writeback thinly over
 * time instead of maximising raw throughput.
 *
 * <p><b>These tuning values are reasoned, not measured.</b> Every unmeasured
 * figure in this project so far has turned out wrong, so treat them as a starting
 * point that beta telemetry should correct.
 */
public final class RocksMcConfig {

    public enum Backend {
        /** Vanilla Anvil region files. The default. */
        ANVIL,
        /** RocksDB, one database per world. */
        ROCKSDB
    }

    private final Backend backend;
    private final long minBlobSize;
    private final boolean syncWrites;
    private final boolean verifyOnRead;
    private final boolean allowBlankStart;

    private final int maxBackgroundJobs;
    private final int maxSubcompactions;
    private final long writeBufferSize;
    private final int maxWriteBufferNumber;
    private final long bytesPerSync;
    private final long blockCacheSize;
    private final int level0SlowdownTrigger;

    private final boolean metricsEnabled;
    private final String metricsBind;
    private final int metricsPort;
    private final int statsLogIntervalSeconds;

    private RocksMcConfig(Backend backend, long minBlobSize, boolean syncWrites,
            boolean verifyOnRead, boolean allowBlankStart, int maxBackgroundJobs,
            int maxSubcompactions, long writeBufferSize, int maxWriteBufferNumber,
            long bytesPerSync, long blockCacheSize, int level0SlowdownTrigger,
            boolean metricsEnabled, String metricsBind, int metricsPort,
            int statsLogIntervalSeconds) {
        this.backend = backend;
        this.minBlobSize = minBlobSize;
        this.syncWrites = syncWrites;
        this.verifyOnRead = verifyOnRead;
        this.allowBlankStart = allowBlankStart;
        this.maxBackgroundJobs = maxBackgroundJobs;
        this.maxSubcompactions = maxSubcompactions;
        this.writeBufferSize = writeBufferSize;
        this.maxWriteBufferNumber = maxWriteBufferNumber;
        this.bytesPerSync = bytesPerSync;
        this.blockCacheSize = blockCacheSize;
        this.level0SlowdownTrigger = level0SlowdownTrigger;
        this.metricsEnabled = metricsEnabled;
        this.metricsBind = metricsBind;
        this.metricsPort = metricsPort;
        this.statsLogIntervalSeconds = statsLogIntervalSeconds;
    }

    public static RocksMcConfig defaults() {
        return of(new Properties());
    }

    /**
     * A copy with {@code verify-on-read} forced to a given value.
     *
     * <p>For the importer, which runs its own full verification pass and would
     * otherwise do the work twice. A real copy rather than a mutable field because
     * one configuration object is shared by every store of a world.
     */
    public RocksMcConfig withVerifyOnRead(boolean value) {
        return new RocksMcConfig(this.backend, this.minBlobSize, this.syncWrites,
            value, this.allowBlankStart, this.maxBackgroundJobs, this.maxSubcompactions,
            this.writeBufferSize, this.maxWriteBufferNumber, this.bytesPerSync,
            this.blockCacheSize, this.level0SlowdownTrigger, this.metricsEnabled,
            this.metricsBind, this.metricsPort, this.statsLogIntervalSeconds);
    }

    public static RocksMcConfig of(Properties props) {
        Backend backend;
        try {
            backend = Backend.valueOf(
                props.getProperty("backend", "anvil").trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            backend = Backend.ANVIL;
        }
        return new RocksMcConfig(
            backend,
            parseLong(props, "min-blob-size", 1024L),
            parseBool(props, "sync-writes", false),
            parseBool(props, "verify-on-read", false),
            parseBool(props, "allow-blank-start", false),
            parseInt(props, "max-background-jobs", 8),
            parseInt(props, "max-subcompactions", 4),
            parseLong(props, "write-buffer-size", 128L * 1024 * 1024),
            parseInt(props, "max-write-buffer-number", 4),
            parseLong(props, "bytes-per-sync", 1024L * 1024),
            parseLong(props, "block-cache-size", 512L * 1024 * 1024),
            parseInt(props, "level0-slowdown-writes-trigger", 20),
            parseBool(props, "metrics-enabled", false),
            props.getProperty("metrics-bind", "127.0.0.1").trim(),
            parseInt(props, "metrics-port", 9940),
            parseInt(props, "stats-log-interval-seconds", 300));
    }

    private static long parseLong(Properties props, String key, long fallback) {
        String s = props.getProperty(key);
        if (s == null) {
            return fallback;
        }
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int parseInt(Properties props, String key, int fallback) {
        return (int) parseLong(props, key, fallback);
    }

    private static boolean parseBool(Properties props, String key, boolean fallback) {
        String s = props.getProperty(key);
        return s == null ? fallback : Boolean.parseBoolean(s.trim());
    }

    public Backend backend() {
        return this.backend;
    }

    public boolean rocksEnabled() {
        return this.backend == Backend.ROCKSDB;
    }

    /**
     * Values at or above this size go to blob files rather than the LSM tree.
     *
     * <p>Real uncompressed chunk NBT is ~19-51 KiB mean, so at 1 KiB essentially
     * every chunk goes to a blob file.
     *
     * <p>Measured at real LSM depth (Phase 1c), this is a near-symmetric trade
     * rather than a clear win: blob files write <b>8.5% fewer bytes</b> but store
     * <b>8.6% more on disk</b>, because they ignore the compression level and
     * dictionary settings so the LSM arm compresses better. Raise this above chunk
     * size to favour storage; leave it low to favour lower compaction CPU.
     */
    public long minBlobSize() {
        return this.minBlobSize;
    }

    /**
     * Whether every write is fsynced before returning.
     *
     * <p>Vanilla's {@code sync-chunk-writes} defaults to {@code true}, giving an
     * fsync-class operation per chunk write. Leaving this {@code false} uses
     * RocksDB's WAL with group commit instead: a crash can lose the last few
     * milliseconds, but the WAL is checksummed and replayable, whereas Anvil's
     * torn-header failure mode is silent and unrecoverable.
     *
     * <p>Measured, enabling this multiplied kernel-observed writes by 3.65x while
     * barely moving RocksDB's own counters -- an fsync forces partial blocks and
     * filesystem metadata to disk. It is the single highest-impact setting here.
     */
    public boolean syncWrites() {
        return this.syncWrites;
    }

    /**
     * Diagnostic: re-read and compare the bytes of every write.
     *
     * <p>Roughly halves write throughput. Worth enabling early in a beta to catch a
     * corrupting bug at the point it happens rather than when a player notices
     * missing terrain.
     */
    public boolean verifyOnRead() {
        return this.verifyOnRead;
    }

    /**
     * Permit starting with an empty database beside a populated Anvil world.
     *
     * <p>Normally that combination aborts, because vanilla would regenerate terrain
     * into RocksDB while {@code playerdata} still refers to the old world -- players
     * keep their coordinates in a world that no longer matches and can end up inside
     * solid blocks. Set this only when a deliberately fresh start is intended.
     */
    public boolean allowBlankStart() {
        return this.allowBlankStart;
    }

    /**
     * Background compaction and flush threads.
     *
     * <p>RocksDB defaults to 2, sized for spinning disks. Compaction bytes are
     * compaction CPU, and unfinished compaction eventually stalls writes on the IO
     * worker, so on fast storage it is better to drain it aggressively.
     *
     * <p>One pool for the world. Before consolidation this was allocated per store,
     * so a three-dimension world really got six times this number of threads.
     */
    public int maxBackgroundJobs() {
        return this.maxBackgroundJobs;
    }

    /** Splits one large compaction across threads, which fast storage can feed. */
    public int maxSubcompactions() {
        return this.maxSubcompactions;
    }

    /**
     * Memtable size, per column family.
     *
     * <p>The dominant write pattern is the same hot chunks being saved repeatedly,
     * and {@code StorageIoWorker} already coalesces within one autosave. A larger
     * memtable extends that coalescing across autosaves, so fewer versions of a
     * chunk ever reach disk.
     *
     * <p>Applies to the {@code chunk} and {@code poi} column families, so worst-case
     * memtable memory is this times {@link #maxWriteBufferNumber()} times two --
     * not times six as it was when every store had its own database. All dimensions
     * share a column family, which is also why the default could be raised.
     */
    public long writeBufferSize() {
        return this.writeBufferSize;
    }

    /** Memtables allowed before writes stall; absorbs autosave convoys. */
    public int maxWriteBufferNumber() {
        return this.maxWriteBufferNumber;
    }

    /**
     * Writeback increment for SST and WAL files.
     *
     * <p>Without this the OS accumulates dirty pages until file close and then
     * flushes them in one burst, which lands on the tick loop as a stall. Syncing
     * every megabyte trades a little throughput for far steadier latency.
     */
    public long bytesPerSync() {
        return this.bytesPerSync;
    }

    /**
     * Block cache size, shared by index, filter and data blocks.
     *
     * <p>One cache for the whole world. Before consolidation each store allocated
     * its own, so this figure silently multiplied by six -- a 512 MiB setting really
     * meant 3 GiB. It now means what it says, which is why the default could be
     * raised to 512 MiB without increasing real usage.
     *
     * <p>Note this does not cache chunk values: those live in blob files, which
     * RocksDB 10.x does not offer a per-column-family blob cache for. Chunk reads
     * therefore rely on this cache for the index plus the OS page cache for blobs.
     */
    public long blockCacheSize() {
        return this.blockCacheSize;
    }

    /** L0 file count that begins throttling writes. Raised from the default 8. */
    public int level0SlowdownTrigger() {
        return this.level0SlowdownTrigger;
    }

    /** Whether to serve Prometheus metrics over HTTP. */
    public boolean metricsEnabled() {
        return this.metricsEnabled;
    }

    /** Interface for the metrics listener. Defaults to loopback, not 0.0.0.0. */
    public String metricsBind() {
        return this.metricsBind;
    }

    public int metricsPort() {
        return this.metricsPort;
    }

    /** Seconds between periodic stats log lines; 0 disables. */
    public int statsLogIntervalSeconds() {
        return this.statsLogIntervalSeconds;
    }

    @Override
    public String toString() {
        return "RocksMcConfig{backend=" + this.backend
            + ", minBlobSize=" + this.minBlobSize
            + ", syncWrites=" + this.syncWrites
            + ", verifyOnRead=" + this.verifyOnRead
            + ", allowBlankStart=" + this.allowBlankStart
            + ", maxBackgroundJobs=" + this.maxBackgroundJobs
            + ", maxSubcompactions=" + this.maxSubcompactions
            + ", writeBufferSize=" + this.writeBufferSize
            + ", maxWriteBufferNumber=" + this.maxWriteBufferNumber
            + ", bytesPerSync=" + this.bytesPerSync
            + ", blockCacheSize=" + this.blockCacheSize
            + ", level0SlowdownTrigger=" + this.level0SlowdownTrigger
            + ", metrics=" + (this.metricsEnabled
                ? this.metricsBind + ":" + this.metricsPort : "disabled")
            + ", statsLogIntervalSeconds=" + this.statsLogIntervalSeconds + '}';
    }
}
