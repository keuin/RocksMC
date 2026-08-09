package com.keuin.rocksmc;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Registry of live stores, and the source for both log output and metrics.
 *
 * <p>Stores register themselves on open and deregister on close, so whatever
 * scrapes metrics sees exactly the set of databases the server currently has
 * open -- including per-dimension stores that appear only once a player enters
 * that dimension.
 *
 * <p>Kept deliberately free of any HTTP or formatting concerns: {@link
 * MetricsExporter} renders it, {@link RocksMc} logs it. Uses a
 * copy-on-write list because registration is rare and iteration happens on both
 * the logging timer and the scrape thread.
 */
public final class StoreRegistry {

    private static final Collection<RocksChunkStore> STORES = new CopyOnWriteArrayList<>();

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

    private StoreRegistry() {
    }
}
