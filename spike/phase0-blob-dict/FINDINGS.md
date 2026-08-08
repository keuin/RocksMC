# Phase 0 findings: BlobDB and ZSTD trained dictionaries

**Date:** 2026-08-09
**Environment:** RocksDB (`rocksdbjni`) 10.10.1, JDK 21.0.12, Linux x86-64
**Reproduce:**

```bash
javac -cp rocksdbjni-10.10.1.jar -d out BlobDictSpike.java WriteAmpSpike.java
java -cp out:rocksdbjni-10.10.1.jar BlobDictSpike 0.15
java -Xmx4G -cp out:rocksdbjni-10.10.1.jar WriteAmpSpike
```

---

## Question

The plan committed to **BlobDB + ZSTD trained dictionaries**, on the reasoning
that Minecraft chunk payloads are highly redundant *across* chunks — block-state
palette strings are re-encoded verbatim in every chunk, biome arrays are often
identical between neighbours, light data is long runs of repeated nibbles — and
that vanilla's per-chunk DEFLATE cannot see any of it.

But the RocksDB Java API exposes `setBlobCompressionType` with **no
blob-specific compression-options setter**. Dictionary settings
(`setMaxDictBytes`, `setZStdMaxTrainBytes`) exist only on `CompressionOptions`,
attached via the column-family-level `setCompressionOptions` — which nominally
governs SST block compression.

RocksDB's own BlobDB documentation describes dictionary compression only as
something key-value separation "opens up the *possibility* of."

So: **do blob files honour trained dictionaries?** If not, each ~8 KiB chunk
compresses in isolation — exactly the per-chunk scope Anvil already has — and the
compression rationale for the whole design collapses.

## Experiment 1 — `BlobDictSpike`

2×2 design with a **positive control**, so the method validates itself:

| Config | blobs | dict | Purpose |
|---|---|---|---|
| A | ON | ON | test |
| B | ON | OFF | test baseline |
| C | OFF | ON | **control** |
| D | OFF | OFF | control baseline |

`C vs D` proves dictionaries work *at all* in this harness. Without that, a null
result in `A vs B` would be indistinguishable from a broken experiment.

Corpus: 4000 synthetic chunk-like values × 8 KiB, fixed seed, identical write
order across all four runs. Values contain a shared-vocabulary palette section,
run-heavy light analogue, near-uniform biome analogue, and a unique
incompressible tail. WAL disabled to measure stored form only. `compactRange()`
forced, since dictionaries are trained during flush/compaction.

### Results, `tailFraction=0.50`

| Config | SST bytes | Blob bytes | Total | Ratio |
|---|---|---|---|---|
| A blobs=ON dict=ON | 89,366 | **18,798,229** | 18,887,595 | 1.73× |
| B blobs=ON dict=OFF | 49,972 | **18,798,229** | 18,848,201 | 1.74× |
| C blobs=OFF dict=ON | 18,025,655 | 0 | 18,025,655 | 1.82× |
| D blobs=OFF dict=OFF | 18,785,988 | 0 | 18,785,988 | 1.74× |

Control C vs D: **−4.0%**. Test A vs B: **+0.2%**.

### Results, `tailFraction=0.15` (terrain-dominated, the common case)

| Config | SST bytes | Blob bytes | Total | Ratio |
|---|---|---|---|---|
| A blobs=ON dict=ON | 87,625 | **6,799,207** | 6,886,832 | 4.76× |
| B blobs=ON dict=OFF | 48,698 | **6,799,207** | 6,847,905 | 4.79× |
| C blobs=OFF dict=ON | 6,218,761 | 0 | 6,218,761 | **5.27×** |
| D blobs=OFF dict=OFF | 6,784,258 | 0 | 6,784,258 | 4.83× |

Control C vs D: **−8.3%**. Test A vs B: **+0.6%**.

### Conclusion

**Blob files ignore trained dictionaries. Confirmed.**

> **Correction (Phase 1b).** This conclusion is *true but understated*. Blob files
> ignore `CompressionOptions` **entirely** — the compression *level* as well as the
> dictionary. Phase 1b configured zstd at levels 3, 9 and 19 and got byte-identical
> blob output (41,558,329 bytes in all three cases), while the same options changed
> SST output substantially. Only `blob_compression_type` is honoured for blobs.
>
> The narrower framing here led to a further wrong inference — that dictionaries are
> useless for this workload generally. On real chunk data with values kept in the
> LSM, dictionaries add **+14% ratio (overworld)** and **+59% (end)**. See
> `../phase1b-codec-sweep/FINDINGS.md`.

The decisive evidence is not the percentages but the exactness: **blob bytes are
byte-identical between A and B in both runs** — `18798229`, then `6799207`. The
dictionary setting had *literally zero* effect on blob encoding. Meanwhile the
control strengthened from −4.0% to −8.3% as redundancy rose, exactly as a working
dictionary should behave. The harness is sound; the null result is real.

Secondary observation: the small SST growth in A vs B (49,972 → 89,366) is the
dictionary itself being stored in SST files, where it then has almost nothing to
compress because the values live in blob files.

