# Minecraft 1.16.5 storage subsystem: IO pattern analysis

A design study of how the vanilla dedicated server persists world data, what its
IO pattern actually is, and whether swapping in an industrial storage engine is
feasible or beneficial.

**Scope:** Minecraft Java Edition 1.16.5 dedicated server. All file and line
references are to the deobfuscated `net.minecraft` tree (Yarn `1.16.5+build.10`
mappings). Numbers marked *unmeasured* are analytical estimates; numbers from
`spike/phase0-blob-dict/FINDINGS.md` are measured.

---

## 1. The stack

The entire storage layer is small — **984 LOC** across seven files in
`world/storage`, plus orchestration above it:

| Layer | File | LOC | Role |
|---|---|---|---|
| Orchestration | `server/world/ThreadedAnvilChunkStorage.java` | — | decides *when* to save |
| Version migration | `world/storage/VersionedChunkStorage.java` | 75 | DataFixer on read |
| Codec variant | `world/storage/SerializingRegionBasedStorage.java` | 212 | POI; splits chunk into 16 sections |
| Async + coalescing | `world/storage/StorageIoWorker.java` | 164 | write-behind buffer |
| Handle cache | `world/storage/RegionBasedStorage.java` | 82 | LRU of 256 open region files |
| Format | `world/storage/RegionFile.java` | 362 | Anvil sector allocation |
| Allocator | `world/storage/SectorMap.java` | **30** | BitSet first-fit |

The layer being replaced in any engine swap is `RegionFile` + `RegionBasedStorage`
+ `SectorMap` = **444 LOC**, behind a four-method interface.

## 2. IO pattern

### 2.1 Shape

Key: chunk coordinate, mapped to a slot via
`regionRelativeX + regionRelativeZ * 32` (`RegionFile.java:311-313`) — 1024 chunks
per `r.<x>.<z>.mca` file.

Value: DEFLATE-compressed NBT (`ChunkStreamVersion.java:17`), **always written
whole**. There is no partial update path; changing one block rewrites the entire
chunk blob. `ChunkBuffer`'s 8096-byte initial allocation
(`RegionFile.java:342`) implies Mojang expects ~8 KiB compressed.

### 2.2 Read path — already near-optimal

`RegionFile.getChunkInputStream` (`:98-138`):

1. Look up the packed sector entry in the in-memory 8 KiB header
2. One positioned `channel.read` at `offset * 4096`
3. Wrap in the recorded decompressor

**This is O(1): one in-memory index hit, one seek.** No tree descent, no bloom
filter, no level fan-out. Any LSM or B-tree will be *worse* here. This is the
single most important fact in the whole analysis and it is routinely overlooked.

Oversized chunks (≥ 256 sectors, i.e. ≥ 1 MiB) spill to an external `c.<x>.<z>.mcc`
file (`:255-262`) — a special case that exists purely because the sector-offset
field is 8 bits wide.

### 2.3 Write path

`RegionFile.writeChunk` (`:246-277`):

1. `sectors.allocate(n)` — linear BitSet scan for a free run (`SectorMap.java:16-29`)
2. `channel.write(data, offset * 4096)`
3. `writeHeader()` — **rewrites all 8192 bytes** (`:298-301`)
4. Free the old sector run

### 2.4 Durability

There is **no write-ahead log**. Crash consistency comes from *write ordering* —
payload before the header pointer that references it — made durable by `O_DSYNC`
(`RegionFile.java:55`). That flag is controlled by `sync-chunk-writes`, which
**defaults to `true`** (`ServerPropertiesHandler.java:82`).

So the default configuration performs an fsync-class operation **per chunk
write**, with no group commit. Per-chunk atomicity rests entirely on a single
4-byte header slot being written atomically.

### 2.5 Coalescing and threading

`StorageIoWorker` is a small memtable with read-your-writes:

- `results` is a `LinkedHashMap<ChunkPos, Result>`; `setResult` overwrites
  `result.nbt` in place (`:37-43`), so repeated saves of a hot chunk collapse into
  one physical write
- `getNbt` consults that map before touching disk (`:47-51`)
- Reads are `HIGH` priority, writes `LOW` (`:90,110`) — reads are latency-critical,
  writes are not

