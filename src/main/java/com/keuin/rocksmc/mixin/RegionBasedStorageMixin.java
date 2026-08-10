package com.keuin.rocksmc.mixin;

import com.keuin.rocksmc.ChunkStore;
import com.keuin.rocksmc.DimensionKey;
import com.keuin.rocksmc.RocksChunkStore;
import com.keuin.rocksmc.RocksDatabase;
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
 * both are redirected automatically. Every one of a world's storage directories --
 * six for a three-dimension world -- now resolves to a <em>single</em> shared
 * database, so all dimensions share one write-ahead log and therefore one crash
 * recovery point. See {@link com.keuin.rocksmc.RocksDatabase} for why that
 * matters, and for what it still does not give (chunk and POI writes are not
 * atomic with respect to each other).
 *
 * <p>When the backend is {@code anvil} (the default) every injection returns
 * immediately and vanilla behaviour is bit-for-bit unchanged.
 */
@Mixin(RegionBasedStorage.class)
public abstract class RegionBasedStorageMixin {

    @Unique
    private ChunkStore rocksmc$store;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void rocksmc$onInit(File directory, boolean dsync, CallbackInfo ci) {
        RocksMcConfig config = RocksMc.config();
        if (!config.rocksEnabled()) {
            return;
        }

        // Identity comes from the directory we were handed, which is the one input
        // guaranteed to be correct here. See DimensionKey for why the registry key
        // is not reachable and why out-of-band channels (ThreadLocal, @Redirect)
        // were rejected as fragile against other mods.
        DimensionKey dimension;
        try {
            dimension = DimensionKey.fromStorageDirectory(directory);
        } catch (IllegalArgumentException e) {
            // An unrecognised layout must never silently become the overworld:
            // that would put two dimensions in one keyspace and lose terrain.
            throw new RuntimeException("rocksmc: cannot determine which dimension "
                + directory + " belongs to. Refusing to start rather than risk "
                + "writing chunks under the wrong dimension id.", e);
        }

        // One database for the whole world, at <world>/rocksmc.db. The Anvil
        // directories are left completely untouched, so the two backends can
        // coexist on disk and rollback stays a config flip.
        File dbPath = new File(dimension.root(), RocksDatabase.DIRECTORY_NAME);

        rocksmc$guardAgainstLegacyLayout(directory, dbPath);
        rocksmc$guardAgainstBlankStart(directory, dbPath, config);

        try {
            this.rocksmc$store = RocksChunkStore.open(dimension, config);
            RocksMc.logger().info("RocksDB store opened at {} for {}", dbPath, dimension);
        } catch (IOException e) {
            // Fail loudly rather than silently falling back to Anvil: a silent
            // fallback would make a half-migrated world look healthy.
            throw new RuntimeException("rocksmc: failed to open RocksDB at " + dbPath, e);
        }
    }

    /**
     * Refuses to start on a world imported by an older build.
     *
     * <p>Format version 1 put each {@code (dimension, leaf)} in its own database at
     * {@code <dir>.rocksdb}. Version 2 uses one database per world. The version
     * marker lives <em>inside</em> a database, so it cannot catch this case: the new
     * database is a different directory and simply looks absent, which the
     * blank-start guard would then report as a fresh world beside populated Anvil
     * files. That message would send an operator looking in the wrong place.
     *
     * <p>The old directories are not touched, so reverting to an older build with
     * {@code backend=rocksdb} still works.
     */
    @Unique
    private static void rocksmc$guardAgainstLegacyLayout(File anvilDir, File dbPath) {
        File legacy = new File(anvilDir.getParentFile(), anvilDir.getName() + ".rocksdb");
        File[] legacyFiles = legacy.listFiles(
            (d, n) -> n.endsWith(".sst") || n.endsWith(".blob"));
        if (legacyFiles == null || legacyFiles.length == 0) {
            return;
        }
        File[] currentFiles = dbPath.listFiles(
            (d, n) -> n.endsWith(".sst") || n.endsWith(".blob"));
        if (currentFiles != null && currentFiles.length > 0) {
            // Already migrated; the stale directory is just leftovers.
            return;
        }
        throw new RuntimeException("rocksmc: refusing to start.\n"
            + "  Found an on-disk format version 1 database: " + legacy + "\n"
            + "  This build uses one database per world:      " + dbPath + "\n"
            + "\n"
            + "Version 1 gave every dimension its own database and its own\n"
            + "write-ahead log. Because the server saves worlds sequentially, a\n"
            + "crash mid-autosave could recover each one to a different point --\n"
            + "a state no tick ever produced. Version 2 shares one log.\n"
            + "\n"
            + "There is no migration path. Re-import from the .mca files:\n"
            + "  ./gradlew importWorld -Pworld=" + anvilDir.getParentFile() + "\n"
            + "\n"
            + "Your .mca files and the old *.rocksdb directories are untouched, so\n"
            + "backend=anvil and older builds both still work. Delete the old\n"
            + "*.rocksdb directories once the re-import is verified.");
    }

    /**
     * Refuses to start with an empty database beside a populated Anvil world.
     *
     * <p>This is the worst trap the mod can spring. Reads would all miss, vanilla
     * would regenerate terrain into RocksDB, and {@code playerdata} is
     * <em>shared</em> -- so players keep coordinates in a world that no longer
     * matches and can materialise inside solid blocks. The {@code .mca} files stay
     * intact, so it is recoverable, but only if someone notices before building on
     * the regenerated terrain.
     *
     * <p>Detection is deliberately cheap and conservative: a non-empty
     * {@code .mca} present while the database directory has no SST or blob files.
     * Both conditions are cheap to check and neither produces false positives on a
     * genuinely fresh world.
     *
     * <p>Note this runs per storage directory, and the database is now shared. A
     * world whose overworld was imported but which has an un-imported dimension
     * beside it is still caught, because the check is on that dimension's own
     * {@code .mca} files -- but a database holding <em>any</em> data passes, so the
     * importer must convert every dimension in one pass. It does.
     */
    @Unique
    private static void rocksmc$guardAgainstBlankStart(File anvilDir, File dbPath,
            RocksMcConfig config) {
        if (config.allowBlankStart()) {
            return;
        }

        File[] regions = anvilDir.listFiles((d, n) -> n.endsWith(".mca"));
        boolean anvilHasData = false;
        if (regions != null) {
            for (File region : regions) {
                // Vanilla creates region files on demand and leaves them empty, so
                // mere existence proves nothing; a header alone is 8 KiB.
                if (region.length() > 8192L) {
                    anvilHasData = true;
                    break;
                }
            }
        }
        if (!anvilHasData) {
            return;
        }

        File[] dbFiles = dbPath.listFiles((d, n) -> n.endsWith(".sst") || n.endsWith(".blob"));
        boolean dbHasData = dbFiles != null && dbFiles.length > 0;
        if (dbHasData) {
            return;
        }

        throw new RuntimeException("rocksmc: refusing to start.\n"
            + "  Anvil world has data:  " + anvilDir + "\n"
            + "  RocksDB store is empty: " + dbPath + "\n"
            + "\n"
            + "Starting like this would regenerate terrain into RocksDB while\n"
            + "playerdata still points at the old world, so players would keep their\n"
            + "coordinates in a world that no longer matches -- possibly inside solid\n"
            + "blocks. Your .mca files have NOT been touched.\n"
            + "\n"
            + "Either import the existing world first:\n"
            + "  ./gradlew importWorld -Pworld=<world-dir>\n"
            + "or set allow-blank-start=true in config/rocksmc.properties if a fresh\n"
            + "world in this directory is genuinely what you want.");
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
