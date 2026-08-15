package com.keuin.rocksmc;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

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

    /**
     * Every key this build understands.
     *
     * <p>Kept as data so {@link #warnAboutUnknownKeys} can tell an operator that
     * {@code max-background-job} is not a setting. A silently ignored typo in a
     * tuning key is invisible: the value simply has no effect and the operator
     * concludes the setting does not work.
     */
    private static final Set<String> KNOWN_KEYS = new LinkedHashSet<>(Arrays.asList(
        "backend",
        "min-blob-size",
        "sync-writes",
        "verify-on-read",
        "allow-blank-start",
        "max-background-jobs",
        "max-subcompactions",
        "write-buffer-size",
        "max-write-buffer-number",
        "bytes-per-sync",
        "block-cache-size",
        "level0-slowdown-writes-trigger",
        "level0-stop-writes-trigger",
        "max-open-files",
        "max-total-wal-size",
        "max-allowed-space-bytes",
        "disk-space-warning-bytes",
        "max-log-file-size",
        "keep-log-file-num",
        "metrics-enabled",
        "metrics-bind",
        "metrics-port",
        "stats-log-interval-seconds",
        "checkpoint-interval-minutes",
        "checkpoint-keep"));

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
    private final int level0StopTrigger;
    private final int maxOpenFiles;
    private final long maxTotalWalSize;
    private final long maxAllowedSpaceBytes;
    private final long diskSpaceWarningBytes;
    private final long maxLogFileSize;
    private final int keepLogFileNum;

    private final boolean metricsEnabled;
    private final String metricsBind;
    private final int metricsPort;
    private final int statsLogIntervalSeconds;

    private final int checkpointIntervalMinutes;
    private final int checkpointKeep;

    /**
     * Mutable staging for parsing, so the immutable config needs no 21-argument
     * constructor. Package-private fields rather than setters: this exists only to
     * carry values between {@link #of} and the constructor.
     */
    private static final class Values {
        Backend backend = Backend.ANVIL;
        long minBlobSize = 1024L;
        boolean syncWrites;
        boolean verifyOnRead;
        boolean allowBlankStart;
        int maxBackgroundJobs = 8;
        int maxSubcompactions = 4;
        long writeBufferSize = 128L * 1024 * 1024;
        int maxWriteBufferNumber = 4;
        long bytesPerSync = 1024L * 1024;
        long blockCacheSize = 512L * 1024 * 1024;
        int level0SlowdownTrigger = 20;
        int level0StopTrigger = 36;
        int maxOpenFiles = -1;
        long maxTotalWalSize;
        long maxAllowedSpaceBytes;
        long diskSpaceWarningBytes = 2L * 1024 * 1024 * 1024;
        long maxLogFileSize = 64L * 1024 * 1024;
        int keepLogFileNum = 4;
        boolean metricsEnabled;
        String metricsBind = "127.0.0.1";
        int metricsPort = 9940;
        int statsLogIntervalSeconds = 300;
        int checkpointIntervalMinutes;
        int checkpointKeep = 6;
    }

    private RocksMcConfig(Values v) {
        this.backend = v.backend;
        this.minBlobSize = v.minBlobSize;
        this.syncWrites = v.syncWrites;
        this.verifyOnRead = v.verifyOnRead;
        this.allowBlankStart = v.allowBlankStart;
        this.maxBackgroundJobs = v.maxBackgroundJobs;
        this.maxSubcompactions = v.maxSubcompactions;
        this.writeBufferSize = v.writeBufferSize;
        this.maxWriteBufferNumber = v.maxWriteBufferNumber;
        this.bytesPerSync = v.bytesPerSync;
        this.blockCacheSize = v.blockCacheSize;
        this.level0SlowdownTrigger = v.level0SlowdownTrigger;
        this.level0StopTrigger = v.level0StopTrigger;
        this.maxOpenFiles = v.maxOpenFiles;
        this.maxTotalWalSize = v.maxTotalWalSize;
        this.maxAllowedSpaceBytes = v.maxAllowedSpaceBytes;
        this.diskSpaceWarningBytes = v.diskSpaceWarningBytes;
        this.maxLogFileSize = v.maxLogFileSize;
        this.keepLogFileNum = v.keepLogFileNum;
        this.metricsEnabled = v.metricsEnabled;
        this.metricsBind = v.metricsBind;
        this.metricsPort = v.metricsPort;
        this.statsLogIntervalSeconds = v.statsLogIntervalSeconds;
        this.checkpointIntervalMinutes = v.checkpointIntervalMinutes;
        this.checkpointKeep = v.checkpointKeep;
    }

    /** A snapshot of this config's values, for making a modified copy. */
    private Values values() {
        Values v = new Values();
        v.backend = this.backend;
        v.minBlobSize = this.minBlobSize;
        v.syncWrites = this.syncWrites;
        v.verifyOnRead = this.verifyOnRead;
        v.allowBlankStart = this.allowBlankStart;
        v.maxBackgroundJobs = this.maxBackgroundJobs;
        v.maxSubcompactions = this.maxSubcompactions;
        v.writeBufferSize = this.writeBufferSize;
        v.maxWriteBufferNumber = this.maxWriteBufferNumber;
        v.bytesPerSync = this.bytesPerSync;
        v.blockCacheSize = this.blockCacheSize;
        v.level0SlowdownTrigger = this.level0SlowdownTrigger;
        v.level0StopTrigger = this.level0StopTrigger;
        v.maxOpenFiles = this.maxOpenFiles;
        v.maxTotalWalSize = this.maxTotalWalSize;
        v.maxAllowedSpaceBytes = this.maxAllowedSpaceBytes;
        v.diskSpaceWarningBytes = this.diskSpaceWarningBytes;
        v.maxLogFileSize = this.maxLogFileSize;
        v.keepLogFileNum = this.keepLogFileNum;
        v.metricsEnabled = this.metricsEnabled;
        v.metricsBind = this.metricsBind;
        v.metricsPort = this.metricsPort;
        v.statsLogIntervalSeconds = this.statsLogIntervalSeconds;
        v.checkpointIntervalMinutes = this.checkpointIntervalMinutes;
        v.checkpointKeep = this.checkpointKeep;
        return v;
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
        Values v = values();
        v.verifyOnRead = value;
        return new RocksMcConfig(v);
    }

    /**
     * Parses a configuration, rejecting what it cannot honour.
     *
     * <h2>Why this is strict now</h2>
     *
     * <p>Every unparseable value used to fall back to its default in silence. That
     * is the wrong trade for storage configuration, and one case was outright
     * dangerous: {@code backend=rocksb} became {@code ANVIL}, after which the mod
     * logged the cheerful "Backend is 'anvil' (vanilla)" line and an operator who
     * believed they had migrated was running vanilla against stale region files.
     *
     * <p>So an unusable {@code backend} now throws, and everything else warns loudly
     * and continues. The asymmetry is deliberate: getting the backend wrong changes
     * which data the server reads, whereas getting a tuning number wrong only makes
     * it slower.
     *
     * @throws IllegalArgumentException if {@code backend} is present but unusable
     */
    public static RocksMcConfig of(Properties props) {
        Values v = new Values();

        String rawBackend = props.getProperty("backend");
        if (rawBackend != null && !rawBackend.trim().isEmpty()) {
            try {
                v.backend = Backend.valueOf(rawBackend.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("rocksmc: backend=" + rawBackend.trim()
                    + " is not a valid backend. Use 'anvil' or 'rocksdb'. Refusing to "
                    + "guess, because silently choosing anvil on a world already "
                    + "migrated to RocksDB would serve stale .mca data.");
            }
        }

        v.minBlobSize = parseLong(props, "min-blob-size", v.minBlobSize, 0L, Long.MAX_VALUE);
        v.syncWrites = parseBool(props, "sync-writes", v.syncWrites);
        v.verifyOnRead = parseBool(props, "verify-on-read", v.verifyOnRead);
        v.allowBlankStart = parseBool(props, "allow-blank-start", v.allowBlankStart);

        v.maxBackgroundJobs = parseInt(props, "max-background-jobs",
            v.maxBackgroundJobs, 1, 256);
        v.maxSubcompactions = parseInt(props, "max-subcompactions",
            v.maxSubcompactions, 1, 256);
        v.writeBufferSize = parseLong(props, "write-buffer-size",
            v.writeBufferSize, 64L * 1024, Long.MAX_VALUE);
        v.maxWriteBufferNumber = parseInt(props, "max-write-buffer-number",
            v.maxWriteBufferNumber, 2, 256);
        v.bytesPerSync = parseLong(props, "bytes-per-sync", v.bytesPerSync, 0L, Long.MAX_VALUE);
        v.blockCacheSize = parseLong(props, "block-cache-size",
            v.blockCacheSize, 1024L * 1024, Long.MAX_VALUE);
        v.level0SlowdownTrigger = parseInt(props, "level0-slowdown-writes-trigger",
            v.level0SlowdownTrigger, 1, 1_000_000);
        v.level0StopTrigger = parseInt(props, "level0-stop-writes-trigger",
            v.level0StopTrigger, 1, 1_000_000);
        v.maxOpenFiles = parseInt(props, "max-open-files", v.maxOpenFiles, -1, 1_000_000);
        v.maxTotalWalSize = parseLong(props, "max-total-wal-size",
            v.maxTotalWalSize, 0L, Long.MAX_VALUE);
        v.maxAllowedSpaceBytes = parseLong(props, "max-allowed-space-bytes",
            v.maxAllowedSpaceBytes, 0L, Long.MAX_VALUE);
        v.diskSpaceWarningBytes = parseLong(props, "disk-space-warning-bytes",
            v.diskSpaceWarningBytes, 0L, Long.MAX_VALUE);
        v.maxLogFileSize = parseLong(props, "max-log-file-size",
            v.maxLogFileSize, 0L, Long.MAX_VALUE);
        v.keepLogFileNum = parseInt(props, "keep-log-file-num", v.keepLogFileNum, 1, 100_000);

        v.metricsEnabled = parseBool(props, "metrics-enabled", v.metricsEnabled);
        v.metricsBind = props.getProperty("metrics-bind", v.metricsBind).trim();
        v.metricsPort = parseInt(props, "metrics-port", v.metricsPort, 1, 65535);
        v.statsLogIntervalSeconds = parseInt(props, "stats-log-interval-seconds",
            v.statsLogIntervalSeconds, 0, Integer.MAX_VALUE);

        v.checkpointIntervalMinutes = parseInt(props, "checkpoint-interval-minutes",
            v.checkpointIntervalMinutes, 0, Integer.MAX_VALUE);
        v.checkpointKeep = parseInt(props, "checkpoint-keep", v.checkpointKeep, 1, 10_000);

        // Throttling must begin before it stops, or RocksDB never gets the chance to
        // slow writes gently and jumps straight to a full stall. Easy to get wrong by
        // raising only the slowdown trigger, which the docs actively suggest doing.
        if (v.level0SlowdownTrigger > v.level0StopTrigger) {
            warn("level0-slowdown-writes-trigger (" + v.level0SlowdownTrigger
                + ") exceeds level0-stop-writes-trigger (" + v.level0StopTrigger
                + "), which would stall writes without throttling first. Raising the "
                + "stop trigger to match.");
            v.level0StopTrigger = v.level0SlowdownTrigger;
        }

        warnAboutUnknownKeys(props);
        return new RocksMcConfig(v);
    }

    /**
     * Warns about keys this build does not read.
     *
     * <p>A misspelled tuning key is otherwise invisible: the value has no effect and
     * the operator concludes the setting is broken rather than misspelled.
     */
    private static void warnAboutUnknownKeys(Properties props) {
        List<String> unknown = new ArrayList<>();
        for (String name : props.stringPropertyNames()) {
            if (!KNOWN_KEYS.contains(name)) {
                unknown.add(name);
            }
        }
        if (!unknown.isEmpty()) {
            Collections.sort(unknown);
            warn("ignoring unrecognised setting(s): " + String.join(", ", unknown)
                + ". Valid keys: " + String.join(", ", KNOWN_KEYS));
        }
    }

    /**
     * Parses a long, warning rather than silently defaulting.
     *
     * <p>Also range-checks: these values go straight into RocksDB's native options,
     * where a zero block cache or a negative thread count is at best rejected deep in
     * JNI and at worst accepted with surprising behaviour.
     */
    private static long parseLong(Properties props, String key, long fallback,
            long min, long max) {
        String s = props.getProperty(key);
        if (s == null || s.trim().isEmpty()) {
            return fallback;
        }
        long parsed;
        try {
            parsed = Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            warn(key + '=' + s.trim() + " is not a number (note: suffixes like 'M' or "
                + "'GiB' are not supported, give plain bytes). Using " + fallback + '.');
            return fallback;
        }
        if (parsed < min || parsed > max) {
            warn(key + '=' + parsed + " is outside the usable range [" + min + ", "
                + max + "]. Using " + fallback + '.');
            return fallback;
        }
        return parsed;
    }

    /**
     * Parses an int with the same strictness.
     *
     * <p>Bounds are checked as a {@code long} before narrowing, so a value above
     * {@code Integer.MAX_VALUE} is reported rather than silently wrapping -- the old
     * cast turned {@code max-background-jobs=4294967304} into {@code 8}.
     */
    private static int parseInt(Properties props, String key, int fallback,
            int min, int max) {
        return (int) parseLong(props, key, fallback, min, max);
    }

    /**
     * Parses a boolean, warning on anything that is not clearly true or false.
     *
     * <p>{@code Boolean.parseBoolean} maps every unrecognised string to {@code false},
     * so {@code sync-writes=1} and {@code sync-writes=yes} both silently meant
     * {@code false} -- the opposite of what the operator wrote.
     */
    private static boolean parseBool(Properties props, String key, boolean fallback) {
        String s = props.getProperty(key);
        if (s == null || s.trim().isEmpty()) {
            return fallback;
        }
        String value = s.trim().toLowerCase(Locale.ROOT);
        if ("true".equals(value)) {
            return true;
        }
        if ("false".equals(value)) {
            return false;
        }
        warn(key + '=' + s.trim() + " is not a boolean; use 'true' or 'false'. Using "
            + fallback + '.');
        return fallback;
    }

    /**
     * Reports a configuration problem.
     *
     * <p>Goes through the mod logger when one is available and falls back to stderr,
     * because {@link #of} is also called by the standalone importer and fidelity
     * tools, which run without log4j configured.
     */
    private static void warn(String message) {
        try {
            RocksMc.logger().warn("rocksmc config: {}", message);
        } catch (RuntimeException | LinkageError e) {
            System.err.println("rocksmc config: " + message);
        }
    }

    public Backend backend() {
        return this.backend;
    }

    /** The backend's name in lower case, for messages an operator reads. */
    public String backendName() {
        return this.backend.name().toLowerCase(Locale.ROOT);
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

    /**
     * L0 file count at which writes stop entirely.
     *
     * <p>Exposed because it must stay at or above {@link #level0SlowdownTrigger()};
     * raising only the slowdown trigger past this would skip throttling and jump
     * straight to a stall. {@link #of} corrects that combination rather than letting
     * it through.
     */
    public int level0StopTrigger() {
        return this.level0StopTrigger;
    }

    /**
     * Cap on simultaneously open SST and blob files, or {@code -1} for unlimited.
     *
     * <p>RocksDB's own default is unlimited, which is fine on a dedicated host and a
     * slow leak toward {@code RLIMIT_NOFILE} on a shared one: a large world's blob
     * files accumulate, and the process dies days later with "too many open files"
     * nowhere near the change that caused it. Left unlimited by default so behaviour
     * does not change silently, but now settable.
     */
    public int maxOpenFiles() {
        return this.maxOpenFiles;
    }

    /**
     * Total WAL bytes before RocksDB forces a memtable flush, or 0 for its default.
     *
     * <p>Matters because {@code sync-writes=false} makes the WAL the durability
     * mechanism, and an idle-but-open world can otherwise hold a large WAL
     * indefinitely. Setting this bounds both recovery time and disk use.
     */
    public long maxTotalWalSize() {
        return this.maxTotalWalSize;
    }

    /**
     * Hard cap on SST bytes, or 0 for no cap.
     *
     * <p>The only pre-emptive defence against filling the disk, and worth more than it
     * looks. When RocksDB hits ENOSPC it latches a background error and refuses every
     * subsequent write; RocksJava exposes no {@code DB::Resume()}, so <b>freeing space
     * does not recover it</b> -- the database stays effectively read-only until the
     * server restarts, while the server keeps running and silently persists nothing.
     * Every world modification after the fill is then lost, with no crash to mark the
     * boundary.
     *
     * <p>Failing writes at a configured ceiling instead turns that into a bounded,
     * reported condition while there is still room to compact and react. Off by
     * default because a sensible value depends on the volume, not on the mod.
     */
    public long maxAllowedSpaceBytes() {
        return this.maxAllowedSpaceBytes;
    }

    /**
     * Free-space threshold below which the mod warns, or 0 to disable.
     *
     * <p>A warning, not a limit: it exists to give an operator notice before the
     * unrecoverable condition above, since the interesting moment is well before the
     * disk is actually full. Defaults to 2 GiB.
     */
    public long diskSpaceWarningBytes() {
        return this.diskSpaceWarningBytes;
    }

    /**
     * Size at which RocksDB rotates its own {@code LOG}.
     *
     * <p>RocksDB's default is 0, meaning never rotate by size -- the file only rolls
     * when the database is reopened. With stats dumps every 600 s that is roughly
     * 2.5 MB/day inside the world directory, in one file, invisible to every size
     * metric this mod exposes, and unbounded on a server that never restarts.
     */
    public long maxLogFileSize() {
        return this.maxLogFileSize;
    }

    /** How many rotated RocksDB {@code LOG} files to keep. */
    public int keepLogFileNum() {
        return this.keepLogFileNum;
    }

    /**
     * Minutes between automatic checkpoints, or 0 to disable.
     *
     * <p>Checkpoints are hard-link based: measured at 0 ms on a real 1.1 GB database,
     * sharing blocks with the live files. That makes frequent ones affordable, but
     * see {@link #checkpointKeep()} -- they pin the SST and blob files they reference,
     * so space is only reclaimed once a checkpoint is deleted.
     */
    public int checkpointIntervalMinutes() {
        return this.checkpointIntervalMinutes;
    }

    /**
     * How many automatic checkpoints to retain; the oldest are pruned.
     *
     * <p>Retention is not optional. A checkpoint hard-links the files live at the
     * time, so obsolete SSTs and blobs cannot be reclaimed while any checkpoint still
     * references them. Keeping them forever converts "near-free" into unbounded growth
     * as compaction rewrites data.
     */
    public int checkpointKeep() {
        return this.checkpointKeep;
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
            + ", level0StopTrigger=" + this.level0StopTrigger
            + ", maxOpenFiles=" + this.maxOpenFiles
            + ", maxTotalWalSize=" + this.maxTotalWalSize
            + ", checkpoints=" + (this.checkpointIntervalMinutes > 0
                ? "every " + this.checkpointIntervalMinutes + "min keep "
                    + this.checkpointKeep
                : "disabled")
            + ", metrics=" + (this.metricsEnabled
                ? this.metricsBind + ":" + this.metricsPort : "disabled")
            + ", statsLogIntervalSeconds=" + this.statsLogIntervalSeconds + '}';
    }
}
