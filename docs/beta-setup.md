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
| Custom dimensions | ✅ identity derived from save directory, 31 unit tests |
| World import | ✅ verified read-back, source `.mca` never modified |
| Metrics + logging | ✅ Prometheus on `/metrics`, periodic log lines |
| Format version guard | ✅ refuses to open a database from another build |
| Blank-world guard | ✅ refuses to regenerate terrain over a populated world |

Known gaps — none block a beta, all matter operationally:

| Gap | Consequence |
|---|---|
| **POI never exercised by a live server** | The dev run wrote 0 POI chunks. Villagers, beds and workstations go through a 16-section split path that has only been tested via the harness |
| **Crash recovery untested** | The WAL is checksummed and replayable in principle; no `kill -9` test has been run |
| **Chunk and POI are not atomic** (Phase 2) | Separate databases. A crash between the two writes can leave them inconsistent |
| **`playerdata`, `data/`, `level.dat` are still flat files** (Phase 3) | Backups must capture them *and* the databases |
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
3. writes into a sibling `<name>.rocksdb`, the exact layout the server expects
4. reads every chunk back and compares, then compacts so the server does not
   inherit the compaction debt

It exits non-zero and tells you not to start the server if anything failed.

Measured on the End of a real world: 33,355 chunks in 29 s, **80.9% smaller on
disk** than the `.mca` files (sparse dimensions suffer worst from Anvil's 4 KiB
sector padding).

⚠️ **`level.dat`, `playerdata/`, `data/` and `advancements/` are not imported**,
because vanilla reads them directly and they need no conversion. They must still
be in place, and must be included in backups.

## 4. Configuration

> ⚠️ **Read this before copying the values.** Resources are currently allocated
> **per store**, not per world (`RocksChunkStore.java:140-141,168-182`), and a world
> with three dimensions opens **six** stores (region + poi each). So every memory
> figure below multiplies by six.
>
> Phase 2 consolidates to one database per world and makes these genuinely shared;
> see [`../TODO.md`](../TODO.md). It is agreed to land **before** the beta. The
> values below are therefore sized for the *current* per-store reality — divide-by-six
> figures that add up to the intended totals — and will be raised once sharing lands.

`/srv/mc-beta/config/rocksmc.properties`:

```properties
backend=rocksdb
min-blob-size=1024
sync-writes=false
verify-on-read=true

# Performance: technical server on Optane.
# NOTE: per-store values under the current layout. With 6 stores open these
# total ~512 MiB block cache and ~1.5 GiB of memtables.
max-background-jobs=4
max-subcompactions=2
write-buffer-size=67108864
max-write-buffer-number=4
bytes-per-sync=1048576
block-cache-size=89478485
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
- **`verify-on-read=true` for the first week.** Roughly halves write throughput,
  which Optane can absorb, and catches a corrupting bug where it happens instead
  of when a player finds a hole in the world. Turn it off once you trust it.
- **`write-buffer-size=64 MiB`, 4 buffers, per store.** Larger memtables coalesce
  more repeated saves of the same hot chunk, which is the dominant write pattern on
  a technical server. Across six stores this is ~1.5 GiB of memtables at worst.
- **`block-cache-size=85 MiB` per store** ≈ 512 MiB in total. Note this caches
  index and filter blocks, **not** chunk values — those live in blob files, and
  RocksDB 10.x has no per-column-family blob cache. Chunk reads rely on this plus
  the OS page cache.
- **`max-background-jobs=4` per store** = 24 background threads across six stores.
  Raised from RocksDB's default of 2 because unfinished compaction eventually
  stalls writes on the IO worker, but not to 8, which would mean 48 threads.

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

Every series is labelled `dimension`, `store` (`region`/`poi`) and `database`, so
a technical server can be queried per dimension — the End behaves nothing like an
Overworld full of farms.

**The four that matter most:**

| Metric | Watch for |
|---|---|
| `rocksmc_write_stopped` | **Any 1 is an incident.** Compaction cannot keep up; writes are fully blocked |
| `rocksmc_delayed_write_rate` | Non-zero means throttling has begun — the early warning before a stop |
| `rocksmc_pending_compaction_bytes` | Rising steadily means background work is losing. Raise `max-background-jobs` |
| `rocksmc_verify_failures_total` | **Must stay 0.** Any increase means the storage layer is corrupting data — stop and roll back |

Useful queries:

```promql
# chunk write rate per dimension
sum by (dimension) (rate(rocksmc_chunk_writes_total[5m]))

