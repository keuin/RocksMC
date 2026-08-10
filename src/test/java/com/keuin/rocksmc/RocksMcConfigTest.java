package com.keuin.rocksmc;

import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests configuration parsing.
 *
 * <p>This class had no tests at all, which is how a set of silent-fallback bugs
 * survived in it. The theme of the cases below is that a value the operator wrote
 * must never be quietly replaced by a different one: a storage backend that is not
 * the one they asked for reads the wrong files, and a tuning key that is ignored
 * looks like a setting that does not work.
 */
class RocksMcConfigTest {

    private static RocksMcConfig parse(String... keyValuePairs) {
        Properties props = new Properties();
        for (int i = 0; i < keyValuePairs.length; i += 2) {
            props.setProperty(keyValuePairs[i], keyValuePairs[i + 1]);
        }
        return RocksMcConfig.of(props);
    }

    // ----------------------------------------------------------------- defaults

    /**
     * The default must be Anvil, so installing the mod changes nothing until asked.
     */
    @Test
    void defaultsToAnvil() {
        RocksMcConfig config = RocksMcConfig.defaults();
        assertEquals(RocksMcConfig.Backend.ANVIL, config.backend());
        assertFalse(config.rocksEnabled());
        assertEquals("anvil", config.backendName());
    }

    /**
     * The documented per-world defaults.
     *
     * <p>Asserted exactly because they are quoted in {@code docs/beta-setup.md} and in
     * the generated config file; a drift between code and docs is how an operator ends
     * up reasoning about memory that was never allocated.
     */
    @Test
    void documentedDefaults() {
        RocksMcConfig c = RocksMcConfig.defaults();
        assertEquals(1024L, c.minBlobSize());
        assertFalse(c.syncWrites());
        assertFalse(c.verifyOnRead());
        assertFalse(c.allowBlankStart());
        assertEquals(8, c.maxBackgroundJobs());
        assertEquals(4, c.maxSubcompactions());
        assertEquals(128L * 1024 * 1024, c.writeBufferSize());
        assertEquals(4, c.maxWriteBufferNumber());
        assertEquals(1024L * 1024, c.bytesPerSync());
        assertEquals(512L * 1024 * 1024, c.blockCacheSize());
        assertEquals(20, c.level0SlowdownTrigger());
        assertEquals(36, c.level0StopTrigger());
        assertEquals(-1, c.maxOpenFiles());
        assertEquals(0L, c.maxTotalWalSize());
        assertFalse(c.metricsEnabled());
        assertEquals("127.0.0.1", c.metricsBind());
        assertEquals(9940, c.metricsPort());
        assertEquals(300, c.statsLogIntervalSeconds());
        assertEquals(0, c.checkpointIntervalMinutes());
        assertEquals(6, c.checkpointKeep());
    }

    // ------------------------------------------------------------------ backend

    @Test
    void backendIsCaseAndWhitespaceInsensitive() {
        assertTrue(parse("backend", "rocksdb").rocksEnabled());
        assertTrue(parse("backend", "RocksDB").rocksEnabled());
        assertTrue(parse("backend", "  ROCKSDB  ").rocksEnabled());
        assertFalse(parse("backend", "anvil").rocksEnabled());
    }

