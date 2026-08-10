# TODO

Active work plan. Written to survive a chat compaction, so it states the reasoning
as well as the steps.

---

## ✅ Phase 2 — consolidate to one database per world (DONE)

**Status:** delivered and verified on the real world
**Was blocking:** the beta rollout

One RocksDB per world at `<world>/rocksmc.db`, shared by all dimensions and both
storage leaves, with `chunk` and `poi` column families and metadata in `default`.
`RocksDatabase` owns the handle and is reference counted; `RocksChunkStore` is now a
thin view over `(database, columnFamily, dimensionOrdinal)` owning no native
resources.

### What it fixes

Separate databases each had their own write-ahead log, and `MinecraftServer.save()`
iterates worlds **sequentially**, so a crash mid-autosave recovered each dimension
to a different point in that sequence — a state no tick ever produced, capable of
duplicating or destroying an entity mid-teleport. One WAL means one recovery point.

It also removed a live misconfiguration: options, block cache, bloom filter and
thread pool were allocated **per store**, so a three-dimension world multiplied every
memory setting by six. A documented 512 MiB block cache really meant ~3 GiB. These
are now per-world and the defaults were raised back to their intended values.

### Verification results

| Check | Result |
|---|---|
| Unit tests | **75 pass** (was 38) |
| Refcounted lifecycle | handle survives until the last store closes; close order irrelevant; double close absorbed; over-release throws; concurrent open shares one handle |
| Per-dimension isolation | chunk (0,0) in all three dimensions returns three different chunks, on real data |
| Chunk/POI isolation | same ordinal, same position, different column families, no aliasing |
| v1 rejection | refused with the re-import command named; verified live on the old dev world |
| Re-import + fidelity | **293,207 / 293,207**, zero mismatches, −33.9% vs Anvil on disk |
| Dev server | 6 stores, 1 handle, 1 path; metrics emit 6 / 2 / 1 series per scope |
| **`kill -9` mid-autosave** | **4 cycles, uniform recovery every time**, 293,207 entries intact afterwards |
| Memory | one block cache, one thread pool, two memtable sets |

### Deviations from the original plan, and why

1. **Metrics were re-scoped rather than left alone.** The plan said to keep
   `RocksChunkStore`'s surface unchanged so the dashboard needed no edits. That was
   wrong: half of `Snapshot`'s fields were database- or column-family-scoped, so
   emitting them per store reported shared values six times. Measured on the real
   world, the old names would have shown **6.40 GiB** on-disk and **881,208** entries
   against a true 1.1 GiB and 293,207. Column-family metrics now carry a `_by_cf`
   suffix so stale queries break loudly instead of quietly aggregating the wrong
   scope.
2. **`sync()` syncs the WAL instead of flushing memtables.** Vanilla calls it once
   per storage instance per autosave — six times for a three-dimension world — and
   all six now reach one database. Flushing there would cut every memtable short six
   times an autosave, producing tiny L0 files and compaction work to merge them, for
   no durability gain: durability comes from the WAL. `flushMemtables()` exists
   separately for the importer and harnesses.
3. **A legacy-layout guard was added.** The format version lives *inside* a database,
   so it cannot catch a v1 world: the v2 database is a different directory and simply
   looks absent, which the blank-start guard would then misreport as a fresh world.
   The guard names the re-import command instead.
4. **`DimensionKey.root()` normalises `.` segments.** `getAbsolutePath()` leaves them
   in, so a server launched with `./world` logged `/srv/./world/rocksmc.db`, which an
   operator comparing paths reads as a different location.

### Fixed in passing

- `DimensionRegistry` held its lock across a blocking `db.flush(...)`, so one
  dimension being seen for the first time would have blocked every other dimension's
  chunk loads once the registry became shared. Now a single synced `WriteBatch`,
  which is also atomic between the assignment and the counter advance.
