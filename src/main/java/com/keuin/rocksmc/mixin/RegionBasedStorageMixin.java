package com.keuin.rocksmc.mixin;

import com.keuin.rocksmc.ChunkStore;
import com.keuin.rocksmc.RocksChunkStore;
import com.keuin.rocksmc.RocksMc;
import com.keuin.rocksmc.RocksMcConfig;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.storage.RegionBasedStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.File;
import java.io.IOException;

/**
 * Redirects vanilla chunk storage to RocksDB.
 *
 * <h2>Why mix into this class rather than redirect its construction</h2>
 *
 * <p>The original plan was to {@code @Redirect} the {@code new
 * RegionBasedStorage(...)} call inside {@code StorageIoWorker} and return a
 * subclass. That is not possible: {@code RegionBasedStorage} is
 * {@code public final} with a package-private constructor, so it cannot be
 * subclassed without an access widener. Mixing into the class itself and
 * cancelling its four seam methods achieves the same result with less machinery.
 *
 * <h2>What this deliberately leaves alone</h2>
 *
 * <p>Everything above the seam is untouched, which is the point:
 *
 * <ul>
 *   <li>{@code StorageIoWorker} keeps its write-behind buffer, its coalescing map
 *       and its read-your-writes behaviour. Repeated saves of one hot chunk still
 *       collapse into a single physical write.</li>
 *   <li>DataFixer still runs above this layer, so ~22.5k LOC of save-format
 *       migration logic is unaffected. Values pass through as opaque,
 *       already-migrated NBT.</li>
 * </ul>
 *
 * <p>Because vanilla constructs this same class for both chunk and POI storage,
 * both are redirected automatically. They still get <em>separate</em> databases
 * for now, so the atomic chunk+POI commit remains future work; only the seam is
 * proven here.
 *
 * <p>When the backend is {@code anvil} (the default) every injection returns
 * immediately and vanilla behaviour is bit-for-bit unchanged.
 */
@Mixin(RegionBasedStorage.class)
public abstract class RegionBasedStorageMixin {

    @Unique
    private ChunkStore rocksmc$store;

    /**
     * Derives a stable dimension id from the storage directory so that separate
     * dimensions never collide inside one keyspace.
     *
     * <p>Directory layout in 1.16.5: {@code world/region}, {@code world/DIM-1/region},
     * {@code world/DIM1/region}, plus {@code world/poi} and its per-dimension
     * equivalents. Hashing the path relative to the world root is sufficient here
     * because each store currently gets its own database; it becomes load-bearing
     * only once column families are shared.
     */
    @Unique
    private static int rocksmc$dimensionId(File directory) {
        String path = directory.getAbsolutePath().replace('\\', '/');
        if (path.contains("/DIM-1")) {
            return -1;
        }
        if (path.contains("/DIM1")) {
            return 1;
        }
        return 0;
    }

    @Inject(method = "<init>", at = @At("TAIL"))
    private void rocksmc$onInit(File directory, boolean dsync, CallbackInfo ci) {
        RocksMcConfig config = RocksMc.config();
        if (!config.rocksEnabled()) {
            return;
        }
        // Sibling of the Anvil directory, so an existing world's .mca files are
        // left completely untouched and the two backends can coexist on disk.
        File dbPath = new File(directory.getParentFile(),
            directory.getName() + ".rocksdb");
        try {
            this.rocksmc$store = new RocksChunkStore(
                dbPath, rocksmc$dimensionId(directory), config);
            RocksMc.logger().info("RocksDB store opened at {}", dbPath);
        } catch (IOException e) {
            // Fail loudly rather than silently falling back to Anvil: a silent
            // fallback would make a half-migrated world look healthy.
            throw new RuntimeException("rocksmc: failed to open RocksDB at " + dbPath, e);
        }
    }

    @Inject(method = "getTagAt", at = @At("HEAD"), cancellable = true)
    private void rocksmc$getTagAt(ChunkPos pos, CallbackInfoReturnable<NbtCompound> cir)
            throws IOException {
        if (this.rocksmc$store != null) {
            cir.setReturnValue(this.rocksmc$store.read(pos));
        }
    }

    @Inject(method = "write", at = @At("HEAD"), cancellable = true)
    private void rocksmc$write(ChunkPos pos, NbtCompound nbt, CallbackInfo ci)
            throws IOException {
        if (this.rocksmc$store != null) {
            this.rocksmc$store.write(pos, nbt);
            ci.cancel();
        }
    }

    /** Vanilla's flush-all entry point, called at autosave boundaries. */
    @Inject(method = "method_26982", at = @At("HEAD"), cancellable = true)
    private void rocksmc$sync(CallbackInfo ci) throws IOException {
        if (this.rocksmc$store != null) {
            this.rocksmc$store.sync();
            ci.cancel();
        }
    }

    @Inject(method = "close", at = @At("HEAD"), cancellable = true)
    private void rocksmc$close(CallbackInfo ci) throws IOException {
        if (this.rocksmc$store != null) {
            if (this.rocksmc$store instanceof RocksChunkStore) {
                RocksMc.logger().info(((RocksChunkStore)this.rocksmc$store).statsSummary());
            }
            this.rocksmc$store.close();
            this.rocksmc$store = null;
            ci.cancel();
        }
    }
}
