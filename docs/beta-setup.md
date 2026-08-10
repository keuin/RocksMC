# Beta server setup

Standing up a rocksmc beta from an existing survival world, on Optane + btrfs,
tuned for performance rather than endurance.

**Status: alpha software. World rollback should be expected, not merely possible.**

---

## 1. What works, and what does not

Verified against a real 293,207-chunk technical-server world:

| | |
|---|---|
| Chunk read/write | ✅ 293,207/293,207 round-tripped, zero mismatches |
| Custom dimensions | ✅ identity derived from save directory, 43 unit tests |
| World import | ✅ verified read-back, source `.mca` never modified |
| **One database per world** | ✅ 6 stores share one handle; verified live and under load |
| **Crash recovery** | ✅ four `kill -9` cycles mid-autosave; all dimensions recovered to **one** consistent point, 293,207 entries intact |
| Metrics + logging | ✅ Prometheus on `/metrics`, periodic log lines |
| Format version guard | ✅ refuses to open a database from another build |
| Legacy-layout guard | ✅ refuses to start on a v1 world, naming the re-import command |
| Blank-world guard | ✅ refuses to regenerate terrain over a populated world |

Known gaps — none block a beta, all matter operationally:

| Gap | Consequence |
|---|---|
| **POI never exercised by a live server** | The dev run wrote 0 POI chunks. Villagers, beds and workstations go through a 16-section split path that has only been tested via the harness and the importer |
| **Chunk and POI are not atomic with respect to each other** | One database means one write-ahead log and therefore one *recovery point*, which is what the crash test verifies. It does **not** batch a chunk write together with its POI write: those originate in independent `StorageIoWorker`s above the seam this mod injects at. A crash can still land between them |
| **`playerdata`, `data/`, `level.dat` are still flat files** (Phase 3) | Backups must capture them *and* the database |
| **No `.mca` interop** (Phase 5) | Amulet, Chunker, BlueMap/Dynmap and pregenerators cannot read the result |
| Read path is slower than Anvil | Anvil resolves a chunk in one in-memory header hit plus one seek. That is O(1) and an LSM cannot beat it |

## 2. Host preparation

**This is the highest-impact step. It matters more than any mod setting.**

btrfs copy-on-write over a database is pathological: measured, an fsync on CoW
produced **+280% kernel write amplification** versus the engine's own accounting.
Disable CoW for the world directory **before** anything is written to it —
`chattr +C` only takes effect on files created afterwards.

```bash
mkdir -p /srv/mc-beta/world
chattr +C /srv/mc-beta/world
lsattr -d /srv/mc-beta/world          # expect ---------------C------
```

Also:

- **No btrfs compression** on this subvolume. RocksDB already compresses with
  ZSTD; doing it twice burns CPU for nothing.
- **`autodefrag` off.** Actively harmful for a database's access pattern.
- Mount with `noatime`.

⚠️ **Snapshot caveat.** A btrfs snapshot forces CoW on the shared extents of
NOCOW files afterwards, so some of the benefit is lost until data is rewritten.
Since rollback is the whole point of a beta, take the snapshots anyway and accept
the drift.

### Optane note

The P4800X changes the calculus. Endurance is a non-issue (~60 DWPD; the
worst-measured configuration reaches ~119 TB over five years), and fsync latency
is microseconds rather than hundreds of microseconds. So every setting below
favours **latency and CPU** over minimising writes.

## 3. Import the world

Work from a **copy**. The importer opens region files read-only and never modifies
them, but a beta should not be the first thing to touch a production mirror.

```bash
cp -r /path/to/survival-mirror /srv/mc-beta/world
cd ~/Projects/self/rocksmc
./gradlew importWorld -Pworld=/srv/mc-beta/world
```

The importer:

1. finds every `region/` and `poi/` directory, including custom dimensions
2. reads each chunk — **including oversized `.mcc` chunks**, which earlier tooling
   in this project skipped
