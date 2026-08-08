# Phase 1b findings: codec performance on real Minecraft world data

**Date:** 2026-08-09
**Corpus:** real technical-server world, DataVersion 2586, stratified per dimension
**Environment:** JDK 21.0.12, zstd-jni 1.5.7-13, lz4-java 1.11.2, snappy-java 1.1.10.7, RocksDB 10.10.1

**Reproduce:**

```bash
python3 extract_strata.py /path/to/world /tmp/rocksmc-corpus --per-stratum 15000
javac -cp 'zstd-jni-1.5.7-13.jar:lz4-java.jar:snappy-java-1.1.10.7.jar' -d out CodecSweep.java
java -Xmx8G -cp 'out:zstd-jni-1.5.7-13.jar:lz4-java.jar:snappy-java-1.1.10.7.jar' \
     CodecSweep /tmp/rocksmc-corpus
javac -cp rocksdbjni-10.10.1.jar -d out EngineSweep.java
java -Xmx8G -cp out:rocksdbjni-10.10.1.jar EngineSweep /tmp/rocksmc-corpus
python3 crosscheck_ratios.py /tmp/rocksmc-corpus   # validates ratios independently
```

---

## Headline

**Two earlier conclusions in this project were wrong, and both were wrong for the
same reason: only default settings were tested.**

