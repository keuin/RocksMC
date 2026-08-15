package com.keuin.rocksmc;

import net.minecraft.util.math.ChunkPos;

/**
 * The chunk key encoding, and its inverse.
 *
 * <p>{@code dimension(4B) | morton(x,z)(8B)}, big-endian, 12 bytes. The dimension
 * prefix is load-bearing: every dimension shares a column family, so it is the only
 * thing separating one dimension's chunks from another's. Morton (Z-order)
 * interleaving of the coordinates preserves 2D locality in RocksDB's 1D ordered
 * keyspace, so a player walking through the world produces near-sequential access and
 * compaction keeps neighbouring chunks together.
 *
 * <p>Big-endian keeps RocksDB's lexicographic byte ordering consistent with numeric
 * ordering. Coordinates are biased by {@link Integer#MIN_VALUE} before interleaving so
 * negative chunk coordinates -- entirely normal in Minecraft -- sort before positive
 * ones under unsigned byte comparison. Without the bias, {@code -1} would spread to
 * all-ones and sort after every positive coordinate, splitting the world into two
 * disjoint halves of the keyspace.
 *
 * <h2>Why the decode direction exists</h2>
 *
 * <p>Writing needs only the forward direction, and for a long time that was all there
 * was; the inverse lived in a test. Exporting needs to go the other way -- given a key
 * from an iterator, which chunk is this -- so it belongs here, next to the encoder it
 * must agree with, rather than being reimplemented by each caller.
 *
 * <h2>The property that makes exporting cheap</h2>
 *
 * <p>A region file's 1024 chunks occupy <b>1024 consecutive Morton codes, aligned to a
 * 1024 boundary</b>. That is not a coincidence of the encoding, it falls out of it:
 * {@code x ^ MIN_VALUE} equals {@code x + 2^31} unsigned, {@code 2^31} is divisible by
 * 32, so the bias survives {@code >> 5} exactly; and bit interleaving is positional, so
 * the low 5 bits of each coordinate land in exactly the low 10 bits of the code.
 *
 * <p>So {@code morton >>> 10} identifies the region and {@code morton & 1023} is the
 * offset within it. A single forward scan of a dimension therefore yields chunks
 * already grouped by region file, contiguously -- no sort, no seek per region, and only
 * one region file open at a time. {@link #regionKeyRange} turns that into an explicit
 * key range so regions can also be exported in parallel.
 */
public final class ChunkKeyCodec {

    /** Bytes in an encoded key: 4 for the dimension ordinal, 8 for the Morton code. */
    public static final int KEY_LENGTH = 12;

    /** Chunks per region file along each axis, and hence 1024 per region. */
    private static final int REGION_SHIFT = 5;

    /** Morton codes per region file: 32 x 32. */
    private static final int CODES_PER_REGION = 1 << (REGION_SHIFT * 2);

    /**
     * Encodes a chunk key.
     *
     * @param dimensionOrdinal the ordinal assigned by {@link DimensionRegistry}
     */
    public static byte[] key(int dimensionOrdinal, ChunkPos pos) {
        long morton = morton(pos.x, pos.z);
        byte[] k = new byte[KEY_LENGTH];
        writeInt(k, 0, dimensionOrdinal);
        writeLong(k, 4, morton);
        return k;
    }

    /** The dimension ordinal a key belongs to. */
    public static int dimensionOrdinal(byte[] key) {
        requireKey(key);
        return ((key[0] & 0xFF) << 24) | ((key[1] & 0xFF) << 16)
            | ((key[2] & 0xFF) << 8) | (key[3] & 0xFF);
    }

    /**
     * The chunk position a key refers to.
     *
     * <p>The exact inverse of {@link #key}, which
     * {@code ChunkKeyTest.mortonInterleaveIsReversible} pins across the coordinate
     * extremes. A decode that disagreed with the encoder would place exported terrain
     * at the wrong coordinates <em>consistently</em>, so it would round-trip cleanly
     * and only show up as a shifted world in a third-party tool.
     */
    public static ChunkPos position(byte[] key) {
        requireKey(key);
        long morton = 0L;
        for (int i = 0; i < 8; i++) {
            morton = (morton << 8) | (key[4 + i] & 0xFFL);
        }
        return new ChunkPos(unspreadX(morton), unspreadZ(morton));
    }

    /** Interleaves the low 32 bits of x and z into a 64-bit Morton code. */
    public static long morton(int x, int z) {
        return (spread(x ^ Integer.MIN_VALUE) << 1) | spread(z ^ Integer.MIN_VALUE);
    }

