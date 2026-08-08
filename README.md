# rocksmc

A RocksDB storage backend for the Minecraft Java Edition 1.16.5 dedicated
server, implemented as a Fabric mod.

**Status: design verification complete (Phases 0 and 0c). No mod code yet, and
the case for writing any has weakened — see "What measurement changed".**

## Why

Vanilla stores chunks in the Anvil region format: 4 KiB-sector allocation inside
`r.<x>.<z>.mca` files, with an 8 KiB header per file mapping 1024 chunk slots to
sector offsets. That design is compact and has an excellent read path — one
in-memory header lookup plus one positioned seek — but it has four structural
weaknesses:

1. **Every chunk write rewrites the entire 8 KiB header**, so a 1 KiB chunk pays
   8 KiB of header cost, and a torn header write can damage the pointers of up to
   1023 unrelated sibling chunks.
2. **Sector allocation is first-fit with no compaction, ever.** Region files grow
   monotonically as chunks relocate and leave holes.
3. **`sync-chunk-writes` defaults to true**, giving an fsync-class operation per
   chunk write, with no group commit.
4. **Chunk and POI data cannot be committed together.** They live in separate IO
   workers over separate directories, so a crash between the two writes leaves
   them inconsistent with no mechanism to detect it.

**Item 4 is the primary justification for this project.** It is the one weakness
with no workaround at the filesystem level: a single atomic write batch across
column families fixes it, and nothing else does.

## Honest framing

The read path will get **worse**, not better. Anvil resolves a chunk in one
in-memory index hit and one seek; that is O(1) and no LSM can beat it.

**If your world is on btrfs or ZFS, you already have most of the snapshot
benefit.** Copy-on-write filesystems give instant, pause-free snapshots with no
application involvement. Earlier drafts of this project claimed "Anvil has no
equivalent" — that was an overstatement. The genuine remaining gain is
*recoverability*: a filesystem snapshot of an Anvil world is crash-consistent but
may capture a torn header, and because Anvil has no write-ahead log that damage is
unrecoverable and silent. A snapshot of a RocksDB world can be replayed back to a
valid state.

The three real performance wins — ZSTD instead of DEFLATE, group commit instead of
per-write fsync, and incremental autosave — are all achievable *without* replacing
the storage engine, at a fraction of the effort and with `.mca` compatibility
preserved. If you want a faster server, do those three things instead of this.

## What measurement changed

Two spikes were run before writing any mod code. Both overturned assumptions the
design had been resting on.

**Phase 0 — dictionaries don't work where we needed them.** The plan assumed
BlobDB plus ZSTD trained dictionaries would capture cross-chunk redundancy
(palette strings re-encoded per chunk, near-identical biome arrays, run-heavy
light data). RocksDB blob files turned out to **ignore dictionaries entirely** —
output was byte-identical with them on and off, while a positive control on SST
files gained 4–8%. Keeping values in the LSM to regain dictionary scope costs
**140× more compaction traffic to save 2.3% of disk space**.

**Phase 0c — WiredTiger is technically better, and that was not the expected
result.** A pre-registered decision rule was fixed before measuring: WiredTiger
wins only with a better ratio *and* bytes written within ~2×. It passed both —
**5.65× vs 4.76× compression (15.7% better), bytes written 1.06×**. The predicted
B-tree in-place-update penalty never materialised, because with BlobDB compaction
is only 0.17% of RocksDB's total write volume; nearly all of it is flush, which
both engines must do.

RocksDB remains the choice on **licensing and packaging, not merit**: Apache-2.0
versus WiredTiger's GPL-only (which a Fabric mod would inherit, foreclosing
permissive release), prebuilt Maven natives versus three source patches on GCC 16,
and a WiredTiger failure mode where the standard PyPI distribution ships no
compressor extensions at all and silently produces a *0.86×* — i.e. expanded —
database.

Full data, method, and the three harness bugs found and corrected along the way:
- [`spike/phase0-blob-dict/FINDINGS.md`](spike/phase0-blob-dict/FINDINGS.md)
- [`spike/phase0c-wiredtiger/FINDINGS.md`](spike/phase0c-wiredtiger/FINDINGS.md)
- [`docs/storage-io-analysis.md`](docs/storage-io-analysis.md)

