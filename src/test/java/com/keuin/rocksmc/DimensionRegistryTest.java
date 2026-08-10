package com.keuin.rocksmc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests that dimension ordinals are assigned sanely and survive restarts.
 *
 * <p>The point of the registry is that an ordinal, once handed out, always refers
 * to the same dimension. If that ever broke, previously written chunks would be
 * reinterpreted as belonging to a different dimension -- so restart stability is
 * the property most worth pinning down.
 *
 * <p>Ordinals matter more now than they used to. Under the old one-database-per-
 * store layout an ordinal was merely a prefix inside a database holding one
 * dimension; now every dimension shares a column family and the ordinal is the
 * only thing keeping them apart.
 */
class DimensionRegistryTest {

    /**
     * Opens a store for a dimension whose world root is {@code worldRoot}.
     *
     * <p>Paths are built under the temp directory rather than being absolute
     * literals, because the database is now created inside the world root and the
     * test must not write outside its sandbox.
     */
    private static RocksChunkStore open(Path worldRoot, String relative) throws Exception {
        File dir = new File(worldRoot.toFile(), relative);
        return RocksChunkStore.open(
            DimensionKey.fromStorageDirectory(dir),
            RocksMcConfig.of(new Properties()));
    }

    @Test
    void vanillaOrdinalsArePinned(@TempDir Path tmp) throws Exception {
        // Pinned values mean the common case has a predictable on-disk layout
        // regardless of the order in which worlds happen to load. Separate roots so
        // each opens its own database, proving the pinning is not an artefact of
        // first-seen order.
        try (RocksChunkStore s = open(tmp.resolve("a"), "region")) {
            assertEquals(0, s.dimensionOrdinal());
            assertEquals(DimensionKey.OVERWORLD, s.dimensionIdentity());
        }
        try (RocksChunkStore s = open(tmp.resolve("b"), "DIM-1/region")) {
            assertEquals(1, s.dimensionOrdinal());
        }
        try (RocksChunkStore s = open(tmp.resolve("c"), "DIM1/region")) {
            assertEquals(2, s.dimensionOrdinal());
        }
    }

    @Test
    void customDimensionGetsOrdinalAboveVanillaRange(@TempDir Path tmp) throws Exception {
        try (RocksChunkStore s = open(tmp,
                "dimensions/twilightforest/twilight_forest/region")) {
            assertTrue(s.dimensionOrdinal() >= 3,
                "custom dimensions must not reuse the pinned vanilla ordinals");
            assertEquals("twilightforest:twilight_forest", s.dimensionIdentity());
        }
    }

    @Test
    void ordinalIsStableAcrossReopen(@TempDir Path tmp) throws Exception {
        int first;
        try (RocksChunkStore s = open(tmp, "dimensions/aether/the_aether/region")) {
            first = s.dimensionOrdinal();
        }
        try (RocksChunkStore s = open(tmp, "dimensions/aether/the_aether/region")) {
            assertEquals(first, s.dimensionOrdinal(),
                "reopening must not reassign an ordinal");
        }
    }

    @Test
    void distinctCustomDimensionsGetDistinctOrdinals(@TempDir Path tmp) throws Exception {
        // This is the regression that motivated the whole change: previously every
        // custom dimension collapsed to 0 and collided with the overworld.
        int a;
        int b;
        try (RocksChunkStore s = open(tmp, "dimensions/aether/the_aether/region")) {
            a = s.dimensionOrdinal();
        }
        try (RocksChunkStore s = open(tmp,
                "dimensions/twilightforest/twilight_forest/region")) {
            b = s.dimensionOrdinal();
        }
        assertNotEquals(a, b);
        assertNotEquals(0, a);
        assertNotEquals(0, b);
    }

    @Test
    void registryPersistsAllSeenDimensions(@TempDir Path tmp) throws Exception {
        try (RocksChunkStore s = open(tmp, "region")) {
            assertEquals(1, s.dimensionRegistry().size());
        }
        try (RocksChunkStore s = open(tmp, "dimensions/ns/dim/region")) {
            assertEquals(2, s.dimensionRegistry().size());
            assertTrue(s.dimensionRegistry().snapshot().containsKey(DimensionKey.OVERWORLD));
            assertTrue(s.dimensionRegistry().snapshot().containsKey("ns:dim"));
        }
    }

    /**
     * region and poi of the same dimension share an ordinal, which is what keeps a
     * dimension's chunk and POI data addressable together now that both live in one
     * database under different column families.
     */
    @Test
    void regionAndPoiOfSameDimensionShareAnOrdinal(@TempDir Path tmp) throws Exception {
        try (RocksChunkStore region = open(tmp, "region");
             RocksChunkStore poi = open(tmp, "poi")) {
            assertEquals(region.dimensionOrdinal(), poi.dimensionOrdinal());
            // ...and they are views onto the same database, not two databases.
            assertSame(region.database(), poi.database());
        }
    }

    /**
     * A stale {@code \0next} counter must never hand out an ordinal already in use.
     *
     * <p>The counter is advanced in the same batch as the assignment it belongs to,
     * so the two cannot disagree; this pins the defensive scan that would catch it
     * if they ever did.
     */
    @Test
    void ordinalsAreNeverReusedAcrossManyDimensions(@TempDir Path tmp) throws Exception {
        Set<Integer> seen = new HashSet<>();
        for (int i = 0; i < 8; i++) {
            try (RocksChunkStore s = open(tmp, "dimensions/ns/dim" + i + "/region")) {
                assertTrue(seen.add(s.dimensionOrdinal()),
                    "ordinal " + s.dimensionOrdinal() + " handed out twice");
            }
        }
        // Plus the three pinned ones, none of which may collide with the above.
        try (RocksChunkStore s = open(tmp, "region")) {
            assertTrue(seen.add(s.dimensionOrdinal()));
        }
        try (RocksChunkStore s = open(tmp, "DIM-1/region")) {
            assertTrue(seen.add(s.dimensionOrdinal()));
        }
        try (RocksChunkStore s = open(tmp, "DIM1/region")) {
            assertTrue(seen.add(s.dimensionOrdinal()));
        }
        assertEquals(11, seen.size());
    }
}
