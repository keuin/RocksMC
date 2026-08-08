# Phase 0c findings: WiredTiger vs RocksDB+BlobDB

**Date:** 2026-08-09
**Environment:** WiredTiger 11.3.1 (built from source), Python 3.14.6, GCC 16.1.1, Linux x86-64
**Compare against:** [`../phase0-blob-dict/FINDINGS.md`](../phase0-blob-dict/FINDINGS.md) (RocksDB `rocksdbjni` 10.10.1)

**Result: WiredTiger passed the pre-registered decision rule. This contradicted
the prediction that motivated the spike.**

---

## Why this spike existed

Two arguments had been made against WiredTiger:

1. *"It probably lacks ZSTD trained-dictionary support, forfeiting cross-chunk
   compression."*
2. *"No maintained Maven artifact with prebuilt natives; you own multi-platform
   packaging forever."*

Phase 0 destroyed argument 1: RocksDB's **blob files ignore dictionaries too**.
The objection applied equally to both engines, so it discriminated nothing. That
left one technical hypothesis worth testing — that a B-tree updating pages *in
place* would write far more bytes than BlobDB's key-value separation, which had
measured only 233 KB of compaction traffic.

## Pre-registered decision rule

Fixed **before** any measurement, so the conclusion could not be fitted to the
data:

> WiredTiger wins only if it beats RocksDB on compression ratio **and** stays
> within ~2× on bytes written. Anything else, and the GPL licence plus
> build-from-source burden settle the question.

## Comparability

Corpus generation mirrors the Java spikes exactly: same seed (20260809), same
value size (8192), same counts, same `tailFraction=0.15`, same structure. Java's
`Random` is a specified 48-bit LCG, reimplemented bit-exactly in
`JavaRandom` so both harnesses see **byte-identical** corpora — without that, any
ratio difference could be nothing but a different random stream.

Both sides disable the write-ahead log to measure stored form rather than log
traffic.

## Getting WiredTiger to run at all

Three obstacles, each relevant to the packaging-burden argument:

1. **PyPI wheel fails to build on GCC 16.** The SWIG wrapper passes
   `const void **` where `void **` is expected; `-Wincompatible-pointer-types`
   became a hard error in GCC 14+. Needed
   `CFLAGS=-Wno-error=incompatible-pointer-types`.
2. **`-Werror` in `cmake/strict/strict_flags_helpers.cmake:34`** fails the core
   build on GCC 16 diagnostics that WiredTiger 11.3.1 predates. Had to be patched
   out.
3. **The PyPI wheel silently supports no compression whatsoever.** WiredTiger
   loads compressors as *runtime shared-object extensions*, not compiled into the
   core library. The wheel ships none of them:

   ```
   $ find /tmp/wtvenv -iname "*zstd*"     # nothing
   $ ldd .../_wiredtiger.*.so | grep zstd # no compression libs linked
   ```

   `block_compressor=zstd` failed with `unknown compressor 'zstd'` — despite
   libzstd, libsnappy, liblz4 and libz all being present on the system. Required
   a full source build with `-DENABLE_ZSTD=1 -DENABLE_SNAPPY=1 -DENABLE_LZ4=1
   -DENABLE_ZLIB=1`, then naming each `.so` in `wiredtiger_open`'s
   `extensions=[...]`.

**This is direct evidence for objection 2, the one that survived.** A first
attempt produced a *silently uncompressed* database measuring 0.86× — worse than
no storage engine at all. Anyone adopting WiredTiger would need to get all of
this right, on every platform, indefinitely.

## Experiment 1 — compression ratio

4000 × 8 KiB = 32,768,000 bytes logical.

| Config | Table bytes | Ratio |
|---|---|---|
| A zstd, no dict | 5,918,720 | 5.54× |
| B zstd + `dictionary=1000` | 5,918,720 | 5.54× |
| C zstd + `prefix_compression` | 5,918,720 | 5.54× |
| D zstd, `leaf_page_max=32KB` | 5,918,720 | 5.54× |
| **E zstd, `leaf_page_max=128KB`** | **5,804,032** | **5.65×** |
| F no compression (baseline) | 38,301,696 | 0.86× |

**RocksDB+BlobDB, same corpus: 6,886,832 bytes = 4.76×.**
**WiredTiger best: 5,804,032 bytes = 5.65× — 15.7% smaller.**

Observations:

- **WiredTiger's `dictionary=1000` changed nothing** (A and B byte-identical at
  5,918,720). This confirms the earlier suspicion: WiredTiger's `dictionary` is
  per-page value dedup, *not* ZSTD trained dictionaries. Neither engine offers
  true cross-chunk dictionary compression, so **that avenue is closed on both
  sides.**
- `prefix_compression` also changed nothing — unsurprising, since these keys share
  almost no prefix bytes.
- Only **larger leaf pages** helped, and only slightly (5.54× → 5.65×). This is
  the "wider compression scope" effect predicted earlier, but the magnitude is
  small: 2.0%.
- Baseline F at 0.86× shows the *expansion* from B-tree page overhead when
  compression is off, and is why the silently-uncompressed wheel was so bad.

**Why WiredTiger wins here:** its ~15.7% edge comes from compressing whole leaf
pages containing *many* values, versus BlobDB compressing each ~8 KiB blob
independently. That is exactly the cross-value scope argument — it just arrives
via page packing rather than dictionaries.

## Experiment 2 — bytes written under overwrite

8000 × 8 KiB × 12 rounds = 786,432,000 bytes logical.

| | RocksDB+BlobDB | WiredTiger |
|---|---|---|
| flush | 134,347,024 | — |
| compaction | 233,194 | — |
| **total written** | **134,580,218** | **143,032,320** |
| on-disk | 11,180,892 | 23,556,096 |

