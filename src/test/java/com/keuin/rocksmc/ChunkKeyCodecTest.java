package com.keuin.rocksmc;

import net.minecraft.util.math.ChunkPos;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the key codec's decode direction and its region ranges.
 *
 * <p>{@code ChunkKeyTest} covers the encoder: uniqueness, ordering, the negative-first
 * bias, locality. This covers what exporting adds — turning a key back into a position,
 * and the region grouping the export design depends on.
 *
 * <p>The decode direction has a nasty failure mode. A decoder that disagreed with the
 * encoder would place exported terrain at the wrong coordinates <em>consistently</em>,
 * so a naive round trip would pass and the only symptom would be a shifted or
 * duplicated world seen in a third-party tool.
 */
class ChunkKeyCodecTest {

    /** Coordinates that have historically broken bit-twiddling: extremes and negatives. */
    private static final int[] INTERESTING = {
        Integer.MIN_VALUE, -1875000, -1000000, -1024, -33, -32, -1, 0, 1, 32, 33,
        1000000, 1875000, Integer.MAX_VALUE
    };

    // ------------------------------------------------------------------- decode

    /** Encode then decode must be the identity, including at the extremes. */
    @Test
    void keyRoundTripsToTheSamePosition() {
        for (int x : INTERESTING) {
            for (int z : INTERESTING) {
                ChunkPos original = new ChunkPos(x, z);
                byte[] key = ChunkKeyCodec.key(7, original);
                assertEquals(original, ChunkKeyCodec.position(key),
                    "round trip failed at " + x + ", " + z);
                assertEquals(7, ChunkKeyCodec.dimensionOrdinal(key));
            }
        }
    }

    /** The dimension ordinal must survive the round trip too, including negatives. */
    @Test
    void dimensionOrdinalRoundTrips() {
        for (int ordinal : new int[] {Integer.MIN_VALUE, -1, 0, 1, 2, 3, 42,
                Integer.MAX_VALUE}) {
            byte[] key = ChunkKeyCodec.key(ordinal, new ChunkPos(-5, 9));
            assertEquals(ordinal, ChunkKeyCodec.dimensionOrdinal(key));
            assertEquals(new ChunkPos(-5, 9), ChunkKeyCodec.position(key));
        }
    }

    /** Randomised sweep, since the hand-picked cases cannot cover the interleave. */
    @Test
    void randomisedKeysRoundTrip() {
        Random random = new Random(20260815L);
        for (int i = 0; i < 50000; i++) {
            ChunkPos pos = new ChunkPos(random.nextInt(), random.nextInt());
            assertEquals(pos, ChunkKeyCodec.position(ChunkKeyCodec.key(3, pos)));
        }
    }