1. "Swapping DEFLATE for ZSTD is not a win" (Phase 1a) — **retracted.** True at
   zstd level 3 (RocksDB's default), false at level 9, which beats vanilla on
   ratio *and* decodes ~3× faster.
2. "Trained dictionaries do nothing" (Phase 0) — **retracted for LSM storage.**
   They add up to +59% ratio on real chunk data. They remain useless in blob
   files, and the reason is bigger than dictionaries (see Experiment B).

## There is no single "Minecraft compression ratio"

Ratios span **4.5× to 24.7×** across dimensions with vanilla's own codec. Any
benchmark reporting one number for "Minecraft data" is describing no real
workload.

| Stratum | Chunks | Mean chunk | deflate-6 |
|---|---|---|---|
| `large` (upper tail) | 49 | 586.8 KiB | **24.66×** |
| `end` | 8,446 | 14.2 KiB | 18.74× |
| `poi_nether` | 703 | 10.4 KiB | 18.34× |
| `nether` | 6,906 | 25.9 KiB | 8.69× |
| `overworld` | 9,118 | 33.3 KiB | 8.16× |
| `poi_overworld` | 2,210 | 586 B | **4.56×** |
| `poi_end` | 60 | 385 B | 2.87× |

The End compresses more than twice as well as the Overworld — vast uniform void
versus player-built terrain full of entropy. POI data barely compresses at all:
values are a few hundred bytes, too small for a codec to find redundancy in.

**Sampling matters.** An initial version took the first N chunks in filename
order, which samples one spatial corner of the map. Fixing it to sample evenly
across region files moved Overworld 7.73→8.16× and End 16.48→18.74×, and captured
the 2.1 MiB outlier chunk the biased sample missed.

## Experiment A — raw codec, no engine

Codecs applied directly to real chunk NBT. Isolates ratio and speed from engine
framing. All configurations verified byte-exact round-trip.

### Overworld (9,118 chunks, 296.1 MiB, mean 33.3 KiB)

| Codec | Ratio | Enc MB/s | Dec MB/s | vs deflate-6 size | vs deflate-6 decode |
|---|---|---|---|---|---|
| deflate-1 | 6.95× | 247 | 620 | +17.3% | 0.97× |
| **deflate-6** (vanilla) | **8.16×** | **84** | **642** | — | — |
| deflate-9 | 8.21× | 11 | 647 | −0.6% | 1.01× |
| zstd-1 | 7.31× | 947 | 1917 | +11.6% | 2.99× |
| zstd-3 (zstd default) | 7.53× | 738 | 1874 | +8.3% | 2.92× |
| **zstd-9** | **8.30×** | 160 | **2022** | **−1.7%** | **3.15×** |
| zstd-19 | 9.03× | 10 | 1613 | **−9.7%** | 2.51× |
| lz4 | 5.01× | 1488 | 1460 | +62.8% | 2.27× |
| lz4hc | 6.20× | 67 | 1348 | +31.6% | 2.10× |
| snappy | 4.78× | 1425 | 2089 | +70.7% | 3.26× |

### End (8,446 chunks, 117.4 MiB, mean 14.2 KiB)

| Codec | Ratio | Enc MB/s | Dec MB/s | vs deflate-6 size |
|---|---|---|---|---|
| **deflate-6** | **18.74×** | 210 | 986 | — |
| zstd-9 | 19.87× | 300 | 2911 | −5.7% |
| zstd-19 | 20.61× | 15 | 2808 | −9.0% |
| lz4 | 12.94× | 4379 | 2171 | +44.8% |
| snappy | 9.30× | 4209 | 3960 | +101.6% |

### Large chunks (49 chunks, 28.1 MiB, mean 586.8 KiB)

Codec differences widen sharply with input size — more material for
window-based matching:

| Codec | Ratio | Dec MB/s | vs deflate-6 size |
|---|---|---|---|
| **deflate-6** | **24.66×** | 1613 | — |
| zstd-9 | 32.59× | 7892 | **−24.3%** |
| zstd-19 | 36.08× | 7114 | **−31.7%** |
| lz4 | 15.44× | 3691 | +59.7% |

### POI Overworld (2,210 chunks, 1.2 MiB, mean 586 B) — the inversion

At sub-KiB values every ranking changes, and **zstd loses to deflate on both
axes**:

| Codec | Ratio | Dec MB/s | vs deflate-6 size | vs deflate-6 decode |
|---|---|---|---|---|
| **deflate-6** | **4.56×** | **403** | — | — |
| zstd-9 | 4.43× | 293 | +3.0% | **0.74×** |
| zstd-19 | 4.70× | 253 | −3.0% | **0.63×** |
| lz4hc | 4.12× | 3080 | +10.5% | 7.70× |
| snappy | 3.50× | 3309 | +30.2% | 8.00× |

Per-call overhead dominates when values are this small, and zstd's is higher than
zlib's. This is the one stratum where vanilla's choice is close to optimal.

### What Experiment A establishes

- **zstd-9 dominates deflate-6** on chunk-sized data: smaller *and* ~3× faster to
  decode. There is no tradeoff to weigh on the main strata.
- **zstd-19 is not "diminishing returns"** — it wins 9–32% on size, contradicting
  prediction 3. But encode collapses to 10 MB/s (~8× slower than deflate-6, 74×
  slower than zstd-3), which matters at autosave.
- **LZ4 and Snappy lose badly on ratio** (+45% to +102%). They are only
  interesting where decode latency dominates, i.e. POI.
- **deflate-9 is nearly free ratio** over deflate-6 (−0.6% to −8.2%) at ~8× the
  encode cost. A vanilla-compatible tweak requiring no format change.
- **Decode is 2–3× faster with zstd across the board.** Since decode sits in the
  chunk-load path and encode happens off-thread at autosave, this is the
  better-placed win.

### Cross-check

Python (`crosscheck_ratios.py`, same C libraries) reproduced Java ratios within
**0.15%** for deflate, zstd and snappy. LZ4 differs 2–4% due to block framing
(`store_size`), not a harness fault. The harness is sound.

## Experiment B — engine-level configurations

### Blob files ignore *all* compression options, not just dictionaries

The most consequential finding. Overworld blob bytes across three explicitly
configured zstd levels:

| Configured level | Blob bytes |
|---|---|
| default (3) | 41,558,329 |
| 9 | **41,558,329** |
| 19 | **41,558,329** |

**Byte-identical.** Meanwhile the same options demonstrably work on SST data:

| Config | Total bytes | Ratio |
|---|---|---|
| blob=off zstd(def) dict=off | 41,519,403 | 7.48× |
| blob=off zstd-9 dict=off | 37,652,007 | 8.25× |
| **blob=off zstd-9 dict=ON** | **33,047,519** | **9.40×** |

Phase 0 concluded "blob files ignore trained dictionaries." The truth is broader:
blob files ignore `CompressionOptions` **entirely** — level *and* dictionary. Only
`blob_compression_type` is honoured.

### Consequently, dictionaries do work — where options are respected

| Stratum | blob=off zstd-9 dict=off | dict=ON | Gain |
|---|---|---|---|
| overworld | 8.25× | **9.40×** | **+14%** |
| end | 19.06× | **30.40×** | **+59%** |
| poi_overworld | 12.10× | 10.75× | **−11%** |

Prediction 4 was wrong. At real value sizes dictionaries are substantial in the
End (highly repetitive endstone/void patterns shared across chunks) and actively
harmful for POI, where the dictionary costs more than it saves.

### Best config per stratum vs vanilla payload

| Stratum | Best engine config | Ratio | vs vanilla |
|---|---|---|---|
| overworld | blob=off zstd-9 dict=ON | 9.40× | **−13.2%** |
| end | blob=off zstd-9 dict=ON | 30.40× | **−38.3%** |
| poi_overworld | blob=off zstd-9 dict=off | 12.10× | **−62.3%** |

### The mod's current configuration is suboptimal

`RocksChunkStore` ships `blob=1KiB` with zstd default and no level set. On real
Overworld data that yields **7.45×, worse than vanilla's 8.16×**. The best
measured config reaches 9.40× — **26% fewer stored bytes**.

This creates a genuine tension with Phase 1a, which found blob files cut
compaction traffic **316×**. But the absolute numbers deflate the concern:
extrapolating Phase 1a, per GB of logical writes blob mode costs ~5 KB of
compaction versus ~1.7 MB without. At a realistic 8–40 MB autosave that is
~0.2 KB versus ~68 KB. **Both are negligible**; the 316× multiplier is alarming
only in relative terms.

**Recommendation (not yet applied):** raise `min_blob_size` above chunk size (or
disable blob files), set zstd level 9 explicitly, and enable dictionaries for
terrain — but *not* POI. Changing stored format warrants its own fidelity run, so
this is recorded rather than committed.

## Predictions vs outcomes

Recorded before measuring, per the pre-registration discipline:

| # | Prediction | Outcome |
|---|---|---|
| 1 | deflate-6 competitive; nothing wins ratio by >10% | **Wrong.** zstd-19 wins 9–32%; with dictionaries, engine configs win 13–62% |
| 2 | LZ4's real-world gap smaller than synthetic suggested | **Wrong.** Gap is comparable or wider (+45% to +102%). The synthetic LZ4 result was, unusually, correct |
| 3 | zstd-19 gains little over zstd-3 for disproportionate encode cost | **Half right.** Encode cost confirmed (74× slower); gains are large, not little (+20% overworld) |
| 4 | Dictionaries ~nil at 28 KiB values | **Wrong.** +14% overworld, +59% end in LSM mode |
| 5 | Decode speed matters more than ratio | **Moot.** zstd-9 wins both simultaneously on the main strata |

Four of five wrong or partly wrong. The common cause: earlier phases tested
library defaults and generalised from them.

## Caveats

- One world, one point in time. The per-dimension spread is structural and should
  generalise; absolute ratios will not.
- Speeds are machine- and JIT-specific — comparative, not absolute. Single-thread,
  no concurrency.
- `large` stratum is only 49 chunks; treat its figures as indicative.
- Brotli was planned but omitted: RocksDB cannot use it, so it would have been
  informational only.
- Encode timings include JNI transition and array-copy costs for zstd/lz4/snappy
  but not for deflate (JDK-internal). This slightly favours deflate on speed —
  and zstd wins anyway.
- Experiment B measures stored size only. It does not re-measure compaction
  traffic at these settings; the compaction figures cited are extrapolated from
  Phase 1a.
