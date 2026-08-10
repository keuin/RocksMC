package com.keuin.rocksmc;

import io.prometheus.metrics.exporter.httpserver.HTTPServer;
import io.prometheus.metrics.model.registry.MultiCollector;
import io.prometheus.metrics.model.registry.PrometheusRegistry;
import io.prometheus.metrics.model.snapshots.CounterSnapshot;
import io.prometheus.metrics.model.snapshots.GaugeSnapshot;
import io.prometheus.metrics.model.snapshots.Labels;
import io.prometheus.metrics.model.snapshots.MetricSnapshot;
import io.prometheus.metrics.model.snapshots.MetricSnapshots;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Prometheus exporter on {@code /metrics}, backed by the official client library.
 *
 * <h2>Why the library at all</h2>
 *
 * <p>This previously rendered the text exposition format by hand. That worked, but
 * hand-rolled formatting owns problems it should not have to: escaping, HELP/TYPE
 * pairing, content negotiation, and OpenMetrics. The library handles those, and
 * gains support for both text and OpenMetrics output without further work.
 *
 * <h2>Scrape safety</h2>
 *
 * <p>{@link RocksChunkStore#snapshot()} reads atomics, and {@link
 * RocksDatabase#snapshot()} reads RocksDB properties, neither under a lock that the
 * IO worker contends for, so a scrape cannot block the tick loop. The worst case is
 * values very slightly inconsistent with each other, which is the right trade for
 * observability.
 *
 * <p>Binds to loopback by default: metrics reveal world size and activity, so
 * exposure should be deliberate.
 */
public final class MetricsExporter implements AutoCloseable {

    private final HTTPServer server;

    private MetricsExporter(HTTPServer server) {
        this.server = server;
    }

    /**
     * Starts the exporter, or returns {@code null} if it cannot bind.
     *
     * <p>A failure to bind must not stop the server: telemetry is useful but not
     * load-bearing, and a port clash should be a warning rather than a crash.
     */
    public static MetricsExporter start(String bind, int port) {
        PrometheusRegistry registry = new PrometheusRegistry();
        registry.register(new StoreCollector());

        try {
            HTTPServer server = HTTPServer.builder()
                .hostname(bind)
                .port(port)
                .registry(registry)
                // Daemon threads so a stuck scrape cannot hold up JVM shutdown.
                .executorService(Executors.newFixedThreadPool(2, daemonFactory()))
                .buildAndStart();
            RocksMc.logger().info("rocksmc metrics on http://{}:{}/metrics", bind, port);
            return new MetricsExporter(server);
        } catch (IOException e) {
            RocksMc.logger().warn("rocksmc: could not start metrics exporter on {}:{} "
                + "({}). Continuing without metrics.", bind, port, e.getMessage());
            return null;
        }
    }

    private static ThreadFactory daemonFactory() {
        return runnable -> {
            Thread t = new Thread(runnable, "rocksmc-metrics");
            t.setDaemon(true);
            return t;
        };
    }

    @Override
    public void close() {
        if (this.server != null) {
            this.server.close();
        }
    }

    /**
     * Collects one snapshot per open store and per open database on every scrape.
     *
     * <h2>Three metric scopes, deliberately</h2>
     *
     * <p>Consolidation onto one database per world means a value's scope is no
     * longer always "a store". Emitting everything per store would report shared
     * numbers once for each of a world's six stores, so {@code sum()} over disk
     * usage would read six times high -- silently, on a dashboard meant to be
     * trusted during a beta. So each value is emitted at the scope it actually has:
     *
     * <table>
     *   <tr><th>Scope</th><th>Labels</th><th>Values</th></tr>
     *   <tr><td>store</td><td>{@code dimension}, {@code store}, {@code database}</td>
     *       <td>IO counters, which each store genuinely owns</td></tr>
     *   <tr><td>column family</td><td>{@code column_family}, {@code database}</td>
     *       <td>SST sizes, key estimates, memtables, compaction backlog</td></tr>
     *   <tr><td>database</td><td>{@code database}</td>
     *       <td>running compactions and flushes, throttling, block cache, blobs</td></tr>
     * </table>
     *
     * <p>Column-family-scoped metrics are named {@code ..._by_cf} rather than reusing
     * the old per-store names. Renaming is the point: a query written against the
     * old names would still return data with the new labels but would be aggregating
     * across a different scope, which is worse than an obvious break.
     *
     * <h2>Why a MultiCollector rather than registered metric objects</h2>
     *
     * <p>The obvious use of this library is to create {@code Counter} and {@code
     * Gauge} instances and increment them at the call sites. That does not fit here:
     * the set of series is not fixed at startup. Stores open lazily -- a dimension's
     * store only exists once something loads that dimension -- and close again on
     * shutdown, so the label combinations present at any moment are whatever {@link
     * StoreRegistry} currently holds.
     *
     * <p>A {@link MultiCollector} is the library's answer to exactly that: it is
     * asked for a fresh set of snapshots on every scrape, so series appear and
     * disappear with the stores they describe.
     *
     * <p>Package-private and separately testable, so the exposition can be
     * exercised without binding a port.
     */
    static final class StoreCollector implements MultiCollector {

        private static final String[] STORE_LABELS = {"dimension", "store", "database"};
        private static final String[] CF_LABELS = {"column_family", "database"};
        private static final String[] DB_LABELS = {"database"};

        private final AtomicLong scrapes = new AtomicLong();

        @Override
        public MetricSnapshots collect() {
            this.scrapes.incrementAndGet();

            List<RocksChunkStore.Snapshot> stores = new ArrayList<>();
            for (RocksChunkStore store : StoreRegistry.stores()) {
                stores.add(store.snapshot());
            }
            List<RocksDatabase.Snapshot> databases = new ArrayList<>();
            for (RocksDatabase database : StoreRegistry.databases()) {
                databases.add(database.snapshot());
            }

            List<MetricSnapshot> out = new ArrayList<>();

            // ---- exporter-level ----
            out.add(gauge("rocksmc_up", "1 if the exporter is serving", 1));
            out.add(gauge("rocksmc_stores", "Number of open per-dimension stores",
                stores.size()));
            out.add(gauge("rocksmc_databases", "Number of open RocksDB databases",
                databases.size()));
            out.add(counter("rocksmc_scrapes_total", "Metrics scrapes served",
                this.scrapes.get()));

            // ---- checkpoints ----
            // Exporter-level rather than per-database: checkpointing is a mod-wide
            // activity on one shared timer, and a per-database split would imply an
            // independence that does not exist.
            out.add(counter("rocksmc_checkpoints_total",
                "Checkpoints created, automatic and manual",
                CheckpointScheduler.checkpointCount()));
            out.add(counter("rocksmc_checkpoint_failures_total",
                "Checkpoint attempts that failed",
                CheckpointScheduler.failureCount()));
            out.add(counter("rocksmc_checkpoints_pruned_total",
                "Old automatic checkpoints deleted by retention",
                CheckpointScheduler.prunedCount()));
            // A timestamp rather than an age, so a scraper computes staleness itself
            // and the value does not change between scrapes for a static system. 0
            // means none has been taken, which is why it is emitted rather than
            // omitted -- absence would look like a scrape problem.
            out.add(gauge("rocksmc_checkpoint_last_success_timestamp_seconds",
                "Unix time of the last successful checkpoint, 0 if none",
                CheckpointScheduler.lastSuccessEpochSeconds()));
            long lastDuration = CheckpointScheduler.lastDurationMillis();
            if (lastDuration >= 0) {
                out.add(gauge("rocksmc_checkpoint_last_duration_seconds",
                    "Duration of the most recent checkpoint",
                    lastDuration / 1000.0));
            }

            // ---- per-store counters ----
            // These are the mod's own AtomicLongs, so they remain honestly
            // attributable to one dimension and one leaf.
            out.add(counter("rocksmc_chunk_reads_total", "Chunks read from storage",
                stores, s -> s.reads));
            out.add(counter("rocksmc_chunk_writes_total", "Chunks written to storage",
                stores, s -> s.writes));
            out.add(counter("rocksmc_bytes_read_total", "Uncompressed NBT bytes read",
                stores, s -> s.bytesRead));
            out.add(counter("rocksmc_bytes_written_total",
                "Uncompressed NBT bytes written", stores, s -> s.bytesWritten));

            // Failure counters are the ones worth alerting on: any non-zero value
            // means the storage layer is not doing its job.
            out.add(counter("rocksmc_read_failures_total", "Failed chunk reads",
                stores, s -> s.readFailures));
            out.add(counter("rocksmc_write_failures_total", "Failed chunk writes",
                stores, s -> s.writeFailures));
            out.add(counter("rocksmc_verify_failures_total",
                "Round-trip verification failures (verify-on-read)",
                stores, s -> s.verifyFailures));

            out.add(gauge("rocksmc_dimension_ordinal",
                "Ordinal assigned to this dimension in the key encoding",
                stores, s -> s.dimensionOrdinal));

            // ---- per-column-family gauges ----
            // All dimensions share a column family, so these cannot be attributed
            // to a dimension. Named _by_cf so a query cannot silently mistake them
            // for the old per-store series.
            out.add(cfGauge("rocksmc_live_sst_bytes_by_cf", "Live SST file bytes",
                databases, cf -> cf.liveSstBytes));
            out.add(cfGauge("rocksmc_total_sst_bytes_by_cf",
                "All SST file bytes including obsolete", databases,
                cf -> cf.totalSstBytes));
            out.add(cfGauge("rocksmc_estimated_keys_by_cf",
                "Estimated number of stored entries", databases,
                cf -> cf.estimatedKeys));
            out.add(cfGauge("rocksmc_memtable_bytes_by_cf", "Memory held by memtables",
                databases, cf -> cf.memtableBytes));

            // Compaction backlog is the leading indicator of trouble on a technical
            // server: it rises long before writes actually stall.
            out.add(cfGauge("rocksmc_pending_compaction_bytes_by_cf",
                "Estimated bytes of compaction still owed", databases,
                cf -> cf.pendingCompactionBytes));
            out.add(cfGauge("rocksmc_compaction_pending_by_cf",
                "1 if a compaction is queued", databases, cf -> cf.compactionPending));
            out.add(cfGauge("rocksmc_memtable_flush_pending_by_cf",
                "1 if a flush is queued", databases, cf -> cf.memtableFlushPending));

            // ---- per-database gauges ----
            out.add(dbGauge("rocksmc_open_stores",
                "Stores sharing this database handle", databases, d -> d.openStores));
            out.add(dbGauge("rocksmc_blob_file_bytes",
                "Blob file bytes on disk, where chunk values live", databases,
                d -> d.blobFileBytes));
            out.add(dbGauge("rocksmc_block_cache_bytes", "Block cache usage",
                databases, d -> d.blockCacheBytes));
            out.add(dbGauge("rocksmc_running_compactions", "Compactions in flight",
                databases, d -> d.runningCompactions));
            out.add(dbGauge("rocksmc_running_flushes", "Memtable flushes in flight",
                databases, d -> d.runningFlushes));

            // These two are the "your server is about to lag" signals.
            out.add(dbGauge("rocksmc_delayed_write_rate",
                "Throttled write rate in bytes/sec, 0 when not throttling",
                databases, d -> d.delayedWriteRate));
            out.add(dbGauge("rocksmc_write_stopped", "1 if writes are fully stopped",
                databases, d -> d.writeStopped));

            return new MetricSnapshots(out);
        }

        private interface Field {
            long get(RocksChunkStore.Snapshot snapshot);
        }

        private interface CfField {
            long get(RocksDatabase.ColumnFamilySnapshot snapshot);
        }

        private interface DbField {
            long get(RocksDatabase.Snapshot snapshot);
        }

        private static Labels labelsFor(RocksChunkStore.Snapshot s) {
            return Labels.of(STORE_LABELS,
                new String[] {s.dimension, s.leaf, s.database});
        }

        /** A single unlabelled gauge, for exporter-level values. */
        private static MetricSnapshot gauge(String name, String help, double value) {
            return GaugeSnapshot.builder()
                .name(name)
                .help(help)
                .dataPoint(GaugeSnapshot.GaugeDataPointSnapshot.builder()
                    .value(value)
                    .build())
                .build();
        }

        private static MetricSnapshot counter(String name, String help, double value) {
            return CounterSnapshot.builder()
                .name(stripTotal(name))
                .help(help)
                .dataPoint(CounterSnapshot.CounterDataPointSnapshot.builder()
                    .value(value)
                    .build())
                .build();
        }

        private static MetricSnapshot gauge(String name, String help,
                List<RocksChunkStore.Snapshot> snapshots, Field field) {
            GaugeSnapshot.Builder builder = GaugeSnapshot.builder().name(name).help(help);
            for (RocksChunkStore.Snapshot s : snapshots) {
                long value = field.get(s);
                if (value < 0) {
                    continue;
                }
                builder.dataPoint(GaugeSnapshot.GaugeDataPointSnapshot.builder()
                    .labels(labelsFor(s))
                    .value(value)
                    .build());
            }
            return builder.build();
        }

        private static MetricSnapshot counter(String name, String help,
                List<RocksChunkStore.Snapshot> snapshots, Field field) {
            CounterSnapshot.Builder builder =
                CounterSnapshot.builder().name(stripTotal(name)).help(help);
            for (RocksChunkStore.Snapshot s : snapshots) {
                long value = field.get(s);
                if (value < 0) {
                    continue;
                }
                builder.dataPoint(CounterSnapshot.CounterDataPointSnapshot.builder()
                    .labels(labelsFor(s))
                    .value(value)
                    .build());
            }
            return builder.build();
        }

        /**
         * A gauge with one series per column family per database.
         *
         * <p>RocksDB properties come back as -1 from the accessor when unavailable.
         * Emitting that as a real sample would corrupt any aggregation over it, so
         * the series is omitted instead.
         */
        private static MetricSnapshot cfGauge(String name, String help,
                List<RocksDatabase.Snapshot> databases, CfField field) {
            GaugeSnapshot.Builder builder = GaugeSnapshot.builder().name(name).help(help);
            for (RocksDatabase.Snapshot db : databases) {
                for (RocksDatabase.ColumnFamilySnapshot cf : db.columnFamilies) {
                    long value = field.get(cf);
                    if (value < 0) {
                        continue;
                    }
                    builder.dataPoint(GaugeSnapshot.GaugeDataPointSnapshot.builder()
                        .labels(Labels.of(CF_LABELS,
                            new String[] {cf.columnFamily, db.database}))
                        .value(value)
                        .build());
                }
            }
            return builder.build();
        }

        /** A gauge with one series per database. */
        private static MetricSnapshot dbGauge(String name, String help,
                List<RocksDatabase.Snapshot> databases, DbField field) {
            GaugeSnapshot.Builder builder = GaugeSnapshot.builder().name(name).help(help);
            for (RocksDatabase.Snapshot db : databases) {
                long value = field.get(db);
                if (value < 0) {
                    continue;
                }
                builder.dataPoint(GaugeSnapshot.GaugeDataPointSnapshot.builder()
                    .labels(Labels.of(DB_LABELS, new String[] {db.database}))
                    .value(value)
                    .build());
            }
            return builder.build();
        }

        /**
         * Strips a trailing {@code _total} from a counter name.
         *
         * <p>The library models a counter by its base name and appends
         * {@code _total} itself when writing the exposition. Passing the suffixed
         * name through would otherwise yield {@code ..._total_total}, silently
         * renaming every counter and breaking existing dashboards and alerts.
         */
        private static String stripTotal(String name) {
            return name.endsWith("_total")
                ? name.substring(0, name.length() - "_total".length())
                : name;
        }
    }
}
