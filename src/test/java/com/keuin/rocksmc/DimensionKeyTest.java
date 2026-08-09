package com.keuin.rocksmc;

import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for dimension identity derivation.
 *
 * <p>These exist because the previous implementation silently mapped every
 * custom dimension to the overworld, and nothing in the test suite caught it.
 * The cases below cover all four directory layouts vanilla produces, both
 * storage leaves, and the malformed inputs that must fail loudly rather than
 * fall through to a default.
 */
class DimensionKeyTest {

    private static DimensionKey parse(String path) {
        return DimensionKey.fromStorageDirectory(new File(path));
    }

    // ---------------------------------------------------------------- vanilla

    @Test
    void overworldRegion() {
        assertEquals(DimensionKey.OVERWORLD, parse("/srv/world/region").identity());
    }

    @Test
    void overworldPoi() {
        DimensionKey key = parse("/srv/world/poi");
        assertEquals(DimensionKey.OVERWORLD, key.identity());
        assertEquals("poi", key.leaf());
    }

    @Test
    void netherRegion() {
        assertEquals(DimensionKey.THE_NETHER, parse("/srv/world/DIM-1/region").identity());
    }

    @Test
    void netherPoi() {
        assertEquals(DimensionKey.THE_NETHER, parse("/srv/world/DIM-1/poi").identity());
    }

    @Test
    void endRegion() {
        assertEquals(DimensionKey.THE_END, parse("/srv/world/DIM1/region").identity());
    }

    @Test
    void endPoi() {
        assertEquals(DimensionKey.THE_END, parse("/srv/world/DIM1/poi").identity());
    }

    // ----------------------------------------------------------------- custom

    @Test
    void customDimension() {
        assertEquals("twilightforest:twilight_forest",
            parse("/srv/world/dimensions/twilightforest/twilight_forest/region").identity());
    }

    @Test
    void customDimensionPoi() {
        DimensionKey key = parse("/srv/world/dimensions/aether/the_aether/poi");
        assertEquals("aether:the_aether", key.identity());
        assertEquals("poi", key.leaf());
    }

    /** Identifier permits '/' in the path component, so nested paths are legal. */
    @Test
    void customDimensionWithNestedPath() {
        assertEquals("mypack:deep/nested/dim",
            parse("/srv/world/dimensions/mypack/deep/nested/dim/region").identity());
    }

    @Test
    void customDimensionWithDotsAndDashes() {
        assertEquals("my-pack.core:dim_1",
            parse("/srv/world/dimensions/my-pack.core/dim_1/region").identity());
    }

    // ------------------------------------------------------ the original bug

    /**
     * The regression this whole change exists to prevent: custom dimensions must
     * never share an identity with the overworld or with each other.
     */
    @Test
    void customDimensionsDoNotCollideWithOverworld() {
        String overworld = parse("/srv/world/region").identity();
        String twilight = parse("/srv/world/dimensions/twilightforest/twilight_forest/region")
            .identity();
        String aether = parse("/srv/world/dimensions/aether/the_aether/region").identity();

        assertNotEquals(overworld, twilight);
        assertNotEquals(overworld, aether);
        assertNotEquals(twilight, aether);
    }

    /**
     * The old implementation used {@code path.contains("/DIM1")}, so a world
     * stored under a directory whose name merely contained that sequence would be
     * misidentified as the end.
     */
    @Test
    void substringMatchDoesNotMisidentify() {
        assertEquals(DimensionKey.OVERWORLD, parse("/backups/DIM1-archive/world/region").identity());
        assertEquals(DimensionKey.OVERWORLD, parse("/srv/DIM-1-old/world/region").identity());
    }

    // -------------------------------------------------------------- rejection

    @Test
    void rejectsUnknownLeaf() {
        assertThrows(IllegalArgumentException.class, () -> parse("/srv/world/entities"));
        assertThrows(IllegalArgumentException.class, () -> parse("/srv/world/data"));
    }

