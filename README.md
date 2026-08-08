# rocksmc

A RocksDB storage backend for the Minecraft Java Edition 1.16.5 dedicated
server, implemented as a Fabric mod.

**Status: Phase 0 complete (design verification). No mod code yet.**

## Why

Vanilla stores chunks in the Anvil region format: 4 KiB-sector allocation inside
`r.<x>.<z>.mca` files, with an 8 KiB header per file mapping 1024 chunk slots to
sector offsets. That design is compact and has an excellent read path — one
in-memory header lookup plus one positioned seek — but it has three structural
weaknesses:

1. **Every chunk write rewrites the entire 8 KiB header**, so a 1 KiB chunk pays
   8 KiB of header cost, and a torn header write can damage the pointers of up to
   1023 unrelated sibling chunks.
2. **Sector allocation is first-fit with no compaction, ever.** Region files grow
   monotonically as chunks relocate and leave holes.
3. **`sync-chunk-writes` defaults to true**, giving an fsync-class operation per
   chunk write via `O_DSYNC`, with no group commit.

Most importantly, Anvil has **no way to take a consistent backup of a running
world**. Safe backup means flushing and pausing.

This project's goal is the *operational* one: consistent live snapshots, atomic
multi-key commits, a checksummed write-ahead log, and bounded space
amplification. It is explicitly **not** a throughput play — see "Honest framing".

## Honest framing

The read path will get **worse**, not better. Anvil resolves a chunk in one
in-memory index hit and one seek; that is O(1) and no LSM can beat it.

The three genuine performance wins available here — ZSTD instead of DEFLATE,
group-commit instead of per-write fsync, and incremental autosave — are all
achievable *without* replacing the storage engine, at a fraction of the effort
and with `.mca` format compatibility preserved.

If you want faster, fix those three things in vanilla. If you want consistent
online backups and atomic cross-subsystem commits, that is what this is for.

## Phase 0 result (important)

The design originally committed to **BlobDB + ZSTD trained dictionaries**, on the
theory that chunk payloads are highly redundant *across* chunks (palette strings
re-encoded per chunk, near-identical biome arrays, run-heavy light data) and that
a shared dictionary would capture what vanilla's per-chunk DEFLATE cannot.

Measurement disproved the second half of that. **RocksDB blob files ignore
trained dictionaries** — blob output was byte-identical with dictionaries on and
off, while a positive control on SST files showed a clear 4–8% gain. Keeping
values in the LSM to regain dictionary scope costs **140× more compaction traffic
to save 2.3% of disk space**.

Conclusion: keep BlobDB, drop dictionaries for chunk data, and retain them only
for the small-value column families that stay in the LSM. Cross-chunk compression
scope is therefore **not** achieved for chunks, and the compression rationale for
this project is correspondingly weaker than planned.

Full data, method, and two harness bugs found and corrected along the way:
[`spike/phase0-blob-dict/FINDINGS.md`](spike/phase0-blob-dict/FINDINGS.md).

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
- [ ] **Phase 1** — `chunk` CF behind the Mixin seam; round-trip fidelity harness
- [ ] **Phase 2** — `poi` into the same DB; atomic chunk+POI `WriteBatch`
- [ ] **Phase 3** — `playerdata` + `state`
- [ ] **Phase 4** — checkpoint-based live snapshots *(the actual payoff)*
- [ ] **Phase 5** — bidirectional `.mca` ⇄ RocksDB converter

Reassessment point is the Phase 2 boundary. Phases 0–2 deliver most of the
insight; 3–5 are where cost and risk concentrate.

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
