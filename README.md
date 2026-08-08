# rocksmc

A RocksDB storage backend for the Minecraft Java Edition 1.16.5 dedicated
server, implemented as a Fabric mod.

**Status: Phase 1 complete and validated. The RocksDB chunk backend works, and
was verified against a real 293,207-chunk server world with zero mismatches.**

## Why

Vanilla stores chunks in the Anvil region format: 4 KiB-sector allocation inside
`r.<x>.<z>.mca` files, with an 8 KiB header per file mapping 1024 chunk slots to
sector offsets. That design is compact and has an excellent read path — one
in-memory header lookup plus one positioned seek — but it has four structural
weaknesses:

1. **Sector rounding wastes a lot of disk.** Every touched chunk consumes a whole
   4 KiB sector regardless of payload. Measured on a real 293k-chunk server world:
   **+66% over its own payload**, and 40× amplification in POI storage, where
   381 KB of data occupies 15.2 MB of files.
2. **Every chunk write rewrites the entire 8 KiB header**, so a 1 KiB chunk pays
   8 KiB of header cost, and a torn header write can damage the pointers of up to
   1023 unrelated sibling chunks.
3. **Sector allocation is first-fit with no compaction, ever.** Region files grow
   monotonically as chunks relocate and leave holes.
4. **Chunk and POI data cannot be committed together.** They live in separate IO
   workers over separate directories, so a crash between the two writes leaves
   them inconsistent with no mechanism to detect it.

Items 1 and 2 are **measured** wins for this project: RocksDB used **33.9% less
disk** than Anvil on a real world, and writes an estimated 0.32× the bytes. Item 4
has no filesystem-level workaround at all, but is not yet implemented — chunk and
POI currently get separate databases.

## Honest framing

**RocksDB compresses Minecraft chunks better than vanilla — but only when tuned.**
At ZSTD's default level 3 it *loses* (7.14× vs vanilla's 7.83×), which is what
earlier drafts of this file reported. At level 9 with trained dictionaries and
values kept in the LSM, it beats vanilla payload by 13% (Overworld) to 62% (POI).
The mod's current shipped configuration is the untuned one, so it is presently on
the losing side of that comparison. See "What measurement changed".

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

All three vanilla-compatible performance fixes still stand on their own and need
no engine swap: group commit instead of per-write fsync, incremental autosave, and
— now measured properly — **ZSTD instead of DEFLATE**. At level 9, ZSTD is both
smaller and ~3× faster to decode than vanilla's deflate-6 on real chunk data, and
`ChunkStreamVersion` already versions compression per chunk, so vanilla could
adopt it *inside* `.mca` with full format compatibility. An earlier draft
retracted this claim; the retraction was wrong, because it only tested ZSTD's
default level.

## Verification

Validated against a real 1.4 GB technical-server world (DataVersion 2586,
1,622 region files, all three dimensions plus POI):

```
chunks=293207 verified=293207 mismatches=0 readFailures=0
RESULT: PASS -- all 293,207 chunks round-tripped with equivalent NBT
```

Run it yourself against any world — region files are opened read-only and all
writes go to a scratch database in a temp directory:

```bash
./gradlew fidelity -Pworld=/path/to/world [-Plimit=5000]
```

`tools/mca_stats.py` reports Anvil size, ratio and fragmentation statistics
directly from `.mca` files, with no JVM or Minecraft needed.

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

**Phase 0c — WiredTiger compressed better than RocksDB, and that was not the
expected result.** A pre-registered decision rule was fixed before measuring:
WiredTiger wins only with a better ratio *and* bytes written within ~2×. It passed
both — **5.65× vs 4.76× compression (15.7% better), bytes written 1.06×**. The
predicted B-tree in-place-update penalty never materialised, because with BlobDB
compaction is only 0.17% of RocksDB's total write volume; nearly all of it is
flush, which both engines must do.

Two later findings nonetheless settle the choice on RocksDB, and **not** on
licensing:

1. **The compression win inverts under the real pattern.** That 15.7% was measured
   on a freshly written table. After 12 overwrite rounds — and chunk saves *are*
   whole-value overwrites — WiredTiger's on-disk footprint was **2.1× larger**
   (23.6 MB vs 11.2 MB).
2. **WiredTiger has had no Java API since 2021.** WT-6675 removed it in 10.0.0.
   Verified against the 11.3.1 source built for this spike: `lang/` is
   python-only, there is no `ENABLE_JAVA` option, and the tree contains zero
   `.java` files. An earlier draft of these docs claimed you would "package the
   in-tree SWIG Java binding" — that was wrong; there is none to package. You
   would author an FFI layer from scratch and maintain it per-platform.