# is anything throttling right now
max by (dimension, store) (rocksmc_delayed_write_rate) > 0

# compaction backlog trend
sum by (dimension) (rocksmc_pending_compaction_bytes)

# on-disk footprint (blobs hold the chunk values)
sum(rocksmc_blob_file_bytes + rocksmc_live_sst_bytes)

# effective compression, uncompressed NBT vs stored bytes
sum(rate(rocksmc_bytes_written_total[1h]))
  / sum(rate(rocksmc_blob_file_bytes[1h]) > 0)
```

Suggested alerts: `rocksmc_write_stopped > 0`,
`increase(rocksmc_verify_failures_total[5m]) > 0`,
`increase(rocksmc_write_failures_total[5m]) > 0`.

**Scraping gotcha:** if `HTTP_PROXY`/`http_proxy` is set in the environment, curl
and some scrapers will route a loopback request through the proxy and return
`503`. Use `curl --noproxy '*'` or add `127.0.0.1` to `NO_PROXY`.

Periodic log lines are kept alongside Prometheus deliberately: a log survives in
an archive after a crash, whereas a scrape only exists if something was collecting
at the time.

## 6. Backup and rollback

Back up **all** of these together — the databases alone are not a world:

```
world/*.rocksdb          world/DIM-1/*.rocksdb     world/DIM1/*.rocksdb
world/level.dat          world/playerdata/         world/data/
world/advancements/      world/stats/
```

The simplest approach on btrfs is a subvolume snapshot of the whole world with the
server stopped.

**Rollback** is deliberately trivial:

```bash
# stop the server, then:
sed -i 's/^backend=rocksdb/backend=anvil/' config/rocksmc.properties
```

The original `.mca` files were never modified, so vanilla picks up exactly where
the import left off. Anything built during the beta lives only in the RocksDB
stores and is lost — which is the expected trade.

Keep the `.mca` files for the whole beta. They are the rollback.

## 7. Pre-flight checklist

- [ ] `chattr +C` applied to the world directory **while empty**, confirmed with `lsattr -d`
- [ ] btrfs compression off, `autodefrag` off, `noatime` on
- [ ] World is a **copy** of the mirror, not the mirror itself
- [ ] `importWorld` completed with `RESULT: OK`
- [ ] `backend=rocksdb` set; `verify-on-read=true` for the first week
- [ ] Metrics scraped and `rocksmc_up 1` visible in the scraper
- [ ] Alerts wired on `write_stopped` and `verify_failures_total`
- [ ] Backup covers databases **and** `level.dat`/`playerdata`/`data`
- [ ] Rollback rehearsed once: flip to `anvil`, confirm the world still loads
- [ ] Players told the world may be rolled back

## 8. First-week watchlist

Given what is untested, these are the specific things to check rather than
waiting for a report:

1. **Villagers.** The POI path has never run under a live server. Confirm
   villagers keep professions and beds across a restart, and that
   `rocksmc_chunk_writes_total{store="poi"}` becomes non-zero.
2. **A hard kill.** `kill -9` the server deliberately, early, while you still have
   a fresh snapshot. Confirm it reopens and terrain is intact. Better to learn this
   on your terms.
3. **Nether and End.** Each dimension gets its own store and its own ordinal;
   confirm all six appear in `rocksmc_stores`.
4. **Compaction under farm load.** Watch `pending_compaction_bytes` while your
   heaviest farms run. If it climbs monotonically, raise `max-background-jobs`.
5. **Tick timings versus the Anvil baseline.** Run the same world on `backend=anvil`
   for a day first if you can, so there is something to compare against.