    @Test
    void rejectsMalformedCustomDimension() {
        // 'dimensions' present but missing the namespace/path pair.
        assertThrows(IllegalArgumentException.class,
            () -> parse("/srv/world/dimensions/region"));
        assertThrows(IllegalArgumentException.class,
            () -> parse("/srv/world/dimensions/onlynamespace/region"));
    }

    @Test
    void rejectsNull() {
        assertThrows(IllegalArgumentException.class,
            () -> DimensionKey.fromStorageDirectory(null));
    }

    @Test
    void rejectsInvalidIdentifierCharacters() {
        // Uppercase is not valid in a namespace, so this cannot be a real
        // dimension directory and must not be silently accepted.
        assertThrows(IllegalArgumentException.class,
            () -> parse("/srv/world/dimensions/BadNamespace/dim/region"));
    }

    // ------------------------------------------------------------- ambiguity

    /**
     * An inherent ambiguity, resolved deliberately in favour of failing.
     *
     * <p>A world stored under a path that itself contains a {@code dimensions}
     * segment is structurally indistinguishable from a malformed custom dimension:
     * {@code /mnt/dimensions/world/region} and
     * {@code /srv/world/dimensions/ns/region} are both "dimensions + one segment +
     * leaf". Telling them apart would require knowing the world root, which this
     * method is not given.
     *
     * <p>Rejecting costs a startup error with an obvious workaround (do not name an
     * ancestor directory {@code dimensions}). Accepting would silently map two
     * dimensions onto one keyspace and destroy terrain. So it rejects.
     */
    @Test
    void rejectsAmbiguousPathsContainingADimensionsSegment() {
        assertThrows(IllegalArgumentException.class,
            () -> parse("/mnt/dimensions/world/region"));
        assertThrows(IllegalArgumentException.class,
            () -> parse("/srv/dimensions/region"));
    }

    /** Only an exact {@code dimensions} segment is ambiguous, not a similar name. */
    @Test
    void similarlyNamedAncestorDirectoriesAreFine() {
        assertEquals(DimensionKey.OVERWORLD,
            parse("/mnt/my-dimensions/world/region").identity());
        assertEquals(DimensionKey.OVERWORLD,
            parse("/mnt/dimensions-backup/world/region").identity());
    }

    /** A vanilla directory nested under a custom dimension is not a real layout. */
    @Test
    void rejectsVanillaDirNestedUnderCustomDimension() {
        assertThrows(IllegalArgumentException.class,
            () -> parse("/srv/world/dimensions/ns/dim/DIM1/region"));
    }

    @Test
    void rejectsEmptyNamespaceOrPath() {
        assertThrows(IllegalArgumentException.class,
            () -> parse("/srv/world/dimensions//dim/region"));
        assertThrows(IllegalArgumentException.class,
            () -> parse("/srv/world/dimensions/ns//region"));
    }

    /** DIM2 is not a vanilla dimension; it is an ordinary directory name. */
    @Test
    void unknownDimDirectoryIsNotTreatedAsVanilla() {
        assertEquals(DimensionKey.OVERWORLD, parse("/srv/world/DIM2/region").identity());
    }

    // -------------------------------------------------------------- mechanics

    @Test
    void windowsSeparatorsParse() {
        assertEquals(DimensionKey.THE_NETHER,
            DimensionKey.fromStorageDirectory(new File("C:\\srv\\world\\DIM-1\\region"))
                .identity());
    }

    @Test
    void trailingSeparatorTolerated() {
        assertEquals(DimensionKey.OVERWORLD, parse("/srv/world/region/").identity());
    }

    /** region and poi are the same dimension but distinct stores. */
    @Test
    void leafDistinguishesStoresWithinADimension() {
        DimensionKey region = parse("/srv/world/region");
        DimensionKey poi = parse("/srv/world/poi");
        assertEquals(region.identity(), poi.identity());
        assertNotEquals(region, poi);
    }

    @Test
    void equalityAndHashing() {
        assertEquals(parse("/a/world/region"), parse("/b/world/region"));
        assertEquals(parse("/a/world/region").hashCode(), parse("/b/world/region").hashCode());
    }
}
