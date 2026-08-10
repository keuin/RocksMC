package com.keuin.rocksmc;

import com.google.common.collect.ImmutableMap;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
import org.rocksdb.WriteBatch;
import org.rocksdb.WriteOptions;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Maps dimension identities to stable integer ordinals, persisted in the database.
 *
 * <h2>Why a registry rather than a computed id</h2>
 *
 * <p>Chunk keys embed the dimension as a fixed-width integer, so a namespaced
 * identity like {@code twilightforest:twilight_forest} has to become a number.
 * Hashing the string would be simpler but risks collisions, and a collision here
 * means two dimensions sharing a keyspace -- silently overwriting each other's
 * terrain. That is the exact failure this whole change exists to eliminate, so a
 * probabilistic scheme is not acceptable.
 *
 * <p>Instead, ordinals are assigned on first sight and written down. Assignment
 * is monotonic and never reused, so an ordinal always refers to the same
 * dimension for the lifetime of the database.
 *
 * <h2>Vanilla ordinals are pinned</h2>
 *
 * <p>Overworld, nether and end get 0, 1 and 2 unconditionally, so the common case
 * has a predictable on-disk layout regardless of the order in which worlds happen
 * to load. Custom dimensions take 3 upward in first-seen order.
 *
 * <h2>Width</h2>
 *
 * <p>Ordinals are int32. Narrowing to int16 was considered and rejected: measured
 * against a real 293k-chunk world it would save 586 KB, or 0.051% of the database,
 * before prefix compression -- which already collapses most of the field, since it
 * is constant within a store. In exchange it would misalign the Morton code and
 * cap the dimension count for no benefit. See docs/design-decisions.md.
 */
public final class DimensionRegistry {

    private static final byte[] NEXT_ORDINAL_KEY = "\u0000next".getBytes(StandardCharsets.UTF_8);

    /** First ordinal available to non-vanilla dimensions. */
    private static final int FIRST_CUSTOM_ORDINAL = 3;

    /**
     * Ordinals reserved for the vanilla dimensions.
     *
     * <p>Pinning them means the common case has a predictable on-disk layout
     * regardless of the order in which worlds happen to load. Kept as data rather
     * than control flow so the mapping can be iterated -- {@link
     * #FIRST_CUSTOM_ORDINAL} is asserted against it below, so the two cannot drift
     * apart if a dimension is ever added or renumbered.
     *
     * <p>Guava's {@code ImmutableMap} rather than {@code Map.of}: the mod targets
     * Java 8 (see {@code options.release = 8}) because Minecraft 1.16.5 runs there,
     * and {@code Map.of} arrived in Java 9. Guava is already on the classpath, being
     * bundled with Minecraft and used throughout vanilla.
     */
    private static final Map<String, Integer> PINNED_ORDINALS = ImmutableMap.of(
        DimensionKey.OVERWORLD, 0,
        DimensionKey.THE_NETHER, 1,
        DimensionKey.THE_END, 2);

    static {
        // A pinned ordinal that strayed into the custom range would eventually be
        // handed to a second dimension, putting two of them in one keyspace.
        for (Map.Entry<String, Integer> e : PINNED_ORDINALS.entrySet()) {
            if (e.getValue() >= FIRST_CUSTOM_ORDINAL) {
                throw new IllegalStateException("pinned ordinal " + e.getValue() + " for "
                    + e.getKey() + " collides with the custom range starting at "
                    + FIRST_CUSTOM_ORDINAL);
            }
        }
    }

    private final RocksDB db;
    private final ColumnFamilyHandle cf;
    private final Map<String, Integer> cache = new HashMap<>();

    /**
     * Synced writes, so an ordinal assignment is durable before it is used.
     *
     * <p>This map is what makes every other key in the database interpretable: an
     * ordinal handed out but not recorded could be given to a different dimension
     * on the next run, putting two dimensions in one keyspace.
     *
     * <p>A synced WAL write rather than a memtable flush. Flushing was both
     * heavier than needed -- durability comes from the WAL, not from reaching an
     * SST -- and actively harmful now that dimensions share a database: the flush
     * happened while holding this object's lock, so one dimension being seen for
     * the first time would block every other dimension's chunk loads for the
     * duration. The previous code also leaked a {@code FlushOptions} per newly
     * seen dimension.
     */
    private final WriteOptions syncedWrites = new WriteOptions().setSync(true);

