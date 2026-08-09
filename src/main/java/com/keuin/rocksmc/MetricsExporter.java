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
 * <h2>Why a MultiCollector rather than registered metric objects</h2>
 *
 * <p>The obvious use of this library is to create {@code Counter} and {@code Gauge}
 * instances and increment them at the call sites. That does not fit here: the set
 * of series is not fixed at startup. Stores open lazily -- a dimension's store only
 * exists once something loads that dimension -- and close again on shutdown, so the
 * label combinations present at any moment are whatever {@link StoreRegistry}
 * currently holds.
 *
 * <p>A {@link MultiCollector} is the library's answer to exactly that: it is asked
 * for a fresh set of snapshots on every scrape, so series appear and disappear with
 * the stores they describe. It also keeps the mod's hot path free of metric
 * plumbing, since the counters already exist as {@code AtomicLong}s inside
 * {@link RocksChunkStore} and RocksDB's own gauges have to be polled regardless.
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
 * <p>{@link RocksChunkStore#snapshot()} reads atomics and RocksDB properties
 * without locking, so a scrape cannot block the IO worker or the tick loop. The
 * worst case is values very slightly inconsistent with each other, which is the
 * right trade for observability.
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
     * Collects one snapshot per open store on every scrape.
     *
     * <p>Package-private and separately testable, so the exposition can be
     * exercised without binding a port.
     */
    static final class StoreCollector implements MultiCollector {

        private static final String[] LABEL_NAMES = {"dimension", "store", "database"};

        private final AtomicLong scrapes = new AtomicLong();

        @Override
        public MetricSnapshots collect() {
            this.scrapes.incrementAndGet();

            List<RocksChunkStore> stores = StoreRegistry.stores();
            List<RocksChunkStore.Snapshot> snapshots = new ArrayList<>(stores.size());
            for (RocksChunkStore store : stores) {
                snapshots.add(store.snapshot());
            }

            List<MetricSnapshot> out = new ArrayList<>();

            // ---- exporter-level ----
            out.add(gauge("rocksmc_up", "1 if the exporter is serving", 1));
            out.add(gauge("rocksmc_stores", "Number of open RocksDB stores",
                snapshots.size()));
            out.add(counter("rocksmc_scrapes_total", "Metrics scrapes served",
                this.scrapes.get()));

            // ---- per-store counters ----
            out.add(counter("rocksmc_chunk_reads_total", "Chunks read from storage",
                snapshots, s -> s.reads));
            out.add(counter("rocksmc_chunk_writes_total", "Chunks written to storage",
                snapshots, s -> s.writes));
            out.add(counter("rocksmc_bytes_read_total", "Uncompressed NBT bytes read",
                snapshots, s -> s.bytesRead));
            out.add(counter("rocksmc_bytes_written_total",
                "Uncompressed NBT bytes written", snapshots, s -> s.bytesWritten));

            // Failure counters are the ones worth alerting on: any non-zero value
            // means the storage layer is not doing its job.
            out.add(counter("rocksmc_read_failures_total", "Failed chunk reads",
                snapshots, s -> s.readFailures));
            out.add(counter("rocksmc_write_failures_total", "Failed chunk writes",
                snapshots, s -> s.writeFailures));
            out.add(counter("rocksmc_verify_failures_total",
                "Round-trip verification failures (verify-on-read)",
                snapshots, s -> s.verifyFailures));

            // ---- per-store gauges ----
            out.add(gauge("rocksmc_live_sst_bytes", "Live SST file bytes",
                snapshots, s -> s.liveSstBytes));
            out.add(gauge("rocksmc_total_sst_bytes",
                "All SST file bytes including obsolete", snapshots,
                s -> s.totalSstBytes));
            out.add(gauge("rocksmc_blob_file_bytes",
                "Blob file bytes on disk, where chunk values live", snapshots,
                s -> s.blobFileBytes));
            out.add(gauge("rocksmc_estimated_keys",
                "Estimated number of stored chunks", snapshots,
                s -> s.estimatedKeys));
            out.add(gauge("rocksmc_memtable_bytes", "Memory held by memtables",
                snapshots, s -> s.memtableBytes));
            out.add(gauge("rocksmc_block_cache_bytes", "Block cache usage",
                snapshots, s -> s.blockCacheBytes));

            // Compaction backlog is the leading indicator of trouble on a technical
            // server: it rises long before writes actually stall.
            out.add(gauge("rocksmc_pending_compaction_bytes",
                "Estimated bytes of compaction still owed", snapshots,
                s -> s.pendingCompactionBytes));
            out.add(gauge("rocksmc_running_compactions", "Compactions in flight",
                snapshots, s -> s.runningCompactions));
            out.add(gauge("rocksmc_running_flushes", "Memtable flushes in flight",
                snapshots, s -> s.runningFlushes));
            out.add(gauge("rocksmc_compaction_pending",
                "1 if a compaction is queued", snapshots, s -> s.compactionPending));
            out.add(gauge("rocksmc_memtable_flush_pending", "1 if a flush is queued",
                snapshots, s -> s.memtableFlushPending));

            // These two are the "your server is about to lag" signals.
            out.add(gauge("rocksmc_delayed_write_rate",
                "Throttled write rate in bytes/sec, 0 when not throttling",
                snapshots, s -> s.delayedWriteRate));
            out.add(gauge("rocksmc_write_stopped", "1 if writes are fully stopped",
                snapshots, s -> s.writeStopped));

            out.add(gauge("rocksmc_dimension_ordinal",
                "Ordinal assigned to this dimension in the key encoding",
                snapshots, s -> s.dimensionOrdinal));

            return new MetricSnapshots(out);
        }

        private interface Field {
            long get(RocksChunkStore.Snapshot snapshot);
        }

        private static Labels labelsFor(RocksChunkStore.Snapshot s) {
            return Labels.of(LABEL_NAMES,
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
                // RocksDB properties come back as -1 from our accessor when
                // unavailable. Emitting that as a real sample would corrupt any
                // aggregation over it, so the series is omitted instead.
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
