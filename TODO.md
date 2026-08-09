# TODO

Active work plan. Written to survive a chat compaction, so it states the reasoning
as well as the steps.

---

## Phase 2 — consolidate to one database per world

**Status:** planned, not started
**Blocks:** the beta rollout (agreed to land this first)
**Estimated:** ~300–400 LOC, mostly splitting `RocksChunkStore`

### Why: separate databases cannot recover to a coherent state

`MinecraftServer.save()` (decompiled tree, `server/MinecraftServer.java:538-556`)
iterates worlds **sequentially** — Overworld, then Nether, then End — calling
`serverWorld.save(...)` on each independently.

With one RocksDB per `(dimension, leaf)`, each database has its own write-ahead log
and its own group-commit boundary. A crash part-way through an autosave therefore
recovers each database to a *different* point in that sequence. Minecraft has a
single tick loop for all dimensions, so there is no tick at which the Overworld had
finished saving but the Nether had not. **Recovery lands on a state no tick ever
produced.**

Verified cross-dimension coupling that this can tear:

| Coupling | Location | Torn outcome |
|---|---|---|
| Entity teleport between dimensions | `entity/Entity.java:2050-2054` (`moveToWorld`) | Entity present in **both** worlds (duplication — an item/mob dupe created by crash recovery) or in **neither** (loss) |
| Nether portal linkage | `entity/Entity.java:1668` | Paired portal survives on one side only |
| Map item data | `server/world/ServerWorld.java:1204-1210` | **All** map state routes through the *Overworld's* `PersistentStateManager` regardless of which dimension the map depicts, so a Nether map already depends on Overworld storage |

### Secondary motive: per-store resource duplication

`RocksChunkStore` allocates resources **per store**, not per world
(`RocksChunkStore.java:140-141`, `:168-182`). With six stores open (region + poi ×
three dimensions) the beta configuration currently multiplies out to:

- `block-cache-size=512 MiB` × 6 = **~3 GB** of block cache, where 512 MB was
  intended
- `write-buffer-size=128 MiB` × `max-write-buffer-number=6` × 6 stores = up to
  **~4.6 GB** of memtables
- `max-background-jobs=8` × 6 = **48** background threads

This is a live misconfiguration in `docs/beta-setup.md`, corrected there in the
same commit that recorded this plan. Consolidation fixes it structurally rather
than by lowering numbers.

### ⚠️ What one database does and does not give

**Does:** one WAL and therefore one recovery point across all dimensions. That
alone eliminates the "no tick produced this" class of corruption. It also makes
Phase 4 checkpoints world-wide rather than per-dimension, which is the only way
that snapshot story actually works.

**Does not:** make chunk and POI writes atomic with respect to each other. RocksDB
guarantees atomicity per `WriteBatch`, not across separate `put` calls. Vanilla's
writes originate in independent `StorageIoWorker` instances that flush on their own
schedule, above the four-method seam this mod injects at. Batching them requires
intercepting at a higher level — recorded below as follow-up, **not** delivered by
this phase. Do not claim atomic cross-subsystem commits on the strength of
consolidation alone.

### Design

**One database per world root**, at `<world>/rocksmc.db`.

The world root is already captured by `DimensionKey`'s regex as the `root` group
(`DimensionKey.java:109`) but is not currently exposed — add an accessor rather
than re-deriving it.

**Three column families:**

| CF | Contents | Notes |
|---|---|---|
| `default` | format version, dimension registry | as today |
| `chunk` | all dimensions' chunk NBT | blob files on |
| `poi` | all dimensions' POI NBT | blob/LSM per size |

Deliberately **not** one column family per dimension: each CF carries its own
memtable, which would reproduce the duplication being removed.

**Key encoding unchanged:** `dimOrdinal(4B) | morton(x,z)(8B)`, 12 bytes total.
This resolves the earlier question about whether `dimensionId` is redundant — under
one shared database it becomes load-bearing. Dimension-first ordering also keeps
each dimension's chunks in a contiguous key range, which is what would make a
future per-dimension `DeleteRange` or bulk export cheap.

**New `RocksDatabase` class** owns the `RocksDB` handle, `DBOptions`, the shared
`LRUCache`, the `BloomFilter` and the `DimensionRegistry`. Reference-counted,
keyed by canonical world path. `RocksChunkStore` becomes a thin view over
`(database, columnFamily, dimensionOrdinal)`.

### ⚠️ Principal risk: shared handle lifecycle

Six `RegionBasedStorage` instances will share one `RocksDB` handle. Only the last
`close()` may release it; releasing early corrupts every dimension at once.

The mixin's `close` injection (`RegionBasedStorageMixin.java:184-194`) is a clean
single choke point, so refcounting is tractable — but it needs an explicit counter
and a dedicated test, not an assumption about call order.

### Migration

Bump `FORMAT_VERSION` 1 → 2. **No in-place migration** — the mod is alpha and the
world is backed up:

