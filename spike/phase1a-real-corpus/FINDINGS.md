# Phase 1a findings: real chunk data invalidates the synthetic baseline

**Date:** 2026-08-09
**Corpus:** 529 chunks from a freshly generated 1.16.5 world (seed 20260809)
**Reproduce:**

```bash
python3 ../../tools/mca_stats.py /path/to/world          # measure Anvil directly
python3 extract_corpus.py /path/to/world                 # dump real NBT
javac -cp rocksdbjni-10.10.1.jar -d out RealCorpusSpike.java
java -Xmx4G -cp out:rocksdbjni-10.10.1.jar RealCorpusSpike corpus.bin 1897252
```

---

## The synthetic corpus was wrong by ~6×

Phases 0 and 0c used 8 KiB values, on the reasoning that
`RegionFile.ChunkBuffer`'s 8096-byte initial allocation reflected Mojang's own
size expectation. Reading a real world shows that number tracks the **compressed**
size:

| Measurement | Real world |
|---|---|
| Compressed chunk (as Anvil stores it) | mean **3.5 KiB**, p50 3.2 KiB, p99 10.3 KiB, max 16.1 KiB |
| **Uncompressed chunk NBT** (what we would store) | mean **51.0 KiB**, p50 46.4 KiB, p99 141.7 KiB, max 268.0 KiB |
| **Vanilla per-chunk DEFLATE ratio** | **14.56×** |

Two consequences:

1. Real uncompressed values are **6× larger** than the synthetic corpus.
2. Vanilla's DEFLATE achieves **14.56×**, not the 4.76× the synthetic data
   implied. Chunk NBT is far more redundant than modelled — dominated by long
   runs in light nibble arrays, near-uniform biome arrays, and repeated palette
   strings.

Every compression figure in Phases 0 and 0c was therefore measured on data
roughly 3× less compressible than the real thing.

## Experiment 1 — compression, against the incumbent

Logical corpus: 26.3 MiB. Vanilla Anvil stores it in **1,897,252 bytes (14.56×)**.

| Config | SST | Blob | Ratio |
|---|---|---|---|
| A blob(min=1KiB) ZSTD | 7,277 | 1,975,062 | 13.93× |
| B blob(min=1KiB) LZ4 | 11,032 | 2,976,701 | 9.25× |
| C blob(min=64KiB) ZSTD | 1,748,911 | 223,550 | 14.00× |
| **D no blobs, ZSTD+dict** | 1,962,999 | 0 | **14.07×** |
| E no blobs, ZSTD | 1,971,503 | 0 | 14.01× |

**Best RocksDB config: 14.07×. Vanilla Anvil: 14.56×. Vanilla is 3.5% smaller.**

### This kills the compression rationale outright

Not "weakens" — **kills**. Earlier the story was "we lose cross-chunk dictionary
scope but still gain DEFLATE→ZSTD." That gain does not exist on real data:
per-chunk DEFLATE at level 6 already extracts essentially all the redundancy in a
51 KiB chunk, and ZSTD on the same value does marginally *worse*.

Note also that **LZ4 is dramatically worse (9.25×)**. Any plan to swap DEFLATE for
LZ4 "for speed" would cost ~57% more disk. The earlier recommendation to prefer
LZ4/ZSTD over DEFLATE was based on synthetic data and is retracted.

Dictionaries again produced almost nothing (D vs E: 14.07× vs 14.01×), consistent
with Phase 0 — and this time even in the LSM, because a 51 KiB value is large
enough that its *internal* redundancy dominates any cross-value sharing.

## Experiment 2 — bytes written, and a genuine win

6 overwrite rounds, 158.1 MiB logical.

| Config | Flush | Compaction | Total |
|---|---|---|---|
| blob(min=1KiB) ZSTD | 12,124,484 | **12,708** | 12,137,192 |
| no blobs, ZSTD | 12,059,311 | 4,016,870 | 16,076,181 |

BlobDB reduces compaction traffic **316×**. With 51 KiB values the case for
key-value separation is far stronger than the synthetic corpus suggested (where it
was 140×), because leveled compaction rewrites much larger values.

### Vanilla's write amplification is the real story

Vanilla writes, per chunk save: the compressed payload **plus a full 8 KiB header
rewrite** (`RegionFile.java:298-301`). For a mean 3.5 KiB compressed chunk, the
header is **70% of the bytes written**.

```
vanilla equivalent:  37,384,920 bytes
RocksDB (BlobDB):    12,137,192 bytes   = 0.32x
```

**RocksDB writes about one third of vanilla's bytes for identical logical
writes.** This is the first performance claim in the entire project that is both
measured and favourable, and it was not on the original list of justifications. It
follows directly from Anvil's fixed-cost header rewrite, which is pure overhead
whose relative weight *grows* as chunks get smaller.

## Revised design decisions

| Decision | Before | After |
|---|---|---|
| Blob files | on, `min_blob_size` 1 KiB | **unchanged** — 316× compaction reduction |
| Blob compression | ZSTD | **unchanged** — LZ4 costs 57% more disk |
| App-layer NBT compression | store uncompressed, let engine compress | **unchanged**, but expect ~3.5% *worse* on-disk than vanilla, not better |
| Trained dictionaries | for LSM CFs | **keep, but expect ~nil** on chunk data |
| "DEFLATE→ZSTD is a real win" | claimed | **retracted** — false on real data |

## Updated justification ledger

| Pillar | Status |
|---|---|
| Cross-chunk compression | ❌ Dead (Phase 0) |
| Better compression than vanilla | ❌ **Dead — vanilla is 3.5% smaller** |
| Sequential writes / HDD | ⚠️ N/A (SSD target) |
| Live snapshots | ⚠️ Weakened — CoW filesystems cover most |
| Atomic cross-subsystem commits | ✅ Intact |
| **Bytes written (flash endurance)** | ✅ **New, measured: 0.32× vanilla** |

So the project trades **+3.5% disk space** for **−68% bytes written**, plus atomic
commits and recoverable snapshots. That is a defensible engineering trade for SSD
endurance — and a completely different argument from the one the project started
with.

## Caveats

- **529 chunks from a fresh spawn area only.** No player building, no redstone, no
  large entity or tile-entity populations. Real long-lived worlds will have
  different (probably larger, less uniform) chunks. Validation against a real
  1.4 GB server world follows in Step 6.
- Vanilla's DEFLATE baseline was recomputed with Python `zlib.compress(level=6)`,
  which matches Java's `DeflaterOutputStream` default. Actual `.mca` on-disk was
  1.8 MiB, consistent.
- The vanilla write-amplification figure is *derived* (payload + 8 KiB per write),
  not instrumented from a running server.
- Single machine, single run, no timing measured.
