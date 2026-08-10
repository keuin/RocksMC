package com.keuin.rocksmc;

import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        assertEquals(parse("/a/world/region"), parse("/a/world/region"));
        assertEquals(parse("/a/world/region").hashCode(), parse("/a/world/region").hashCode());
    }

    /**
     * Two worlds' overworlds share an identity and a leaf but address different
     * databases, so they must not compare equal. Before {@link DimensionKey#root()}
     * existed they did, which would let a caller keying on this conflate two worlds.
     */
    @Test
    void keysUnderDifferentRootsAreNotEqual() {
        assertNotEquals(parse("/a/world/region"), parse("/b/world/region"));
    }

    // ------------------------------------------------------------------- root

    /**
     * The world root is what groups a world's six storage directories onto one
     * database, so every layout must yield it. The number of segments to climb
     * differs per layout, which is why it comes from the regex rather than from
     * counting {@code getParentFile()} calls.
     */
    @Test
    void rootOfOverworld() {
        assertEquals(new File("/srv/world"), parse("/srv/world/region").root());
        assertEquals(new File("/srv/world"), parse("/srv/world/poi").root());
    }

    @Test
    void rootOfVanillaNetherAndEnd() {
        assertEquals(new File("/srv/world"), parse("/srv/world/DIM-1/region").root());
        assertEquals(new File("/srv/world"), parse("/srv/world/DIM-1/poi").root());
        assertEquals(new File("/srv/world"), parse("/srv/world/DIM1/region").root());
        assertEquals(new File("/srv/world"), parse("/srv/world/DIM1/poi").root());
    }

    @Test
    void rootOfCustomDimension() {
        assertEquals(new File("/srv/world"),
            parse("/srv/world/dimensions/twilightforest/twilight_forest/region").root());
        assertEquals(new File("/srv/world"),
            parse("/srv/world/dimensions/aether/the_aether/poi").root());
    }

    /** A nested custom path must not be mistaken for part of the root. */
    @Test
    void rootOfCustomDimensionWithNestedPath() {
        assertEquals(new File("/srv/world"),
            parse("/srv/world/dimensions/mypack/deep/nested/dim/region").root());
    }

    /**
     * The property Phase 2 depends on: every storage directory of one world agrees
     * on the root, and that is what makes them share a database.
     */
    @Test
    void everyStorageDirectoryOfAWorldSharesOneRoot() {
        String[] dirs = {
            "/srv/world/region",
            "/srv/world/poi",
            "/srv/world/DIM-1/region",
            "/srv/world/DIM-1/poi",
            "/srv/world/DIM1/region",
            "/srv/world/DIM1/poi",
            "/srv/world/dimensions/twilightforest/twilight_forest/region",
            "/srv/world/dimensions/twilightforest/twilight_forest/poi",
        };
        File expected = new File("/srv/world");
        for (String dir : dirs) {
            assertEquals(expected, parse(dir).root(), dir);
        }
    }

    /** Distinct worlds must not collapse onto one root, or onto one database. */
    @Test
    void distinctWorldsHaveDistinctRoots() {
        assertNotEquals(parse("/srv/world-a/region").root(),
            parse("/srv/world-b/region").root());
        // A world nested inside another world's directory is still its own root.
        assertNotEquals(parse("/srv/world/region").root(),
            parse("/srv/world/nested/region").root());
    }

    /**
     * Backslash-separated paths yield a root with the dimension part stripped.
     *
     * <p>Asserted as a suffix rather than an equality because {@code
     * fromStorageDirectory} resolves through {@code getAbsolutePath()}: on a Linux
     * JVM a {@code C:\...} literal is not absolute, so the working directory gets
     * prepended. That is an artefact of running a Windows path on Linux, not of the
     * parsing. The property under test is that {@code \DIM-1\region} is removed and
     * nothing else is.
     */
    @Test
    void rootWithWindowsSeparators() {
        DimensionKey key = DimensionKey.fromStorageDirectory(
            new File("C:\\srv\\world\\DIM-1\\region"));
        assertEquals(DimensionKey.THE_NETHER, key.identity());
        assertTrue(key.root().getPath().endsWith("C:\\srv\\world"),
            "expected a root ending in C:\\srv\\world, got " + key.root());
        assertFalse(key.root().getPath().contains("DIM-1"),
            "the dimension segment must not remain in the root: " + key.root());
    }

    @Test
    void rootToleratesTrailingSeparator() {
        assertEquals(new File("/srv/world"), parse("/srv/world/region/").root());
    }

    /**
     * A launch path like {@code ./world} must not leak a {@code .} segment into the
     * root, because that root appears in log lines and error messages and would look
     * like a different path to an operator comparing them.
     */
    @Test
    void rootDropsRedundantDotSegments() {
        assertFalse(parse("./world/region").root().getPath().contains("/./"),
            "a '.' segment leaked into the root: " + parse("./world/region").root());
        assertEquals(new File(System.getProperty("user.dir"), "world"),
            parse("./world/region").root());
        assertEquals(new File(System.getProperty("user.dir"), "world"),
            parse("./world/DIM-1/region").root());
    }

    /**
     * A storage directory directly at the filesystem root leaves the regex's root
     * group empty. Mapping that to {@code /} keeps the result absolute; a bare
     * {@code File("")} would resolve against the working directory instead, so a
     * database would be created somewhere unrelated.
     */
    @Test
    void rootAtFilesystemRootStaysAbsolute() {
        File root = parse("/region").root();
        assertEquals(new File("/"), root);
        assertTrue(root.isAbsolute(), "root must stay absolute, got " + root);
    }
}
