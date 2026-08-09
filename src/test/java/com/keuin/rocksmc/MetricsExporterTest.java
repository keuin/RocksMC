package com.keuin.rocksmc;

import io.prometheus.metrics.expositionformats.PrometheusTextFormatWriter;
import io.prometheus.metrics.model.snapshots.MetricSnapshots;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the metric collection and its rendered exposition.
 *
 * <p>These assert on the text the library produces rather than on the snapshot
 * objects, because the failure modes that matter are all visible only after
 * rendering: a {@code -1} sentinel leaking into a sample would silently corrupt
 * aggregations, and a counter named with a redundant {@code _total} suffix would
 * quietly rename every series and break existing dashboards and alerts.
 */
class MetricsExporterTest {

    private static RocksChunkStore open(Path dir, String dimensionPath) throws Exception {
        Properties props = new Properties();
        props.setProperty("backend", "rocksdb");
        return new RocksChunkStore(
            dir.toFile(),
            DimensionKey.fromStorageDirectory(new File(dimensionPath)),
            RocksMcConfig.of(props));
    }

    /** Collects and renders exactly as a scrape would, without binding a port. */
    private static String render() throws Exception {
        MetricSnapshots snapshots = new MetricsExporter.StoreCollector().collect();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrometheusTextFormatWriter.create().write(out, snapshots);
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    @Test
    void rendersExporterLevelMetricsWithNoStoresOpen() throws Exception {
        String out = render();
        assertTrue(out.contains("rocksmc_up 1"), out);
        assertTrue(out.contains("rocksmc_stores 0"), out);
    }

    @Test
    void rendersOneSeriesPerStoreWithDimensionLabels(@TempDir Path tmp) throws Exception {
        try (RocksChunkStore overworld = open(tmp.resolve("ow"), "/w/region");
             RocksChunkStore nether = open(tmp.resolve("nether"), "/w/DIM-1/region")) {

            String out = render();

            assertTrue(out.contains("rocksmc_stores 2"), out);
            assertTrue(out.contains("dimension=\"minecraft:overworld\""), out);
            assertTrue(out.contains("dimension=\"minecraft:the_nether\""), out);
            assertTrue(out.contains("store=\"region\""), out);

            // Ordinals are pinned, so these are stable and worth asserting exactly.
            assertEquals(0, overworld.dimensionOrdinal());
            assertEquals(1, nether.dimensionOrdinal());
            assertTrue(out.contains("rocksmc_dimension_ordinal{database=\"ow\","
                + "dimension=\"minecraft:overworld\",store=\"region\"} 0"), out);
        }
    }

    /**
     * The library appends {@code _total} to counter names itself, so passing an
     * already-suffixed name would produce {@code ..._total_total}.
     */
    @Test
    void counterNamesEndInExactlyOneTotal(@TempDir Path tmp) throws Exception {
        try (RocksChunkStore ignored = open(tmp.resolve("ow"), "/w/region")) {
            String out = render();
            assertFalse(out.contains("_total_total"), "double _total suffix:\n" + out);
            assertTrue(out.contains("rocksmc_chunk_writes_total"), out);
            assertTrue(out.contains("rocksmc_verify_failures_total"), out);
        }
    }

    @Test
    void everyMetricHasHelpAndType(@TempDir Path tmp) throws Exception {
        try (RocksChunkStore ignored = open(tmp.resolve("ow"), "/w/region")) {
            String out = render();
            int help = count(out, "# HELP ");
            int type = count(out, "# TYPE ");
            assertEquals(help, type, "every metric needs both HELP and TYPE:\n" + out);
            assertTrue(help > 10, "expected a substantial metric set, got " + help);
        }
    }

    /**
     * A {@code -1} from an unavailable RocksDB property must be skipped, not
     * emitted: a scraper would otherwise average it into real data.
     */
    @Test
    void negativeSentinelsAreNotEmitted(@TempDir Path tmp) throws Exception {
        try (RocksChunkStore ignored = open(tmp.resolve("ow"), "/w/region")) {
            for (String line : render().split("\n")) {
                if (line.startsWith("#") || line.isEmpty()) {
                    continue;
                }
                String value = line.substring(line.lastIndexOf(' ') + 1);
                assertFalse(value.startsWith("-"),
                    "negative sample leaked into metrics: " + line);
            }
        }
    }

    @Test
    void samplesAreWellFormed(@TempDir Path tmp) throws Exception {
        try (RocksChunkStore ignored = open(tmp.resolve("ow"), "/w/region")) {
            for (String line : render().split("\n")) {
                if (line.startsWith("#") || line.isEmpty()) {
                    continue;
                }
                assertTrue(line.matches("[a-zA-Z_][a-zA-Z0-9_]*(\\{[^}]*\\})? -?[0-9.eE+]+"),
                    "malformed exposition line: " + line);
            }
        }
    }

    /** Series must disappear with the stores they describe, not linger. */
    @Test
    void seriesDisappearWhenStoresClose(@TempDir Path tmp) throws Exception {
        try (RocksChunkStore ignored = open(tmp.resolve("ow"), "/w/region")) {
            assertTrue(render().contains("rocksmc_stores 1"));
        }
        assertTrue(render().contains("rocksmc_stores 0"),
            "closing a store must remove its series");
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        int i = 0;
        while ((i = haystack.indexOf(needle, i)) >= 0) {
            n++;
            i += needle.length();
        }
        return n;
    }
}
