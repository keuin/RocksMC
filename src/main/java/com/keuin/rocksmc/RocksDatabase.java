package com.keuin.rocksmc;

import org.rocksdb.BlockBasedTableConfig;
import org.rocksdb.BloomFilter;
import org.rocksdb.Cache;
import org.rocksdb.Checkpoint;
import org.rocksdb.ColumnFamilyDescriptor;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.ColumnFamilyOptions;
import org.rocksdb.CompressionType;
import org.rocksdb.DBOptions;
import org.rocksdb.Env;
import org.rocksdb.FlushOptions;
import org.rocksdb.LRUCache;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.SstFileManager;
import org.rocksdb.WriteOptions;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One RocksDB instance per world, shared by every dimension and every leaf.
 *
 * <h2>Why one database, and not one per (dimension, leaf)</h2>
 *
 * <p>The previous layout gave each of a world's six storage directories its own
 * database, and therefore its own write-ahead log and its own group-commit
 * boundary. {@code MinecraftServer.save()} iterates worlds <b>sequentially</b> --
 * overworld, then nether, then end -- so a crash part-way through an autosave
 * recovered each database to a different point in that sequence. Minecraft has a
 * single tick loop for all dimensions, so there is no tick at which the overworld
 * had finished saving but the nether had not: recovery landed on a state no tick
 * ever produced.
 *
 * <p>That is not theoretical. Entity teleports between dimensions remove from one
 * world and add to another, so a torn recovery duplicates or loses the entity.
 * Map item state routes through the <em>overworld's</em> {@code
 * PersistentStateManager} regardless of which dimension the map depicts, so a
 * nether map already depends on overworld storage. A shared WAL gives one recovery
 * point for all of it.
 *
 * <p>It also fixes a resource-duplication bug. Options, the block cache, the bloom
 * filter and the background thread pool used to be allocated per store, so a
 * three-dimension world multiplied every memory figure by six -- a 512 MiB block
 * cache setting really meant 3 GiB. Now they are allocated once and the numbers in
 * the configuration mean what they say.
 *
 * <h2>⚠️ What this does not give</h2>
 *
 * <p>A shared WAL is <b>not</b> the same as atomic chunk+POI commits. RocksDB
 * guarantees atomicity per {@code WriteBatch}, not across separate {@code put}
 * calls, and vanilla's chunk and POI writes originate in independent {@code
 * StorageIoWorker} instances that flush on their own schedule -- above the seam
 * this mod injects at. Batching them requires intercepting higher up. Do not read
 * consolidation as delivering cross-subsystem atomicity.
 *
 * <h2>Column families</h2>
 *
 * <table>
 *   <tr><th>CF</th><th>Contents</th></tr>
 *   <tr><td>{@code default}</td><td>format version, dimension registry</td></tr>
 *   <tr><td>{@code chunk}</td><td>every dimension's chunk NBT</td></tr>
 *   <tr><td>{@code poi}</td><td>every dimension's POI NBT</td></tr>
 * </table>
 *
 * <p>Deliberately <b>not</b> one column family per dimension: each column family
 * carries its own memtable, which would reproduce the duplication being removed
 * here. Dimensions are separated within a column family by the ordinal in the key
 * prefix instead, which also keeps each dimension in a contiguous key range.
 *
 * <p>The metadata lives in {@code default} rather than a fourth column family for
 * the same reason -- one fewer memtable -- and is safe there because every
 * metadata key begins with a NUL byte, which no dimension identity can contain.
 *
 * <h2>⚠️ Lifecycle: reference counted</h2>
 *
 * <p>Six {@code RegionBasedStorage} instances share one handle, and releasing it
 * while another store still holds it would corrupt every dimension at once. So
 * {@link #open} hands out a shared instance and increments a counter, {@link
 * #release} decrements it, and only the last release closes anything.
 *
 * <p>Both operations hold a single static lock covering the map lookup, the
 * counter and the close. That is what makes them atomic with respect to each
 * other: without it, a release falling to zero could interleave with an open that
 * had already found the instance in the map, handing out a database that is about
 * to be closed.
 *
 * <p>Over-releasing throws rather than being tolerated. A negative count means a
 * caller has a double-close bug, and the consequence of continuing is a
 * use-after-free inside RocksDB's native code affecting the whole world, so it
 * must be loud and immediate.
 */
public final class RocksDatabase {

    static {
        RocksDB.loadLibrary();
    }

    /**
     * On-disk format version.
     *
     * <p>Bumped 1 -> 2 by the consolidation to one database per world: version 1
     * databases are per-(dimension, leaf) directories with a different column
     * family layout, and reading them under these assumptions would misinterpret
     * every key. There is no migration; the world is re-imported.
     */
    static final int FORMAT_VERSION = 2;

    /** Directory name of a world's database, relative to the world root. */
    public static final String DIRECTORY_NAME = "rocksmc.db";

    /** Column family holding chunk NBT for every dimension. */
    public static final String CHUNK_CF = "chunk";

    /** Column family holding POI NBT for every dimension. */
    public static final String POI_CF = "poi";

    /**
     * Metadata keys, both NUL-prefixed so they cannot collide with a dimension
     * identity, which is always a printable namespaced string.
     */
    private static final String FORMAT_VERSION_KEY = "\u0000format";

    /** Live databases by canonical world root, with their reference counts. */
    private static final Map<File, RocksDatabase> OPEN = new HashMap<>();

    /** Guards {@link #OPEN}, every reference count, and every close. */
    private static final Object LOCK = new Object();

    private final File path;
    private final File worldRoot;
    private final RocksDB db;
    private final DBOptions dbOptions;
    private final ColumnFamilyOptions dataCfOptions;
    private final ColumnFamilyOptions metaCfOptions;
    private final WriteOptions writeOptions;
    private final BloomFilter bloomFilter;
    private final Cache blockCache;
    private final SstFileManager sstFileManager;
    private final DimensionRegistry dimensionRegistry;

    /** Column family handles by name, including {@code default}. */
    private final Map<String, ColumnFamilyHandle> columnFamilies;

    /** Guarded by {@link #LOCK}. */
    private int references;
    private boolean closed;

    /**
     * Opens a world's database, or joins the one already open for it.
     *
     * <p>The returned instance is shared. Every caller must eventually call
     * {@link #release()} exactly once, and must not touch the instance afterwards.
     *
     * <p>Identity is the <b>canonical</b> world root, so two paths reaching the
     * same world through a symlink join one database rather than racing to open the
     * same directory twice. Should canonicalisation fail -- a path that does not
     * exist yet -- the absolute path is used instead; RocksDB's own directory
     * {@code LOCK} then still turns a genuine double-open into a clean error rather
     * than corruption.
     *
     * <p>The first caller's tuning governs, because the options, the block cache
     * and the thread pool are now genuinely shared and cannot be per-store. In
     * practice every caller passes the same configuration.
     *
     * @param worldRoot the world directory, as returned by {@link DimensionKey#root()}
     */
    public static RocksDatabase open(File worldRoot, RocksMcConfig config) throws IOException {
        File canonical = canonicalise(worldRoot);
        synchronized (LOCK) {
            RocksDatabase existing = OPEN.get(canonical);
            if (existing != null) {
                existing.references++;
                return existing;
            }
            RocksDatabase opened = new RocksDatabase(canonical, config);
            opened.references = 1;
            OPEN.put(canonical, opened);
            StoreRegistry.registerDatabase(opened);
            return opened;
        }
    }

    private static File canonicalise(File worldRoot) {
        try {
            return worldRoot.getCanonicalFile();
        } catch (IOException e) {
            return worldRoot.getAbsoluteFile();
        }
    }

    private RocksDatabase(File worldRoot, RocksMcConfig config) throws IOException {
        this.worldRoot = worldRoot;
        this.path = new File(worldRoot, DIRECTORY_NAME);
        if (!this.path.exists() && !this.path.mkdirs()) {
            throw new IOException("Could not create RocksDB directory: " + this.path);
        }

        this.bloomFilter = new BloomFilter(10);
        this.blockCache = new LRUCache(config.blockCacheSize());
        BlockBasedTableConfig tableConfig = new BlockBasedTableConfig()
            .setFilterPolicy(this.bloomFilter)
            .setBlockCache(this.blockCache)
            // Chunk reads are single-key point lookups, never scans, so bias the
            // index for lookup speed.
            .setCacheIndexAndFilterBlocks(true)
            .setPinL0FilterAndIndexBlocksInCache(true);

        // One options object shared by both data column families. They hold the
        // same shape of value -- tens of KiB of NBT -- so there is nothing to
        // tune differently, and RocksDB permits one descriptor's options to be
        // reused across column families.
        this.dataCfOptions = new ColumnFamilyOptions()
            .setCompressionType(CompressionType.ZSTD_COMPRESSION)
            .setBottommostCompressionType(CompressionType.ZSTD_COMPRESSION)
            .setTableFormatConfig(tableConfig)
            // Key-value separation. Chunk values are tens of KiB, so without this
            // leveled compaction would rewrite them repeatedly.
            .setEnableBlobFiles(true)
            .setMinBlobSize(config.minBlobSize())
            .setBlobCompressionType(CompressionType.ZSTD_COMPRESSION)
            .setEnableBlobGarbageCollection(true)
            // Reclaim space from overwritten blobs rather than growing without
            // bound, which is precisely the failure mode Anvil's never-compacted
            // sector allocator has.
            .setBlobGarbageCollectionAgeCutoff(0.25)
            .setBlobGarbageCollectionForceThreshold(0.5)
            // Larger memtables coalesce more repeated saves of the same hot chunk
            // before any of it reaches disk, which is the dominant write pattern on
            // a technical server.
            .setWriteBufferSize(config.writeBufferSize())
            .setMaxWriteBufferNumber(config.maxWriteBufferNumber())
            // Raised from the default 8: fast storage drains L0 quickly, so
            // throttling writes early costs tick time for no benefit.
            .setLevel0SlowdownWritesTrigger(config.level0SlowdownTrigger())
            // Must stay at or above the slowdown trigger, which the config enforces:
            // otherwise writes stall without being throttled gently first.
            .setLevel0StopWritesTrigger(config.level0StopTrigger());

        // The format marker and the dimension registry are a handful of tiny
        // entries. Blob files and heavy compression would be pure overhead.
        this.metaCfOptions = new ColumnFamilyOptions()
            .setCompressionType(CompressionType.NO_COMPRESSION);

        this.dbOptions = new DBOptions()
            .setCreateIfMissing(true)
            .setCreateMissingColumnFamilies(true)
            .setMaxBackgroundJobs(config.maxBackgroundJobs())
            .setMaxSubcompactions(config.maxSubcompactions())
            // Trickle writeback out in small increments instead of letting it pile
            // up until file close, which otherwise shows up as a tick stall.
            .setBytesPerSync(config.bytesPerSync())
            .setWalBytesPerSync(config.bytesPerSync())
            // RocksDB's own LOG defaults to never rotating by size (max_log_file_size
            // = 0), so it only rolls when the database is reopened. With stats dumps
            // every 600s that is roughly 2.5 MB/day inside the world directory, in a
            // single file, invisible to every size metric this mod exposes -- and a
            // server that never restarts grows it without bound.
            .setMaxLogFileSize(config.maxLogFileSize())
            .setKeepLogFileNum(config.keepLogFileNum())
            // Unlimited by default, matching RocksDB. Settable because a large
            // world's blob files accumulate and a shared host with a low
            // RLIMIT_NOFILE otherwise dies days later with "too many open files".
            .setMaxOpenFiles(config.maxOpenFiles());

        // 0 means "RocksDB's default", so only override when asked. With
        // sync-writes=false the WAL is the durability mechanism, and bounding it
        // bounds both recovery time and disk use on a mostly-idle world.
        if (config.maxTotalWalSize() > 0) {
            this.dbOptions.setMaxTotalWalSize(config.maxTotalWalSize());
        }

        // A space cap, if configured. This is the only pre-emptive defence available
        // against filling the disk, and it matters more than it looks: when RocksDB
        // hits ENOSPC it latches a background error and refuses all further writes,
        // and RocksJava exposes no DB::Resume(), so freeing space does NOT recover the
        // database -- it stays read-only until the server restarts. Meanwhile the
        // server keeps running and silently persists nothing. Failing writes early,
        // while there is still headroom to compact and to react, is far better.
        SstFileManager manager = null;
        if (config.maxAllowedSpaceBytes() > 0) {
            try {
                manager = new SstFileManager(Env.getDefault());
                manager.setMaxAllowedSpaceUsage(config.maxAllowedSpaceBytes());
                this.dbOptions.setSstFileManager(manager);
            } catch (RocksDBException e) {
                // Not fatal: the database is still usable, just without the cap.
                RocksMc.logger().error("rocksmc: could not install the SST file "
                    + "manager, so max-allowed-space-bytes is NOT in effect", e);
                manager = null;
            }
        }
        this.sstFileManager = manager;

        this.writeOptions = new WriteOptions().setSync(config.syncWrites());

        List<ColumnFamilyDescriptor> descriptors = Arrays.asList(
            new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY, this.metaCfOptions),
            new ColumnFamilyDescriptor(bytes(CHUNK_CF), this.dataCfOptions),
            new ColumnFamilyDescriptor(bytes(POI_CF), this.dataCfOptions));

        List<ColumnFamilyHandle> handles = new ArrayList<>();
        RocksDB opened;
        try {
            opened = RocksDB.open(this.dbOptions, this.path.getAbsolutePath(),
                descriptors, handles);
        } catch (RocksDBException e) {
            closeNativeOptions();
            throw new IOException("Failed to open RocksDB at " + this.path, e);
        }
        this.db = opened;

        Map<String, ColumnFamilyHandle> byName = new HashMap<>();
        byName.put("default", handles.get(0));
        byName.put(CHUNK_CF, handles.get(1));
        byName.put(POI_CF, handles.get(2));
        this.columnFamilies = Collections.unmodifiableMap(byName);

        try {
            // Before anything is read or written, confirm the on-disk layout is one
            // this build understands. Without this a format change would silently
            // reinterpret existing keys, which is unrecoverable.
            checkFormatVersion();
            this.dimensionRegistry = new DimensionRegistry(this.db, metaCf());
        } catch (IOException | RuntimeException e) {
            // An unusable database means keys cannot be interpreted, so refuse to
            // serve rather than write chunks that cannot be read back.
            //
            // RuntimeException is caught as well as IOException, and that is
            // load-bearing: DimensionRegistry.decodeInt throws IllegalStateException
            // on a corrupt ordinal, and columnFamilyFor throws on a missing family.
            // Letting either escape here would leak the native handle -- and with it
            // RocksDB's on-disk LOCK -- so every later open in the same JVM would
            // fail with a lock error and the obvious "restart and retry" would not
            // work without killing the JVM.
            closeNative();
            throw e;
        }
    }

    /**
     * Releases one reference, closing the database on the last one.
     *
     * <p>Safe to call from any thread. Idempotent only in the sense that the last
     * release closes exactly once -- calling it more times than {@link #open} was
     * called is a bug and throws.
     */
    public void release() throws IOException {
        synchronized (LOCK) {
            if (this.references <= 0) {
                throw new IllegalStateException("rocksmc: database at " + this.path
                    + " released more times than it was opened. This is a "
                    + "double-close bug; continuing would use freed native memory "
                    + "for every dimension at once.");
            }
            this.references--;
            if (this.references > 0) {
                return;
            }
            OPEN.remove(this.worldRoot);
            StoreRegistry.deregisterDatabase(this);
            IOException failure = null;
            try {
                // Everything above the seam has already been flushed by vanilla's
                // own shutdown; this makes the WAL durable before the handle goes.
                syncWal();
            } catch (IOException e) {
                failure = e;
            }
            closeNative();
            if (failure != null) {
                throw failure;
            }
        }
    }

    /** Visible for tests: how many stores currently share this handle. */
    int referenceCount() {
        synchronized (LOCK) {
            return this.references;
        }
    }

    /** Visible for tests: whether the native handle has been released. */
    boolean isClosed() {
        synchronized (LOCK) {
            return this.closed;
        }
    }

    /**
     * Reads, or on first open writes, the on-disk format version.
     *
     * <p>A database written by another format version cannot be read safely, so a
     * mismatch aborts rather than guessing.
     */
    private void checkFormatVersion() throws IOException {
        byte[] key = bytes(FORMAT_VERSION_KEY);
        try {
            byte[] stored = this.db.get(metaCf(), key);
            if (stored == null) {
                this.db.put(metaCf(), key, bytes(String.valueOf(FORMAT_VERSION)));
                return;
            }
            int found = Integer.parseInt(new String(stored, StandardCharsets.UTF_8).trim());
            if (found != FORMAT_VERSION) {
                throw new IOException("rocksmc: database at " + this.path
                    + " was written with on-disk format version " + found
                    + ", but this build only understands version " + FORMAT_VERSION
                    + ". There is no migration path; re-import the world with\n"
                    + "  ./gradlew importWorld -Pworld=" + this.worldRoot
                    + " -Poverwrite");
            }
        } catch (RocksDBException e) {
            throw new IOException("failed to read format version at " + this.path, e);
        } catch (NumberFormatException e) {
            throw new IOException("corrupt format version marker at " + this.path, e);
        }
    }

    // ------------------------------------------------------------------ access

    RocksDB handle() {
        return this.db;
    }

    WriteOptions writeOptions() {
        return this.writeOptions;
    }

    /** The metadata column family: format version and dimension registry. */
    private ColumnFamilyHandle metaCf() {
        return this.columnFamilies.get("default");
    }

    /**
     * The column family a storage leaf maps onto.
     *
     * @param leaf {@code region} or {@code poi}, as given by {@link DimensionKey#leaf()}
     */
    ColumnFamilyHandle columnFamilyFor(String leaf) {
        String name = columnFamilyNameFor(leaf);
        ColumnFamilyHandle handle = this.columnFamilies.get(name);
        if (handle == null) {
            throw new IllegalStateException("no column family " + name);
        }
        return handle;
    }

    /**
     * Maps a storage leaf to a column family name.
     *
     * <p>{@code region} becomes {@code chunk} because the column family holds chunk
     * NBT, not Anvil regions -- the concept the directory name refers to does not
     * exist here. Anything else throws: {@link DimensionKey} only produces these
     * two, so a third value means the parser and this mapping have drifted apart.
     */
    static String columnFamilyNameFor(String leaf) {
        if ("region".equals(leaf)) {
            return CHUNK_CF;
        }
        if ("poi".equals(leaf)) {
            return POI_CF;
        }
        throw new IllegalArgumentException("unknown storage leaf: " + leaf);
    }

    /** The column families holding chunk and POI data, for metrics. */
    static List<String> dataColumnFamilies() {
        return Arrays.asList(CHUNK_CF, POI_CF);
    }

    public DimensionRegistry dimensionRegistry() {
        return this.dimensionRegistry;
    }

    /** The database directory, {@code <world>/rocksmc.db}. */
    public File path() {
        return this.path;
    }

    /** The world root this database serves, canonicalised. */
    public File worldRoot() {
        return this.worldRoot;
    }

    /**
     * Identifies this database, for the {@code database} metric label and log lines.
     *
     * <p>The full canonical world path, not the directory's short name. The short
     * name collided: a host running two worlds at {@code /srv/a/world} and
     * {@code /srv/b/world} produced two series with identical labels, and the
     * Prometheus client rejects that with {@code DuplicateLabelsException} -- so the
     * <em>entire</em> {@code /metrics} scrape failed, not merely the one series.
     * Monitoring went dark exactly on the multi-world setups most likely to need it.
     *
     * <p>Long labels are the lesser evil. Dashboards use this as a grouping key
     * rather than matching a literal, so they keep working; anything shorter is
     * either ambiguous again or a hash an operator cannot read.
     */
    public String name() {
        return this.worldRoot.getPath();
    }

    /**
     * Throws if the native handle has been released.
     *
     * <p>The last line of defence against a use-after-free. RocksDB's Java API does not
     * check {@code isOwningHandle()} on {@code compactRange}, {@code flush},
     * {@code flushWal} or {@code getLongProperty} -- each passes the raw native handle
     * to JNI -- so calling one after {@code close()} is a dangling pointer, not an
     * exception. That is reachable because JVM shutdown hooks run concurrently: a
     * maintenance command, a metrics scrape or the stats logger can still be inside one
     * of these while vanilla's hook closes the database.
     *
     * <p>Checked under {@link #LOCK}, the same lock {@code release} holds while
     * closing, so the check and the close cannot interleave. An
     * {@link IllegalStateException} is the right outcome: callers already treat a
     * failed maintenance operation as reportable, and a clear exception during shutdown
     * is vastly better than a SIGSEGV with no attribution.
     */
    private void checkOpen() {
        synchronized (LOCK) {
            if (this.closed) {
                throw new IllegalStateException("rocksmc: database at " + this.path
                    + " is closed; refusing to use a released native handle. This "
                    + "normally means an operation was still running when the server "
                    + "shut down.");
            }
        }
    }

    // ------------------------------------------------------------- durability

    /**
     * Makes every prior write durable, without flushing memtables.
     *
     * <p>Vanilla calls its flush-all entry point once per storage instance per
     * autosave, which is six times per autosave for a three-dimension world. Under
     * the old layout that meant six independent databases each flushing their own
     * memtables. Now they share one, and flushing memtables here would be actively
     * harmful: it would cut every memtable short six times an autosave, producing a
     * stream of tiny L0 files and the compaction work to merge them again -- for no
     * durability gain, because durability comes from the WAL.
     *
     * <p>So this syncs the WAL and nothing else. That is the same guarantee
     * {@code sync-writes=true} gives per write, applied once per autosave: after it
     * returns, every write issued before it survives a crash.
     */
    public void syncWal() throws IOException {
        checkOpen();
        try {
            this.db.flushWal(true);
        } catch (RocksDBException e) {
            throw new IOException("RocksDB WAL sync failed at " + this.path, e);
        }
    }

    /**
     * Flushes memtables to SST files across all column families.
     *
     * <p>Not part of the autosave path -- see {@link #syncWal()} -- but needed by
     * the importer and the fidelity harness, which must get data out of memtables
     * before measuring on-disk size or exercising the read path against storage.
     */
    public void flushMemtables() throws IOException {
        checkOpen();
        try (FlushOptions flushOptions = new FlushOptions().setWaitForFlush(true)) {
            // Atomic across column families, so chunk and POI cannot end up flushed
            // to inconsistent points.
            this.db.flush(flushOptions, new ArrayList<>(this.columnFamilies.values()));
        } catch (RocksDBException e) {
            throw new IOException("RocksDB flush failed at " + this.path, e);
        }
    }

    /**
     * Compacts the whole keyspace, merging L0 files and collecting obsolete blobs.
     *
     * <p>Not something the server needs -- RocksDB compacts in the background --
     * but measurement harnesses must call it before sizing the database, or
     * un-merged L0 files and unreferenced blobs inflate the apparent footprint.
     */
    public void compact() throws IOException {
        checkOpen();
        try {
            for (ColumnFamilyHandle cf : this.columnFamilies.values()) {
                this.db.compactRange(cf);
            }
        } catch (RocksDBException e) {
            throw new IOException("RocksDB compaction failed at " + this.path, e);
        }
    }

    /**
     * Creates a consistent, application-level snapshot via hard links.
     *
     * <p>This is the capability Anvil cannot offer at all. A filesystem snapshot of
     * a live Anvil world is only crash-consistent and may capture a torn 8 KiB
     * header, which -- with no write-ahead log -- is silently unrecoverable.
     *
     * <p>Now that one database covers the whole world, a checkpoint is world-wide
     * rather than per-dimension. Under the old layout six separate checkpoints
     * could not be taken at one instant, so the set of them had exactly the
     * incoherence problem this class exists to fix.
     *
     * @param target destination directory; must not already exist
     */
    public void checkpoint(File target) throws IOException {
        checkOpen();
        try (Checkpoint cp = Checkpoint.create(this.db)) {
            cp.createCheckpoint(target.getAbsolutePath());
        } catch (RocksDBException e) {
            throw new IOException("Checkpoint to " + target + " failed", e);
        }
    }

    // ---------------------------------------------------------------- metrics

    /** A database-wide RocksDB property, or -1 if unavailable. */
    long longProperty(String name) {
        // Deliberately the -1 sentinel rather than an exception: this is the metrics
        // and logging path, which the exporter already treats -1 as "unavailable" and
        // omits. A scrape racing shutdown should lose a series, not fail.
        synchronized (LOCK) {
            if (this.closed) {
                return -1L;
            }
        }
        try {
            return this.db.getLongProperty(name);
        } catch (RocksDBException e) {
            return -1L;
        }
    }

    /** A per-column-family RocksDB property, or -1 if unavailable. */
    long longProperty(String columnFamily, String name) {
        synchronized (LOCK) {
            if (this.closed) {
                return -1L;
            }
        }
        ColumnFamilyHandle cf = this.columnFamilies.get(columnFamily);
        if (cf == null) {
            return -1L;
        }
        try {
            return this.db.getLongProperty(cf, name);
        } catch (RocksDBException e) {
            return -1L;
        }
    }

    /**
     * Total size of blob files on disk.
     *
     * <p>Read from {@code rocksdb.live-blob-file-size} per column family rather than by
     * scanning the directory. The scan was one directory read plus a {@code stat} per
     * blob file on every snapshot -- every metrics scrape, every stats line, twice per
     * compaction -- which is cheap at 52 blob files and thousands of syscalls at
     * multi-TB scale. The property is an in-memory counter.
     *
     * <p>Verified equivalent on the real 293,207-chunk world: the per-column-family
     * properties summed to 1,143,408,619 bytes against a filesystem scan of exactly
     * the same, delta zero. Note the property is per-column-family -- queried on the
     * default column family it returns 0, so it must be summed rather than asked once.
     */
    long blobFileBytes() {
        long total = 0L;
        for (String cf : dataColumnFamilies()) {
            long bytes = longProperty(cf, "rocksdb.live-blob-file-size");
            if (bytes < 0) {
                return -1L;
            }
            total += bytes;
        }
        return total;
    }

    /**
     * A point-in-time view of the whole database, for logging and metrics.
     *
     * <p>Split from the per-store view deliberately. These values are properties of
     * the shared database, so attaching them to a store would report the same
     * number six times and make any {@code sum()} over them six times too large.
     */
    public Snapshot snapshot() {
        List<ColumnFamilySnapshot> cfs = new ArrayList<>();
        for (String cf : dataColumnFamilies()) {
            cfs.add(new ColumnFamilySnapshot(
                cf,
                longProperty(cf, "rocksdb.live-sst-files-size"),
                longProperty(cf, "rocksdb.total-sst-files-size"),
                longProperty(cf, "rocksdb.estimate-num-keys"),
                longProperty(cf, "rocksdb.size-all-mem-tables"),
                longProperty(cf, "rocksdb.estimate-pending-compaction-bytes"),
                longProperty(cf, "rocksdb.compaction-pending"),
                longProperty(cf, "rocksdb.mem-table-flush-pending"),
                longProperty(cf, "rocksdb.live-blob-file-size")));
        }
        return new Snapshot(
            name(),
            referenceCount(),
            longProperty("rocksdb.num-running-compactions"),
            longProperty("rocksdb.num-running-flushes"),
            longProperty("rocksdb.actual-delayed-write-rate"),
            longProperty("rocksdb.is-write-stopped"),
            longProperty("rocksdb.block-cache-usage"),
            blobFileBytes(),
            cfs);
    }

    /** Immutable database-wide metrics view. */
    public static final class Snapshot {
        public final String database;
        public final long openStores;
        public final long runningCompactions;
        public final long runningFlushes;
        public final long delayedWriteRate;
        public final long writeStopped;
        public final long blockCacheBytes;
        public final long blobFileBytes;
        public final List<ColumnFamilySnapshot> columnFamilies;

        Snapshot(String database, long openStores, long runningCompactions,
                long runningFlushes, long delayedWriteRate, long writeStopped,
                long blockCacheBytes, long blobFileBytes,
                List<ColumnFamilySnapshot> columnFamilies) {
            this.database = database;
            this.openStores = openStores;
            this.runningCompactions = runningCompactions;
            this.runningFlushes = runningFlushes;
            this.delayedWriteRate = delayedWriteRate;
            this.writeStopped = writeStopped;
            this.blockCacheBytes = blockCacheBytes;
            this.blobFileBytes = blobFileBytes;
            this.columnFamilies = Collections.unmodifiableList(columnFamilies);
        }
    }

    /**
     * Immutable per-column-family metrics view.
     *
     * <p>Separate from both other scopes because these values cover all dimensions
     * in one column family. They cannot be attributed to a dimension, and summing
     * them per store would treble them.
     */
    public static final class ColumnFamilySnapshot {
        public final String columnFamily;
        public final long liveSstBytes;
        public final long totalSstBytes;
        public final long estimatedKeys;
        public final long memtableBytes;
        public final long pendingCompactionBytes;
        public final long compactionPending;
        public final long memtableFlushPending;
        /** Blob file bytes for this column family, where nearly all chunk bytes live. */
        public final long blobFileBytes;

        ColumnFamilySnapshot(String columnFamily, long liveSstBytes, long totalSstBytes,
                long estimatedKeys, long memtableBytes, long pendingCompactionBytes,
                long compactionPending, long memtableFlushPending, long blobFileBytes) {
            this.columnFamily = columnFamily;
            this.liveSstBytes = liveSstBytes;
            this.totalSstBytes = totalSstBytes;
            this.estimatedKeys = estimatedKeys;
            this.memtableBytes = memtableBytes;
            this.pendingCompactionBytes = pendingCompactionBytes;
            this.compactionPending = compactionPending;
            this.memtableFlushPending = memtableFlushPending;
            this.blobFileBytes = blobFileBytes;
        }
    }

    // ---------------------------------------------------------------- teardown

    /** Closes the native handle and everything it depends on, in order. */
    private void closeNative() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        if (this.dimensionRegistry != null) {
            this.dimensionRegistry.close();
        }
        // Column family handles must be released before the database itself.
        for (ColumnFamilyHandle handle : this.columnFamilies.values()) {
            handle.close();
        }
        if (this.db != null) {
            this.db.close();
        }
        closeNativeOptions();
    }

    /**
     * Closes the options objects.
     *
     * <p>Also used on a failed open, where the database handle does not exist yet.
     */
    private void closeNativeOptions() {
        if (this.writeOptions != null) {
            this.writeOptions.close();
        }
        if (this.dbOptions != null) {
            this.dbOptions.close();
        }
        if (this.dataCfOptions != null) {
            this.dataCfOptions.close();
        }
        if (this.metaCfOptions != null) {
            this.metaCfOptions.close();
        }
        if (this.bloomFilter != null) {
            this.bloomFilter.close();
        }
        // Released after the options that reference it, or RocksDB may touch a
        // freed cache during teardown.
        if (this.blockCache != null) {
            this.blockCache.close();
        }
        if (this.sstFileManager != null) {
            this.sstFileManager.close();
        }
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public String toString() {
        return "RocksDatabase[" + this.path + ", refs=" + referenceCount() + ']';
    }
}