- `importWorld` writes the new layout directly
- opening a v1 database aborts with a message naming the re-import command
  (the existing format guard already does this; only the constant changes)
- old `*.rocksdb` directories are left untouched, so `backend=anvil` rollback is
  unaffected

### Steps

1. Expose `root` on `DimensionKey`; add a test for each of the four layouts
2. Write `RocksDatabase` with refcounting; test open/close ordering in isolation
   **before** wiring anything to it
3. Reduce `RocksChunkStore` to a view; keep the public surface
   (`read`/`write`/`sync`/`close`/`snapshot`) unchanged so metrics and the
   dashboard need no edits
4. Update the mixin to resolve `(world root, leaf)` → shared database + CF
5. Bump `FORMAT_VERSION` to 2
6. Update `WorldImporter` for the single-database layout
7. Correct the tuning defaults in `RocksMcConfig` and the generated config
   template, now that resources are genuinely shared
8. Verification (below)
9. Update `docs/beta-setup.md`, `README.md`, `docs/storage-io-analysis.md`

### Verification

| Check | Requirement |
|---|---|
| Existing tests | all 38 still pass |
| New: refcounted lifecycle | handle survives until the last store closes |
| New: per-dimension isolation | two dimensions in one CF never alias |
| New: v1 rejection | opening a v1 database aborts with the re-import hint |
| Re-import + fidelity on the real world | **293,207 / 293,207**, zero mismatches |
| Dev server | 6 stores share one handle; metrics still emit 6 store series |
| **`kill -9` mid-autosave** | **single recovery point across dimensions** — the entire point of this phase, so this test is mandatory rather than optional |
| Memory | one block cache, not six |

---

## Follow-up: true cross-store `WriteBatch` atomicity

**Status:** identified, not scoped

Consolidation gives a shared WAL. Genuine atomicity between a chunk write and its
POI write needs both in one `WriteBatch`, which means intercepting above
`StorageIoWorker` rather than at the current `RegionBasedStorage` seam. That is a
larger design change than Phase 2 and should follow beta telemetry, not precede it.

---

## Deferred phases

| Phase | Scope | Notes |
|---|---|---|
| **3** | `playerdata`, `data/*.dat`, `level.dat` mirror into CFs | Widens blast radius to player inventories. Until done, backups must include these files — they are **not** in RocksDB |
| **4** | Checkpoint-based recoverable snapshots | `RocksChunkStore.checkpoint()` exists but nothing calls it. Only meaningful once Phase 2 makes checkpoints world-wide |
| **5** | Bidirectional `.mca` ⇄ RocksDB converter | Required for Amulet, Chunker, BlueMap/Dynmap and pregenerators. Likely more code than the storage layer it replaces |

---

## Open items carried from review

### `DimensionRegistry` locking

Reviewed and left as-is. The premise that the map is immutable at runtime does not
hold: `ordinalFor()` writes to it at `DimensionRegistry.java:136` on the
lazy-assignment path. The lock guards a check → allocate → persist → cache sequence
that must be atomic, because `allocateOrdinal()` does a read-modify-write on the
`\0next` counter; two threads missing the cache could otherwise be handed the same
ordinal, putting two dimensions in one keyspace.

Not converted to a read-write lock: `ordinalFor()` needs exclusive access anyway,
`size()`/`snapshot()` have **no production callers** (test-only), and an RWLock is
slower than `synchronized` when uncontended. Contention was checked and does not
exist — the metrics path (`RocksChunkStore.snapshot()`) reads only final fields,
atomics and RocksDB properties, and never touches the registry.

**Real defect worth fixing at Phase 2:** the lock is held across a blocking
`db.flush(...)`. Harmless today (startup, single-threaded), but once a shared
registry can assign an ordinal during play, one dimension's flush would block every
other dimension's chunk load. Also `FlushOptions` is never closed there — a small
native leak per newly seen dimension.

### `min-blob-size` is an unresolved trade

Phase 1c measured blob files as a near-symmetric trade: **8.5% fewer bytes written**
against **8.6% more stored on disk**, because blob files ignore the compression
level and dictionary settings entirely. Either default is defensible; it is a
deployment question, not a correctness one. Left at 1024 (blobs on) for the beta
because compaction bytes are compaction CPU competing with the tick loop.

### Untested areas, highest first

1. **POI has never run under a live server.** The dev run logged
   `poi.rocksdb: writes=0`. POI flows through `SerializingRegionBasedStorage`,
   which splits chunks into 16 sections above the seam — exercised only by the
   harness. Verify villagers keep professions and beds across a restart.
2. **Crash recovery is unverified.** The WAL is checksummed and replayable in
   principle; no `kill -9` test has been run. Phase 2 makes this mandatory.
3. **Vanilla Anvil has never been instrumented.** Every comparison against it is
   derived (payload + 8 KiB header per write), which is how the withdrawn "0.32×
   vanilla writes" claim went wrong. Any future "X× better than vanilla" needs the
   same `/proc/self/io` treatment used in Phase 1c.