3. writes into `<world>/rocksmc.db` — **one database for the whole world**, the
   exact layout the server expects
4. reads every chunk back and compares, then compacts so the server does not
   inherit the compaction debt

It exits non-zero and tells you not to start the server if anything failed.

Measured on the real world: **293,207 chunks in 31.6 s** on 24 cores, and the
resulting database is **33.9% smaller on disk** than the `.mca` files (the fair
file-to-file comparison; sparse dimensions do far better still, the End by 80.9%,
because Anvil's 4 KiB sector padding hurts them worst).

The import runs one region file per worker thread, one worker per core by default.
Add `-Pthreads=n` to cap it — worth doing if the machine is also running something
else, since it will otherwise saturate every core. `-Pthreads=1` gives the old
sequential behaviour (342 s on the same world) and is the thing to try first if an
import ever behaves oddly.

⚠️ Every dimension must be imported **in one pass**, which is what the command
above does. The blank-start guard checks whether the shared database holds *any*
data, so importing one dimension and then starting a server would let the others
regenerate silently.

⚠️ **`level.dat`, `playerdata/`, `data/` and `advancements/` are not imported**,
because vanilla reads them directly and they need no conversion. They must still
be in place, and must be included in backups.

### Upgrading from an older rocksmc build

Builds before the one-database-per-world change wrote a separate database per
`(dimension, leaf)` at `<dir>.rocksdb`. There is **no migration**: re-import from
the `.mca` files, which were never modified.

```bash
./gradlew importWorld -Pworld=/srv/mc-beta/world
```

The server refuses to start if it finds the old layout, and names this command in
the error. The old `*.rocksdb` directories are left untouched, so an older build
and `backend=anvil` both still work; delete them once the re-import is verified.

## 4. Configuration

Resources are allocated **once per world**, so the figures below mean what they
say. (Before consolidation each of a world's six stores allocated its own, which
silently multiplied every memory figure by six.)

`/srv/mc-beta/config/rocksmc.properties`:

```properties
backend=rocksdb
min-blob-size=1024
sync-writes=false
verify-on-read=true

# Performance: technical server on Optane. Per world, not per dimension.
max-background-jobs=8
max-subcompactions=4
write-buffer-size=134217728
max-write-buffer-number=4
bytes-per-sync=1048576
block-cache-size=536870912
level0-slowdown-writes-trigger=24

# Telemetry
metrics-enabled=true
metrics-bind=127.0.0.1
metrics-port=9940
stats-log-interval-seconds=60
```

Reasoning for the choices that matter:

- **`min-blob-size=1024` (blobs on).** Disabling blob files stores ~8.6% less but
  costs ~8.5% more bytes written *and* 1.35–1.51× the compaction traffic.
  Compaction bytes are compaction **CPU**, competing with the tick loop — the
  scarce resource on a technical server. On a 375 GB drive you do not need the
  space.
- **`sync-writes=false`.** On Optane `true` is affordable, but `false` keeps fsync
  latency off the IO worker and the WAL remains checksummed and replayable. With
  rollback accepted, take the speed. Measured, `true` multiplies kernel-observed
  writes by 3.65× — irrelevant for endurance here, but it is still latency.
  ⚠️ Note the crash test showed the expected consequence: writes made in the last
  moments before a `kill -9`, with no completed save, are gone. What is guaranteed
  is that *every dimension loses the same ones*.
- **`verify-on-read=true` for the first week.** Roughly halves write throughput,
  which Optane can absorb, and catches a corrupting bug where it happens instead
  of when a player finds a hole in the world. Turn it off once you trust it.
- **`write-buffer-size=128 MiB`, 4 buffers, per column family.** Larger memtables
  coalesce more repeated saves of the same hot chunk, the dominant write pattern on
  a technical server. Two data column families (`chunk`, `poi`), so worst case is
  ~1 GiB of memtables for the whole world.
- **`block-cache-size=512 MiB`** — one cache for the world. Note this caches index
  and filter blocks, **not** chunk values: those live in blob files, and RocksDB
  10.x has no per-column-family blob cache. Chunk reads rely on this plus the OS
  page cache.
- **`max-background-jobs=8`** — one thread pool for the world, up from RocksDB's
  default of 2 because unfinished compaction eventually stalls writes on the IO
  worker.

⚠️ **These values are reasoned, not measured.** Every unmeasured figure in this
project has so far turned out wrong. Use the metrics below to correct them.

Also note: **`sync-chunk-writes` in `server.properties` has no effect** with this
backend. The mod logs a warning about it at startup.

## 5. Monitoring

```
http://127.0.0.1:9940/metrics
```

A ready-made Grafana dashboard lives in
[`dashboards/rocksmc-overview.json`](../dashboards/rocksmc-overview.json). The
datasource is a template variable rather than a hardcoded UID, so import it and pick
your Prometheus when prompted. See
[`dashboards/README.md`](../dashboards/README.md) for the panel layout and what
each one means.

Every series is labelled at the scope it genuinely has, which is not always the
dimension:

| Scope | Labels | Examples |
|---|---|---|
| Store | `dimension`, `store` (`region`/`poi`), `database` | chunk reads/writes, bytes, failures |
| Column family | `column_family` (`chunk`/`poi`), `database` | SST bytes, key estimates, memtables, compaction backlog — all dimensions share a column family, so these **cannot** be split per dimension |
| Database | `database` | blob bytes, block cache, throttling, write stops — one write path per world |

⚠️ The column-family metrics are suffixed **`_by_cf`** (e.g.
`rocksmc_live_sst_bytes_by_cf`). The rename is deliberate: the pre-consolidation
names were per-store, so a query written against them would still return data
while silently aggregating a different scope. Summing the old names over a
three-dimension world overstated disk usage by **6×** and key counts by **3×**.

**The four that matter most:**

| Metric | Watch for |
|---|---|
| `rocksmc_write_stopped` | **Any 1 is an incident.** Compaction cannot keep up; writes are fully blocked |
| `rocksmc_delayed_write_rate` | Non-zero means throttling has begun — the early warning before a stop |
| `rocksmc_pending_compaction_bytes_by_cf` | Rising steadily means background work is losing. Raise `max-background-jobs` |
| `rocksmc_verify_failures_total` | **Must stay 0.** Any increase means the storage layer is corrupting data — stop and roll back |

Also worth a glance: `rocksmc_databases` must read **1** per world. If it ever
tracks `rocksmc_stores` instead, the shared-handle consolidation has broken and a
crash can recover dimensions to different points.

Useful queries:

```promql
# chunk write rate per dimension
sum by (dimension) (rate(rocksmc_chunk_writes_total[5m]))

# is anything throttling right now
max by (database) (rocksmc_delayed_write_rate) > 0

# compaction backlog trend, per column family
sum by (column_family) (rocksmc_pending_compaction_bytes_by_cf)

# on-disk footprint (blobs hold the chunk values)
sum(rocksmc_blob_file_bytes) + sum(rocksmc_live_sst_bytes_by_cf)

# stored bytes per entry, against ~3.5 KiB per chunk for vanilla Anvil
(sum(rocksmc_blob_file_bytes) + sum(rocksmc_live_sst_bytes_by_cf))
  / clamp_min(sum(rocksmc_estimated_keys_by_cf), 1)
```

Suggested alerts: `rocksmc_write_stopped > 0`,
`increase(rocksmc_verify_failures_total[5m]) > 0`,
`increase(rocksmc_write_failures_total[5m]) > 0`.

**Scraping gotcha:** if `HTTP_PROXY`/`http_proxy` is set in the environment, curl
and some scrapers will route a loopback request through the proxy and return
`503`. Use `curl --noproxy '*'` or add `127.0.0.1` to `NO_PROXY`.

**Protobuf scrapes return 500.** The mod bundles the text-only exposition module to
keep 2 MB of shaded protobuf out of the jar, so `text/plain` and OpenMetrics work
and a scraper explicitly demanding `application/vnd.google.protobuf` gets a 500.
Prometheus negotiates text by default; only an unusual scraper configuration hits
this.

**If the server refuses to start with `NoClassDefFoundError` on an
`io.prometheus.*` class**, the mod jar was built with an incomplete bundled-library
list. That is a build defect rather than a configuration one — rebuild with
`./gradlew build`, which now runs `verifyBundledLibraries` and fails loudly instead
of shipping a jar that cannot link. As an immediate workaround,
`metrics-enabled=false` avoids loading the exporter at all.

Periodic log lines are kept alongside Prometheus deliberately: a log survives in
an archive after a crash, whereas a scrape only exists if something was collecting
at the time.

## 6. Backup and rollback

Back up **all** of these together — the database alone is not a world:

```
world/rocksmc.db         world/level.dat           world/playerdata/
world/data/              world/advancements/       world/stats/
```

One database now covers every dimension, so there is a single directory to capture
rather than six. The simplest approach on btrfs is a subvolume snapshot of the
whole world with the server stopped.

**Rollback** is deliberately trivial:

```bash
# stop the server, then:
sed -i 's/^backend=rocksdb/backend=anvil/' config/rocksmc.properties
```

The original `.mca` files were never modified, so vanilla picks up exactly where
the import left off. Anything built during the beta lives only in `rocksmc.db`
and is lost — which is the expected trade.

Keep the `.mca` files for the whole beta. They are the rollback.

## 7. Pre-flight checklist

- [ ] `chattr +C` applied to the world directory **while empty**, confirmed with `lsattr -d`
- [ ] btrfs compression off, `autodefrag` off, `noatime` on
- [ ] World is a **copy** of the mirror, not the mirror itself
- [ ] `importWorld` completed with `RESULT: OK`, covering **every** dimension in one pass
- [ ] No leftover `*.rocksdb` directories from an older build (the server will refuse to start)
- [ ] `backend=rocksdb` set; `verify-on-read=true` for the first week
- [ ] Metrics scraped and `rocksmc_up 1` visible in the scraper
- [ ] `rocksmc_databases` reads **1**, and `rocksmc_stores` reaches **6** once all dimensions load
- [ ] Alerts wired on `write_stopped` and `verify_failures_total`
- [ ] Backup covers `rocksmc.db` **and** `level.dat`/`playerdata`/`data`
- [ ] Rollback rehearsed once: flip to `anvil`, confirm the world still loads
- [ ] Players told the world may be rolled back

## 8. First-week watchlist

Given what is untested, these are the specific things to check rather than
waiting for a report:

1. **Villagers.** The POI path has never run under a live server, only via the
   importer and the harness. Confirm villagers keep professions and beds across a
   restart, and that `rocksmc_chunk_writes_total{store="poi"}` becomes non-zero.
2. **A hard kill.** Already verified here — four `kill -9` cycles mid-autosave
   recovered every dimension to one consistent point with all 293,207 entries
   intact. Repeat it on your own hardware and filesystem anyway, early, while you
   still have a fresh snapshot: btrfs and Optane are not what was tested.
   Expect writes from the final unsaved moments to be gone; that is
   `sync-writes=false` working as documented.
3. **Nether and End.** Confirm all six stores appear (`rocksmc_stores` = 6) while
   `rocksmc_databases` stays at 1.
4. **Compaction under farm load.** Watch `pending_compaction_bytes_by_cf` while your
   heaviest farms run. If it climbs monotonically, raise `max-background-jobs`.
5. **Tick timings versus the Anvil baseline.** Run the same world on `backend=anvil`
   for a day first if you can, so there is something to compare against.
