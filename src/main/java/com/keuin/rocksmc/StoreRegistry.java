package com.keuin.rocksmc;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Registry of live stores and databases, and the source for both log output and
 * metrics.
 *
 * <p>Two collections rather than one, because after the consolidation to one
 * database per world the two have genuinely different scopes. A store is one
 * dimension's view of one column family and owns its own IO counters; a database is
 * shared by every store of a world and owns the SST sizes, the compaction state and
 * the block cache. Six stores map onto one database, so reporting a database's
 * numbers once per store would make every aggregate six times too large.
 *
 * <p>Stores and databases register on open and deregister on close, so whatever
 * scrapes metrics sees exactly what the server currently has open -- including
 * per-dimension stores that appear only once a player enters that dimension.
 *
 * <p>Kept deliberately free of any HTTP or formatting concerns: {@link
 * MetricsExporter} renders it, {@link RocksMc} logs it. Uses copy-on-write lists
 * because registration is rare and iteration happens on both the logging timer and
 * the scrape thread.
 */
public final class StoreRegistry {

    private static final Collection<RocksChunkStore> STORES = new CopyOnWriteArrayList<>();
    private static final Collection<RocksDatabase> DATABASES = new CopyOnWriteArrayList<>();

    public static void register(RocksChunkStore store) {
        STORES.add(store);
    }

    public static void deregister(RocksChunkStore store) {
        STORES.remove(store);
    }

    public static List<RocksChunkStore> stores() {
        return new ArrayList<>(STORES);
    }

    public static int size() {
        return STORES.size();
    }

    /**
     * Registered by {@link RocksDatabase#open} on first open, not once per store.
     *
     * <p>Package-private: reference counting is the database's business, and an
     * outside caller registering one would corrupt the metric scopes.
     */
    static void registerDatabase(RocksDatabase database) {
        DATABASES.add(database);
    }

    static void deregisterDatabase(RocksDatabase database) {
        DATABASES.remove(database);
    }

    public static List<RocksDatabase> databases() {
        return new ArrayList<>(DATABASES);
    }

    public static int databaseCount() {
        return DATABASES.size();
    }

    private StoreRegistry() {
    }
}
