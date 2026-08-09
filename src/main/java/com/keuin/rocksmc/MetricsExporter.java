package com.keuin.rocksmc;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Prometheus exporter on {@code /metrics}.
 *
 * <h2>No new dependency</h2>
 *
 * <p>Uses the JDK's own {@code com.sun.net.httpserver}, present since Java 6, and
 * renders the text exposition format by hand. Pulling in the Prometheus client
 * library would add a jar to a mod that already bundles ~50 MB of RocksDB natives,
 * and the format is a few lines of string building.
 *
 * <h2>Scrape safety</h2>
 *
 * <p>Metrics are gathered from {@link RocksChunkStore#snapshot()}, which reads
 * atomics and RocksDB properties without locking. A scrape therefore cannot block
 * the IO worker or the tick loop, and the worst case is values very slightly
 * inconsistent with each other -- the right trade for observability.
 *
 * <p>Binds to loopback by default. Metrics reveal world size and activity, so this
 * should only be exposed deliberately.
 *
 * <h2>Metric design</h2>
 *
 * <p>Every series carries {@code dimension} and {@code store} labels, so a
 * technical server can be queried per dimension -- overworld farms behave nothing
 * like the End. Counters end in {@code _total} and use the {@code counter} type so
 * {@code rate()} works correctly; gauges are instantaneous.
 */
public final class MetricsExporter implements AutoCloseable {

    private static final String CONTENT_TYPE = "text/plain; version=0.0.4; charset=utf-8";

    private final HttpServer server;
    private final AtomicLong scrapes = new AtomicLong();

    private MetricsExporter(HttpServer server) {
        this.server = server;
    }

    /**
     * Starts the exporter, or returns {@code null} if it cannot bind.
     *
     * <p>A failure to bind must not stop the server: telemetry is useful but not
     * load-bearing, and a port clash should be a warning rather than a crash.
     */
    public static MetricsExporter start(String bind, int port) {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(bind, port), 4);
            MetricsExporter exporter = new MetricsExporter(server);
            server.createContext("/metrics", exporter::handleMetrics);
            server.createContext("/", exporter::handleRoot);
            // Daemon threads so a stuck scrape cannot hold up JVM shutdown.
            server.setExecutor(Executors.newFixedThreadPool(2, daemonFactory()));
            server.start();
            RocksMc.logger().info("rocksmc metrics on http://{}:{}/metrics", bind, port);
            return exporter;
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

    private void handleRoot(HttpExchange exchange) throws IOException {
        respond(exchange, 200, "rocksmc metrics exporter\nsee /metrics\n");
    }

    private void handleMetrics(HttpExchange exchange) throws IOException {
        this.scrapes.incrementAndGet();
        try {
            respond(exchange, 200, render());
        } catch (RuntimeException e) {
            // Never let a metrics bug surface as a broken scrape with no explanation.
            RocksMc.logger().warn("rocksmc: metrics rendering failed", e);
            respond(exchange, 500, "# rendering failed: " + e + "\n");
        }
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", CONTENT_TYPE);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    /** Renders the current state in Prometheus text exposition format. */
    String render() {
        List<RocksChunkStore> stores = StoreRegistry.stores();
        List<RocksChunkStore.Snapshot> snapshots = new ArrayList<>(stores.size());
        for (RocksChunkStore store : stores) {
            snapshots.add(store.snapshot());
        }

        StringBuilder sb = new StringBuilder(4096);

        gauge(sb, "rocksmc_up", "1 if the exporter is serving", () ->
            line(sb, "rocksmc_up", "", 1));

        gauge(sb, "rocksmc_stores", "Number of open RocksDB stores", () ->
            line(sb, "rocksmc_stores", "", snapshots.size()));

        counter(sb, "rocksmc_scrapes_total", "Metrics scrapes served", () ->
            line(sb, "rocksmc_scrapes_total", "", this.scrapes.get()));

        // ---- per-store counters ----
        counter(sb, "rocksmc_chunk_reads_total", "Chunks read from storage",
            () -> forEach(sb, snapshots, "rocksmc_chunk_reads_total", s -> s.reads));
        counter(sb, "rocksmc_chunk_writes_total", "Chunks written to storage",
            () -> forEach(sb, snapshots, "rocksmc_chunk_writes_total", s -> s.writes));
        counter(sb, "rocksmc_bytes_read_total", "Uncompressed NBT bytes read",
            () -> forEach(sb, snapshots, "rocksmc_bytes_read_total", s -> s.bytesRead));
        counter(sb, "rocksmc_bytes_written_total", "Uncompressed NBT bytes written",
            () -> forEach(sb, snapshots, "rocksmc_bytes_written_total", s -> s.bytesWritten));

        // Failure counters are the ones worth alerting on: any non-zero value means
        // the storage layer is not doing its job.
        counter(sb, "rocksmc_read_failures_total", "Failed chunk reads",
            () -> forEach(sb, snapshots, "rocksmc_read_failures_total", s -> s.readFailures));
        counter(sb, "rocksmc_write_failures_total", "Failed chunk writes",
            () -> forEach(sb, snapshots, "rocksmc_write_failures_total", s -> s.writeFailures));
        counter(sb, "rocksmc_verify_failures_total",
            "Round-trip verification failures (verify-on-read)",
            () -> forEach(sb, snapshots, "rocksmc_verify_failures_total",
                s -> s.verifyFailures));

        // ---- per-store gauges ----
        gauge(sb, "rocksmc_live_sst_bytes", "Live SST file bytes",
            () -> forEach(sb, snapshots, "rocksmc_live_sst_bytes", s -> s.liveSstBytes));
        gauge(sb, "rocksmc_total_sst_bytes", "All SST file bytes including obsolete",
            () -> forEach(sb, snapshots, "rocksmc_total_sst_bytes", s -> s.totalSstBytes));
        gauge(sb, "rocksmc_blob_file_bytes",
            "Blob file bytes on disk, where chunk values live",
            () -> forEach(sb, snapshots, "rocksmc_blob_file_bytes", s -> s.blobFileBytes));
        gauge(sb, "rocksmc_estimated_keys", "Estimated number of stored chunks",
            () -> forEach(sb, snapshots, "rocksmc_estimated_keys", s -> s.estimatedKeys));
        gauge(sb, "rocksmc_memtable_bytes", "Memory held by memtables",
            () -> forEach(sb, snapshots, "rocksmc_memtable_bytes", s -> s.memtableBytes));
        gauge(sb, "rocksmc_block_cache_bytes", "Block cache usage",
            () -> forEach(sb, snapshots, "rocksmc_block_cache_bytes",
                s -> s.blockCacheBytes));

        // Compaction backlog is the leading indicator of trouble on a technical
        // server: it rises long before writes actually stall.
        gauge(sb, "rocksmc_pending_compaction_bytes",
            "Estimated bytes of compaction still owed",
            () -> forEach(sb, snapshots, "rocksmc_pending_compaction_bytes",
                s -> s.pendingCompactionBytes));
        gauge(sb, "rocksmc_running_compactions", "Compactions in flight",
            () -> forEach(sb, snapshots, "rocksmc_running_compactions",
                s -> s.runningCompactions));
        gauge(sb, "rocksmc_running_flushes", "Memtable flushes in flight",
            () -> forEach(sb, snapshots, "rocksmc_running_flushes",
                s -> s.runningFlushes));
        gauge(sb, "rocksmc_compaction_pending", "1 if a compaction is queued",
            () -> forEach(sb, snapshots, "rocksmc_compaction_pending",
                s -> s.compactionPending));
        gauge(sb, "rocksmc_memtable_flush_pending", "1 if a flush is queued",
            () -> forEach(sb, snapshots, "rocksmc_memtable_flush_pending",
                s -> s.memtableFlushPending));

        // These two are the "your server is about to lag" signals.
        gauge(sb, "rocksmc_delayed_write_rate",
            "Throttled write rate in bytes/sec, 0 when not throttling",
            () -> forEach(sb, snapshots, "rocksmc_delayed_write_rate",
                s -> s.delayedWriteRate));
        gauge(sb, "rocksmc_write_stopped", "1 if writes are fully stopped",
            () -> forEach(sb, snapshots, "rocksmc_write_stopped", s -> s.writeStopped));

        gauge(sb, "rocksmc_dimension_ordinal",
            "Ordinal assigned to this dimension in the key encoding",
            () -> forEach(sb, snapshots, "rocksmc_dimension_ordinal",
                s -> s.dimensionOrdinal));

        return sb.toString();
    }

    private interface Emitter {
        void emit();
    }

    private interface Field {
        long get(RocksChunkStore.Snapshot snapshot);
    }

    private static void gauge(StringBuilder sb, String name, String help, Emitter body) {
        sb.append("# HELP ").append(name).append(' ').append(help).append('\n');
        sb.append("# TYPE ").append(name).append(" gauge\n");
        body.emit();
    }

    private static void counter(StringBuilder sb, String name, String help, Emitter body) {
        sb.append("# HELP ").append(name).append(' ').append(help).append('\n');
        sb.append("# TYPE ").append(name).append(" counter\n");
        body.emit();
    }

    private static void forEach(StringBuilder sb, List<RocksChunkStore.Snapshot> snapshots,
            String name, Field field) {
        for (RocksChunkStore.Snapshot s : snapshots) {
            // RocksDB returns -1 from our accessor when a property is unavailable.
            // Emitting that as a real sample would corrupt any aggregation, so skip.
            long value = field.get(s);
            if (value < 0) {
                continue;
            }
            line(sb, name, labels(s), value);
        }
    }

    private static String labels(RocksChunkStore.Snapshot s) {
        return "dimension=\"" + escape(s.dimension) + "\","
            + "store=\"" + escape(s.leaf) + "\","
            + "database=\"" + escape(s.database) + "\"";
    }

    private static void line(StringBuilder sb, String name, String labels, long value) {
        sb.append(name);
        if (!labels.isEmpty()) {
            sb.append('{').append(labels).append('}');
        }
        sb.append(' ').append(value).append('\n');
    }

    /** Escapes a label value per the exposition format. */
    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\':
                    out.append("\\\\");
                    break;
                case '"':
                    out.append("\\\"");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                default:
                    out.append(c);
            }
        }
        return out.toString();
    }

    @Override
    public void close() {
        if (this.server != null) {
            this.server.stop(0);
        }
    }
}
