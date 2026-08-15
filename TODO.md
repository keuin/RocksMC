# TODO

Active work plan. Written to survive a chat compaction, so it states the reasoning
as well as the steps.

---

## ⏸ WHERE THINGS STAND (read this first)

Everything below the next divider is history. Current state:

**201 tests pass, `./gradlew build` clean, working tree clean.** Deployed and working on
the beta mirror at `/opt/onesmp/mirror_rocksmc` (real 293,207-chunk world; POI verified
live by the operator). 8 commits ahead of `origin/master`, not pushed.

### IN PROGRESS — Phase 5, the `.mca` exporter

`ChunkKeyCodec` and `AnvilWriter` are done and tested. `WorldExporter` and `ExportMain`
are committed as **WIP with no tests and never run** (`7e5868b`) — treat as a draft.

Remaining, in order:

1. `exportWorld` Gradle task, mirroring `importWorld` (`build.gradle`, ~30 lines).
2. Tests. The one that matters most: **`.mca` → import → export → `AnvilReader`**, asserting
   the `{ChunkPos → NBT}` maps are equal. Reuse `AnvilReaderTest.writeRegion` /
   `WorldImporterTest.writeRegion` as fixtures, plus **one chunk big enough to force the
   ≥256-sector spill** through the whole path. Also needed: a test pinning that
   serialise→parse→serialise is byte-stable, since the exporter's hash-based
   verification depends on it.
3. Verify on the real world: extract `~/kbackup-2025-09-29_02-06-31_before-recovery-rollback.zip`
   to `/tmp`, `importWorld`, then `exportWorld`, then compare exported `.mca` against the
   originals chunk-for-chunk. Expect 293,207.
4. Docs: README roadmap (Phase 5 currently unchecked at line ~273), `beta-setup.md`
   (a `.mca` exporter changes the rollback story — the `.mca` files stop being a
   permanent 1.69 GiB tax and become regenerable on demand).

Design decisions already made and agreed, do not relitigate:

- Reuse vanilla `RegionFile`; do **not** hand-roll a writer.
- Standalone tool (no Fabric Loader → no mixin → vanilla's writer is stock bytecode).
- **Read-only open + snapshot** by default. For a running server the supported route is
  `/rocksmc checkpoint` then `--database <world>/rocksmc-checkpoints/<name>`; pointing at
  a live database would miss unflushed memtables.
- Verification by SHA-256 of serialised NBT, not by holding NBT (memory).

### Then: Phase 3 — `playerdata` + saved data

Last on purpose: widens the blast radius to player inventories. Seams are clean
(`WorldSaveHandler.savePlayerData`/`loadPlayerData`/`getSavedPlayerIds`, one call site
each; `PersistentState.save` for writes, `PersistentStateManager.readFromFile` for reads).
⚠️ Trap: on a read failure vanilla **silently creates a fresh player** and overwrites the
good data on logout, so returning null from a DB hook is data loss, not a no-op.

`data/` is the largest thing still unprotected by the database (scoreboard, maps,
`idcounts.dat`, forceload, command storage) — only as safe as file backups.

### Operator-side, not code

- Repeat the `kill -9` test on target hardware (Optane + btrfs, `chattr +C`). The four
  verified cycles ran on tmpfs, which has entirely different fsync behaviour.
- The mirror still holds its `.mca` files (~1.69 GiB). They are the only rollback until
  Phase 5 ships.

### Rules earned the hard way — do not rediscover these

- **Verify from the shipped jar, not `runServer`.** Two bugs reached production that dev
  mode could not show: a missing bundled Prometheus module, and command registration.
- **Validate a probe in both directions before trusting it.** In-game probes once
  reported total data loss that had not happened.
- **Never let a hand-maintained list be checked by care alone.** `ModMetadataTest` guards
  the mixin list; `verifyBundledLibraries` guards the dependency list *and* the mod's own
  classes.
- **Log success, not only failure.** The command bug was undiagnosable because
  registration was silent.
- **Permission levels come from `ops.json`, not `op-permission-level`.** The latter is read
  only when `/op` runs. Commands and alerts both require level 3.
- **Prefer the simple fix.** I built configurable permission levels; a single `4`→`3` was
  correct and the operator was right to reject the machinery.
- A flaky test is worse than no test. `Thread.join(timeout)` returns whether or not the
  thread finished — use a latch.

---
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

- [x] **POI under a live server** — confirmed by the operator: villagers keep
      professions and beds across a restart.
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
