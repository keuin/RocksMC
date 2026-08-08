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
     * <p>Phase 1a measured real uncompressed chunk NBT at ~51 KiB mean, and blob
     * files cut compaction traffic by 316x at that size, so essentially all chunks
     * should qualify. 1 KiB is well below even the smallest observed chunk.
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