Concurrency is deliberately limited: `writeChunk` and `getChunkInputStream` are
`synchronized` per region file, and each storage instance owns one serial
`TaskExecutor`. Chunk *loads* run on a worker pool via
`CompletableFuture.supplyAsync` (`ThreadedAnvilChunkStorage.java:455`), but each
individual read blocks on `join()` inside `StorageIoWorker.getNbt` (`:63`).

### 2.6 Write volume

Defaults: `view-distance` 10, `max-players` 20
(`ServerPropertiesHandler.java:76-77`). Autosave every 6000 ticks = 5 minutes
(`MinecraftServer.java:794`). Only dirty chunks are written — `needsSaving()`
gates it (`ThreadedAnvilChunkStorage.java:626`).

*Unmeasured estimate:* perhaps 1,000–5,000 dirty chunks per autosave × ~8 KiB ≈
**8–40 MB logical per 5 minutes**, i.e. tens to hundreds of KB/s sustained.

This is the number that decides the whole question. It is *tiny*. Chunk IO is not
the bottleneck on a vanilla server; CPU is — worldgen, lighting, entity and
redstone ticking, plus the autosave convoy.

## 3. Where Anvil is genuinely weak

Three defects, all visible in the code:

### 3.1 Fixed 8 KiB header amplification

Every chunk write rewrites the full header (`:298-301`). For an 8 KiB chunk that
is ~2× write amplification; for a 1 KiB chunk it is 8× — the cost is *fixed*
regardless of payload size.

Worse: writing chunk A rewrites the header entries for all 1023 siblings in the
same region file. A torn header write can therefore corrupt pointers to
**unrelated chunks**. The constructor's extensive header-validation logic
(`:70-88`, four distinct corruption cases with warnings) reads as evidence that
this failure mode occurs in practice.

### 3.2 Unbounded fragmentation

`SectorMap` is 30 lines: first-fit allocation over a `BitSet`, free-after-write.
A chunk that grows past its current sector count relocates and leaves a hole.
**There is no compaction, ever.** Region files grow monotonically.

### 3.3 fsync per write

Covered in §2.4. No group commit, no batching across the chunks of one autosave.

## 4. Engine swap: feasibility

**Feasible, unusually so.** The seam is 444 LOC behind four methods
(`getTagAt`, `write`, `method_26982`, `close`), and the interface is *already* a
blob KV store. DataFixer sits **above** the seam
(`VersionedChunkStorage.java:29`, `SerializingRegionBasedStorage.java:124`), so
keeping values as opaque NBT blobs leaves ~22.5k LOC of migration logic untouched.

The `.mcc` oversized-chunk special case disappears entirely, since a real KV store
handles large values natively.

### Candidate assessment

| Engine | Verdict | Reason |
|---|---|---|
| **RocksDB** | ✅ Recommended | LSM KV, point lookups, key-value separation, checkpoints, maintained `rocksdbjni` with prebuilt natives, Apache-2.0 |
| **WiredTiger** | ❌ **No Java API since 2021** | Measured *better* on a fresh write (15.7% ratio, bytes written 1.06×), but **2.1× larger on-disk after overwrites** — the representative pattern. And WT-6675 removed the Java language API in 10.0.0; there is no binding to use. See below |
| **Redis + AOF** | ❌ | Redundant second copy of data already heap-resident; IPC per read; AOF-rewrite forks stall the tick loop. See §5.4 |
| LMDB | ⚠️ Interesting, not evaluated | mmap reads are extremely fast and MVCC snapshots are excellent, but copy-on-write means random writes and there is **no built-in compression** — a hard loss given a measured 4.8–5.7× ratio |
| TiKV | ❌ | Distributed, Raft-replicated, network RTT per point read. The tick loop needs sub-ms chunk reads |
| ClickHouse | ❌❌ | Columnar OLAP. `loadChunk` is a single-key point read (`ThreadedAnvilChunkStorage.java:455-459`); MergeTree has no efficient single-row update, and every chunk save is a whole-value overwrite |

### The WiredTiger question, and two retracted arguments

Three claims were made about WiredTiger over the course of this work. **Two were
wrong, and it is worth recording which.**

