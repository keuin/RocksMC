package com.keuin.rocksmc;

import io.prometheus.metrics.expositionformats.PrometheusTextFormatWriter;
import io.prometheus.metrics.model.snapshots.MetricSnapshots;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.math.ChunkPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
 *
 * <p>The scope tests matter most. Consolidation means a world's six stores share one
 * database, so emitting a shared value per store would make {@code sum()} over disk
 * usage read six times high -- with nothing on a dashboard to indicate it.
 */
class MetricsExporterTest {

    private static RocksChunkStore open(Path worldRoot, String relative) throws Exception {
        Properties props = new Properties();
        props.setProperty("backend", "rocksdb");
        File dir = new File(worldRoot.toFile(), relative);
        return RocksChunkStore.open(
            DimensionKey.fromStorageDirectory(dir),
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
        assertTrue(out.contains("rocksmc_databases 0"), out);
    }

    @Test
    void rendersOneSeriesPerStoreWithDimensionLabels(@TempDir Path tmp) throws Exception {
        try (RocksChunkStore overworld = open(tmp, "region");
             RocksChunkStore nether = open(tmp, "DIM-1/region")) {

            String out = render();

            assertTrue(out.contains("rocksmc_stores 2"), out);
            assertTrue(out.contains("dimension=\"minecraft:overworld\""), out);
            assertTrue(out.contains("dimension=\"minecraft:the_nether\""), out);
            assertTrue(out.contains("store=\"region\""), out);

            // Ordinals are pinned, so these are stable and worth asserting exactly.
            assertEquals(0, overworld.dimensionOrdinal());
            assertEquals(1, nether.dimensionOrdinal());
            assertTrue(out.contains("dimension=\"minecraft:overworld\",store=\"region\"} 0"),
                out);
        }
    }

    /**
     * Two stores, one database: the database-scoped series must appear <b>once</b>.
     *
     * <p>This is the regression the metric split exists to prevent. Emitting these
     * per store would double them here and sextuple them on a real three-dimension
     * world, silently inflating every disk-usage panel.
     */
    @Test
    void databaseScopedSeriesAreEmittedOncePerDatabase(@TempDir Path tmp) throws Exception {
        try (RocksChunkStore region = open(tmp, "region");
             RocksChunkStore poi = open(tmp, "poi");
             RocksChunkStore nether = open(tmp, "DIM-1/region")) {

            String out = render();
            assertTrue(out.contains("rocksmc_stores 3"), out);
            assertTrue(out.contains("rocksmc_databases 1"),
                "three stores must share one database:\n" + out);

            for (String metric : new String[] {"rocksmc_blob_file_bytes",
                    "rocksmc_block_cache_bytes", "rocksmc_write_stopped",
                    "rocksmc_delayed_write_rate", "rocksmc_running_compactions",
                    "rocksmc_running_flushes"}) {
                assertEquals(1, countSeries(out, metric),
                    metric + " must be emitted once per database, not once per store:\n"
                        + out);
            }

            // And it must carry only the database label: a dimension label here
            // would invite a per-dimension breakdown that cannot be honoured.
            for (String line : seriesLines(out, "rocksmc_write_stopped")) {
                assertFalse(line.contains("dimension="),
                    "a database-scoped metric must not claim a dimension: " + line);
            }
            assertEquals(3, region.database().referenceCount());
            assertTrue(poi.database() == nether.database());
        }
    }

    /**
     * Column-family-scoped series appear once per column family, never per dimension.
     *
     * <p>All dimensions share a column family, so an SST size cannot be attributed
     * to one. Two column families exist, so two series -- regardless of how many
     * dimensions are open.
     */
    @Test
    void columnFamilyScopedSeriesAreEmittedOncePerColumnFamily(@TempDir Path tmp)
            throws Exception {
        try (RocksChunkStore ow = open(tmp, "region");
             RocksChunkStore nether = open(tmp, "DIM-1/region");
             RocksChunkStore end = open(tmp, "DIM1/region");
             RocksChunkStore poi = open(tmp, "poi")) {

            // Four stores across two column families.
            String out = render();
            assertTrue(out.contains("rocksmc_stores 4"), out);

            for (String metric : new String[] {"rocksmc_live_sst_bytes_by_cf",
                    "rocksmc_estimated_keys_by_cf", "rocksmc_memtable_bytes_by_cf",
                    "rocksmc_pending_compaction_bytes_by_cf"}) {
                assertEquals(2, countSeries(out, metric),
                    metric + " must have one series per column family:\n" + out);
            }
            assertTrue(out.contains("column_family=\"chunk\""), out);
            assertTrue(out.contains("column_family=\"poi\""), out);

            for (String line : seriesLines(out, "rocksmc_live_sst_bytes_by_cf")) {
                assertFalse(line.contains("dimension="),
                    "a column-family-scoped metric must not claim a dimension: " + line);
            }
        }
    }

    /**
     * The old per-store names for shared values must be gone, not merely relabelled.
     *
     * <p>A query written against the old name would otherwise keep returning data
     * while silently aggregating over a different scope, which is worse than an
     * obvious break.
     */
    @Test
    void oldPerStoreNamesForSharedValuesAreRemoved(@TempDir Path tmp) throws Exception {
        try (RocksChunkStore ignored = open(tmp, "region")) {
            String out = render();
            for (String retired : new String[] {"rocksmc_live_sst_bytes",
                    "rocksmc_total_sst_bytes", "rocksmc_estimated_keys",
                    "rocksmc_memtable_bytes", "rocksmc_pending_compaction_bytes",
                    "rocksmc_compaction_pending", "rocksmc_memtable_flush_pending"}) {
                assertFalse(hasMetric(out, retired),
                    retired + " is now column-family scoped and must be renamed "
                        + "_by_cf so stale queries break loudly:\n" + out);
            }
        }
    }

    /** Per-store IO counters remain per store: they are the mod's own counters. */
    @Test
    void perStoreCountersKeepTheirNamesAndLabels(@TempDir Path tmp) throws Exception {
        try (RocksChunkStore region = open(tmp, "region");
             RocksChunkStore poi = open(tmp, "poi")) {
            region.write(new ChunkPos(0, 0), tag());
            poi.write(new ChunkPos(0, 0), tag());
            poi.write(new ChunkPos(1, 0), tag());

            String out = render();
            assertEquals(2, countSeries(out, "rocksmc_chunk_writes_total"),
                "each store owns its own IO counters:\n" + out);
            assertTrue(out.contains("dimension=\"minecraft:overworld\",store=\"region\"} 1"),
                out);
            assertTrue(out.contains("dimension=\"minecraft:overworld\",store=\"poi\"} 2"),
                out);
        }
    }

    /**
     * The library appends {@code _total} to counter names itself, so passing an
     * already-suffixed name would produce {@code ..._total_total}.
     */
    @Test
    void counterNamesEndInExactlyOneTotal(@TempDir Path tmp) throws Exception {
        try (RocksChunkStore ignored = open(tmp, "region")) {
            String out = render();
            assertFalse(out.contains("_total_total"), "double _total suffix:\n" + out);
            assertTrue(out.contains("rocksmc_chunk_writes_total"), out);
            assertTrue(out.contains("rocksmc_verify_failures_total"), out);
        }
    }

    @Test
    void everyMetricHasHelpAndType(@TempDir Path tmp) throws Exception {
        try (RocksChunkStore ignored = open(tmp, "region")) {
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
        try (RocksChunkStore ignored = open(tmp, "region")) {
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
        try (RocksChunkStore ignored = open(tmp, "region")) {
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
        try (RocksChunkStore ignored = open(tmp, "region")) {
            assertTrue(render().contains("rocksmc_stores 1"));
        }
        String out = render();
        assertTrue(out.contains("rocksmc_stores 0"),
            "closing a store must remove its series");
        assertTrue(out.contains("rocksmc_databases 0"),
            "closing the last store must remove the database series");
    }

    /** The store count drops per store, but the database survives to the last one. */
    @Test
    void databaseSeriesSurvivesUntilTheLastStoreCloses(@TempDir Path tmp) throws Exception {
        RocksChunkStore region = open(tmp, "region");
        RocksChunkStore poi = open(tmp, "poi");
        assertTrue(render().contains("rocksmc_databases 1"));

        region.close();
        String out = render();
        assertTrue(out.contains("rocksmc_stores 1"), out);
        assertTrue(out.contains("rocksmc_databases 1"),
            "the database must persist while another store holds it:\n" + out);

        poi.close();
        assertTrue(render().contains("rocksmc_databases 0"));
    }

    private static NbtCompound tag() {
        NbtCompound nbt = new NbtCompound();
        nbt.putString("marker", "x");
        return nbt;
    }

    /** Whether a metric is present, matched on its HELP line to avoid prefix hits. */
    private static boolean hasMetric(String exposition, String name) {
        return exposition.contains("# HELP " + name + " ")
            || exposition.contains("# TYPE " + name + " ");
    }

    /** Sample lines belonging to one metric, excluding metrics it prefixes. */
    private static List<String> seriesLines(String exposition, String name) {
        List<String> lines = new ArrayList<>();
        for (String line : exposition.split("\n")) {
            if (line.startsWith("#") || line.isEmpty()) {
                continue;
            }
            int brace = line.indexOf('{');
            int space = line.indexOf(' ');
            int end = brace >= 0 && (space < 0 || brace < space) ? brace : space;
            if (end > 0 && line.substring(0, end).equals(name)) {
                lines.add(line);
            }
        }
        return lines;
    }

    private static int countSeries(String exposition, String name) {
        return seriesLines(exposition, name).size();
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