    /** Recovers x from a Morton code, undoing the bias. */
    public static int unspreadX(long morton) {
        return (int) compact(morton >>> 1) ^ Integer.MIN_VALUE;
    }

    /** Recovers z from a Morton code, undoing the bias. */
    public static int unspreadZ(long morton) {
        return (int) compact(morton) ^ Integer.MIN_VALUE;
    }

    /**
     * The half-open key range covering every chunk of one region file.
     *
     * <p>Exact, not approximate: a region's chunks are 1024 consecutive Morton codes
     * aligned to a 1024 boundary, so the range is
     * {@code [base, base + 1024)} where {@code base} is the region's code block. That
     * makes one region a unit of work for a parallel export with no coordination
     * between workers, and it means an iterator can be bounded rather than
     * filtered.
     *
     * @return {@code [lower, upper)} — lower inclusive, upper exclusive
     */
    public static byte[][] regionKeyRange(int dimensionOrdinal, int regionX, int regionZ) {
        // The region's code block is the Morton code of its first chunk with the
        // intra-region bits cleared. Deriving it from that chunk rather than
        // interleaving the region coordinates directly keeps this in terms of the one
        // encoder everything else uses.
        long base = morton(regionX << REGION_SHIFT, regionZ << REGION_SHIFT)
            & ~(long) (CODES_PER_REGION - 1);

        byte[] lower = new byte[KEY_LENGTH];
        writeInt(lower, 0, dimensionOrdinal);
        writeLong(lower, 4, base);

        byte[] upper = new byte[KEY_LENGTH];
        writeInt(upper, 0, dimensionOrdinal);
        writeLong(upper, 4, base + CODES_PER_REGION);

        return new byte[][] {lower, upper};
    }

    /**
     * The first key of a dimension, for seeking.
     *
     * <p>Four bytes rather than twelve: a short prefix is a valid seek target and
     * positions the iterator at the dimension's first chunk whatever its coordinates.
     */
    public static byte[] dimensionPrefix(int dimensionOrdinal) {
        byte[] prefix = new byte[4];
        writeInt(prefix, 0, dimensionOrdinal);
        return prefix;
    }

    /** Whether a key belongs to the given dimension. */
    public static boolean belongsTo(byte[] key, int dimensionOrdinal) {
        return key != null && key.length == KEY_LENGTH
            && dimensionOrdinal(key) == dimensionOrdinal;
    }

    // ----------------------------------------------------------------- internals

    /** Spreads the 32 bits of {@code v} into the even bit positions of a long. */
    private static long spread(int v) {
        long r = v & 0xFFFFFFFFL;
        r = (r | (r << 16)) & 0x0000FFFF0000FFFFL;
        r = (r | (r << 8)) & 0x00FF00FF00FF00FFL;
        r = (r | (r << 4)) & 0x0F0F0F0F0F0F0F0FL;
        r = (r | (r << 2)) & 0x3333333333333333L;
        r = (r | (r << 1)) & 0x5555555555555555L;
        return r;
    }

    /** Gathers the even bits of {@code v} into the low 32 bits: the inverse of spread. */
    private static long compact(long v) {
        long r = v & 0x5555555555555555L;
        r = (r | (r >>> 1)) & 0x3333333333333333L;
        r = (r | (r >>> 2)) & 0x0F0F0F0F0F0F0F0FL;
        r = (r | (r >>> 4)) & 0x00FF00FF00FF00FFL;
        r = (r | (r >>> 8)) & 0x0000FFFF0000FFFFL;
        return (r | (r >>> 16)) & 0x00000000FFFFFFFFL;
    }

    private static void writeInt(byte[] target, int offset, int value) {
        target[offset] = (byte) (value >>> 24);
        target[offset + 1] = (byte) (value >>> 16);
        target[offset + 2] = (byte) (value >>> 8);
        target[offset + 3] = (byte) value;
    }

    private static void writeLong(byte[] target, int offset, long value) {
        for (int i = 0; i < 8; i++) {
            target[offset + i] = (byte) (value >>> (8 * (7 - i)));
        }
    }

    private static void requireKey(byte[] key) {
        if (key == null || key.length != KEY_LENGTH) {
            throw new IllegalArgumentException("not a chunk key: expected "
                + KEY_LENGTH + " bytes, got " + (key == null ? "null" : key.length));
        }
    }

    private ChunkKeyCodec() {
    }
}
