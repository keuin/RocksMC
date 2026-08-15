package com.keuin.rocksmc;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.LiteralText;
import net.minecraft.text.Text;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Reports storage failures the moment they happen.
 *
 * <h2>Why this exists separately from the stats logger</h2>
 *
 * <p>Failures used to surface only through the periodic stats line, which has three
 * problems. It is up to {@code stats-log-interval-seconds} late, so a fault is
 * reported minutes after the write that hit it. It can be switched off -- and is, on
 * the beta server, which runs {@code stats-log-interval-seconds=0} -- leaving
 * Prometheus as the only channel. And it ran on a {@code scheduleAtFixedRate} task
 * that a single escaping {@code Error} would cancel permanently and silently, taking
 * the alerts with it.
 *
 * <p>So alerts are raised from the failing code path instead, synchronously with the
 * failure, independent of any timer or exporter.
 *
 * <h2>Rate limiting</h2>
 *
 * <p>A storage fault is rarely singular: a full disk fails every subsequent write.
 * Unbounded logging would flood the log during precisely the incident when the log
 * matters most, so the first occurrence of each kind reports immediately and repeats
 * are throttled. The suppressed count is included when the next one is emitted, so
 * the volume is still visible.
 *
 * <h2>In-game broadcast</h2>
 *
 * <p>Also sent to online operators, because silent data loss is the failure mode this
 * project has worked hardest to avoid and an operator watching chat should not have to
 * be tailing a log to find out. Limited to permission level 3, matching the commands:
 * the message names paths and failure counts, and ordinary players can neither act on
 * it nor should see it.
 *
 * <p>Level 3 rather than 4 for the same reason the commands use 3. An operator's level
 * comes from their per-player entry in {@code ops.json}, not from
 * {@code op-permission-level}, which is only consulted when {@code /op} runs. A server
 * whose operators sit at 3 would have had these alerts silently reach nobody.
 */
public final class FailureReporter {

    /** Minimum gap between repeats of the same alert kind. */
    private static final long THROTTLE_MILLIS = 60_000L;

    /**
     * The live server, for broadcasting. Set once the server exists.
     *
     * <p>Held as a reference rather than looked up because there is no static
     * accessor for it, and {@code null} until the server is constructed -- alerts
     * raised before then still reach the log.
     */
    private static final AtomicReference<MinecraftServer> SERVER = new AtomicReference<>();

    /** Kinds of alert, each throttled independently. */
    public enum Kind {
        /** A chunk could not be read back. */
        READ_FAILURE,
        /** A chunk could not be written. Data loss unless the caller retries. */
        WRITE_FAILURE,
        /** verify-on-read found stored bytes differing from what was written. */
        VERIFY_FAILURE,
        /** RocksDB has stopped accepting writes; compaction cannot keep up. */
        WRITE_STOPPED,
        /** RocksDB is throttling writes -- the warning before a stop. */
        WRITE_THROTTLED,
        /** Free disk space is low enough to threaten the database. */
        DISK_LOW,
        /** A WAL sync or flush failed. */
        SYNC_FAILURE,
        /** An automatic checkpoint failed. */
        CHECKPOINT_FAILURE
    }

    private static final AtomicLong[] LAST_REPORT = new AtomicLong[Kind.values().length];
    private static final AtomicLong[] SUPPRESSED = new AtomicLong[Kind.values().length];

    static {
        for (int i = 0; i < Kind.values().length; i++) {
            LAST_REPORT[i] = new AtomicLong(0);
            SUPPRESSED[i] = new AtomicLong(0);
        }
    }

    /** Records the server, so alerts can reach operators in game. */
    public static void setServer(MinecraftServer server) {
        SERVER.set(server);
    }