*Retracted #1:* "WiredTiger probably lacks ZSTD trained-dictionary support, so it
forfeits cross-chunk compression." Phase 0 showed RocksDB's **blob files ignore
dictionaries too**, and Phase 0c showed WiredTiger's `dictionary=` setting is
per-page value dedup, not trained dictionaries — output was byte-identical with
it on and off. **Neither engine offers true cross-value dictionary compression.**
The objection discriminated nothing.

*Retracted #2:* "You would own multi-platform packaging of the in-tree SWIG Java
binding." **There is no in-tree Java binding.** WiredTiger 10.0.0 (2021-04-12)
removed it:

> WT-6675 Remove WiredTiger Java language API and documentation

Verified against the 11.3.1 source built for Phase 0c: `lang/` contains only
`python`, there is no `ENABLE_JAVA` cmake option, and the tree holds zero `.java`
files. The Java tutorials still online sit under a `/mongodb-3.4/` path —
pre-removal artefacts. So the cost is not *packaging* a binding but *authoring*
an FFI layer (~20-25 C entry points, `byte[]` ↔ `WT_ITEM` marshalling per chunk
read and write, `WT_SESSION` thread-affinity, error mapping, native handle
lifecycle) and maintaining it across platforms indefinitely.

*Survived, and decisive:* the engine is not reachable from Java at acceptable
cost. Secondary: GPL2/GPL3/Commercial licensing, three patches required to build
on GCC 16, and a distribution that ships **no compressor extensions at all**,
silently producing an *uncompressed* database measuring 0.86× — larger than its
input.

On the write-amplification hypothesis specifically: the prediction that a B-tree's
in-place page updates would cost far more bytes than key-value separation **did
not survive measurement** — 1.06×, not the expected multiple. The reason is
visible in RocksDB's own numbers: with BlobDB, compaction accounts for 233 KB out
of 134.6 MB total, or 0.17%. Nearly all write volume is *flush*, which both
engines must do. Key-value separation was defending against a cost that barely
exists at this value size and write rate.

**But WiredTiger's compression advantage inverts under the representative access
pattern.** Its 15.7% edge was measured on a freshly written table; after 12
overwrite rounds its on-disk footprint was **2.1× RocksDB's** (23.6 MB vs
11.2 MB). Chunk saves are whole-value overwrites by nature, so the overwrite
figure is the one that matters, and there RocksDB wins by more than WiredTiger
wins on a fresh write. The cause is uninvestigated — likely checkpoint retention
or free-space fragmentation rather than steady-state size.

Full data: `spike/phase0c-wiredtiger/FINDINGS.md`.

## 5. Engine swap: benefit

### 5.1 What does not improve

**Read path gets worse.** Anvil is one in-memory header hit plus one seek. An LSM
point lookup may probe memtable, then multiple levels, then a blob file
dereference. O(1) is the floor and Anvil is already there.

**Write throughput is a non-issue.** At 8–40 MB per 5 minutes, even a 20×
amplification is under 3 MB/s sustained — irrelevant on any SSD.

Write amplification nevertheless matters, just not for throughput. See §5.7.

### 5.2 What genuinely improves

- **A single recovery point across dimensions.** Vanilla's
  `MinecraftServer.save()` iterates worlds sequentially, so one database per
  dimension means one write-ahead log per dimension, and a crash mid-autosave
  recovers each to a different point in that sequence. Since Minecraft has a single
  tick loop for all dimensions, that is a state no tick ever produced — it can
  duplicate or destroy an entity caught mid-teleport. One database per world
  eliminates the failure class outright, and there is no filesystem-level substitute
  for it. See `known-limitations.md` L2; delivered by Phase 2.
- **Atomic cross-subsystem commits.** Today chunk and POI data live in separate
  `StorageIoWorker` instances over separate directories
  (`VersionedChunkStorage.java:26` vs `SerializingRegionBasedStorage.java:50`), so
  `ThreadedAnvilChunkStorage.save()` (`:625,648`) *cannot* commit them together.
  A `WriteBatch` spanning column families would fix that — but note this needs
  more than consolidation: RocksDB guarantees atomicity per batch, and those writes
  originate above the seam this mod injects at, on independent flush schedules.
  Consolidation is necessary and not sufficient, so this remains follow-up work.
- **Recoverable snapshots.** RocksDB checkpoints are hard-link based and
  near-instant with no pause. But see §5.5: copy-on-write filesystems already
  provide instant snapshots, so the real gain is *recoverability*, not capability.
  Note also that a checkpoint is only world-wide once the databases are
  consolidated; per-dimension checkpoints have the same tearing problem as
  per-dimension WALs.
