# Phase 1c results: steady-state write amplification

**Date:** 2026-08-09
**Machine:** Linux 6.12.95+deb13-amd64, 12 CPUs, JDK 21.0.11, copy-on-write filesystem
**Corpus:** 23,670 real chunks, mean 18,875 B uncompressed
**Runs:** `--quick` (192 MiB fill) and full (1 GiB fill, 2 GiB measured)
**Raw data:** `results-20260809-175735.*` (quick), `results-20260809-180022.*` (full) — untracked

---

## Validity

All eight configurations passed the validity gate, and the no-blob arm reached
**three populated levels** (`L0=3 L1=24 L2=244`), so leveled compaction was
genuinely exercised. The blob arm stayed at two levels (`L0=3 L1=268`) — expected
and correct, since key-value separation keeps only keys and pointers in the LSM.

Amplification rose consistently from the quick run to the full run (e.g. 1.216 →
1.481 for blob/sync=false) in every configuration, which is the signature of a
tree that has grown deeper. The earlier measurements never showed this because
they never grew a tree at all.

## Headline: the 316× figure collapses to ~1.4×

Phase 1a reported that blob files cut compaction traffic **316×**. That was
measured on an 11.2 MB database with no LSM levels. At real depth:

| Config | blob compaction | no-blob compaction | Ratio |
|---|---|---|---|
| sync=false, zipfian | 830,193,093 | 1,121,112,486 | **1.35×** |
| sync=false, uniform | 815,177,404 | 1,194,172,052 | 1.46× |
| sync=true, zipfian | 843,876,209 | 1,252,453,956 | 1.48× |
| sync=true, uniform | 878,643,573 | 1,330,747,654 | 1.51× |

**~1.3–1.5×, not 316×.** The original figure was off by more than two orders of
magnitude, in the direction that flattered the design decision already taken.

## The WAL is ~1× logical on its own

`WAL/logical = 1.0015`. The write-ahead log alone writes as much as the
application does.

**Every prior harness in this project set `disableWAL(true)`**, so all of them
omitted roughly half of total write volume. That is why omitting it was
disqualifying rather than merely imprecise.

## Total bytes written

| Config | Engine amp | vs blob=on |
|---|---|---|
| blob=on, sync=false, zipfian | **1.481×** | — |
| blob=on, sync=false, uniform | 1.478× | — |
| blob=on, sync=true, zipfian | 1.488× | — |
| blob=off, sync=false, zipfian | 1.607× | +8.5% |
| blob=off, sync=false, uniform | 1.644× | +11.3% |
| blob=off, sync=true, zipfian | 1.669× | +12.2% |
| blob=off, sync=true, uniform | 1.708× | +13.3% |

Disabling blob files costs **8.5–13.3% more bytes written**, not the ~300× the
earlier figure implied.

## The compression/endurance tradeoff, finally quantified

Phase 1b found that disabling blob files improves compression, because blob files
ignore the compression level and dictionary settings entirely. This run measures
what that costs:

| | blob=on | blob=off | Difference |
|---|---|---|---|
| Bytes written | 1.481× | 1.607× | **+8.5% writes** |
| On-disk size | 115,303,090 | 105,441,791 | **−8.6% size** |

**A near-symmetric trade: roughly 8.6% less disk for roughly 8.5% more writes.**

Both effects are modest, and neither dominates. Over five years at 11.25 GiB/day
logical the write difference is **~3.75 TB** — against consumer TBW ratings of
150–600 TB, a few percent of drive life.

That makes this a genuine judgement call rather than a forced choice:

- **SSD-constrained or long-lived deployment** → keep blob files on (current
  default)
- **Storage-constrained** → disable blobs, tune zstd-9 with dictionaries per
  Phase 1b, accept ~8.5% more wear

The earlier framing — that the 316× compaction penalty made disabling blobs
untenable — **does not hold**. Phase 1b's tuning recommendation is viable.

## The largest effect is `sync-writes`, and only the kernel sees it

The most consequential result was not in the plan.

| Config | Engine amp | **Kernel amp** | Ratio |
|---|---|---|---|
| blob=on, sync=false | 1.481× | **1.549×** | +5% fs overhead |
| blob=on, sync=true | 1.488× | **5.649×** | **+280%** |
| blob=off, sync=false | 1.607× | 1.675× | +4% |
| blob=off, sync=true | 1.669× | 5.926× | +255% |

With identical operation counts (113,677 in both cases), switching
`sync-writes=false → true`:

- moves **engine** counters by **+0.4%**
- moves **kernel** writes by **3.65×**

RocksDB's own counters are nearly blind to it. An fsync per write forces partial
4 KiB blocks and copy-on-write metadata to disk, and none of that appears in
`WAL_FILE_BYTES` or `COMPACT_WRITE_BYTES`. **Any measurement trusting tickers
alone would have missed a 3.65× effect entirely** — which vindicates the
`/proc/self/io` cross-check as the single most valuable part of this harness.

Projected over five years using kernel figures:

| Config | 5-year TBW |
|---|---|
| blob=on, sync=false | **31.1 TB** |
| blob=off, sync=false | 33.6 TB |
| blob=on, sync=true | **113.3 TB** |
| blob=off, sync=true | 118.8 TB |

**`sync-writes` costs ~82 TB over five years — roughly 22× the entire blob-vs-LSM
difference (3.75 TB).** The setting the project spent three phases debating is
dwarfed by one durability flag.

Note this was measured on a copy-on-write filesystem, where fsync is unusually
expensive. Expect a smaller ratio on ext4/xfs — but the direction and the
invisibility to engine counters hold regardless.

Vanilla is not exempt: `sync-chunk-writes` defaults to **true**, so vanilla pays
an `O_DSYNC` per chunk write with no group commit. This is a shared cost, not a
RocksDB penalty.

## Corrections this run forces

| Earlier claim | Status |
|---|---|
| "Blob files cut compaction 316×" | ❌ **Off by >100×.** Real depth: 1.35–1.51× |
| "Compaction traffic is negligible" | ⚠️ Correctly challenged, but the *reason* was wrong — compaction is ~0.4–0.6× logical, far from negligible; it just doesn't differ much between configs |
| "RocksDB writes 0.32× vanilla" | ❌ **Not credible.** That excluded the WAL, which alone is 1.0× logical. Total engine amp is ~1.48×, so the vanilla comparison needs redoing with instrumentation rather than derivation |
| "Disabling blobs is untenable for endurance" | ❌ **Retracted.** It costs 8.5%, not 300% |

## What remains unmeasured

- **Vanilla Anvil was never instrumented.** Every comparison against it is
  derived (payload + 8 KiB header per write). Given how badly derivation
  misled on the RocksDB side, the vanilla side deserves the same
  `/proc/self/io` treatment before any "X× better than vanilla" claim is made.
- **One filesystem only**, and a CoW one, which inflates the fsync result.
- **Shrunken level geometry** (4 MB memtable, 8 MB L1) reaches depth quickly but
  absolute bytes will differ from default-configured production.
- Multi-year projections extrapolate from ~2 GiB of writes and assume
  11.25 GiB/day.
- Single run per configuration; no repetitions, so small differences
  (e.g. zipfian vs uniform at 1.481 vs 1.478) are within noise.

## Recommended actions

1. **Keep `sync-writes=false` as the default** — now the highest-impact setting
   by a wide margin, and the config already ships this way.
2. **Phase 1b's tuning is unblocked.** Disabling blob files for ~26% better
   compression costs ~8.5% more writes, which is defensible.
3. **Instrument vanilla before comparing to it.** The 0.32× claim should be
   treated as withdrawn until measured the same way.
