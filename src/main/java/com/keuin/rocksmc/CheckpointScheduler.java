package com.keuin.rocksmc;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Takes and prunes RocksDB checkpoints, on a timer and on demand.
 *
 * <h2>What a checkpoint is, and is not</h2>
 *
 * <p>RocksDB creates a checkpoint by hard-linking the SST and blob files that are
 * live at that instant and writing a fresh manifest. Measured on a real 1.1 GB
 * database it takes <b>4 ms</b> and the links share blocks with the original, so it
 * costs neither time nor space worth budgeting for. It is consistent by construction
 * and needs no server pause, which is what a filesystem snapshot of a live Anvil
 * world cannot offer -- that captures whatever was on disk, possibly a torn 8 KiB
 * region header, with no write-ahead log to recover from.
 *
 * <p>⚠️ <b>It is not an off-device backup.</b> Hard links share blocks with the live
 * database, so a checkpoint protects against logical corruption, a bad deploy or a
 * mistaken command -- not against losing the drive. That distinction is stated
 * wherever checkpoints are surfaced, because "instant backup" is the wrong mental
 * model and would be a dangerous one.
 *
 * <p>⚠️ <b>Checkpoints pin disk space.</b> A checkpoint holds references to the files
 * live when it was taken, so once compaction rewrites that data the old files cannot
 * be reclaimed while any checkpoint still references them. Space therefore grows with
 * retention times write volume, not with checkpoint count alone. This is why
 * retention is mandatory rather than optional, and why the default keeps six.
 *
 * <h2>Scheduling</h2>
 *
 * <p>A single daemon thread, shared across every open database. Checkpoints are cheap
 * but not free, and taking several concurrently would contend inside RocksDB for no
 * benefit.
 *
 * <p>The timer is deliberately independent of the autosave boundary. Vanilla's
 * autosave is already the point at which the WAL is synced by the storage seam, but
 * hooking the schedule to it would tie checkpoint frequency to a server setting that
 * has nothing to do with backup policy. A checkpoint is valid at any instant because
 * RocksDB guarantees consistency at the point it is taken, not at a save boundary --
 * the shared WAL from the one-database-per-world consolidation is what makes that
 * true across dimensions.
 */
public final class CheckpointScheduler {

    /** Timestamped names, sortable as plain strings so retention can order them. */
    private static final String TIME_FORMAT = "yyyyMMdd-HHmmss";

    /** Where checkpoints live, relative to the world root. */
    public static final String DIRECTORY_NAME = "rocksmc-checkpoints";

    /**
     * Prefix marking a checkpoint as automatic.
     *
     * <p>Retention only ever deletes directories carrying it, so a checkpoint an
     * operator named by hand is never pruned. Losing a deliberately-named
     * {@code before-upgrade} to the retention timer would be exactly the wrong
     * failure.
     */
    static final String AUTOMATIC_PREFIX = "auto-";

    private static final AtomicLong CHECKPOINTS = new AtomicLong();
    private static final AtomicLong FAILURES = new AtomicLong();
    private static final AtomicLong PRUNED = new AtomicLong();
    private static final AtomicLong LAST_SUCCESS_EPOCH_SECONDS = new AtomicLong();
    private static final AtomicLong LAST_DURATION_MILLIS = new AtomicLong(-1L);

    private static ScheduledExecutorService timer;

