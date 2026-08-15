package com.keuin.rocksmc;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.storage.RegionFile;

import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;

/**
 * Writes chunk NBT into Anvil region files.
 *
 * <h2>Why this wraps vanilla's writer instead of implementing one</h2>
 *
 * <p>{@code RegionFile} is {@code public}, non-final, and has public constructors, so
 * it is directly usable. It already implements every part of the format that is easy
 * to get subtly wrong:
 *
 * <ul>
 *   <li>the 5-byte frame, where {@code length} counts the payload <em>plus the scheme
 *       byte</em> — an off-by-one truncates every chunk by a byte and every reader
 *       reports corruption;</li>
 *   <li>zlib-framed deflate, not raw deflate: scheme 2 means RFC 1950, and getting it
 *       wrong makes every chunk fail to inflate;</li>
 *   <li>the sector allocator, including reuse and freeing the old copy only
 *       <em>after</em> the header points at the new one, which is what makes a write
 *       crash-tolerant;</li>
 *   <li>spilling chunks of 256 or more sectors into an external {@code c.X.Z.mcc}
 *       file, and deleting a stale one when a chunk shrinks;</li>
 *   <li>rounding the file up to a whole sector on close.</li>
 * </ul>
 *
 * <p>That last pair are the traps worth naming. A hand-rolled writer that computed
 * {@code sectors} and stored it in the header's 8-bit size field would write
 * {@code 256 & 0xFF == 0} for an oversized chunk — a "size 0" entry that vanilla zeroes
 * on open with only a warning, silently dropping the largest chunks in the world.
 * Separately, an unpadded final sector round-trips fine through this project's own
 * reader, which tolerates a short tail, and is then rejected by stricter third-party
 * parsers that trust the header and read {@code size * 4096} bytes. Both are cases
 * where local verification passes and the actual consumer fails.
 *
 * <p>The independent-implementation argument that justifies {@link AnvilReader} does
 * not apply here. That parser exists because the mixin redirects vanilla's reader, so
 * reading Anvil through vanilla would read RocksDB straight back. Nothing redirects
 * {@code RegionFile}'s writer, and the verification oracle is already independent:
 * vanilla writes, {@link AnvilReader} reads back.
 *
 * <h2>Lifetime</h2>
 *
 * <p>One instance owns one region file and must be closed, because
 * {@code RegionFile.close} is what pads the file to a sector boundary and forces it to
 * disk. Not thread-safe by design: {@code RegionFile} synchronises its writes, but each
 * instance owns an independent sector allocator, so two instances open on the same path
 * would corrupt each other. The caller keeps one per output path — which the Morton
 * grouping in {@link ChunkKeyCodec} makes trivial, since a scan visits a region's
 * chunks contiguously.
 */
public final class AnvilWriter implements AutoCloseable {

    /**
     * Sector count at which vanilla spills a chunk to an external {@code .mcc} file.
     *
     * <p>Only used for reporting: {@code RegionFile} applies the rule itself. Worth
     * surfacing because oversized chunks are the ones most likely to expose a gap in a
     * third-party tool's format support, so an export says how many it wrote.
     */
    private static final int EXTERNAL_SECTOR_THRESHOLD = 256;

    private static final int SECTOR = 4096;

    private final RegionFile regionFile;
    private final File path;
    private int chunksWritten;
    private int externalChunks;

    /**
     * Opens a region file for writing, creating it if absent.
     *
     * @param path      the {@code r.X.Z.mca} file to write
     * @param directory the containing directory, which vanilla uses to place
     *                  {@code .mcc} files for oversized chunks
     */
    public AnvilWriter(File path, File directory) throws IOException {
        this.path = path;
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("could not create " + directory);
        }
        // dsync=false: this is a bulk export, and close() forces the file to disk once
        // at the end. Passing true would fsync on every chunk, which measured a 3.65x
        // increase in kernel-observed writes elsewhere in this project for no benefit
        // to a batch job that is rerunnable.
        this.regionFile = new RegionFile(path, directory, false);
    }

    /**
     * Writes one chunk from already-serialised NBT.
     *
     * <p>Takes bytes rather than an {@code NbtCompound} so a caller that needs the
     * serialised form anyway -- to hash it for verification, say -- does not pay for
     * serialising twice. An earlier version of this class serialised once to estimate
     * the size and again to write, doubling the cost of the hot path.
     */
    public void write(ChunkPos pos, byte[] nbtBytes) throws IOException {
        try (DataOutputStream out = this.regionFile.getChunkOutputStream(pos)) {
            out.write(nbtBytes);
        }
        this.chunksWritten++;
        if (sectorEstimate(nbtBytes.length) >= EXTERNAL_SECTOR_THRESHOLD) {
            this.externalChunks++;
        }
    }

    /** Convenience for callers that hold NBT and do not need the bytes. */
    public void write(ChunkPos pos, NbtCompound nbt) throws IOException {
        write(pos, serialise(nbt));
    }

    /** Serialises NBT exactly as it is stored and written: uncompressed. */
    public static byte[] serialise(NbtCompound nbt) throws IOException {
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream(64 * 1024);
        try (DataOutputStream out = new DataOutputStream(buffer)) {
            NbtIo.write(nbt, out);
        }
        return buffer.toByteArray();
    }

    /**
     * An upper bound on the sectors a chunk will occupy, for reporting only.
     *
     * <p>Computed from the uncompressed size, so it over-counts: a chunk reported as
     * external may compress below the threshold. That is the safe direction for a
     * warning whose only job is telling an operator oversized chunks are present, and
     * measuring exactly would mean compressing twice.
     */
    private static int sectorEstimate(int uncompressedBytes) {
        return (uncompressedBytes + 5 + SECTOR - 1) / SECTOR;
    }

    public int chunksWritten() {
        return this.chunksWritten;
    }

    /** Chunks large enough that vanilla may have spilled them to a {@code .mcc} file. */
    public int externalChunks() {
        return this.externalChunks;
    }

    public File path() {
        return this.path;
    }

    /**
     * Closes the region file.
     *
     * <p>Not optional and not merely tidy: {@code RegionFile.close} pads the file to a
     * whole sector and forces it to disk. Abandoning the handle instead leaves a file
     * whose final sector is short — which this project's reader tolerates and stricter
     * third-party parsers do not.
     */
    @Override
    public void close() throws IOException {
        this.regionFile.close();
    }
}
