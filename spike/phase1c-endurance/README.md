# Phase 1c: steady-state write amplification (flash endurance)

Measures how many bytes actually reach the SSD per byte of logical chunk write,
under a realistic LSM tree, with the write-ahead log counted.

**Run it:**

```bash
./run_endurance.sh --world /path/to/minecraft/world --work /path/on/the/ssd --quick   # ~15 min, validate setup
./run_endurance.sh --world /path/to/minecraft/world --work /path/on/the/ssd           # ~4 h, real numbers
```

Send back the two files it names at the end (`results-*.json`, `results-*.log`).

---

## Why this exists

A reader pushed back on the claim that compaction traffic is "negligible":
negligible per autosave still integrates into real SSD wear across the years a
server actually runs. Checking that objection found **four defects**, three of
which had been stated as fact:

| # | Defect | Consequence |
|---|---|---|
| 1 | Per-GiB arithmetic wrong by **15×** | Published "~5 KB vs ~1.7 MB"; actual **~82 KB vs ~26 MB** |
| 2 | "Negligible" never integrated over time | **+0.55 TB over 5 years, +33% of writes** |
| 3 | Test DB was **11.2 MB** — never filled one 64 MB memtable, never reached L1 | The 316× compaction figure measured **cold start**, not steady state |
| 4 | **All five** prior harnesses set `disableWAL(true)` | The WAL is written on every put; omitting it disqualifies any endurance claim |

Defects 3 and 4 are structural, so this is a new experiment rather than a
corrected sum.

## What it does differently

**Forces a real LSM tree.** Level geometry is shrunk (4 MB memtable, 8 MB L1
base, static leveling) so several levels populate within the time budget. The
harness reads `rocksdb.num-files-at-levelN` and **aborts loudly** unless at least
two levels hold files.

Sizing is against *on-disk* bytes, not logical: chunk NBT compresses ~8×, so
1 GiB of writes becomes only ~128 MB of SST. An earlier version sized against
logical volume, produced a single populated level, and was correctly rejected by
its own check.

**Counts every byte.** `WAL_FILE_BYTES` + `FLUSH_WRITE_BYTES` +
`COMPACT_WRITE_BYTES`, reported separately.

> `FLUSH_WRITE_BYTES` already includes blob bytes written during flush. Adding
> `BLOB_DB_BLOB_FILE_BYTES_WRITTEN` on top double-counts — a mistake made in
> Phase 0. Blob bytes are printed for information only, never summed.

**Cross-checks against the kernel.** `/proc/self/io` `write_bytes` is compared
with RocksDB's own counters, so the tickers are validated rather than trusted.

**Eight configurations:** blob on/off × WAL sync/group-commit × Zipfian/uniform.
Zipfian models a few players revisiting the same chunks; uniform models
exploration touching fresh terrain.

## Preflight checks

The script refuses to run rather than produce misleading numbers:

- **`--world` is mandatory.** Synthetic corpora have already caused two wrong
  conclusions in this project (6× off on chunk size, wrong on compressibility).
- **tmpfs/ramfs targets rejected.** On RAM-backed storage `/proc/self/io`
  measures memory traffic and the measurement is void.
- JDK ≥11 located via `JAVA_HOME` → `PATH` → common install paths; a JRE without
  `javac` is reported clearly.
- RocksDB jar fetched from Maven Central and **SHA-1 verified**.
- Free space checked before starting.
- OS, filesystem type, JVM and CPU count recorded in the results JSON.

## Cost

| Mode | Time | Writes |
|---|---|---|
| `--quick` | ~15–20 min | a few GB |
| full | ~4 hours | tens to low hundreds of GB |

A rounding error against a 150–600 TB drive rating, but real wear. Pick the
target device deliberately.

## Reading the output

```
    logical        402,677,205
    WAL            403,107,470
    flush           29,678,071  (includes flush-time blob writes)
    compaction      38,249,308
    blob(info)     ...          <- informational, NOT added to the total
    ENGINE SUM     470,434,849   = 1.17x logical
    kernel         ...           = ...x logical  (/proc/self/io)
    filesystem overhead: +N% above engine counters
```

**`ENGINE SUM` is the comparable figure across machines.** The kernel number is
what the device actually sees, but it includes filesystem behaviour: on
copy-on-write filesystems (btrfs, ZFS) data, metadata and checksum-tree writes
are all charged to the process, and a validation run on btrfs showed the kernel
figure at **+233%** over the engine counters with `sync=true`. Expect a much
smaller gap on ext4/xfs. This is why the filesystem type is recorded.

**The WAL dominates.** It is roughly 1× logical on its own, which is why omitting
it from earlier measurements was disqualifying rather than merely imprecise.

## Interpreting `blob=on` vs `blob=off`

These are validated differently, on purpose.

With key-value separation the LSM stores only keys and pointers, so it **stays
shallow by design** and will not develop deep levels at any realistic data
volume. That is the mechanism working, not a defect — so requiring depth from the
blob arm would reject the configuration under test. The blob arm is instead
validated by confirming `.blob` files were produced.

The no-blob arm **must** reach real depth, because that is where leveled
compaction cost lives, and failing to reach it is exactly what invalidated
Phase 1a.

## What this decides

Phase 1b found that blob files ignore compression level and dictionaries
entirely, so keeping chunks in the LSM stores **~26% fewer bytes**. Against that,
key-value separation avoids compaction rewriting large values.

**Storage size and flash endurance therefore pull in opposite directions**, and
the compaction side has never been measured in a valid regime. This experiment
settles which effect is larger.

## Caveats

- Shrunken level geometry reaches realistic *depth* faster than production
  geometry would. Leveled amplification scales with level count, so this is a
  reasonable proxy — but absolute byte counts will differ from a
  default-configured server.
- Multi-year projections extrapolate from hours, and assume 11.25 GiB/day logical
  (40 MB dirty chunks per 5-minute autosave). Both assumptions are printed with
  the results.
- The vanilla Anvil comparison is *derived* (payload + 8 KiB header per write),
  not instrumented from a running server.
- Single machine, single run per configuration, no repetitions.