**Ratio: 1.06× — comfortably within the 2× threshold.**

### A methodology error caught before reporting

My first version of this experiment was about to compare WiredTiger's *total*
bytes written (143 MB) against RocksDB's **compaction-only** figure (233 KB) — a
531× penalty that would have been pure artefact. RocksDB's comparable total is
flush + compaction = **134,580,218**.

Two further flaws in the first run:

1. It rewrote a **byte-identical** corpus every round, letting unchanged-page
   optimisations understate bytes written. Fixed by randomising each value's
   incompressible tail per round (`mutate()`), matching the Java harness's
   per-put `value(rnd)`.
2. It checkpointed **once at the end**, so a 512 MB cache absorbed all 12 rounds
   and only the final state ever reached disk. Fixed with a checkpoint per round,
   matching RocksDB's per-round flush.

Together these changed the measured figure from 123,834,368 to 143,032,320.

### The hypothesis was wrong

The B-tree in-place-update penalty **did not materialise** at 1.06×. The reason is
visible in the RocksDB numbers themselves: with BlobDB, compaction is only
233 KB — 0.17% of its total. Nearly all of RocksDB's write volume is *flush*, and
flushing is work both engines must do. Key-value separation was defending against
a cost that barely exists at this write volume and value size.

Note also that WiredTiger's on-disk footprint here (23.5 MB) is **2.1× RocksDB's**
(11.2 MB), the opposite of Experiment 1. This is unreconciled — likely
checkpoint retention or free-space fragmentation after 12 rounds of overwrites,
not steady-state size. It is a real open question, not a rounding artefact.

## Verdict

**WiredTiger passes the pre-registered rule on technical merit: better ratio
(5.65× vs 4.76×), bytes written within 2× (1.06×).**

**But it is not reachable from Java, which settles the question regardless.**

### WiredTiger removed its Java API in 2021

From the WiredTiger 10.0.0 changelog (2021-04-12), alongside removals of the
Async API and LevelDB compatibility:

> WT-6675 Remove WiredTiger Java language API and documentation

Verified directly against the 11.3.1 source tree built for this spike:

| Check | Result |
|---|---|
| `lang/` contents | `python` only |
| `ENABLE_JAVA` cmake option | absent (only `ENABLE_PYTHON` exists) |
| `*.java` files anywhere in tree | **zero** |
| SWIG interface files | `lang/python/wiredtiger.i`, `bench/workgen/workgen.i` — no Java |

The Java tutorials still hosted on `source.wiredtiger.com` are under a
`/mongodb-3.4/` path — pre-removal artefacts. No maintained third-party Java
binding was found.

**This corrects an earlier claim in this project's docs** that one would "own
multi-platform packaging of the in-tree SWIG Java binding." There is no such
binding. The cost is not packaging an existing binding; it is *authoring* an FFI
layer: ~20-25 C entry points, `byte[]` ↔ `WT_ITEM` marshalling on every chunk read
and write, `WT_SESSION` thread-affinity management (sessions are not thread-safe),
error-to-exception mapping, and native handle lifecycle with no GC safety net —
then maintaining it across platforms indefinitely.

### The compression win inverts under the real access pattern

Experiment 1 measured a *freshly written* table. Experiment 2, after 12 overwrite
rounds, showed WiredTiger's on-disk footprint at **23,556,096 vs RocksDB's
11,180,892 — 2.1× larger.**

Chunk saves are whole-value overwrites by nature (`RegionFile.writeChunk` always
rewrites the entire chunk; `StorageIoWorker` coalesces repeated saves of the same
hot chunk). **The overwrite condition is therefore the representative one**, and in
it RocksDB wins on size by more than WiredTiger wins on a fresh write.

This was initially recorded as a minor unexplained caveat. That was an
under-weighting: the metric WiredTiger wins is measured in the less
representative condition. The cause remains uninvestigated — likely checkpoint
retention or free-space fragmentation rather than steady-state size — but
"unexplained" is not the same as "unimportant."

### Summary

| Factor | RocksDB | WiredTiger |
|---|---|---|
| Ratio, fresh write | 4.76× | **5.65×** (+15.7%) |
| Bytes written | 134.6 MB | 143.0 MB (1.06×) |
| **On-disk after overwrites** | **11.2 MB** | 23.6 MB (**2.1× worse**) |
| Trained dictionaries | ✗ | ✗ (both lack it) |
| **Java API** | Maven Central, prebuilt natives | **none since 2021 (WT-6675)** |
| Licence | Apache-2.0 OR GPL-2.0 | GPL2/GPL3/Commercial |
| Builds on GCC 16 out of the box | ✓ | ✗ (three patches needed) |
| Compression working by default | ✓ | ✗ (silent 0.86× trap) |

**Honest summary: WiredTiger compresses a freshly written table better than
RocksDB, and I predicted the opposite. It loses on the overwrite pattern this
workload actually produces, and it has no Java API at all — so the decision does
not turn on licensing.**

## Caveats

- **Synthetic corpus.** Real chunk-NBT redundancy is modelled, not measured.
- **Cross-language harnesses.** Java/RocksDB vs Python/WiredTiger. Corpora are
  byte-identical, but flush/checkpoint semantics are only *approximately* aligned;
  the 1.06× figure should be read as "same order of magnitude", not a precise
  ratio.
- **Statistics semantics differ.** RocksDB `FLUSH_WRITE_BYTES` +
  `COMPACT_WRITE_BYTES` vs WiredTiger `bytes written`. These are not guaranteed to
  count identical things.
- **On-disk discrepancy in Experiment 2 is unexplained** (23.6 MB vs 11.2 MB) and
  would need checkpoint-retention analysis to resolve.
- Single machine, single run per config, no repetitions, no timing measured.