    public DimensionRegistry(RocksDB db, ColumnFamilyHandle cf) throws IOException {
        this.db = db;
        this.cf = cf;
        load();
    }

    /** Reads the whole mapping into memory. It is tiny -- one entry per dimension. */
    private void load() throws IOException {
        try (RocksIterator it = this.db.newIterator(this.cf)) {
            for (it.seekToFirst(); it.isValid(); it.next()) {
                byte[] k = it.key();
                if (isReservedKey(k)) {
                    continue;
                }
                String identity = new String(k, StandardCharsets.UTF_8);
                this.cache.put(identity, decodeInt(it.value()));
            }
        }
    }

    /**
     * Returns the ordinal for a dimension, assigning one if this is the first time
     * it has been seen.
     *
     * <p>The assignment is durable before returning, so a crash cannot leave an
     * ordinal in use but unrecorded -- which would let a later run hand the same
     * number to a different dimension.
     *
     * <p>Synchronised because the check-allocate-persist-cache sequence must be
     * atomic: {@link #allocateOrdinal} does a read-modify-write on the shared
     * counter, so two threads both missing the cache could otherwise be handed the
     * same ordinal.
     */
    public synchronized int ordinalFor(String identity) throws IOException {
        Integer known = this.cache.get(identity);
        if (known != null) {
            return known;
        }

        int assigned = pinnedOrdinal(identity);
        boolean custom = assigned < 0;
        if (custom) {
            assigned = allocateOrdinal();
        }

        // One batch, so the assignment and the counter advance commit together.
        // Separately they could tear: a crash between them would leave an ordinal
        // recorded that the counter does not know about, or vice versa.
        try (WriteBatch batch = new WriteBatch()) {
            batch.put(this.cf, identity.getBytes(StandardCharsets.UTF_8), encodeInt(assigned));
            if (custom) {
                batch.put(this.cf, NEXT_ORDINAL_KEY, encodeInt(assigned + 1));
            }
            this.db.write(this.syncedWrites, batch);
        } catch (RocksDBException e) {
            throw new IOException("failed to persist dimension ordinal for " + identity, e);
        }
        this.cache.put(identity, assigned);
        return assigned;
    }

    /** @return the reserved ordinal for a vanilla dimension, or -1 if not pinned */
    private static int pinnedOrdinal(String identity) {
        Integer pinned = PINNED_ORDINALS.get(identity);
        return pinned == null ? -1 : pinned;
    }

    /**
     * Next unused ordinal, avoiding both the pinned range and anything in use.
     *
     * <p>Read only: the caller commits the advanced counter in the same batch as
     * the assignment it is for.
     */
    private int allocateOrdinal() throws IOException {
        int next = FIRST_CUSTOM_ORDINAL;
        try {
            byte[] stored = this.db.get(this.cf, NEXT_ORDINAL_KEY);
            if (stored != null) {
                next = decodeInt(stored);
            }
        } catch (RocksDBException e) {
            throw new IOException("failed to read next dimension ordinal", e);
        }

        // Defend against a stale counter: never hand out a number already taken.
        while (this.cache.containsValue(next) || next < FIRST_CUSTOM_ORDINAL) {
            next++;
        }
        return next;
    }

    /** Number of dimensions currently registered, excluding internal bookkeeping. */
    public synchronized int size() {
        return this.cache.size();
    }

    public synchronized Map<String, Integer> snapshot() {
        return new HashMap<>(this.cache);
    }

    /**
     * Releases the native write options.
     *
     * <p>Called by {@link RocksDatabase} on the last release. Does not touch the
     * database or the column family handle, which the database owns.
     */
    void close() {
        this.syncedWrites.close();
    }

    /** Internal keys are prefixed with a NUL byte, which no identity can contain. */
    private static boolean isReservedKey(byte[] key) {
        return key.length > 0 && key[0] == 0;
    }

    private static byte[] encodeInt(int v) {
        return new byte[] {
            (byte) (v >>> 24), (byte) (v >>> 16), (byte) (v >>> 8), (byte) v
        };
    }

    private static int decodeInt(byte[] b) {
        if (b == null || b.length < 4) {
            throw new IllegalStateException("corrupt dimension ordinal value");
        }
        return ((b[0] & 0xFF) << 24) | ((b[1] & 0xFF) << 16)
            | ((b[2] & 0xFF) << 8) | (b[3] & 0xFF);
    }
}
