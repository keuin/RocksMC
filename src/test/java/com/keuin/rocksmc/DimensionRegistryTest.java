package com.keuin.rocksmc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests that dimension ordinals are assigned sanely and survive restarts.
 *
 * <p>The point of the registry is that an ordinal, once handed out, always refers
 * to the same dimension. If that ever broke, previously written chunks would be
 * reinterpreted as belonging to a different dimension -- so restart stability is
 * the property most worth pinning down.
 */
class DimensionRegistryTest {

    private static RocksChunkStore open(Path dir, String dimensionPath) throws Exception {
        return new RocksChunkStore(
            dir.toFile(),
            DimensionKey.fromStorageDirectory(new File(dimensionPath)),
            RocksMcConfig.of(new Properties()));
    }

    @Test
    void vanillaOrdinalsArePinned(@TempDir Path tmp) throws Exception {
        // Pinned values mean the common case has a predictable on-disk layout
        // regardless of the order in which worlds happen to load.
        try (RocksChunkStore s = open(tmp.resolve("ow"), "/w/region")) {
            assertEquals(0, s.dimensionOrdinal());
            assertEquals(DimensionKey.OVERWORLD, s.dimensionIdentity());
        }
        try (RocksChunkStore s = open(tmp.resolve("nether"), "/w/DIM-1/region")) {
            assertEquals(1, s.dimensionOrdinal());
        }
        try (RocksChunkStore s = open(tmp.resolve("end"), "/w/DIM1/region")) {
            assertEquals(2, s.dimensionOrdinal());
        }
    }

    @Test
    void customDimensionGetsOrdinalAboveVanillaRange(@TempDir Path tmp) throws Exception {
        try (RocksChunkStore s = open(tmp.resolve("db"),
                "/w/dimensions/twilightforest/twilight_forest/region")) {
            assertTrue(s.dimensionOrdinal() >= 3,
                "custom dimensions must not reuse the pinned vanilla ordinals");
            assertEquals("twilightforest:twilight_forest", s.dimensionIdentity());
        }
    }

    @Test
    void ordinalIsStableAcrossReopen(@TempDir Path tmp) throws Exception {
        Path db = tmp.resolve("db");
        int first;
        try (RocksChunkStore s = open(db, "/w/dimensions/aether/the_aether/region")) {
            first = s.dimensionOrdinal();
        }
        try (RocksChunkStore s = open(db, "/w/dimensions/aether/the_aether/region")) {
            assertEquals(first, s.dimensionOrdinal(),
                "reopening must not reassign an ordinal");
        }
    }

    @Test
    void distinctCustomDimensionsGetDistinctOrdinals(@TempDir Path tmp) throws Exception {
        // This is the regression that motivated the whole change: previously every
        // custom dimension collapsed to 0 and collided with the overworld.
        Path db = tmp.resolve("db");
        int a;
        int b;
        try (RocksChunkStore s = open(db, "/w/dimensions/aether/the_aether/region")) {
            a = s.dimensionOrdinal();
        }
        try (RocksChunkStore s = open(db, "/w/dimensions/twilightforest/twilight_forest/region")) {
            b = s.dimensionOrdinal();
        }
        assertNotEquals(a, b);
        assertNotEquals(0, a);
        assertNotEquals(0, b);
    }

    @Test
    void registryPersistsAllSeenDimensions(@TempDir Path tmp) throws Exception {
        Path db = tmp.resolve("db");
        try (RocksChunkStore s = open(db, "/w/region")) {
            assertEquals(1, s.dimensionRegistry().size());
        }
        try (RocksChunkStore s = open(db, "/w/dimensions/ns/dim/region")) {
            assertEquals(2, s.dimensionRegistry().size());
            assertTrue(s.dimensionRegistry().snapshot().containsKey(DimensionKey.OVERWORLD));
            assertTrue(s.dimensionRegistry().snapshot().containsKey("ns:dim"));
        }
    }

    /**
     * region and poi of the same dimension share an ordinal. They are separate
     * databases today, but once Phase 2 merges them the shared ordinal is what
     * keeps a dimension's chunk and POI data addressable together.
     */
    @Test
    void regionAndPoiOfSameDimensionShareAnOrdinal(@TempDir Path tmp) throws Exception {
        Path db = tmp.resolve("db");
        int region;
        try (RocksChunkStore s = open(db, "/w/dimensions/ns/dim/region")) {
            region = s.dimensionOrdinal();
        }
        try (RocksChunkStore s = open(db, "/w/dimensions/ns/dim/poi")) {
            assertEquals(region, s.dimensionOrdinal());
        }
    }
}
