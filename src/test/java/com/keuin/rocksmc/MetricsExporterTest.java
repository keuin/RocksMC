package com.keuin.rocksmc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the Prometheus rendering.
 *
 * <p>The exposition format is unforgiving: a malformed line makes a scraper reject
 * the whole payload, and a {@code -1} sentinel leaking into a sample would silently
 * corrupt any aggregation over it. Both are cheap to assert and impossible to spot
 * by eye in a wall of metrics.
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

    @Test
    void rendersNothingWhenNoStoresAreOpen() {
        String out = renderViaReflection();
        assertTrue(out.contains("rocksmc_up 1"), out);
        assertTrue(out.contains("rocksmc_stores 0"), out);
    }

    @Test
    void rendersOneSeriesPerStoreWithDimensionLabels(@TempDir Path tmp) throws Exception {
        try (RocksChunkStore overworld = open(tmp.resolve("ow"), "/w/region");
             RocksChunkStore nether = open(tmp.resolve("nether"), "/w/DIM-1/region")) {

            String out = renderViaReflection();

            assertTrue(out.contains("rocksmc_stores 2"), out);
            assertTrue(out.contains("dimension=\"minecraft:overworld\""), out);
            assertTrue(out.contains("dimension=\"minecraft:the_nether\""), out);
            assertTrue(out.contains("store=\"region\""), out);

            // Ordinals are pinned, so these are stable and worth asserting exactly.
            assertTrue(out.contains("rocksmc_dimension_ordinal{dimension=\""
                + "minecraft:overworld\",store=\"region\","
                + "database=\"ow\"} 0"), out);
            assertEquals(0, overworld.dimensionOrdinal());
            assertEquals(1, nether.dimensionOrdinal());
        }
    }

    @Test
    void everyMetricHasHelpAndType(@TempDir Path tmp) throws Exception {
        try (RocksChunkStore ignored = open(tmp.resolve("ow"), "/w/region")) {
            String out = renderViaReflection();
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
            String out = renderViaReflection();
            for (String line : out.split("\n")) {
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
            String out = renderViaReflection();
            for (String line : out.split("\n")) {
                if (line.startsWith("#") || line.isEmpty()) {
                    continue;
                }
                // name[{labels}] value
                assertTrue(line.matches("[a-zA-Z_][a-zA-Z0-9_]*(\\{[^}]*\\})? -?\\d+"),
                    "malformed exposition line: " + line);
            }
        }
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

    /** render() is package-private and does not need a bound port to exercise. */
    private static String renderViaReflection() {
        try {
            java.lang.reflect.Constructor<MetricsExporter> ctor =
                MetricsExporter.class.getDeclaredConstructor(
                    com.sun.net.httpserver.HttpServer.class);
            ctor.setAccessible(true);
            MetricsExporter exporter = ctor.newInstance((Object)null);
            java.lang.reflect.Method render =
                MetricsExporter.class.getDeclaredMethod("render");
            render.setAccessible(true);
            return (String)render.invoke(exporter);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
