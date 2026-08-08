package com.keuin.rocksmc;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.math.ChunkPos;

import java.io.Closeable;
import java.io.IOException;

/**
 * The storage seam.
 *
 * <p>Vanilla's chunk persistence funnels through four operations on
 * {@code RegionBasedStorage}, which this interface mirrors exactly:
 *
 * <pre>
 *   getTagAt(ChunkPos)          -&gt; read a chunk's NBT, or null if absent
 *   write(ChunkPos, NbtCompound) -&gt; overwrite a chunk's NBT wholesale
 *   method_26982()               -&gt; flush/sync everything durably
 *   close()                      -&gt; release resources
 * </pre>
 *
 * <p>Keeping the contract this narrow is what makes swapping the engine
 * tractable: the async write-behind buffer, coalescing map and read-your-writes
 * behaviour all live in {@code StorageIoWorker} <em>above</em> this seam, and
 * DataFixer version migration lives above that. Implementations therefore see
 * opaque, already-migrated NBT and must not interpret it.
 *
 * <p><strong>Values must round-trip byte-for-byte semantically.</strong> The
 * {@code DataVersion} tag lives inside the blob, so an implementation that
 * altered structure would silently break save-format migration.
 *
 * <p>Implementations are not required to be thread-safe. Vanilla serialises all
 * access through a single-threaded {@code TaskExecutor} per storage instance,
 * and {@code RegionBasedStorage}'s own methods are effectively serialised the
 * same way.
 */
public interface ChunkStore extends Closeable {

    /**
     * Reads a chunk's stored NBT.
     *
     * @return the chunk's NBT, or {@code null} if no chunk is stored at this position
     */
    NbtCompound read(ChunkPos pos) throws IOException;

    /**
     * Writes a chunk's NBT, replacing any previous value at this position.
     *
     * <p>Callers treat chunks as immutable blobs: there is no partial-update path
     * in vanilla, and none is offered here.
     */
    void write(ChunkPos pos, NbtCompound nbt) throws IOException;

    /**
     * Makes all prior writes durable.
     *
     * <p>Vanilla calls this at autosave boundaries and on shutdown.
     */
    void sync() throws IOException;

    @Override
    void close() throws IOException;
}