    /**
     * Reports a failure, at most once per throttle window per kind.
     *
     * <p>Never throws: it is called from write and read paths where an exception
     * would turn a reportable fault into a lost chunk or a crashed IO worker.
     *
     * @param kind    what went wrong, throttled independently of other kinds
     * @param message operator-facing detail, already specific
     */
    public static void report(Kind kind, String message) {
        try {
            long now = System.currentTimeMillis();
            AtomicLong last = LAST_REPORT[kind.ordinal()];
            long previous = last.get();

            if (previous != 0 && now - previous < THROTTLE_MILLIS) {
                SUPPRESSED[kind.ordinal()].incrementAndGet();
                return;
            }
            // compareAndSet so two threads failing at once produce one alert, not
            // two. Losing the race means the other thread is reporting right now.
            if (!last.compareAndSet(previous, now)) {
                SUPPRESSED[kind.ordinal()].incrementAndGet();
                return;
            }

            long suppressed = SUPPRESSED[kind.ordinal()].getAndSet(0);
            String suffix = suppressed > 0
                ? " (" + suppressed + " further occurrence(s) suppressed in the last "
                    + (THROTTLE_MILLIS / 1000) + "s)"
                : "";

            RocksMc.logger().error("rocksmc: {}: {}{}", kind, message, suffix);
            broadcast(kind, message + suffix);
        } catch (Throwable t) {
            // A reporting failure must never become the primary failure. Last resort
            // only, because the logger itself may be what broke.
            try {
                System.err.println("rocksmc: failed to report " + kind + ": " + message);
            } catch (Throwable ignored) {
                // Nothing left to try.
            }
        }
    }

    /**
     * Sends an alert to every online player at permission level 3 or above.
     *
     * <p>Scheduled onto the server thread rather than sent directly: {@code report}
     * is called from RocksDB's IO workers and background threads, and touching the
     * player list or the network handler off-thread is not safe.
     */
    private static void broadcast(Kind kind, String message) {
        // A legacy section-code prefix rather than Formatting.RED, because javac
        // cannot read net.minecraft.util.Formatting from the remapped 1.16.5 jar:
        // its enum constructor carries a malformed
        // RuntimeInvisibleParameterAnnotations attribute and compilation fails with
        // "bad class file". The code renders identically and costs no dependency.
        broadcastToOperators("\u00a7c[rocksmc] " + kind + ": " + message);
    }

    /**
     * Sends a fully-formed message to every online operator.
     *
     * <p>Lives here rather than in a class of its own because this is where the server
     * reference is held, and because getting this right is fiddlier than it looks: the
     * caller is usually a background thread, so the send has to be marshalled onto the
     * server thread, and a failure to deliver a message must never propagate into
     * whatever was being reported. Two callers needing that is not a reason to have two
     * copies of it.
     *
     * <p>Takes a String rather than a {@code ServerCommandSource}, deliberately.
     * Holding a source across a long operation is unsafe in two separate ways: a
     * player's source pins the {@code ServerPlayerEntity} and keeps it reachable after
     * they disconnect, and an RCON source writes into a buffer the dedicated server
     * <em>shares between commands</em> and clears at the start of each one -- so a late
     * write can land inside an unrelated command's response.
     */
    static void broadcastToOperators(String message) {
        MinecraftServer server = SERVER.get();
        if (server == null) {
            return;
        }
        Text text = new LiteralText(message);
        server.execute(() -> {
            try {
                for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                    // hasPermissionLevel resolves through the server's own op list, so
                    // it already covers operator status; checking isOperator as well
                    // would be redundant and would disagree with the identical gate
                    // used by the commands themselves. Kept at the same level as those
                    // commands deliberately: whoever can act on an alert should get it.
                    if (player.hasPermissionLevel(RocksMcCommand.PERMISSION_LEVEL)) {
                        player.sendMessage(text, false);
                    }
                }
            } catch (Throwable t) {
                RocksMc.logger().warn("rocksmc: could not broadcast to operators", t);
            }
        });
    }

    /** Visible for tests: forget throttle state between cases. */
    static void resetForTesting() {
        SERVER.set(null);
        for (int i = 0; i < Kind.values().length; i++) {
            LAST_REPORT[i].set(0);
            SUPPRESSED[i].set(0);
        }
    }

    /** Visible for tests: how many reports of this kind were throttled away. */
    static long suppressedCount(Kind kind) {
        return SUPPRESSED[kind.ordinal()].get();
    }

    private FailureReporter() {
    }
}
