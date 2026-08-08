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
| **RocksDB** | ✅ Viable | LSM KV, point lookups, key-value separation, checkpoints, maintained `rocksdbjni` with prebuilt natives |
| WiredTiger | ⚠️ Workable | Right shape, first-class checkpoints. But no maintained Maven artifact with natives — you own multi-platform packaging indefinitely. No evidence of ZSTD trained-dictionary support |
| TiKV | ❌ | Distributed, Raft-replicated, network RTT per point read. The tick loop needs sub-ms chunk reads |
| ClickHouse | ❌❌ | Columnar OLAP. `loadChunk` is a single-key point read (`ThreadedAnvilChunkStorage.java:455-459`); MergeTree has no efficient single-row update, and every chunk save is a whole-value overwrite |

On the RocksDB-vs-WiredTiger write-amplification question specifically: the
intuition that LSM compaction repeatedly rewrites multi-KiB values is **correct**,
and measured — 32.8 MB of compaction traffic with values in the LSM. But
key-value separation reduces that to **233 KB** for identical logical writes, a
140× difference. The write-amp objection to RocksDB does not survive contact with
BlobDB. See `spike/phase0-blob-dict/FINDINGS.md`.

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

- **Consistent snapshots of a live world.** RocksDB checkpoints are hard-link
  based and near-instant, with no server pause. **Anvil has no equivalent** —
  safe backup requires flush-and-pause. This is the strongest argument for the
  swap, and it is an operational argument, not a performance one.
- **Atomic cross-subsystem commits.** Today chunk and POI data live in separate
  `StorageIoWorker` instances over separate directories
  (`VersionedChunkStorage.java:26` vs `SerializingRegionBasedStorage.java:50`), so
  `ThreadedAnvilChunkStorage.save()` (`:625,648`) *cannot* commit them together.
  A single `WriteBatch` across column families fixes that.
- **Checksummed WAL** replacing crash-safety-by-write-ordering, and elimination of
  the torn-header blast radius (§3.1).
- **Bounded space amplification** replacing never-compacted fragmentation (§3.2).
- **Collapsing many tiny files.** `PersistentStateManager` allocates one `.dat`
  per saved-data type (`:32,121`), including one per in-game map
  (`ServerWorld.java:1205-1210`). Long-lived worlds accumulate thousands.

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
**Measurement killed it for chunk data:** RocksDB blob files ignore trained
dictionaries (byte-identical output with dictionaries on and off), and keeping
values in the LSM to regain dictionary scope costs 140× compaction traffic for a
2.3% size win.

What survives: **DEFLATE → ZSTD** is a real CPU and modest ratio improvement. But
`ChunkStreamVersion` already versions compression per chunk (`:16-18`), so vanilla
could adopt ZSTD *inside* `.mca` and capture that win with **no engine change and
full format compatibility**.

## 6. Conclusion

**Feasible: yes.** The seam is 444 LOC and already blob-KV shaped.

**Beneficial: only for operational reasons.** Specifically: live consistent
snapshots, atomic cross-subsystem commits, checksummed WAL, bounded space
amplification. None of these are obtainable from Anvil at any amount of tuning.

**Not beneficial for performance.** The read path regresses from optimal, write
amplification improves marginally at best, and absolute write volume is so low
that none of it matters. The three genuine performance wins —

1. ZSTD instead of DEFLATE (`ChunkStreamVersion.java:17`)
2. group-commit instead of `O_DSYNC` per write (`RegionFile.java:55`)
3. incremental rather than convoy autosave (`MinecraftServer.java:794`)

— are all achievable **without** replacing the engine, at a fraction of the
effort, preserving `.mca` compatibility with the entire third-party ecosystem
(Amulet, Chunker, BlueMap/Dynmap, pregenerators, world editors).

Anyone whose motivation is "the vanilla server is slow" should do those three
things and stop. Anyone who needs to back up a running world without pausing it
has no option within Anvil, and that is the case this project exists to serve.

## 7. Caveats

- Write-volume, header-amplification, and fragmentation figures are **analytical,
  not measured**. No world was generated; the empirical phase was explicitly out
  of scope.
- Compression and amplification measurements used **synthetic** corpora modelling
  chunk redundancy. Real-world magnitudes are unknown.
- Single RocksDB version (10.10.1), single machine, no repetitions. Size figures
  are deterministic under a fixed seed; timing was not measured at all.