- The same code leaked a `FlushOptions` per newly seen dimension.
- `WorldImporter` claimed to force `verify-on-read` off but the line was a no-op
  self-assignment; there is now a real `RocksMcConfig.withVerifyOnRead(false)`.

### Methodological note worth keeping

The crash test nearly produced a false "data lost" conclusion. Three in-game probes
were unreliable: `execute if block ... run say` returns an empty RCON reply even on
a hit, `data get block` only works on block entities, and a `clone` destination above
the build height matches nothing regardless of the source. Two of them reported total
loss that had not happened. The verdict rests on decoding stored chunk NBT directly
from the database. **Validate the probe in both directions before trusting a
measurement** — the same failure that produced the retracted write-amplification
claims.

---

## Follow-up: true cross-store `WriteBatch` atomicity

**Status:** identified, not scoped

Consolidation gave a shared WAL and therefore a shared recovery point. It did **not**
make a chunk write atomic with its POI write: RocksDB guarantees atomicity per
`WriteBatch`, and those two writes originate in independent `StorageIoWorker`
instances that flush on their own schedule, above the four-method seam this mod
injects at. Batching them means intercepting higher up — a larger design change that
should follow beta telemetry rather than precede it.

Do not describe the current state as atomic cross-subsystem commits.

---

## Before the beta

- [ ] **Run a live server with villagers** and confirm POI writes appear
      (`rocksmc_chunk_writes_total{store="poi"}` non-zero). POI has still never been
      exercised by a live server — only by the importer and the harness. This is the
      highest untested risk.
- [ ] Repeat the `kill -9` test on the **target hardware and filesystem** (Optane +
      btrfs with `chattr +C`). The four cycles here ran on tmpfs, which has entirely
      different fsync and writeback behaviour.
- [ ] Rehearse the rollback once: flip to `backend=anvil`, confirm the world loads.
- [ ] Import the beta world with `verify-on-read=true` for the first week.

---

## Deferred phases

| Phase | Scope | Notes |
|---|---|---|
| **3** | `playerdata`, `data/*.dat`, `level.dat` mirror into CFs | Widens blast radius to player inventories. Until done, backups must include these files — they are **not** in RocksDB |
| **4** | Checkpoint-based recoverable snapshots | `RocksDatabase.checkpoint()` exists and is tested, but nothing calls it. Now genuinely world-wide, which is what makes it meaningful |
| **5** | Bidirectional `.mca` ⇄ RocksDB converter | Required for Amulet, Chunker, BlueMap/Dynmap and pregenerators. Likely more code than the storage layer it replaces |

---

## Open items carried from review

### `min-blob-size` is an unresolved trade

Phase 1c measured blob files as a near-symmetric trade: **8.5% fewer bytes written**
against **8.6% more stored on disk**, because blob files ignore the compression
level and dictionary settings entirely. Either default is defensible; it is a
deployment question, not a correctness one. Left at 1024 (blobs on) for the beta
because compaction bytes are compaction CPU competing with the tick loop.

### Untested areas, highest first

1. **POI has never run under a live server.** The dev runs logged
   `poi: writes=0` — no villagers in the test world's loaded area. POI flows through
   `SerializingRegionBasedStorage`, which splits chunks into 16 sections above the
   seam; the importer wrote 3,754 POI entries successfully and the harness round-trips
   them, but neither exercises the live section-split path. Verify villagers keep
   professions and beds across a restart.
2. **Crash recovery on real storage.** Verified four times, but on tmpfs. btrfs CoW
   and Optane behave differently enough that the test is worth repeating on target.
3. **Vanilla Anvil has never been instrumented.** Every comparison against it is
   derived (payload + 8 KiB header per write), which is how the withdrawn "0.32×
   vanilla writes" claim went wrong. Any future "X× better than vanilla" needs the
   same `/proc/self/io` treatment used in Phase 1c.
4. **Multi-world servers are untested.** The code keys databases on the canonical
   world root and the tests cover two worlds in one JVM, but no multiverse-style mod
   has been tried.
