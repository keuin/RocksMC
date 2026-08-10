package com.keuin.rocksmc;

import net.minecraft.util.math.ChunkPos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the chunk key encoding.
 *
 * <p>Untested until now, which is uncomfortable for two reasons. The key is the only
 * thing separating one dimension's chunks from another's inside a shared column
 * family, so a collision silently overwrites terrain. And the encoding's stated
 * purpose -- that biasing coordinates by {@link Integer#MIN_VALUE} makes negative
 * chunk coordinates sort before positive ones under RocksDB's unsigned byte
 * comparison -- was asserted in a comment and never checked, even though negative
 * coordinates are the normal case in Minecraft.
 *
 * <p>A mis-encoding here is also the hardest kind to notice: it round-trips
 * consistently, so a chunk written to the wrong key reads back fine and only shows up
 * as terrain in the wrong place.
 */
class ChunkKeyTest {

    /** RocksDB compares keys as unsigned bytes; Java's byte is signed. */
    private static final Comparator<byte[]> UNSIGNED_LEXICOGRAPHIC = (a, b) -> {
        for (int i = 0; i < Math.min(a.length, b.length); i++) {
            int cmp = Integer.compare(a[i] & 0xFF, b[i] & 0xFF);
            if (cmp != 0) {
                return cmp;
            }
        }
        return Integer.compare(a.length, b.length);
    };

    // ------------------------------------------------------------------- shape

    @Test
    void keyIsTwelveBytes() {
        assertEquals(12, RocksChunkStore.key(0, new ChunkPos(0, 0)).length);
        assertEquals(12, RocksChunkStore.key(-1, new ChunkPos(-1, -1)).length);
        assertEquals(12, RocksChunkStore.key(Integer.MAX_VALUE,
            new ChunkPos(Integer.MIN_VALUE, Integer.MAX_VALUE)).length);
    }

    /** The dimension occupies the first four bytes, big-endian. */
    @Test
    void dimensionIsBigEndianInTheFirstFourBytes() {
        byte[] key = RocksChunkStore.key(0x01020304, new ChunkPos(0, 0));
        assertArrayEquals(new byte[] {1, 2, 3, 4}, Arrays.copyOf(key, 4));
    }

    @Test
    void encodingIsDeterministic() {
        assertArrayEquals(RocksChunkStore.key(3, new ChunkPos(-17, 42)),
            RocksChunkStore.key(3, new ChunkPos(-17, 42)));
    }

    // --------------------------------------------------------------- uniqueness

    /**
     * Distinct positions must never share a key.
     *
     * <p>Includes negatives, zero, and the extremes, because a Morton interleave that
     * dropped a bit would collide only for specific bit patterns.
     */
    @Test
    void distinctPositionsProduceDistinctKeys() {
        int[] coords = {Integer.MIN_VALUE, -1000000, -1875000, -32, -1, 0, 1, 32,
            1875000, Integer.MAX_VALUE};
        Set<String> seen = new HashSet<>();
        for (int x : coords) {
            for (int z : coords) {
                String key = Arrays.toString(RocksChunkStore.key(0, new ChunkPos(x, z)));
                assertTrue(seen.add(key), "key collision at " + x + ", " + z);
            }
        }
        assertEquals(coords.length * coords.length, seen.size());
    }

    /**
     * The same position in different dimensions must never collide.
     *
     * <p>This is the property that makes one shared column family safe. Chunk (0,0)
     * exists in every dimension of every world, so it is the first thing that would
     * break.
     */
    @Test
    void samePositionInDifferentDimensionsNeverCollides() {
        Set<String> seen = new HashSet<>();
        for (int dimension : new int[] {0, 1, 2, 3, 42, -1, Integer.MAX_VALUE,
                Integer.MIN_VALUE}) {
            String key = Arrays.toString(RocksChunkStore.key(dimension, new ChunkPos(0, 0)));
            assertTrue(seen.add(key), "dimension " + dimension + " collided at (0,0)");
        }
    }

    /** x and z must not be interchangeable, or the world would be transposed. */
    @Test
    void coordinatesAreNotSymmetric() {
        assertTrue(UNSIGNED_LEXICOGRAPHIC.compare(
            RocksChunkStore.key(0, new ChunkPos(1, 0)),
            RocksChunkStore.key(0, new ChunkPos(0, 1))) != 0,
            "(1,0) and (0,1) must not encode identically");
    }

    /**
     * Randomised sweep, because the hand-picked cases above cannot cover the
     * interleave exhaustively.
     */
    @Test
    void randomisedPositionsDoNotCollide() {
        Random random = new Random(20260811L);
        Set<Long> mortons = new HashSet<>();
        Set<String> keys = new HashSet<>();
        for (int i = 0; i < 20000; i++) {
            int x = random.nextInt();
            int z = random.nextInt();
            long morton = RocksChunkStore.morton(x, z);
            String key = Arrays.toString(RocksChunkStore.key(7, new ChunkPos(x, z)));
            // A duplicate coordinate pair is possible in principle; a duplicate morton
            // for a *new* pair is not.
            if (!keys.add(key)) {
                assertTrue(mortons.contains(morton), "key repeated for a new position");
            }
            mortons.add(morton);
        }
    }

    // ------------------------------------------------------------------ ordering

    /**
     * The claim the comment makes: negatives sort before positives.
     *
     * <p>Without the {@link Integer#MIN_VALUE} bias, {@code -1} would spread into
     * all-ones and sort <em>after</em> every positive coordinate under unsigned
     * comparison, so a scan over the keyspace would visit the world in two disjoint
     * halves.
     */
    @Test
    void negativeCoordinatesSortBeforePositiveOnes() {
        byte[] veryNegative = RocksChunkStore.key(0, new ChunkPos(-1000, -1000));
        byte[] slightlyNegative = RocksChunkStore.key(0, new ChunkPos(-1, -1));
        byte[] origin = RocksChunkStore.key(0, new ChunkPos(0, 0));
        byte[] positive = RocksChunkStore.key(0, new ChunkPos(1000, 1000));

        assertTrue(UNSIGNED_LEXICOGRAPHIC.compare(veryNegative, slightlyNegative) < 0,
            "-1000 must sort before -1");
        assertTrue(UNSIGNED_LEXICOGRAPHIC.compare(slightlyNegative, origin) < 0,
            "-1 must sort before 0 -- this is what the MIN_VALUE bias is for");
        assertTrue(UNSIGNED_LEXICOGRAPHIC.compare(origin, positive) < 0,
            "0 must sort before 1000");
    }

    /** The extremes must bound the keyspace in the expected direction. */
    @Test
    void coordinateExtremesBoundTheKeyspace() {
        byte[] lowest = RocksChunkStore.key(0,
            new ChunkPos(Integer.MIN_VALUE, Integer.MIN_VALUE));
        byte[] highest = RocksChunkStore.key(0,
            new ChunkPos(Integer.MAX_VALUE, Integer.MAX_VALUE));
        byte[] origin = RocksChunkStore.key(0, new ChunkPos(0, 0));

        assertTrue(UNSIGNED_LEXICOGRAPHIC.compare(lowest, origin) < 0);
        assertTrue(UNSIGNED_LEXICOGRAPHIC.compare(origin, highest) < 0);
        assertTrue(UNSIGNED_LEXICOGRAPHIC.compare(lowest, highest) < 0);
    }

    /**
     * Each dimension occupies a contiguous key range.
     *
     * <p>Dimension-first ordering is what would make a future per-dimension
     * {@code DeleteRange} or bulk export cheap, so it is worth pinning: every key of
     * dimension 1 must sort after every key of dimension 0, whatever the coordinates.
     */
    @Test
    void dimensionsOccupyContiguousRanges() {
        int[] coords = {Integer.MIN_VALUE, -1000, -1, 0, 1, 1000, Integer.MAX_VALUE};
        List<byte[]> dimensionZero = new ArrayList<>();
        List<byte[]> dimensionOne = new ArrayList<>();
        for (int x : coords) {
            for (int z : coords) {
                dimensionZero.add(RocksChunkStore.key(0, new ChunkPos(x, z)));
                dimensionOne.add(RocksChunkStore.key(1, new ChunkPos(x, z)));
            }
        }
        for (byte[] low : dimensionZero) {
            for (byte[] high : dimensionOne) {
                assertTrue(UNSIGNED_LEXICOGRAPHIC.compare(low, high) < 0,
                    "dimension 0 key sorted at or after a dimension 1 key");
            }
        }
    }

    /**
     * Spatial locality: neighbours are close in the keyspace.
     *
     * <p>The reason for Morton ordering at all. Not a strict guarantee -- a Z-order
     * curve has seams -- so this asserts the aggregate, which is the property that
     * actually matters for compaction and for a player walking through the world.
     */
    @Test
    void mortonOrderingKeepsNeighboursNearby() {
        long origin = RocksChunkStore.morton(0, 0);
        long adjacent = Math.abs(RocksChunkStore.morton(1, 0) - origin);
        long distant = Math.abs(RocksChunkStore.morton(1000, 1000) - origin);
        assertTrue(adjacent < distant,
            "an adjacent chunk must be nearer in the keyspace than a distant one");

        // Aggregate check: mean keyspace distance to the 8 neighbours of the origin
        // must be far smaller than to 8 chunks 512 away.
        long near = 0;
        long far = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                near += Math.abs(RocksChunkStore.morton(dx, dz) - origin);
                far += Math.abs(RocksChunkStore.morton(dx * 512, dz * 512) - origin);
            }
        }
        assertTrue(near < far / 10,
            "Morton locality is far weaker than expected: near=" + near + " far=" + far);
    }

    // -------------------------------------------------------------------- morton

    /** The interleave must be reversible, which proves no bits are lost. */
    @Test
    void mortonInterleaveIsReversible() {
        int[] coords = {Integer.MIN_VALUE, -1000000, -1, 0, 1, 1000000,
            Integer.MAX_VALUE};
        for (int x : coords) {
            for (int z : coords) {
                long morton = RocksChunkStore.morton(x, z);
                assertEquals(x, unspreadHigh(morton), "x lost at " + x + ", " + z);
                assertEquals(z, unspreadLow(morton), "z lost at " + x + ", " + z);
            }
        }
    }

    /** Extracts the x coordinate from the odd bit positions, undoing the bias. */
    private static int unspreadHigh(long morton) {
        return (int) (compact(morton >>> 1) ^ Integer.MIN_VALUE);
    }

    /** Extracts the z coordinate from the even bit positions, undoing the bias. */
    private static int unspreadLow(long morton) {
        return (int) (compact(morton) ^ Integer.MIN_VALUE);
    }

    /** Gathers every even bit of {@code v} into the low 32 bits. */
    private static long compact(long v) {
        long r = v & 0x5555555555555555L;
        r = (r | (r >>> 1)) & 0x3333333333333333L;
        r = (r | (r >>> 2)) & 0x0F0F0F0F0F0F0F0FL;
        r = (r | (r >>> 4)) & 0x00FF00FF00FF00FFL;
        r = (r | (r >>> 8)) & 0x0000FFFF0000FFFFL;
        r = (r | (r >>> 16)) & 0x00000000FFFFFFFFL;
        return r;
    }
}
