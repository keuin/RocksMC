package com.keuin.rocksmc;

import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.fabric.api.command.v1.CommandRegistrationCallback;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.LiteralText;
import net.minecraft.text.Text;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Operator commands under {@code /rocksmc}.
 *
 * <h2>Why this exists</h2>
 *
 * <p>Before this the mod had no runtime surface at all. An operator could not take a
 * backup, read a counter, or flush anything without restarting the server, and
 * {@link RocksDatabase#checkpoint} -- the one capability Anvil structurally cannot
 * offer -- was written, tested and unreachable. The periodic log line was the only
 * output, on a default five-minute timer.
 *
 * <h2>What runs where</h2>
 *
 * <p>{@code stats} and {@code dimensions} read atomics and RocksDB properties, so
 * they answer inline on the server thread. {@code flush}, {@code compact} and
 * {@code checkpoint} are dispatched to a single background thread and report when
 * they finish.
 *
 * <p>That split is the important part. A compaction of a real 1.1 GB database is not
 * instant, and {@code flushMemtables} blocks until the flush completes; running
 * either on the server thread would stall every player for its duration. A
 * checkpoint measured 0 ms on that same database because it only creates hard links,
 * but it is grouped with the others because its cost scales with file count rather
 * than being constant, and because a failure must not propagate into the tick loop.
 *
 * <p>One background thread, not a pool: these are all whole-database operations and
 * running two at once would contend inside RocksDB for no gain. A second request
 * while one is running is refused rather than queued, so an operator cannot
 * accidentally stack six compactions by pressing enter repeatedly.
 *
 * <p>Every subcommand requires permission level 4 (server console or an operator at
 * the highest level), because they all either expose world size or consume real IO.
 */
public final class RocksMcCommand {

    /**
     * Runs the blocking subcommands. Created lazily so a server that never uses
     * them pays nothing, and daemon so a stuck compaction cannot hold up JVM exit.
     */
    private static ExecutorService worker;

    /** Guards {@link #worker} and the single-operation-at-a-time rule. */
    private static final Object LOCK = new Object();

    /** Set while a background operation is running, so a second is refused. */
    private static String running;

    /**
     * Subscribes to command-tree construction.
     *
     * <p>Called once from the mod initialiser. The callback then fires every time
     * vanilla builds a command tree, which is what makes this correct where a
     * one-shot registration was not.
     *
     * <h2>⚠️ Why not register directly, or from a mixin on CommandManager</h2>
     *
     * <p>Because the dispatcher is not stable for the life of the server.
     * {@code MinecraftServer.reloadResources} builds a fresh
     * {@code ServerResourceManager}, which constructs a fresh {@code CommandManager}
     * with a fresh {@code CommandDispatcher}. Anything registered into the previous
     * one is discarded, silently and with no log line.
     *
     * <p>This mod previously did register once, from an {@code @Inject} at the tail
     * of {@code CommandManager}'s constructor. It worked in a bare development
     * server and failed on a real one: a mod in the stack triggered a reload after
     * startup, the dispatcher was replaced, and {@code /rocksmc} simply did not
     * exist -- with nothing in the log to say so, because nothing had gone wrong as
     * far as the mixin could tell. The seam was right; registering once was wrong.
     *
     * <p>{@link CommandRegistrationCallback} is the API built for exactly this. Its
     * own implementation injects at the same place, but as an event that re-fires per
     * construction rather than a single registration.
     */
    public static void registerCallback() {
        CommandRegistrationCallback.EVENT.register((dispatcher, dedicated) ->
            register(dispatcher));
    }

    /**
     * Adds {@code /rocksmc} to one dispatcher.
     *
     * <p>Package-private and idempotent per dispatcher: it is invoked once per
     * command-tree build, so it must assume nothing about how many times it runs.
     */
    static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        LiteralArgumentBuilder<ServerCommandSource> root = CommandManager
            .literal("rocksmc")
            .requires(source -> source.hasPermissionLevel(4));

        root.then(CommandManager.literal("stats")
            .executes(context -> stats(context.getSource())));

        root.then(CommandManager.literal("dimensions")
            .executes(context -> dimensions(context.getSource())));

        root.then(CommandManager.literal("flush")
            .executes(context -> background(context.getSource(), "flush", database -> {
                database.flushMemtables();
                return "flushed memtables to SST files";
            })));

        root.then(CommandManager.literal("compact")
            .executes(context -> background(context.getSource(), "compact", database -> {
                long before = liveBytes(database);
                database.compact();
                long after = liveBytes(database);
                return String.format(Locale.ROOT,
                    "compacted: %,d -> %,d bytes on disk", before, after);
            })));

        root.then(CommandManager.literal("checkpoint")
            .executes(context -> background(context.getSource(), "checkpoint",
                database -> checkpoint(database, null)))
            .then(CommandManager.argument("name", StringArgumentType.word())
                .executes(context -> {
                    String name = StringArgumentType.getString(context, "name");
                    return background(context.getSource(), "checkpoint",
                        database -> checkpoint(database, name));
                })));

        root.then(CommandManager.literal("checkpoints")
            .executes(context -> listCheckpoints(context.getSource())));

        dispatcher.register(root);
        // Logged on every command-tree build, not just the first. The previous bug
        // was undiagnosable precisely because success was silent: the log showed
        // neither a registration nor a failure, so there was no way to tell "did not
        // run" from "ran into a dispatcher that was later thrown away".
        RocksMc.logger().info("rocksmc: registered /rocksmc ({} subcommands)",
            subcommands().size());
    }

    /**
     * Lists existing checkpoints, marking which are automatic.
     *
     * <p>The companion to taking them: without this an operator has to go to the
     * filesystem to find out what they can roll back to, and cannot tell which are
     * subject to retention.
     */
    private static int listCheckpoints(ServerCommandSource source) {
        List<RocksDatabase> databases = StoreRegistry.databases();
        if (databases.isEmpty()) {
            source.sendError(text("rocksmc: no database open."));
            return 0;
        }
        for (RocksDatabase database : databases) {
            List<File> checkpoints = CheckpointScheduler.list(database);
            if (checkpoints.isEmpty()) {
                source.sendFeedback(text(database.name()
                    + ": no checkpoints. Take one with /rocksmc checkpoint"), false);
                continue;
            }
            source.sendFeedback(text(database.name() + ": " + checkpoints.size()
                + " checkpoint(s), oldest first"), false);
            for (File checkpoint : checkpoints) {
                boolean automatic = checkpoint.getName()
                    .startsWith(CheckpointScheduler.AUTOMATIC_PREFIX);
                // A shallow listing, not a recursive stat walk. Summing bytes means a
                // stat of every file in every checkpoint, on the server thread:
                // harmless at the default retention of six, but retention is tunable
                // to 10,000, manual checkpoints are never pruned, and the file count
                // grows with world size -- so it scales into tens of thousands of
                // syscalls on the tick loop, in a diagnostic an operator reaches for
                // during an incident.
                //
                // No real information is lost. The files are hard links, so a
                // checkpoint's apparent size is roughly the live database's and says
                // nothing about the space it actually pins.
                String[] entries = checkpoint.list();
                source.sendFeedback(text(String.format(Locale.ROOT,
                    "  %-28s %5s files %s",
                    checkpoint.getName(),
                    entries == null ? "?" : String.valueOf(entries.length),
                    automatic ? "(automatic, subject to retention)" : "(manual, kept)")),
                    false);
            }
            // Stated every time, because "instant backup" is the wrong mental model and
            // a dangerous one to act on.
            source.sendFeedback(text("  hard-linked: protects against corruption and "
                + "bad deploys, NOT against losing the drive"), false);
        }
        return 1;
    }

    /** How long shutdown waits for an in-flight maintenance operation. */
    private static final int SHUTDOWN_WAIT_SECONDS = 30;

    /**
     * Stops the background worker, waiting for an operation in flight.
     *
     * <p>Called from the mod's shutdown hook, and it has to be. JVM shutdown hooks run
     * <em>concurrently</em> with no ordering guarantee, so vanilla's hook can complete
     * its world save -- releasing the last reference and closing the native handle --
     * while a {@code /rocksmc compact} issued moments earlier is still running here.
     * RocksDB's Java API does not guard against that: {@code compactRange},
     * {@code flush} and {@code getLongProperty} pass the raw native handle straight to
     * JNI without checking {@code isOwningHandle()}, so the result is a dangling
     * pointer rather than an exception -- a JVM crash during shutdown, or a write
     * against freed memory, non-deterministic and very hard to attribute.
     *
     * <p>A bounded wait rather than an indefinite one: a compaction of a large database
     * genuinely takes a while, but a shutdown that never finishes is worse than one
     * that gives up. On timeout it says so, and {@code RocksDatabase}'s own closed
     * check is the second line of defence.
     */
    static void shutdown() {
        ExecutorService pending;
        String inFlight;
        synchronized (LOCK) {
            pending = worker;
            inFlight = running;
            worker = null;
        }
        if (pending == null) {
            return;
        }
        if (inFlight != null) {
            RocksMc.logger().info("rocksmc: waiting for {} to finish before shutdown",
                inFlight);
        }
        pending.shutdown();
        try {
            if (!pending.awaitTermination(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS)) {
                RocksMc.logger().warn("rocksmc: {} did not finish within {}s; "
                    + "abandoning it. The database refuses further operations from it "
                    + "rather than touching a released handle.",
                    inFlight == null ? "a maintenance operation" : inFlight,
                    SHUTDOWN_WAIT_SECONDS);
                pending.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pending.shutdownNow();
        }
    }

    // ------------------------------------------------------------------ inline

    /**
     * Reports per-store IO and per-database state.
     *
     * <p>Deliberately mirrors the three metric scopes rather than flattening them:
     * conflating a shared value with a per-dimension one is exactly the mistake that
     * made the old dashboard read six times high.
     */
    private static int stats(ServerCommandSource source) {
        List<RocksDatabase> databases = StoreRegistry.databases();
        if (databases.isEmpty()) {
            source.sendError(text("rocksmc: no database open. Backend is "
                + RocksMc.config().backendName() + "."));
            return 0;
        }

        for (RocksDatabase database : databases) {
            RocksDatabase.Snapshot d = database.snapshot();
            source.sendFeedback(text(String.format(Locale.ROOT,
                "%s  stores=%d  blob=%s  blockCache=%s",
                d.database, d.openStores, bytes(d.blobFileBytes),
                bytes(d.blockCacheBytes))), false);

            for (RocksDatabase.ColumnFamilySnapshot cf : d.columnFamilies) {
                source.sendFeedback(text(String.format(Locale.ROOT,
                    "  cf %-5s sst=%s keys~%,d memtable=%s pendingCompaction=%s",
                    cf.columnFamily, bytes(cf.liveSstBytes), cf.estimatedKeys,
                    bytes(cf.memtableBytes), bytes(cf.pendingCompactionBytes))), false);
            }

            // Surfaced explicitly rather than left in the numbers: these are the two
            // states that actually cause tick lag.
            if (d.writeStopped > 0) {
                source.sendError(text("  WRITES STOPPED -- compaction cannot keep up"));
            } else if (d.delayedWriteRate > 0) {
                source.sendError(text(String.format(Locale.ROOT,
                    "  writes throttled to %,d B/s", d.delayedWriteRate)));
            }
        }

        long reads = 0;
        long writes = 0;
        long failures = 0;
        for (RocksChunkStore store : StoreRegistry.stores()) {
            RocksChunkStore.Snapshot s = store.snapshot();
            source.sendFeedback(text(String.format(Locale.ROOT,
                "  %-24s %-6s reads=%,d writes=%,d",
                s.dimension, s.leaf, s.reads, s.writes)), false);
            reads += s.reads;
            writes += s.writes;
            failures += s.readFailures + s.writeFailures + s.verifyFailures;
        }
        source.sendFeedback(text(String.format(Locale.ROOT,
            "total reads=%,d writes=%,d failures=%,d", reads, writes, failures)), false);
        if (failures > 0) {
            source.sendError(text("FAILURES PRESENT -- see the server log; "
                + "any non-zero value means the storage layer is not doing its job"));
        }
        return 1;
    }

    /**
     * Lists the persisted dimension-to-ordinal mapping.
     *
     * <p>Worth exposing because the ordinal is the only thing separating one
     * dimension's chunks from another's inside a shared column family, so an
     * operator debugging a custom-dimension problem needs to see it.
     */
    private static int dimensions(ServerCommandSource source) {
        List<RocksDatabase> databases = StoreRegistry.databases();
        if (databases.isEmpty()) {
            source.sendError(text("rocksmc: no database open."));
            return 0;
        }
        for (RocksDatabase database : databases) {
            source.sendFeedback(text(database.name() + ':'), false);
            Map<String, Integer> byName =
                new TreeMap<>(database.dimensionRegistry().snapshot());
            for (Map.Entry<String, Integer> e : byName.entrySet()) {
                source.sendFeedback(text(String.format(Locale.ROOT,
                    "  %-4d %s", e.getValue(), e.getKey())), false);
            }
        }
        return 1;
    }

    // -------------------------------------------------------------- background

    /** A whole-database operation that may block, returning a summary line. */
    private interface DatabaseTask {
        String run(RocksDatabase database) throws IOException;
    }

    /**
     * Runs {@code task} against every open database off the server thread.
     *
     * <p>Refuses if another operation is already running rather than queueing, so a
     * repeated command cannot stack work. Reports to the console rather than the
     * original source: by the time a compaction finishes the player may have
     * disconnected, and {@code ServerCommandSource} is not safe to use from another
     * thread.
     */
    private static int background(ServerCommandSource source, String name,
            DatabaseTask task) {
        List<RocksDatabase> databases = StoreRegistry.databases();
        if (databases.isEmpty()) {
            source.sendError(text("rocksmc: no database open."));
            return 0;
        }

        synchronized (LOCK) {
            if (running != null) {
                source.sendError(text("rocksmc: " + running + " is already running. "
                    + "Wait for it to finish; these are whole-database operations and "
                    + "running two at once only makes both slower."));
                return 0;
            }
            running = name;
            if (worker == null) {
                worker = Executors.newSingleThreadExecutor(runnable -> {
                    Thread t = new Thread(runnable, "rocksmc-command");
                    t.setDaemon(true);
                    return t;
                });
            }
            worker.submit(() -> runTask(name, task, databases));
        }

        source.sendFeedback(text("rocksmc: " + name + " started in the background; "
            + "progress goes to the server log."), true);
        return 1;
    }

    private static void runTask(String name, DatabaseTask task,
            List<RocksDatabase> databases) {
        try {
            for (RocksDatabase database : databases) {
                long start = System.nanoTime();
                try {
                    String summary = task.run(database);
                    RocksMc.logger().info("rocksmc: {} on {} finished in {} ms -- {}",
                        name, database.name(),
                        (System.nanoTime() - start) / 1_000_000L, summary);
                } catch (IOException | RuntimeException e) {
                    // Logged, never rethrown: this runs on a background thread whose
                    // uncaught exception would be invisible, and a failed maintenance
                    // operation must not look like success.
                    RocksMc.logger().error("rocksmc: {} on {} FAILED",
                        name, database.name(), e);
                }
            }
        } finally {
            synchronized (LOCK) {
                running = null;
            }
        }
    }

    /**
     * Creates a checkpoint under {@code <world>/rocksmc-checkpoints/}.
     *
     * <p>Hard-link based, so it is near-instant and costs almost no space -- measured
     * at 0 ms on a real 1.1 GB database. Unlike a filesystem snapshot of a live Anvil
     * world it is consistent by construction and needs no server pause.
     *
     * <p><b>Not a substitute for an off-device backup.</b> The links share blocks with
     * the live database, so this protects against logical corruption and bad deploys,
     * not against losing the drive.
     */
    private static String checkpoint(RocksDatabase database, String name)
            throws IOException {
        File target = CheckpointScheduler.create(database, name);
        // Manual checkpoints are never pruned, so retention is not applied here --
        // see CheckpointScheduler.prune. An operator naming one by hand means to keep
        // it, and losing a deliberate 'before-upgrade' to the timer would be exactly
        // the wrong failure.
        return "checkpoint at " + target + " ("
            + CheckpointScheduler.formatBytes(CheckpointScheduler.directoryBytes(target))
            + ", hard-linked so it shares blocks with the live database -- "
            + "not an off-device backup)";
    }

    // ------------------------------------------------------------------ output

    private static Text text(String message) {
        return new LiteralText(message);
    }

    /** Live on-disk bytes across every column family, plus blob files. */
    private static long liveBytes(RocksDatabase database) {
        long total = Math.max(0L, database.blobFileBytes());
        for (String cf : RocksDatabase.dataColumnFamilies()) {
            total += Math.max(0L, database.longProperty(cf, "rocksdb.live-sst-files-size"));
        }
        return total;
    }

    /** Human-readable bytes; a raw count of blob bytes is unreadable in chat. */
    static String bytes(long value) {
        return CheckpointScheduler.formatBytes(value);
    }

    /** Visible for tests: the subcommands a build offers. */
    static List<String> subcommands() {
        List<String> out = new ArrayList<>();
        out.add("stats");
        out.add("dimensions");
        out.add("flush");
        out.add("compact");
        out.add("checkpoint");
        out.add("checkpoints");
        return out;
    }

    private RocksMcCommand() {
    }
}