Secondary WiredTiger costs, recorded because they were real: three source patches
to compile on GCC 16, and a distribution that ships no compressor extensions at
all, silently producing an *expanded* 0.86× database.

**Phase 1a / Step 6 — the compression rationale died, and a better one replaced
it.** Both earlier phases sized their corpus from `RegionFile.ChunkBuffer`'s
8096-byte allocation, which turns out to track the *compressed* chunk. Real
uncompressed chunk NBT is far larger, and on a played-in world it compresses about
half as well as fresh worldgen suggests:

| | Synthetic | Generated world | **Real server world** |
|---|---|---|---|
| Mean uncompressed chunk | 8 KiB | 52 KiB | **28 KiB** (max 2.1 MiB) |
| Vanilla DEFLATE ratio | 4.76× | 14.56× | **7.83×** |

So per-chunk DEFLATE extracts far more redundancy than the synthetic corpus
implied. At ZSTD's *default* level the engine loses to it (7.14× vs 7.83×), which
is where the "compression rationale is dead" conclusion came from.

**That conclusion was wrong, and Phase 1b found out why: only library defaults had
ever been tested.** Sweeping levels and dictionaries on real per-dimension corpora:

| Codec (Overworld stratum) | Ratio | Decode MB/s | vs vanilla |
|---|---|---|---|
| deflate-6 (vanilla) | 8.16× | 642 | — |
| zstd-3 (library default) | 7.53× | 1874 | +8.3% size |
| **zstd-9** | **8.30×** | **2022** | **−1.7% size, 3.15× decode** |
| zstd-19 | 9.03× | 1613 | −9.7% size |
| lz4 | 5.01× | 1460 | +62.8% size |
| snappy | 4.78× | 2089 | +70.7% size |

**zstd-9 wins on both axes simultaneously** — smaller and ~3× faster to decode.
And there is no single "Minecraft ratio": across dimensions vanilla itself spans
**4.5×–24.7×**, since End void compresses far better than player-built Overworld,
and sub-KiB POI values barely compress at all.

Two further corrections from the same sweep:

- **Blob files ignore `CompressionOptions` entirely** — level *as well as*
  dictionary. Configuring zstd at 3, 9 and 19 gave byte-identical blob output
  (41,558,329 bytes each time) while the same settings changed SST output a lot.
  Phase 0 had reported only the dictionary half of this.
- **Dictionaries do work in LSM mode**: +14% (Overworld), +59% (End), but −11% for
  POI. So the setting belongs per-data-type, not globally.

The LZ4 finding, unusually, **held up** — it really is 45–102% larger on real data.

Best tuned engine config beats vanilla payload by **13% (Overworld)** to
**62% (POI)**. The mod currently ships the untuned configuration, so applying this
is outstanding work.

Separately, comparing compression ratios turned out not to be the main question.
Anvil allocates in whole 4 KiB sectors and rewrites an 8 KiB header per chunk save,
and on a real world that padding is **+66% over its own payload**:

| | Bytes | Ratio |
|---|---|---|
| Uncompressed NBT | 8,188,756,910 | — |
| Anvil payload | 1,045,882,671 | 7.83× |
| **Anvil actual on disk** | **1,736,006,292** | 4.72× |
| **RocksDB actual on disk** | **1,147,195,844** | 7.14× |

**RocksDB uses 33.9% less disk** — not because it compresses better, but because
it does not pay sector padding. The effect is extreme in sparse dimensions and POI
data: `poi` stores 381 KB of payload in 15.2 MB of files, a 40× amplification.

Full data, method, and the six harness bugs found and corrected along the way
(including one that inverted this very conclusion):
- [`spike/phase0-blob-dict/FINDINGS.md`](spike/phase0-blob-dict/FINDINGS.md)
- [`spike/phase0c-wiredtiger/FINDINGS.md`](spike/phase0c-wiredtiger/FINDINGS.md)
- [`spike/phase1a-real-corpus/FINDINGS.md`](spike/phase1a-real-corpus/FINDINGS.md)
- [`spike/step6-real-world/FINDINGS.md`](spike/step6-real-world/FINDINGS.md)
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
- [x] **Phase 1** — chunk storage behind the Mixin seam; round-trip fidelity harness
- [x] **Step 6** — validate against a real 293,207-chunk server world
- [ ] **Phase 2** — `poi` into the same DB; atomic chunk+POI `WriteBatch`
- [ ] **Phase 3** — `playerdata` + `state`
- [ ] **Phase 4** — checkpoint-based recoverable snapshots
- [ ] **Phase 5** — bidirectional `.mca` ⇄ RocksDB converter

Phase 1 works and is verified at real-world scale. The remaining phases are
unstarted; Phase 2 is where the atomic-commit payoff actually lands, since chunk
and POI currently get separate databases.

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