- **Checksummed WAL** replacing crash-safety-by-write-ordering, and elimination of
  the torn-header blast radius (§3.1).
- **Bounded space amplification** replacing never-compacted fragmentation (§3.2).
- **Collapsing many tiny files.** `PersistentStateManager` allocates one `.dat`
  per saved-data type (`:32,121`), including one per in-game map
  (`ServerWorld.java:1205-1210`). Long-lived worlds accumulate thousands.
- **No per-read throwaway allocation.** Every chunk read allocates a fresh,
  sector-*rounded* heap buffer — `ByteBuffer.allocate(k * 4096)`
  (`RegionFile.java:106-108`) — which is then wrapped via `buffer.array()`
  (`:170-172`). Never pooled, never direct. Minor, but it is GC pressure directly
  on the chunk-load path.

### 5.3 The compression question, resolved by measurement

The payload is *pathologically* redundant across chunks, and per-chunk DEFLATE
cannot see any of it:

- Up to two 2048-byte light nibble arrays per section
  (`ChunkNibbleArray.java:15-16`), across 18 section slots
  (`ChunkSerializer.java:241,257-261`)
- 1024-int biome arrays (`BiomeArray.java:13-15`), frequently *identical* between
  neighbouring chunks
- Block-state palette names re-encoded as literal strings in every chunk
  (`NbtHelper.java:35-46`, `ChunkSerializer.java:253`)
- **Zero uses of `setDictionary` anywhere in the tree** — DEFLATE's preset
  dictionary facility is entirely unused

#### There is no single "Minecraft compression ratio"

Measured on a real server world with vanilla's own codec (deflate-6), ratios span
**4.5×–24.7×** depending purely on what kind of data a chunk holds:

| Data | Mean chunk | deflate-6 ratio |
|---|---|---|
| Large chunks (upper tail) | 586.8 KiB | 24.66× |
| End terrain | 14.2 KiB | 18.74× |
| Nether terrain | 25.9 KiB | 8.69× |
| Overworld terrain | 33.3 KiB | 8.16× |
| POI (overworld) | 586 B | 4.56× |
| POI (end) | 385 B | 2.87× |

Any single figure for "Minecraft data" describes no real workload. The End
compresses more than twice as well as the Overworld — uniform void versus
player-built terrain — and POI values are too small for a codec to find much
redundancy in at all.

#### Codec comparison on real chunk data

Measured directly, without engine framing (Phase 1b). Overworld stratum:

| Codec | Ratio | Decode MB/s | vs deflate-6 |
|---|---|---|---|
| deflate-6 (vanilla) | 8.16× | 642 | — |
| **zstd-9** | **8.30×** | **2022** | **−1.7% size, 3.15× decode** |
| zstd-19 | 9.03× | 1613 | −9.7% size, 2.51× decode |
| zstd-3 (library default) | 7.53× | 1874 | +8.3% size |
| lz4 | 5.01× | 1460 | +62.8% size |
| snappy | 4.78× | 2089 | +70.7% size |

Three results matter:

1. **zstd-9 dominates vanilla on both axes** — smaller *and* ~3× faster to decode.
   There is no tradeoff to weigh. This is significant because decode sits in the
   chunk-load path while encode happens off-thread at autosave.
2. **LZ4 and Snappy lose badly on ratio** (+45% to +102%). They are only
   interesting where per-call latency dominates, i.e. sub-KiB POI values.
3. **Level choice matters more than codec choice.** ZSTD at its default level 3
   *loses* to vanilla; at level 9 it wins. Two earlier conclusions in this project
   were wrong precisely because they tested library defaults and generalised.

#### Where dictionaries actually work

Blob files ignore `CompressionOptions` **entirely** — level *and* dictionary.
Configuring zstd at 3, 9 and 19 produced byte-identical blob output
(41,558,329 bytes in all three cases), while the same settings changed SST output
substantially. Only `blob_compression_type` is honoured for blobs.

With values kept in the LSM, where the options are respected, trained dictionaries
are worthwhile after all:

