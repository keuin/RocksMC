package com.keuin.rocksmc;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests failure reporting and its throttling.
 *
 * <p>The reporter exists because a storage failure previously reached an operator only
 * through the periodic stats line -- up to five minutes late, switched off entirely on
 * the beta server, and killable by a single escaping {@code Error}. So the properties
 * worth pinning are that the first occurrence is never suppressed, that a flood is
 * bounded, that the suppressed count is not silently discarded, and above all that
 * reporting can never itself throw into the write path it is called from.
 */
class FailureReporterTest {

    @BeforeEach
    void reset() {
        FailureReporter.resetForTesting();
    }

    /** The first failure of a kind must always be reported, never throttled. */
    @Test
    void firstOccurrenceIsAlwaysReported() {
        FailureReporter.report(FailureReporter.Kind.WRITE_FAILURE, "first");
        assertEquals(0, FailureReporter.suppressedCount(FailureReporter.Kind.WRITE_FAILURE),
            "the first report must not be counted as suppressed");
    }

    /**
     * Repeats within the window are counted rather than logged.
     *
     * <p>A full disk fails every subsequent write, so unbounded logging would flood the
     * log during exactly the incident when it matters most.
     */
    @Test
    void repeatsWithinTheWindowAreSuppressedAndCounted() {
        FailureReporter.report(FailureReporter.Kind.WRITE_FAILURE, "first");
        for (int i = 0; i < 50; i++) {
            FailureReporter.report(FailureReporter.Kind.WRITE_FAILURE, "flood " + i);
        }
        assertEquals(50, FailureReporter.suppressedCount(FailureReporter.Kind.WRITE_FAILURE),
            "every suppressed repeat must still be counted, so the volume is visible");
    }

    /**
     * Kinds throttle independently.
     *
     * <p>A stream of write failures must not mask the first verify failure, which is a
     * different and more serious diagnosis.
     */
    @Test
    void kindsAreThrottledIndependently() {
        for (int i = 0; i < 10; i++) {
            FailureReporter.report(FailureReporter.Kind.WRITE_FAILURE, "write " + i);
        }
        FailureReporter.report(FailureReporter.Kind.VERIFY_FAILURE, "corruption");

        assertEquals(9, FailureReporter.suppressedCount(FailureReporter.Kind.WRITE_FAILURE));
        assertEquals(0, FailureReporter.suppressedCount(FailureReporter.Kind.VERIFY_FAILURE),
            "a different kind must report immediately rather than be masked");
    }

    /** Every kind must be reportable; a missing case would silently drop alerts. */
    @Test
    void everyKindCanBeReported() {
        for (FailureReporter.Kind kind : FailureReporter.Kind.values()) {
            FailureReporter.report(kind, "probe");
            assertEquals(0, FailureReporter.suppressedCount(kind),
                kind + " did not report on its first occurrence");
        }
    }

    /**
     * Reporting must never throw.
     *
     * <p>It is called from the chunk write path. An exception escaping here would turn
     * a reportable fault into a lost chunk, or kill the IO worker outright -- the
     * reporter would have caused a worse failure than the one it was describing.
     */
    @Test
    void reportingNeverThrows() {
        FailureReporter.report(FailureReporter.Kind.WRITE_FAILURE, null);
        FailureReporter.report(FailureReporter.Kind.READ_FAILURE, "");
        // No server is set in tests, so the broadcast path must no-op rather than NPE.
        FailureReporter.report(FailureReporter.Kind.DISK_LOW, "no server attached");
        assertTrue(true, "reached without throwing");
    }

    /** Concurrent failures must produce one alert, not one per thread. */
    @Test
    void concurrentReportsCollapseToOne() throws Exception {
        int threads = 8;
        // A latch rather than Thread.join(timeout), which returns whether or not the
        // thread finished. Asserting an exact suppressed count while a worker might
        // still be starved -- entirely possible when this runs alongside the RocksDB
        // tests -- made this fail intermittently, which is worse than not testing it at
        // all because it teaches people to re-run red builds.
        CountDownLatch done = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            Thread worker = new Thread(() -> {
                try {
                    FailureReporter.report(FailureReporter.Kind.SYNC_FAILURE, "race");
                } finally {
                    done.countDown();
                }
            });
            worker.setDaemon(true);
            worker.start();
        }

        assertTrue(done.await(60, TimeUnit.SECONDS),
            "workers did not finish; the count below would be meaningless");
        assertEquals(threads - 1,
            FailureReporter.suppressedCount(FailureReporter.Kind.SYNC_FAILURE),
            "exactly one thread should have reported; the rest counted as suppressed");
    }

    /**
     * Broadcasting must not throw when no server is attached.
     *
     * <p>Which is the state during an import, an export, and every unit test. A
     * reporting path that only works on a running server is a reporting path that fails
     * exactly when something is already going wrong.
     */
    @Test
    void broadcastingWithoutAServerIsSilentRatherThanFatal() {
        FailureReporter.broadcastToOperators("\u00a7a[rocksmc] compact finished");
        FailureReporter.broadcastToOperators("");
        // Reaching here without an exception is the assertion.
    }

    /**
     * Every operator-facing message must be routed through this one method.
     *
     * <p>A mechanical check rather than a matter of care, because the failure mode of
     * hand-rolling the broadcast elsewhere is silent: the loop would work on a test
     * server and then deliver from a background thread on a real one, where touching the
     * player list off the server thread is a data race. That is the sort of bug that
     * shows up as an unreproducible crash months later.
     */
    @Test
    void nothingElseIteratesThePlayerListToReachOperators() throws Exception {
        java.io.File sources = new java.io.File("src/main/java/com/keuin/rocksmc");
        java.io.File[] files = sources.listFiles((d, n) -> n.endsWith(".java"));
        assertTrue(files != null && files.length > 0, "no sources found at " + sources);
        List<String> offenders = new java.util.ArrayList<>();
        for (java.io.File file : files) {
            if (file.getName().equals("FailureReporter.java")) {
                continue;
            }
            String body = new String(java.nio.file.Files.readAllBytes(file.toPath()),
                java.nio.charset.StandardCharsets.UTF_8);
            if (body.contains("getPlayerList()")) {
                offenders.add(file.getName());
            }
        }
        assertEquals(java.util.Collections.emptyList(), offenders,
            "these reach operators without FailureReporter.broadcastToOperators, so they "
                + "bypass its marshalling onto the server thread");
    }
}