## Design

The seam is unusually clean. Vanilla's entire storage layer is ~984 LOC, and the
part being replaced is 444 LOC behind a four-method interface:

```
getTagAt(ChunkPos) -> NbtCompound
write(ChunkPos, NbtCompound)
method_26982()   // flush/sync all
close()
```

Injection point: `@Redirect` the `new RegionBasedStorage(...)` construction inside
`StorageIoWorker`. The async write-behind buffer above it is kept as-is — its
coalescing map and read-your-writes behaviour remain useful in front of RocksDB.

**Core invariant: values stay verbatim NBT blobs.** `DataVersion` lives inside the
blob and DataFixer runs *above* the seam, so roughly 22.5k LOC of save-format
migration logic is untouched. Breaking this invariant means owning schema
migration forever.

### Column families

| CF | Key | Replaces | Storage |
|---|---|---|---|
| `chunk` | dim + Morton(x,z) | `r.*.mca` | blob files |
| `poi` | dim + Morton(x,z) | `poi/*.mca` | blob / LSM |
| `playerdata` | player UUID | `playerdata/<uuid>.dat` | blob files |
| `state` | dim + id | `data/<id>.dat` | LSM + dictionary |
| `meta` | fixed keys | `level.dat` (mirror only) | LSM |

`level.dat` stays a real file: vanilla reads it *before* a session exists, but the
database can only open after the session lock is acquired. `session.lock` is
likewise retained — vanilla and every third-party tool check for it.

`state` is a quiet win: vanilla allocates one small `.dat` file per in-game map,
so long-lived worlds accumulate thousands of tiny files.

### Durability change

Replacing `O_DSYNC`-per-write with WAL + group commit **changes the durability
contract**: a crash can lose the last N milliseconds instead of nothing. The
compensating gain is a checksummed WAL replacing crash-safety-by-write-ordering,
plus elimination of the torn-header blast radius. Sync interval is configurable
and a strict parity mode is retained.

## Roadmap

- [x] **Phase 0** — verify blob dictionary support; select design branch
- [x] **Phase 0c** — benchmark WiredTiger against RocksDB (no JNI)
- [ ] **Phase 1** — `chunk` CF behind the Mixin seam; round-trip fidelity harness
- [ ] **Phase 2** — `poi` into the same DB; atomic chunk+POI `WriteBatch`
- [ ] **Phase 3** — `playerdata` + `state`
- [ ] **Phase 4** — checkpoint-based recoverable snapshots
- [ ] **Phase 5** — bidirectional `.mca` ⇄ RocksDB converter

**Currently paused at the Phase 0/1 boundary, pending a deliberate decision.**

Measurement removed or weakened three of the four original justifications (see
"What measurement changed" above), leaving atomic cross-subsystem commits as the
one pillar with no filesystem-level substitute. On SSD with copy-on-write
snapshots available, that is a thin return against a permanent `.mca` converter
obligation, a ~50 MB native dependency, a read path that regresses from optimal,
and — from Phase 3 onward — a blast radius that includes player inventories.

Proceeding is defensible as a hands-on exercise in embedded storage engines. That
is a legitimate reason; it is simply a different one from the operational case the
project started with, and worth naming rather than dressing up.

## Risks

- **Ecosystem lock-out.** `.mca` is the interchange format for Amulet, Chunker,
  BlueMap/Dynmap, pregenerators, and every world editor. The Phase 5 converter is
  mandatory, and will likely be more code than the storage layer it replaces.
- **Widened blast radius.** Once `playerdata` is in scope, a storage bug risks
  player inventories, not just terrain. Use throwaway worlds.
- **Native dependency.** `rocksdbjni` is ~50 MB of platform binaries; the mod is
  no longer pure-Java portable.
- **Unmeasured baseline.** No real world was generated. Compression and
  amplification figures come from synthetic corpora.

## Licence

Mod code here is original work. It contains no Minecraft source. Development
referenced a locally decompiled 1.16.5 server tree, which is Mojang's
copyrighted property and is **not** included in or redistributed by this
repository.