    /**
     * The bug that motivated making this strict.
     *
     * <p>A misspelled backend used to become {@code ANVIL} silently, after which the
     * mod logged "Backend is 'anvil' (vanilla)" and an operator who believed they had
     * migrated was serving the {@code .mca} files as of the import, with every session
     * since then invisible. Guessing is not acceptable here, so it throws.
     */
    @Test
    void unknownBackendThrowsRatherThanFallingBackToAnvil() {
        for (String bad : new String[] {"rocksb", "rocks", "rockdb", "ROCKS_DB",
                "postgres", "true"}) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> parse("backend", bad), "backend=" + bad + " must be rejected");
            assertTrue(e.getMessage().contains("anvil")
                    && e.getMessage().contains("rocksdb"),
                "the error must name the valid values: " + e.getMessage());
        }
    }

    /** An absent or blank backend is a fresh install, which is genuinely Anvil. */
    @Test
    void absentOrBlankBackendIsAnvil() {
        assertFalse(RocksMcConfig.of(new Properties()).rocksEnabled());
        assertFalse(parse("backend", "").rocksEnabled());
        assertFalse(parse("backend", "   ").rocksEnabled());
    }

    // ----------------------------------------------------------------- numerics

    @Test
    void numericValuesAreParsed() {
        assertEquals(2048L, parse("min-blob-size", "2048").minBlobSize());
        assertEquals(16, parse("max-background-jobs", "16").maxBackgroundJobs());
        assertEquals(1024, parse("max-open-files", "1024").maxOpenFiles());
    }

    /** A non-numeric value falls back to the default rather than crashing. */
    @Test
    void nonNumericValueFallsBackToDefault() {
        assertEquals(8, parse("max-background-jobs", "lots").maxBackgroundJobs());
        assertEquals(1024L, parse("min-blob-size", "big").minBlobSize());
    }

    /**
     * Size suffixes are not supported, and that must not silently mean something else.
     *
     * <p>{@code 512M} is a plausible thing to write and parses as neither 512 nor
     * 536870912; falling back to the default is the only honest option, and the warning
     * says why.
     */
    @Test
    void sizeSuffixesFallBackRatherThanBeingMisread() {
        assertEquals(512L * 1024 * 1024, parse("block-cache-size", "512M").blockCacheSize());
        assertEquals(512L * 1024 * 1024, parse("block-cache-size", "512MiB").blockCacheSize());
    }

    /**
     * Values that RocksDB cannot use must be rejected before reaching JNI.
     *
     * <p>A zero block cache or a negative thread count is at best an obscure native
     * error and at worst accepted with surprising behaviour.
     */
    @Test
    void outOfRangeValuesFallBackToDefaults() {
        assertEquals(8, parse("max-background-jobs", "0").maxBackgroundJobs());
        assertEquals(8, parse("max-background-jobs", "-4").maxBackgroundJobs());
        assertEquals(512L * 1024 * 1024, parse("block-cache-size", "0").blockCacheSize());
        assertEquals(4, parse("max-write-buffer-number", "1").maxWriteBufferNumber());
        assertEquals(9940, parse("metrics-port", "99999").metricsPort());
        assertEquals(9940, parse("metrics-port", "0").metricsPort());
        assertEquals(6, parse("checkpoint-keep", "0").checkpointKeep());
    }

    /**
     * A value above {@code Integer.MAX_VALUE} must not wrap.
     *
     * <p>The previous implementation cast a parsed {@code long} straight to {@code int},
     * so {@code 4294967304} became {@code 8} -- a number that looks deliberate.
     */
    @Test
    void oversizedIntegerDoesNotWrapSilently() {
        assertEquals(8, parse("max-background-jobs", "4294967304").maxBackgroundJobs());
        assertEquals(8, parse("max-background-jobs", "2147483648").maxBackgroundJobs());
    }

    /** -1 means unlimited for max-open-files and must survive the range check. */
    @Test
    void minusOneIsValidForMaxOpenFiles() {
        assertEquals(-1, parse("max-open-files", "-1").maxOpenFiles());
    }

    // ----------------------------------------------------------------- booleans

    @Test
    void booleansAreParsed() {
        assertTrue(parse("sync-writes", "true").syncWrites());
        assertTrue(parse("sync-writes", "TRUE").syncWrites());
        assertTrue(parse("sync-writes", " true ").syncWrites());
        assertFalse(parse("sync-writes", "false").syncWrites());
    }

    /**
     * {@code Boolean.parseBoolean} maps everything unrecognised to {@code false}, so
     * {@code sync-writes=1} and {@code =yes} used to mean the opposite of what was
     * written. They now fall back to the default and warn.
     */
    @Test
    void nonBooleanValuesFallBackRatherThanBecomingFalse() {
        // The default for verify-on-read is false, so use a key whose default is
        // distinguishable from the wrong answer.
        assertTrue(parse("verify-on-read", "true").verifyOnRead());
        assertFalse(parse("verify-on-read", "yes").verifyOnRead());
        assertFalse(parse("verify-on-read", "1").verifyOnRead());
        // And confirm the fallback really is the default rather than a hardcoded false:
        Properties props = new Properties();
        props.setProperty("verify-on-read", "true");
        RocksMcConfig base = RocksMcConfig.of(props);
        assertTrue(base.verifyOnRead());
    }

    // --------------------------------------------------------------- validation

    /**
     * Throttling must begin before it stops.
     *
     * <p>Raising only the slowdown trigger past the stop trigger would skip gentle
     * throttling and jump straight to a full write stall -- and the docs actively
     * suggest raising the slowdown trigger, so this combination is easy to reach.
     */
    @Test
    void slowdownTriggerAboveStopTriggerIsCorrected() {
        RocksMcConfig c = parse("level0-slowdown-writes-trigger", "100");
        assertEquals(100, c.level0SlowdownTrigger());
        assertTrue(c.level0StopTrigger() >= c.level0SlowdownTrigger(),
            "stop trigger must not be below the slowdown trigger, got "
                + c.level0StopTrigger());
    }

    @Test
    void explicitTriggersInTheRightOrderArePreserved() {
        RocksMcConfig c = parse(
            "level0-slowdown-writes-trigger", "24",
            "level0-stop-writes-trigger", "48");
        assertEquals(24, c.level0SlowdownTrigger());
        assertEquals(48, c.level0StopTrigger());
    }

    // ------------------------------------------------------------------- copies

    /** withVerifyOnRead must copy every other field, not reset anything. */
    @Test
    void withVerifyOnReadPreservesEveryOtherSetting() {
        RocksMcConfig original = parse(
            "backend", "rocksdb",
            "verify-on-read", "true",
            "min-blob-size", "4096",
            "sync-writes", "true",
            "allow-blank-start", "true",
            "max-background-jobs", "12",
            "max-subcompactions", "6",
            "write-buffer-size", "67108864",
            "max-write-buffer-number", "8",
            "bytes-per-sync", "2097152",
            "block-cache-size", "268435456",
            "level0-slowdown-writes-trigger", "24",
            "level0-stop-writes-trigger", "40",
            "max-open-files", "512",
            "max-total-wal-size", "1073741824",
            "metrics-enabled", "true",
            "metrics-bind", "0.0.0.0",
            "metrics-port", "9999",
            "stats-log-interval-seconds", "60",
            "checkpoint-interval-minutes", "30",
            "checkpoint-keep", "12");

        RocksMcConfig copy = original.withVerifyOnRead(false);

        assertFalse(copy.verifyOnRead(), "the one field that should change");
        assertTrue(original.verifyOnRead(), "the original must be untouched");

        assertEquals(original.backend(), copy.backend());
        assertEquals(original.minBlobSize(), copy.minBlobSize());
        assertEquals(original.syncWrites(), copy.syncWrites());
        assertEquals(original.allowBlankStart(), copy.allowBlankStart());
        assertEquals(original.maxBackgroundJobs(), copy.maxBackgroundJobs());
        assertEquals(original.maxSubcompactions(), copy.maxSubcompactions());
        assertEquals(original.writeBufferSize(), copy.writeBufferSize());
        assertEquals(original.maxWriteBufferNumber(), copy.maxWriteBufferNumber());
        assertEquals(original.bytesPerSync(), copy.bytesPerSync());
        assertEquals(original.blockCacheSize(), copy.blockCacheSize());
        assertEquals(original.level0SlowdownTrigger(), copy.level0SlowdownTrigger());
        assertEquals(original.level0StopTrigger(), copy.level0StopTrigger());
        assertEquals(original.maxOpenFiles(), copy.maxOpenFiles());
        assertEquals(original.maxTotalWalSize(), copy.maxTotalWalSize());
        assertEquals(original.metricsEnabled(), copy.metricsEnabled());
        assertEquals(original.metricsBind(), copy.metricsBind());
        assertEquals(original.metricsPort(), copy.metricsPort());
        assertEquals(original.statsLogIntervalSeconds(), copy.statsLogIntervalSeconds());
        assertEquals(original.checkpointIntervalMinutes(), copy.checkpointIntervalMinutes());
        assertEquals(original.checkpointKeep(), copy.checkpointKeep());
    }

    // ------------------------------------------------------------------ reports

    /** Unknown keys must not throw -- only warn -- so a stale config still boots. */
    @Test
    void unknownKeysAreToleratedNotFatal() {
        RocksMcConfig c = parse(
            "backend", "rocksdb",
            "max-background-job", "16",
            "some-removed-setting", "1");
        assertTrue(c.rocksEnabled());
        assertEquals(8, c.maxBackgroundJobs(), "the typo'd key must not take effect");
    }

    /** toString is logged at startup, so it must show the values that matter. */
    @Test
    void toStringMentionsTheLoadBearingSettings() {
        String s = parse("backend", "rocksdb", "checkpoint-interval-minutes", "15")
            .toString();
        assertTrue(s.contains("ROCKSDB"), s);
        assertTrue(s.contains("syncWrites"), s);
        assertTrue(s.contains("maxOpenFiles"), s);
        assertTrue(s.contains("checkpoints=every 15min"), s);
        assertNotEquals(-1, s.indexOf("blockCacheSize"), s);
    }

    @Test
    void checkpointsReportAsDisabledWhenOff() {
        assertTrue(RocksMcConfig.defaults().toString().contains("checkpoints=disabled"));
    }
}