    /** A malformed key must be rejected, not silently decoded to a plausible position. */
    @Test
    void malformedKeysAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> ChunkKeyCodec.position(null));
        assertThrows(IllegalArgumentException.class,
            () -> ChunkKeyCodec.position(new byte[11]));
        assertThrows(IllegalArgumentException.class,
            () -> ChunkKeyCodec.position(new byte[13]));
        assertThrows(IllegalArgumentException.class,
            () -> ChunkKeyCodec.dimensionOrdinal(new byte[4]));
    }

    @Test
    void belongsToIdentifiesTheDimension() {
        byte[] key = ChunkKeyCodec.key(2, new ChunkPos(1, 1));
        assertTrue(ChunkKeyCodec.belongsTo(key, 2));
        assertTrue(!ChunkKeyCodec.belongsTo(key, 3));
        assertTrue(!ChunkKeyCodec.belongsTo(new byte[4], 2), "a short key belongs to nothing");
        assertTrue(!ChunkKeyCodec.belongsTo(null, 2));
    }

    // ------------------------------------------------------------ region grouping

    /**
     * The property the whole export design rests on.
     *
     * <p>A region's 1024 chunks must occupy 1024 <em>consecutive</em> Morton codes,
     * aligned to a 1024 boundary. If that holds, one forward scan of a dimension yields
     * chunks already grouped by region file, so the exporter needs one region file open
     * at a time and no sorting. If it did not hold, the exporter would need to either
     * sort every key or seek per region, and would silently interleave regions.
     */
    @Test
    void aRegionsChunksAreOneThousandTwentyFourConsecutiveCodes() {
        int[][] regions = {{0, 0}, {-1, -1}, {-2, -3}, {5, -7}, {1000, -1000},
            {-33, 17}, {123456, -98765}, {65535, 65535}, {-65536, -65536}};

        for (int[] region : regions) {
            int regionX = region[0];
            int regionZ = region[1];
            Set<Long> codes = new HashSet<>();
            long min = Long.MAX_VALUE;
            long max = Long.MIN_VALUE;

            for (int dx = 0; dx < 32; dx++) {
                for (int dz = 0; dz < 32; dz++) {
                    long code = ChunkKeyCodec.morton(
                        (regionX << 5) + dx, (regionZ << 5) + dz);
                    codes.add(code);
                    min = Math.min(min, code);
                    max = Math.max(max, code);
                }
            }

            assertEquals(1024, codes.size(),
                "region " + regionX + "," + regionZ + " must have 1024 distinct codes");
            assertEquals(1023, max - min,
                "region " + regionX + "," + regionZ + " codes must be consecutive");
            assertEquals(0, min % 1024,
                "region " + regionX + "," + regionZ + " must be 1024-aligned");
        }
    }

    /** The high bits of a code identify the region; the low ten the offset within it. */
    @Test
    void codeSplitsIntoRegionAndOffset() {
        for (int[] region : new int[][] {{0, 0}, {-1, -1}, {7, -9}, {-100000, 100000}}) {
            long expectedBlock = -1L;
            for (int dx = 0; dx < 32; dx++) {
                for (int dz = 0; dz < 32; dz++) {
                    int x = (region[0] << 5) + dx;
                    int z = (region[1] << 5) + dz;
                    long code = ChunkKeyCodec.morton(x, z);
                    long block = code >>> 10;
                    if (expectedBlock < 0) {
                        expectedBlock = block;
                    }
                    assertEquals(expectedBlock, block,
                        "every chunk of a region shares one code block");
                    // And the position must still decode correctly.
                    assertEquals(x, ChunkKeyCodec.unspreadX(code));
                    assertEquals(z, ChunkKeyCodec.unspreadZ(code));
                }
            }
        }
    }

    /**
     * The computed range must bound exactly the region's chunks and nothing else.
     *
     * <p>Too wide and a parallel export would write another region's chunks into the
     * wrong file; too narrow and it would drop terrain silently.
     */
    @Test
    void regionKeyRangeBoundsExactlyThatRegion() {
        for (int[] region : new int[][] {{0, 0}, {-1, -1}, {3, -4}, {-500, 600}}) {
            byte[][] range = ChunkKeyCodec.regionKeyRange(1, region[0], region[1]);
            byte[] lower = range[0];
            byte[] upper = range[1];

            assertTrue(compare(lower, upper) < 0, "the range must be non-empty");

            // Every chunk in the region falls inside.
            for (int dx = 0; dx < 32; dx++) {
                for (int dz = 0; dz < 32; dz++) {
                    byte[] key = ChunkKeyCodec.key(1, new ChunkPos(
                        (region[0] << 5) + dx, (region[1] << 5) + dz));
                    assertTrue(compare(lower, key) <= 0 && compare(key, upper) < 0,
                        "chunk " + dx + "," + dz + " of region " + region[0] + ","
                            + region[1] + " fell outside its own range");
                }
            }

            // Chunks of the neighbouring regions fall outside.
            for (int[] delta : new int[][] {{-1, 0}, {1, 0}, {0, -1}, {0, 1}, {1, 1}}) {
                int nx = region[0] + delta[0];
                int nz = region[1] + delta[1];
                for (int dx = 0; dx < 32; dx += 7) {
                    for (int dz = 0; dz < 32; dz += 7) {
                        byte[] key = ChunkKeyCodec.key(1,
                            new ChunkPos((nx << 5) + dx, (nz << 5) + dz));
                        assertTrue(compare(key, lower) < 0 || compare(key, upper) >= 0,
                            "a chunk of neighbouring region " + nx + "," + nz
                                + " fell inside region " + region[0] + "," + region[1]);
                    }
                }
            }
        }
    }

    /** The range must be scoped to one dimension, or an export would mix them. */
    @Test
    void regionKeyRangeIsScopedToOneDimension() {
        byte[][] range = ChunkKeyCodec.regionKeyRange(0, 0, 0);
        byte[] otherDimension = ChunkKeyCodec.key(1, new ChunkPos(0, 0));
        assertTrue(compare(otherDimension, range[1]) >= 0,
            "another dimension's chunk must sort outside this dimension's region range");
    }

    @Test
    void dimensionPrefixSortsBeforeEveryChunkOfThatDimension() {
        byte[] prefix = ChunkKeyCodec.dimensionPrefix(2);
        assertEquals(4, prefix.length, "a short prefix is a valid seek target");
        for (int x : INTERESTING) {
            byte[] key = ChunkKeyCodec.key(2, new ChunkPos(x, x));
            assertTrue(compare(prefix, key) < 0,
                "the prefix must seek to at or before every chunk of the dimension");
        }
        // And it must sort before the next dimension's prefix.
        assertTrue(compare(prefix, ChunkKeyCodec.dimensionPrefix(3)) < 0);
    }

    @Test
    void keyMatchesTheStoreEncoder() {
        // The store delegates here; this pins that they cannot drift apart.
        for (int x : new int[] {-1, 0, 1, 12345}) {
            ChunkPos pos = new ChunkPos(x, -x);
            assertArrayEquals(RocksChunkStore.key(4, pos), ChunkKeyCodec.key(4, pos));
        }
    }

    /** RocksDB compares keys as unsigned bytes; Java's byte is signed. */
    private static int compare(byte[] a, byte[] b) {
        for (int i = 0; i < Math.min(a.length, b.length); i++) {
            int cmp = Integer.compare(a[i] & 0xFF, b[i] & 0xFF);
            if (cmp != 0) {
                return cmp;
            }
        }
        return Integer.compare(a.length, b.length);
    }

    @Test
    void keyLengthIsPublishedAndCorrect() {
        assertEquals(12, ChunkKeyCodec.KEY_LENGTH);
        assertEquals(ChunkKeyCodec.KEY_LENGTH,
            ChunkKeyCodec.key(0, new ChunkPos(0, 0)).length);
        assertEquals(ChunkKeyCodec.KEY_LENGTH,
            Arrays.stream(ChunkKeyCodec.regionKeyRange(0, 0, 0))
                .mapToInt(k -> k.length).max().orElse(-1));
    }
}