**The best-compressing config measured was C — blobs OFF, dict ON, 5.27×** —
because dictionaries only work on SSTs. That is Branch B from the plan, and it is
empirically the ratio winner. Which forced a second question.

## Experiment 2 — `WriteAmpSpike`

Branch B (raise `min_blob_size` above chunk size, keep ~8 KiB values in the LSM)
regains dictionary scope, but gives back exactly the protection key-value
separation existed to provide. How much does that cost?

8000 values × 8 KiB × 12 overwrite rounds = 750 MiB logical, modelling repeated
autosaves of the same dirty chunks.

### Two corrections applied first

An initial run produced nonsense that is worth recording, because both errors are
easy to make and both inflate confidence in a wrong answer:

1. **Double-counting.** `FLUSH_WRITE_BYTES` already includes blob bytes written
   during flush (`flush=20993708` vs `blob=20818984` were near-identical), so
   adding `BLOB_DB_BLOB_FILE_BYTES_WRITTEN` counted the same bytes twice.
2. **Write amp below 1.0×, which is definitionally impossible.** The first run
   divided *compressed* bytes written by *uncompressed* logical bytes, producing
   a compression ratio wearing an amplification label.

The corrected harness reports **relative** bytes-written between configs and
declines to state an absolute write-amp figure, since that number is not
meaningful when the engine compresses.

### Results

| Config | Flush bytes | Compact bytes | Total written | On-disk | Ratio |
|---|---|---|---|---|---|
| blobs=ON dict=OFF | 134,347,024 | **233,194** | 134,580,218 | 11,180,892 | 5.86× |
| blobs=OFF dict=ON | 131,132,822 | **32,758,402** | 163,891,224 | 10,920,631 | 6.00× |

- Compaction bytes: **140.5× more** under Branch B
- Total bytes written: **1.22× more** under Branch B
- On-disk size: **2.3% smaller** under Branch B

### Conclusion

**Branch B is rejected.** It costs 140× the compaction traffic to save 2.3% of
disk space. Key-value separation is doing exactly its advertised job: 233 KB of
compaction versus 32.8 MB for identical logical writes.

This also settles the earlier RocksDB-vs-WiredTiger debate empirically. The
concern was that LSM compaction rewrites multi-KiB values repeatedly — true, and
visible here as Branch B's 32.8 MB. BlobDB reduces it to 233 KB, so the write-amp
objection to RocksDB does not survive contact with key-value separation.

---

## Decision

**Keep BlobDB. Drop the trained-dictionary requirement for the `chunk` CF.**

| CF | Value size | Storage | Compression |
|---|---|---|---|
| `chunk` | ~8 KiB | blob files | per-blob ZSTD |
| `poi` | small–medium | blob files (large) / LSM (small) | ZSTD + dict where in LSM |
| `playerdata` | ~1–20 KiB | blob files | per-blob ZSTD |
| `state` | small, many | **LSM** | **ZSTD + trained dictionary** |
| `meta` | tiny | LSM | ZSTD + dict |

Rationale: BlobDB's 140× compaction advantage dominates a 2.3% size difference.
Dictionaries still apply to the small-value CFs that stay in the LSM — notably
`state`, which accumulates thousands of tiny `map_<n>.dat`-derived entries and is
precisely the shape dictionaries help most.

### Consequences for the plan

1. **Cross-chunk compression scope is not achieved for chunk data.** The plan's
   compression rationale is materially weakened. Honest position: we get
   DEFLATE→ZSTD (a real CPU and modest ratio win, also obtainable *without*
   changing engines, since `ChunkStreamVersion` already versions compression per
   chunk) but **not** the cross-chunk dedup that motivated the choice.
2. **Palette interning is now the only route to cross-chunk scope.** Hoisting
   block-state strings into a shared registry CF would remove the redundancy
   structurally rather than relying on the codec. This breaks the
   "values stay verbatim NBT / DataFixer untouched" invariant and complicates the
   `.mca` converter, so it is **not** adopted now — recorded as the one remaining
   lever if compression ratio later proves to matter.
3. **The earlier "dictionary is a durability artifact" warning is moot for
   chunks.** Where dictionaries are used (LSM CFs), RocksDB stores them inside
   each SST file — self-contained, versioned with the file, covered by
   checkpoints. Nothing external to lose.
4. **The operational case is untouched.** Live consistent snapshots via
   checkpoints, atomic cross-CF `WriteBatch`, checksummed WAL, and bounded space
   amplification were always the real justification, and none of them depended on
   dictionary support.

## Caveats

- Corpus is **synthetic**. Real chunk NBT redundancy is modelled, not measured;
  no world was generated (empirical phase was explicitly descoped). The
  byte-identical blob result is robust to corpus choice, but the *magnitude* of
  the dictionary win on real data is unknown.
- Single RocksDB version (10.10.1). Blob dictionary support could land later; the
  `A vs B` check is cheap to re-run against a newer release.
- Measured on one machine, no repetitions; size figures are deterministic given
  the fixed seed, but timing was not measured at all.