| Stratum | zstd-9, no dict | zstd-9 + dict | Gain |
|---|---|---|---|
| Overworld | 8.25× | **9.40×** | +14% |
| End | 19.06× | **30.40×** | +59% |
| POI (overworld) | 12.10× | 10.75× | **−11%** |

So the right setting is per-data-type: dictionaries help terrain substantially and
actively hurt POI, where the dictionary costs more than it saves.

WiredTiger reaches cross-value scope by a different route — compressing whole leaf
pages containing many values, measured at 5.65× against BlobDB's per-blob 4.76× on
synthetic data (Phase 0c). The scope argument was sound in principle; page packing
and LSM dictionaries are simply two mechanisms for the same idea.

**Bottom line:** a well-tuned engine configuration beats vanilla payload by
**13% (overworld)** to **62% (POI)**. But note that `ChunkStreamVersion` already
versions compression per chunk (`:16-18`), so vanilla could adopt ZSTD *inside*
`.mca` and capture most of the codec win with **no engine change and full format
compatibility**.

### 5.4 Why not an in-memory store

A natural proposal is: hold the whole map in RAM (Redis, or similar) and persist
via an append-only log. The reasoning behind it is sound — append-only sequential
writes are a genuinely good fit for cheap storage — but it does not survive contact
with how the server actually works.

**Loaded chunks are already resident in the JVM heap as deserialized objects.**
`ThreadedAnvilChunkStorage.currentChunkHolders` is a
`Long2ObjectLinkedOpenHashMap<ChunkHolder>` (`:96-98`), fronted by a four-entry
direct cache in `ServerChunkManager` (`chunkCache = new Chunk[4]`, `:60-62`).

The consequence is decisive: the hot path — block reads and writes, entity
ticking, redstone — **never touches storage at all.** It operates on
`PalettedContainer` objects in heap. Storage is consulted only on two cold events:
chunk load (a player walks into new terrain) and chunk save
(autosave or unload).

So an in-memory store would:

1. **Pay for a second copy** of data that is either already in heap (the working
   set) or, by definition, not currently being touched (everything else).
2. **Lose to the OS page cache, which already does this for free.** If there is
   enough RAM to hold the map, Linux caches the `.mca` files and
   `channel.read` (`RegionFile.java:108`) becomes a memcpy from page cache. No
   IPC, no second copy, no configuration.
3. **Make reads slower.** Every chunk load becomes serialize → socket → context
   switch → lookup → response → deserialize. Even over a unix socket that is tens
   of microseconds plus a cross-process dependency, against a page-cache hit in
   single-digit microseconds.
4. **Introduce fork stalls.** With a large resident set, periodic AOF rewrite
   forks incur page-table copying and copy-on-write faults, landing latency spikes
   on the tick loop.
5. **Regress durability.** `appendfsync everysec` risks ~1 s of loss; `always`
   means an fsync per write. Vanilla's `O_DSYNC` (`RegionFile.java:55`, default
   on) loses nothing.

The useful insight inside the proposal — *make the write path sequential* — is
real, and RocksDB's WAL already provides it: in-process, sequential append, no
second copy, no fork stalls, with group commit available.

### 5.5 Filesystem snapshots vs engine checkpoints

An earlier draft of this analysis claimed Anvil "has no equivalent" to consistent
online snapshots. **That was an overstatement and is corrected here.**

Copy-on-write filesystems (btrfs, ZFS) and LVM thin volumes provide instant
snapshots with no application involvement and no pause. If the world lives on
btrfs, most of the snapshot benefit is available today, for zero engineering.

The distinction that actually matters is *what kind* of consistency:

| Approach | Snapshot cost | Consistency |
|---|---|---|
| btrfs/ZFS + **Anvil** | Instant, no pause | **Crash-consistent only.** May capture a torn 8 KiB header mid-rewrite (`RegionFile.java:298-301`). Anvil has **no WAL**, so such damage is unrecoverable *and silent* |
| btrfs/ZFS + **RocksDB** | Instant, no pause | Crash-consistent, but the **checksummed WAL makes it recoverable** — replay reconstructs a valid state |
| **RocksDB checkpoint** | Hard-link, no pause | **Application-consistent** by construction; no torn state possible |

So the honest position: a filesystem snapshot of an Anvil world is *probably*
fine and *occasionally* silently corrupt, with no mechanism to detect or repair
it. A filesystem snapshot of a RocksDB world is recoverable, and an engine
checkpoint is consistent by design.

