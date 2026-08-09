package com.keuin.rocksmc;

/**
 * Configuration, read from {@code config/rocksmc.properties}.
 *
 * <p>Defaults are deliberately conservative: the backend is {@code anvil}, so
 * installing the mod changes nothing until explicitly enabled. Vanilla therefore
 * remains the live reference implementation for A/B comparison.
 */
public final class RocksMcConfig {

    public enum Backend {
        /** Vanilla Anvil region files. The default. */
        ANVIL,
        /** RocksDB, one database per world directory. */
        ROCKSDB
    }

    private final Backend backend;
    private final long minBlobSize;
    private final boolean syncWrites;
    private final boolean verifyOnRead;

    private RocksMcConfig(Backend backend, long minBlobSize, boolean syncWrites,
            boolean verifyOnRead) {
        this.backend = backend;
        this.minBlobSize = minBlobSize;
        this.syncWrites = syncWrites;
        this.verifyOnRead = verifyOnRead;
    }

    public static RocksMcConfig defaults() {
        return new RocksMcConfig(Backend.ANVIL, 1024L, false, false);
    }

    public static RocksMcConfig of(java.util.Properties props) {
        Backend backend;
        try {
            backend = Backend.valueOf(
                props.getProperty("backend", "anvil").trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            backend = Backend.ANVIL;
        }
        return new RocksMcConfig(
            backend,
            parseLong(props.getProperty("min-blob-size"), 1024L),
            Boolean.parseBoolean(props.getProperty("sync-writes", "false")),
            Boolean.parseBoolean(props.getProperty("verify-on-read", "false"))
        );
    }

    private static long parseLong(String s, long fallback) {
        if (s == null) {
            return fallback;
        }
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
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
     * size to favour storage; leave it low to favour SSD endurance.
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
     * <p>Set {@code true} for strict parity with vanilla durability.
     */
    public boolean syncWrites() {
        return this.syncWrites;
    }

    /**
     * Diagnostic: re-read and compare every write. Very slow; harness use only.
     */
    public boolean verifyOnRead() {
        return this.verifyOnRead;
    }

    @Override
    public String toString() {
        return "RocksMcConfig{backend=" + this.backend
            + ", minBlobSize=" + this.minBlobSize
            + ", syncWrites=" + this.syncWrites
            + ", verifyOnRead=" + this.verifyOnRead + '}';
    }
}
