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
| **WiredTiger** | ⚠️ **Technically better, rejected on licence/packaging** | **Measured: 15.7% better compression ratio and bytes written within 1.06×** (Phase 0c). Loses on GPL2/GPL3-only licence, no prebuilt Java artifact, and three build patches needed on GCC 16 |
| **Redis + AOF** | ❌ | Redundant second copy of data already heap-resident; IPC per read; AOF-rewrite forks stall the tick loop. See §5.4 |
| LMDB | ⚠️ Interesting, not evaluated | mmap reads are extremely fast and MVCC snapshots are excellent, but copy-on-write means random writes and there is **no built-in compression** — a hard loss given a measured 4.8–5.7× ratio |
| TiKV | ❌ | Distributed, Raft-replicated, network RTT per point read. The tick loop needs sub-ms chunk reads |
| ClickHouse | ❌❌ | Columnar OLAP. `loadChunk` is a single-key point read (`ThreadedAnvilChunkStorage.java:455-459`); MergeTree has no efficient single-row update, and every chunk save is a whole-value overwrite |

### The WiredTiger question, and a retracted argument

Two objections were originally raised against WiredTiger. **One of them was
wrong, and it is worth recording which.**

*Retracted:* "WiredTiger probably lacks ZSTD trained-dictionary support, so it
forfeits cross-chunk compression." Phase 0 showed RocksDB's **blob files ignore
dictionaries too**, and Phase 0c showed WiredTiger's `dictionary=` setting is
per-page value dedup, not trained dictionaries — output was byte-identical with
it on and off. **Neither engine offers true cross-value dictionary compression.**
The objection discriminated nothing.

*Survived:* packaging and licensing. WiredTiger is GPL2/GPL3/Commercial, has no
maintained Maven artifact with prebuilt natives, needed three patches to compile
on GCC 16, and — most dangerously — its PyPI distribution ships **no compressor
extensions at all**, silently producing an *uncompressed* database measuring
0.86×, i.e. larger than its input.

On the write-amplification hypothesis specifically: the prediction that a B-tree's
in-place page updates would cost far more bytes than key-value separation **did
not survive measurement** — 1.06×, not the expected multiple. The reason is
visible in RocksDB's own numbers: with BlobDB, compaction accounts for 233 KB out
of 134.6 MB total, or 0.17%. Nearly all write volume is *flush*, which both
engines must do. Key-value separation was defending against a cost that barely
exists at this value size and write rate.

Full data: `spike/phase0c-wiredtiger/FINDINGS.md`.

## 5. Engine swap: benefit

### 5.1 What does not improve

**Read path gets worse.** Anvil is one in-memory header hit plus one seek. An LSM
point lookup may probe memtable, then multiple levels, then a blob file
dereference. O(1) is the floor and Anvil is already there.

**Write amplification barely improves.** Anvil's ~2× is already good. BlobDB
achieves roughly comparable amplification; plain leveled compaction on 8 KiB
values would be far worse.

**Throughput is a non-issue either way.** At 8–40 MB per 5 minutes, even a 20×
amplification is under 3 MB/s sustained — irrelevant on any SSD. Write
amplification matters here only for *flash endurance* over multi-year deployments.

### 5.2 What genuinely improves

- **Atomic cross-subsystem commits.** Today chunk and POI data live in separate
  `StorageIoWorker` instances over separate directories
  (`VersionedChunkStorage.java:26` vs `SerializingRegionBasedStorage.java:50`), so
  `ThreadedAnvilChunkStorage.save()` (`:625,648`) *cannot* commit them together.
  A single `WriteBatch` across column families fixes that. **This is the pillar
  with no filesystem-level substitute**, and after the corrections in §5.5 it is
  the strongest remaining argument for the swap.
- **Recoverable snapshots.** RocksDB checkpoints are hard-link based and
  near-instant with no pause. But see §5.5: copy-on-write filesystems already
  provide instant snapshots, so the real gain is *recoverability*, not capability.
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

This made shared-dictionary compression the most attractive part of the design.
**Measurement killed that specific mechanism on both engines:** RocksDB blob files
ignore trained dictionaries (byte-identical output with them on and off), keeping
values in the LSM instead costs 140× compaction traffic for a 2.3% size win, and
WiredTiger's `dictionary=` turned out to be per-page value dedup rather than
trained dictionaries — also byte-identical on and off.

What *does* recover cross-value scope is **page packing**: WiredTiger compressing
whole leaf pages containing many values measured **5.65×**, against BlobDB's
per-blob **4.76×** — a 15.7% edge (Phase 0c). So the scope argument was correct in
principle; it simply arrives via page size rather than dictionaries, and the
magnitude is modest.

What survives regardless of engine: **DEFLATE → ZSTD** is a real CPU and ratio
improvement. But `ChunkStreamVersion` already versions compression per chunk
(`:16-18`), so vanilla could adopt ZSTD *inside* `.mca` and capture that with **no
engine change and full format compatibility**.

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

1. ZSTD instead of DEFLATE (`ChunkStreamVersion.java:17`)
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

On engine choice: **WiredTiger measured better than RocksDB** on this workload
(15.7% better ratio, bytes written within 1.06×). RocksDB remains the
recommendation on licensing (Apache-2.0 vs GPL-only) and packaging (prebuilt Maven
natives vs three build patches and a silent no-compression failure mode) — not on
technical merit. That distinction is worth stating plainly rather than
retrofitting a technical justification onto a practical decision.

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
- One unexplained result: WiredTiger's on-disk size after 12 overwrite rounds was
  2.1× RocksDB's, the opposite of its Experiment 1 advantage. Probably checkpoint
  retention or free-space fragmentation rather than steady-state size, but it was
  not investigated.
- Two harness bugs were found and corrected during this work (double-counted blob
  bytes; an impossible sub-1.0× write-amplification figure from dividing
  compressed by uncompressed bytes), and one unfair comparison was caught before
  publication (WiredTiger *total* bytes against RocksDB *compaction-only* bytes).
  Both spike FINDINGS files document them. Treat any remaining single-source
  number here with corresponding suspicion.