    /**
     * Starts the timer if {@code checkpoint-interval-minutes} is set.
     *
     * <p>Idempotent: a second call while running is ignored, so a reload cannot end up
     * with two timers writing checkpoints on overlapping schedules.
     */
    public static synchronized void start(RocksMcConfig config) {
        int minutes = config.checkpointIntervalMinutes();
        if (minutes <= 0 || timer != null) {
            return;
        }
        timer = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread t = new Thread(runnable, "rocksmc-checkpoint");
            t.setDaemon(true);
            return t;
        });
        // First run one full interval in, not immediately: a checkpoint taken during
        // startup would capture a world nothing has touched yet, and would race the
        // stores still being opened.
        timer.scheduleAtFixedRate(() -> runScheduled(config),
            minutes, minutes, TimeUnit.MINUTES);
        RocksMc.logger().info("rocksmc: automatic checkpoints every {} min, keeping {}",
            minutes, config.checkpointKeep());
    }

    public static synchronized void stop() {
        if (timer != null) {
            timer.shutdownNow();
            timer = null;
        }
    }

    /**
     * One scheduled pass over every open database.
     *
     * <p>Catches everything, because an exception escaping a
     * {@code scheduleAtFixedRate} task cancels the schedule permanently and silently:
     * checkpoints would simply stop, with no further log line to say so. {@code Error}
     * is included for the same reason.
     *
     * <p>Package-private rather than private so a test can drive one pass directly.
     * Waiting on the real timer would make the test slow and flaky, and testing
     * {@code create} instead would prove nothing about this method -- the reporting that
     * matters lives in the catch below, not in the operation that throws.
     */
    static void runScheduled(RocksMcConfig config) {
        try {
            for (RocksDatabase database : StoreRegistry.databases()) {
                try {
                    File created = create(database, null);
                    RocksMc.logger().info("rocksmc: checkpoint {} ({})",
                        created.getName(), formatBytes(directoryBytes(created)));
                    prune(database, config.checkpointKeep());
                } catch (IOException | RuntimeException e) {
                    FAILURES.incrementAndGet();
                    RocksMc.logger().error("rocksmc: automatic checkpoint of {} failed. "
                        + "Rollback protection is NOT in place for this interval.",
                        database.name(), e);
                    // Told to the operators, not only the log. This is the one failure
                    // whose whole purpose is to be noticed before it is needed: nothing
                    // else in the server behaves differently when a checkpoint is
                    // missing, so a silent failure is discovered at the moment someone
                    // reaches for a rollback that was never taken. FailureReporter's
                    // throttle is right here -- a broken checkpoint will fail again next
                    // interval, and repeating it every few minutes would train people to
                    // ignore it.
                    FailureReporter.report(FailureReporter.Kind.CHECKPOINT_FAILURE,
                        "automatic checkpoint of " + database.name() + " failed ("
                            + e.getClass().getSimpleName()
                            + "); rollback protection is NOT in place");
                }
            }
        } catch (Throwable t) {
            FAILURES.incrementAndGet();
            RocksMc.logger().error("rocksmc: checkpoint timer pass failed", t);
        }
    }

    /**
     * Creates a checkpoint, timestamped when {@code name} is null.
     *
     * @param name explicit name, or {@code null} for an automatic timestamped one
     * @return the directory created
     * @throws IOException if the target exists or RocksDB refuses
     */
    public static File create(RocksDatabase database, String name) throws IOException {
        File parent = new File(database.worldRoot(), DIRECTORY_NAME);
        String label = name != null ? name : AUTOMATIC_PREFIX + timestamp();
        File target = new File(parent, label);

        if (target.exists()) {
            throw new IOException("checkpoint already exists: " + target
                + " (choose another name, or delete it first)");
        }
        // Idempotent, so a scheduled checkpoint and a manual one racing here cannot
        // make the loser fail. See AnvilWriter for the same reasoning.
        try {
            java.nio.file.Files.createDirectories(parent.toPath());
        } catch (IOException e) {
            throw new IOException("could not create " + parent, e);
        }

        long start = System.nanoTime();
        // RocksDB requires the target not to exist; it creates the directory itself.
        database.checkpoint(target);
        LAST_DURATION_MILLIS.set((System.nanoTime() - start) / 1_000_000L);
        CHECKPOINTS.incrementAndGet();
        LAST_SUCCESS_EPOCH_SECONDS.set(System.currentTimeMillis() / 1000L);
        return target;
    }

    /**
     * Deletes the oldest automatic checkpoints beyond {@code keep}.
     *
     * <p>Only automatic ones: a hand-named checkpoint is a deliberate act and is never
     * pruned. Ordering is by name, which is why the timestamp format is
     * lexicographically sortable and in UTC -- a local-time name would reorder across
     * a DST transition and delete the wrong one.
     *
     * @return how many were deleted
     */
    public static int prune(RocksDatabase database, int keep) {
        File parent = new File(database.worldRoot(), DIRECTORY_NAME);
        File[] entries = parent.listFiles(
            (dir, n) -> n.startsWith(AUTOMATIC_PREFIX) && new File(dir, n).isDirectory());
        if (entries == null || entries.length <= keep) {
            return 0;
        }

        List<File> automatic = new ArrayList<>(Arrays.asList(entries));
        Collections.sort(automatic, (a, b) -> a.getName().compareTo(b.getName()));

        int deleted = 0;
        int excess = automatic.size() - keep;
        for (int i = 0; i < excess; i++) {
            File victim = automatic.get(i);
            if (deleteRecursively(victim)) {
                deleted++;
                PRUNED.incrementAndGet();
                RocksMc.logger().info("rocksmc: pruned old checkpoint {}",
                    victim.getName());
            } else {
                // Not fatal, but it means space is not being reclaimed, which is the
                // whole point of retention.
                RocksMc.logger().warn("rocksmc: could not delete old checkpoint {}. "
                    + "It still pins the SST and blob files it references, so disk use "
                    + "will keep growing until it is removed by hand.", victim);
            }
        }
        return deleted;
    }

    /** Existing checkpoints, newest last. Automatic and manual alike. */
    public static List<File> list(RocksDatabase database) {
        File parent = new File(database.worldRoot(), DIRECTORY_NAME);
        File[] entries = parent.listFiles(File::isDirectory);
        List<File> out = new ArrayList<>();
        if (entries != null) {
            out.addAll(Arrays.asList(entries));
            Collections.sort(out, (a, b) -> a.getName().compareTo(b.getName()));
        }
        return out;
    }

    /**
     * A UTC timestamp, sortable as a string.
     *
     * <p>UTC rather than local time so names sort chronologically regardless of host
     * timezone, and so two checkpoints cannot collide during a DST fallback.
     */
    public static String timestamp() {
        SimpleDateFormat format = new SimpleDateFormat(TIME_FORMAT, Locale.ROOT);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date());
    }

    // ---------------------------------------------------------------- metrics

    public static long checkpointCount() {
        return CHECKPOINTS.get();
    }

    public static long failureCount() {
        return FAILURES.get();
    }

    public static long prunedCount() {
        return PRUNED.get();
    }

    /** Epoch seconds of the last successful checkpoint, or 0 if there has been none. */
    public static long lastSuccessEpochSeconds() {
        return LAST_SUCCESS_EPOCH_SECONDS.get();
    }

    /** Duration of the most recent checkpoint in ms, or -1 if there has been none. */
    public static long lastDurationMillis() {
        return LAST_DURATION_MILLIS.get();
    }

    /** Visible for tests: forget accumulated counters between cases. */
    static void resetCountersForTesting() {
        CHECKPOINTS.set(0);
        FAILURES.set(0);
        PRUNED.set(0);
        LAST_SUCCESS_EPOCH_SECONDS.set(0);
        LAST_DURATION_MILLIS.set(-1L);
    }

    // ----------------------------------------------------------------- helpers

    /**
     * Apparent size of a directory tree.
     *
     * <p>Sums file lengths, so for a checkpoint this reports the size of the data it
     * references rather than the blocks it adds -- which are almost none, since the
     * files are hard links. Reported anyway because it tells an operator how much data
     * the checkpoint would restore.
     */
    static long directoryBytes(File dir) {
        File[] files = dir.listFiles();
        if (files == null) {
            return 0L;
        }
        long total = 0L;
        for (File f : files) {
            total += f.isDirectory() ? directoryBytes(f) : f.length();
        }
        return total;
    }

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

    /** Human-readable bytes; a raw count is unreadable in a log line or in chat. */
    public static String formatBytes(long value) {
        if (value < 0) {
            return "n/a";
        }
        if (value < 1024) {
            return value + " B";
        }
        String[] units = {"KiB", "MiB", "GiB", "TiB"};
        double scaled = value / 1024.0;
        int unit = 0;
        while (scaled >= 1024 && unit < units.length - 1) {
            scaled /= 1024.0;
            unit++;
        }
        return String.format(Locale.ROOT, "%.1f %s", scaled, units[unit]);
    }

    private CheckpointScheduler() {
    }
}