That is a real improvement, but a much narrower one than "Anvil cannot do this."
Anyone with btrfs or ZFS already available should weigh the remaining delta
honestly before adopting a new storage engine for snapshot reasons alone.

### 5.6 Rotational storage (secondary observation)

Not a primary consideration for SSD deployments, but worth recording as a genuine
Anvil weakness: the write path is close to worst-case for a spinning disk.

Per chunk write (`RegionFile.java:246-277`): first-fit sector allocation scattered
across the file, a positioned write at `offset * 4096`, then a **full 8 KiB header
rewrite at offset 0** — a mandatory seek back to the start of the file — all under
`O_DSYNC`. That is roughly two seeks plus a synchronous flush for every chunk
saved.

A log-structured engine converts this to sequential appends. If rotational storage
were a target, that alone would justify the change; on SSD it is largely moot.

### 5.7 Flash endurance

Write throughput is irrelevant here (§5.1), but **total bytes written is not**.
A Minecraft server runs continuously for months or years, so write amplification
integrates into real SSD wear. This is the one performance-adjacent axis where the
storage engine choice has lasting consequences.

#### Anvil's fixed overhead

Every chunk save rewrites the entire 8 KiB region header (`RegionFile.java:298-301`)
in addition to the payload. Measured on a real world, the mean compressed chunk is
**3.5 KiB**, so the header is roughly **70% of the bytes written per save**. That
overhead is fixed, meaning its relative cost *grows* as chunks get smaller — and it
is paid on every single write, forever.

With `sync-chunk-writes` defaulting to true (`ServerPropertiesHandler.java:82`),
each of those writes is also `O_DSYNC` (`RegionFile.java:55`), with no group commit.

#### RocksDB's tradeoff

Key-value separation avoids rewriting multi-KiB values during compaction. Against
that, an LSM writes each value to the WAL, then to a flushed SST or blob file, then
potentially again at each compaction level.

**Measured (Phase 1c)** at real LSM depth — three populated levels — with the WAL
counted and RocksDB's counters cross-checked against `/proc/self/io`:

| Config | Engine amplification | On-disk |
|---|---|---|
| blob=on, `sync-writes=false` | **1.481×** | 115,303,090 |
| blob=off, `sync-writes=false` | 1.607× | 105,441,791 |

So key-value separation writes **8.5% fewer bytes** but stores **8.6% more on
disk**, because blob files ignore the compression level and dictionary settings
(§5.3). A near-symmetric trade: neither side dominates, and the choice becomes a
deployment question rather than a correctness one.

Three earlier claims in this document were wrong and are corrected here:

- "Blob files cut compaction 316×" — **off by more than two orders of magnitude.**
  At real depth the ratio is **1.35–1.51×**. The original was measured on an 11.2 MB
  database with no levels populated.
- "~82 KB/GiB versus ~26 MB/GiB" — superseded; those were floors from the same
  invalid run.
- "RocksDB writes 0.32× vanilla" — **withdrawn.** It excluded the WAL, which alone
  is ~1.0× logical. Vanilla has never been instrumented the same way, so no ratio
  against it is currently defensible.

#### The dominant factor is `sync-writes`, and engine counters cannot see it

| Config | Engine amp | Kernel amp (`/proc/self/io`) |
|---|---|---|
| blob=on, sync=false | 1.481× | **1.549×** |
| blob=on, sync=true | 1.488× | **5.649×** |

At identical operation counts, enabling per-write fsync moved RocksDB's own
counters by **+0.4%** and kernel-observed writes by **3.65×**. An fsync per write
forces partial 4 KiB blocks and filesystem metadata to disk, none of which appears
in `WAL_FILE_BYTES` or `COMPACT_WRITE_BYTES`.

Projected over five years at ~11 GiB/day logical, that flag costs roughly **82 TB**
— about **22× the entire blob-versus-LSM difference (3.75 TB)**. The setting this
analysis spent three sections debating is dwarfed by one durability option.

Vanilla is not exempt: `sync-chunk-writes` defaults to true, so it pays `O_DSYNC`
per chunk write with no group commit. This is a shared cost.

