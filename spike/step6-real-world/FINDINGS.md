# Step 6 findings: validation against a real 1.4 GB server world

**Date:** 2026-08-09
**World:** `kbackup-2025-09-29_02-06-31_before-recovery-rollback.zip` — a real
technical server (Carpet mod present), DataVersion **2586** (genuine 1.16.5, so no
DataFixer migration confounds the results)
**Scale:** 1,622 region files, **293,207 chunks**, 1.62 GiB on disk, 8.19 GiB
uncompressed NBT

**Reproduce:**

```bash
./gradlew fidelity -Pworld=/path/to/extracted/world
```

---

## Result: PASS

**293,207 / 293,207 chunks round-tripped with equivalent NBT. Zero mismatches,
zero read failures.**

This covers all three dimensions plus POI storage, at a scale ~550× the generated
test world, on data produced by years of real play rather than fresh worldgen.

## Headline numbers

| Metric | Value |
|---|---|
| Uncompressed NBT | 8,188,756,910 B (mean 27,928 B/chunk) |
| Anvil payload (compressed chunks only) | 1,045,882,671 B (7.83×) |
| **Anvil actual on disk** | **1,736,006,292 B** (4.72×) — **+66.0% sector padding** |
| **RocksDB actual on disk** | **1,147,195,844 B** (7.14×) |
| RocksDB vs Anvil payload | +9.7% |
| **RocksDB vs Anvil on disk** | **−33.9%** |

## Three measurement bugs found, and the last one inverted the conclusion

### 1. `limit` broke the outer loop, not the inner

The smoke run reported 16.4 chunks/s for the overworld. The limit check `break`ed
only the inner loop, so after reaching the cap the harness still called
`readRegion()` on **every remaining region file** — fully parsing and inflating
every chunk in each — and discarded the results. Fixed with a labelled break;
throughput went from 16 to ~1,250 chunks/s.

### 2. Sizing before compaction

`sync()` alone leaves un-merged L0 files and unreferenced blobs on disk, inflating
RocksDB's apparent footprint. The Phase 0/1a spikes all compacted first, so the
harness was inconsistent with its own baselines. Added `compact()` before sizing.

### 3. **Comparing RocksDB's files against Anvil's payload** — the big one

The harness compared RocksDB's *real on-disk bytes* against the *sum of Anvil's
compressed chunk payloads*. But Anvil allocates in whole 4 KiB sectors and carries
an 8 KiB header per file, and that padding is **real disk consumption**. Charging
RocksDB for its true size while crediting Anvil only for its payload is not a
comparison.

On this world the difference is enormous — **+66.0% padding overall**:

| Directory | Padding overhead | RocksDB vs payload | RocksDB vs on-disk |
|---|---|---|---|
| `region` | +52.3% | +10.1% | **−27.7%** |
| `DIM-1/region` | +69.1% | +7.7% | **−36.3%** |
| `DIM1/region` | +470.9% | +8.8% | **−80.9%** |
| `poi` | +3896.9% | −52.8% | **−98.8%** |
| `DIM1/poi` | +4491.2% | −22.4% | **−98.3%** |

So the conclusion flips: **not "+9.7% larger" but "−33.9% smaller."**

The pathological cases are sparse dimensions and POI data, where mean payloads are
127–724 bytes but every touched chunk still consumes a full 4 KiB sector. `poi`
stores 381 KB of data in 15.2 MB of files — **40× amplification**. `DIM1/region`
holds 24 MB of payload in 137 MB of files. This is the fixed-cost sector
allocation from `RegionFile.java:246-277` and `SectorMap.java`, measured at scale.

## What this corrects from earlier phases

Phase 1a concluded "vanilla is 3.5% smaller" from the generated world, and the
first real-world run said "+9.7% larger." **Both were payload comparisons and both
were wrong** as statements about disk usage.

| Claim | Status |
|---|---|
| "Vanilla compresses better" (payload vs payload) | ✅ Still true: 7.83× vs 7.14× |
| "Vanilla uses less disk" | ❌ **False.** Anvil uses 51% more space than RocksDB |
| "The swap costs disk space" | ❌ **Retracted.** It saves 33.9% |

The compression *ratio* finding stands — per-chunk DEFLATE genuinely beats ZSTD on
these values. Anvil simply gives that advantage back, and more, in sector padding.

## Real-world chunk characteristics vs the generated world

| | Generated (529 chunks) | Real (293,207 chunks) |
|---|---|---|
| Mean uncompressed | 52,215 B | 27,928 B |
| p50 uncompressed | 46.4 KiB | 27.7 KiB |
| Max uncompressed | 268 KiB | **2.1 MiB** |
| DEFLATE ratio | 14.56× | 7.83× |

Two things the synthetic and generated corpora both missed:

1. **Real chunks are smaller on average but far more variable.** The 2.1 MiB
   maximum is ~8× the generated world's largest — presumably heavy tile-entity or
   entity concentrations (storage halls, mob farms).
2. **Real chunks compress about half as well** (7.83× vs 14.56×). Fresh worldgen
   produces highly uniform terrain; played-in worlds contain far more entropy.

Every compression figure from Phases 0, 0c and 1a is therefore optimistic about
redundancy. The synthetic corpus was wrong in *both* directions: 6× too small on
value size, and too generous on compressibility.

## Throughput

~1,250 chunks/s for the overworld (171 s for 214,478 chunks), including Anvil
parse, inflate, NBT decode, RocksDB write, read-back, and deep NBT compare. Not a
server-performance figure — the real chunk load path is asynchronous and
coalesced — but it shows the backend handles a full real world in minutes.

## Justification ledger, final

| Pillar | Status |
|---|---|
| Cross-chunk dictionary compression | ❌ Dead (Phase 0) |
| Better compression *ratio* than vanilla | ❌ Dead (7.14× vs 7.83×) |
| **Less disk than vanilla** | ✅ **New: −33.9%**, from eliminating sector padding |
| Bytes written / flash endurance | ✅ 0.32× vanilla (Phase 1a, derived) |
| Atomic cross-subsystem commits | ✅ Intact (not yet implemented) |
| Live snapshots | ⚠️ Weakened — CoW filesystems cover most; RocksDB adds recoverability |
| Read latency | ❌ Worse — Anvil's O(1) header+seek is optimal |

## Caveats

- One world, one snapshot in time. A different server would have different chunk
  distributions, though the sector-padding effect is structural and should hold
  anywhere.
- The 0.32× bytes-written figure is still **derived** (payload + 8 KiB header per
  write), not instrumented from a running server under load.
- RocksDB sizes are post-compaction. A live server compacts in the background and
  would sit somewhat above this steady-state figure.
- Fidelity is *semantic* NBT equality, not byte equality of re-serialised output.
  That is the correct check — map ordering is not significant in NBT — but it does
  not prove byte-identical re-encoding.
- POI round-trips through `RocksChunkStore` here. In the real server POI flows
  through `SerializingRegionBasedStorage`, which splits chunks into 16 sections
  above this seam; that path is exercised by the dev server run but not
  independently verified at this scale.