Measured on a copy-on-write filesystem, where fsync is unusually expensive; expect
a smaller ratio on ext4/xfs. The direction, and the invisibility to engine
counters, hold regardless. Full data: `spike/phase1c-endurance/RESULTS.md`.

## 6. Conclusion

**Feasible: yes.** The seam is 444 LOC and already blob-KV shaped.

**Beneficial: narrowly, and for one reason.** After measurement, the four original
justifications did not fare equally:

| Justification | Status |
|---|---|
| Cross-chunk compression via dictionaries | ❌ **Dead.** Neither RocksDB blob files nor WiredTiger's `dictionary=` provide trained dictionaries; the LSM alternative costs 140× compaction for 2.3% |
| Sequential writes for rotational storage | ⚠️ **Real but situational** (§5.6); moot on SSD |
| Consistent live snapshots | ⚠️ **Weakened.** Copy-on-write filesystems already give instant snapshots; the true gain is *recoverability* (§5.5) |
| Atomic cross-subsystem commits | ✅ **Intact.** Chunk and POI genuinely cannot be committed together today, and no filesystem feature substitutes for it |

Still true, and not addressed by any filesystem: the torn-header blast radius
across 1023 siblings (§3.1), never-compacted fragmentation (§3.2), thousands of
tiny `.dat` files, and per-read throwaway allocation (§5.2).

**Not beneficial for performance.** The read path regresses from an already
optimal O(1), write amplification improves marginally at best, and absolute write
volume is so low that none of it matters. The three genuine performance wins —

1. ZSTD level 9 instead of DEFLATE-6 (`ChunkStreamVersion.java:17`) — measured
   smaller *and* ~3× faster to decode on real chunk data (§5.3)
2. group-commit instead of `O_DSYNC` per write (`RegionFile.java:55`)
3. incremental rather than convoy autosave (`MinecraftServer.java:794`)

— are all achievable **without** replacing the engine, at a fraction of the
effort, preserving `.mca` compatibility with the entire third-party ecosystem
(Amulet, Chunker, BlueMap/Dynmap, pregenerators, world editors).

**Recommendation.** Anyone whose motivation is "the vanilla server is slow" should
do those three things and stop. Anyone who needs atomic multi-subsystem commits,
or recoverable rather than merely crash-consistent backups, has a real case — but
should weigh it against a permanent `.mca` converter obligation, a native
dependency, and a read path that gets worse.

On engine choice: **RocksDB, and not merely by default.** WiredTiger measured
better on a *fresh* write (15.7% ratio, bytes written 1.06×) and that finding
stands — it contradicted the prediction. But it loses on the pattern this workload
actually produces (2.1× larger on-disk after overwrites), and WT-6675 removed its
Java language API in 2021, so there is no binding to use at all. The decision does
not turn on licensing.

## 7. Caveats

- Write-volume, header-amplification, and fragmentation figures are **analytical,
  not measured**. No world was generated; the empirical phase was explicitly out
  of scope.
- Compression and amplification measurements used **synthetic** corpora modelling
  chunk redundancy. Real-world magnitudes are unknown.
- Single engine versions (RocksDB 10.10.1, WiredTiger 11.3.1), single machine, no
  repetitions. Size figures are deterministic under a fixed seed; timing was not
  measured at all.
- **The two engine harnesses are written in different languages** (Java/RocksDB,
  Python/WiredTiger). Corpora are byte-identical — Java's `Random` LCG was
  reimplemented exactly — but flush and checkpoint semantics are only
  *approximately* aligned, and the two engines' write-bytes statistics are not
  guaranteed to count identical things. The 1.06× bytes-written comparison should
  be read as "same order of magnitude", not a precise ratio.
- One result that inverts the headline: WiredTiger's on-disk size after 12
  overwrite rounds was 2.1× RocksDB's, the opposite of its fresh-write advantage.
  Probably checkpoint retention or free-space fragmentation, but not investigated.
  Since chunk saves are overwrites, this is the more representative condition.
- Two harness bugs were found and corrected during this work (double-counted blob
  bytes; an impossible sub-1.0× write-amplification figure from dividing
  compressed by uncompressed bytes), and one unfair comparison was caught before
  publication (WiredTiger *total* bytes against RocksDB *compaction-only* bytes).
  Both spike FINDINGS files document them. Treat any remaining single-source
  number here with corresponding suspicion.
