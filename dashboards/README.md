# Dashboards

`rocksmc-overview.json` — Grafana dashboard for the rocksmc Prometheus exporter.

## Import

Grafana → Dashboards → New → Import → upload the JSON, then pick your Prometheus
datasource when prompted.

The datasource is a **template variable**, not a hardcoded UID, so the same file
works across environments and can be provisioned as-is. Two further variables:

| Variable | Purpose |
|---|---|
| `datasource` | Which Prometheus to query |
| `instance` | Which server, for several rocksmc servers behind one Prometheus |
| `dimension` | Filter by dimension; applies to the chunk-activity panels only, since disk usage is not per-dimension (see below) |

## Three metric scopes

This is the thing to understand before reading any panel. One database serves every
dimension of a world, so a value's scope is not always "a dimension":

| Scope | Labels | What it covers |
|---|---|---|
| **Store** | `dimension`, `store`, `database` | The mod's own IO counters — reads, writes, bytes, failures. Genuinely per dimension |
| **Column family** (`_by_cf`) | `column_family`, `database` | SST bytes, blob bytes, key estimates, memtables, compaction backlog. **All dimensions share a column family**, so RocksDB cannot attribute these to one |
| **Database** | `database` | Blob bytes, block cache, throttling, write stops. One write path per world |

⚠️ Column-family metrics carry a **`_by_cf`** suffix. Before consolidation these
were per-store names; a query written against the old name would still return data
today while silently aggregating a different scope, so they were renamed to break
loudly instead. Summing the old names across a three-dimension world overstated
on-disk size by **6×** and entry counts by **3×** — measured, not estimated.

## Prerequisites

Enable the exporter in `config/rocksmc.properties`:

```properties
metrics-enabled=true
metrics-bind=127.0.0.1
metrics-port=9940
```

Scrape config:

```yaml
scrape_configs:
  - job_name: rocksmc
    static_configs:
      - targets: ['127.0.0.1:9940']
```

## Layout

Ordered by what you should look at first.

1. **Health** — exporter up, writes stopped, verify failures, IO failures, store
   count, on-disk size, entry count, and **databases (must be 1 per world)**. All
   single stats, colour-coded so a problem is visible without reading numbers.
2. **Write pressure** — compaction backlog per column family, throttling, background
   work, memtable memory. This is where trouble appears *before* players notice tick
   lag.
3. **Chunk activity** — read/write rates, NBT throughput, mean chunk size, and the
   failure counters over time. The only section that is genuinely per-dimension.
4. **Storage** — on-disk bytes, obsolete SST awaiting reclaim, stored bytes per
   entry, block cache usage.
5. **Diagnostics** (collapsed) — dimension ordinal table, scrape rate.

## The four panels that matter

| Panel | Meaning |
|---|---|
| **Writes stopped** | Any non-zero value is an incident. Compaction has fallen behind and chunk writes are fully blocked, which stalls the tick loop. Raise `max-background-jobs` |
| **Verify failures** | Must stay 0. Any increase means the storage layer read back something different from what it wrote — stop the server and roll back to `backend=anvil`. Only populated when `verify-on-read=true` |
| **Pending compaction backlog** | A steady climb means background work is losing to incoming writes. It ends in throttling, then a stop |
| **Databases (must be 1 per world)** | If this ever tracks the store count, the shared-handle consolidation has broken — and a crash can then recover dimensions to different points, which is the failure this design exists to prevent |

Suggested alerts:

```promql
max(rocksmc_write_stopped) > 0
increase(rocksmc_verify_failures_total[5m]) > 0
increase(rocksmc_write_failures_total[5m]) > 0
increase(rocksmc_read_failures_total[5m]) > 0
max(rocksmc_databases) > 1
```

## Reading the numbers

**`rocksmc_blob_file_bytes` also exists per column family** as
`rocksmc_blob_file_bytes_by_cf`. Both come from RocksDB's own
`live-blob-file-size` counter and the database-wide one is the sum of the
per-column-family ones, so either is safe to aggregate — just not both together.

**Blob files hold nearly all the bytes.** Key-value separation is enabled, so SST
files contain only keys and blob pointers and will look tiny next to blob files —
measured on a real world, 1.1 GiB of blobs against 3.7 MB of SST. That is the
mechanism working, not a misconfiguration. It is also why blob bytes are reported
per database: blob file names carry no column family.

**Block cache does not cache chunk values.** It holds index and filter blocks only;
RocksDB 10.x has no per-column-family blob cache, so chunk reads rely on this cache
plus the OS page cache.

**Disk usage is not per dimension, but activity is.** Use the `dimension` variable
on the chunk-activity panels, where it means something. For storage, the split
available is per column family (`chunk` vs `poi`), which is a different and coarser
cut. Measured on a real world, vanilla's own compression spans 2.87×–24.66× across
dimensions, so aggregate compression figures remain hard to interpret either way.

**Stores appear lazily; the database does not.** A dimension's store only opens when
something loads that dimension, so expect the store count to climb from 2 to 6 as
players visit the Nether and End. The database count stays at 1 throughout.

## Validation

The JSON is checked against the real exporter rather than by eye:

- parses as JSON, no duplicate panel IDs
- every panel and target references `${datasource}`, never a literal UID
- every metric referenced exists in a live scrape, and every exported metric is
  used by at least one panel
- all 32 expressions parse against the real PromQL grammar (`promql-parser`), with
  Grafana macros substituted first
- **every label filter is one the series actually carries**, and every
  `{{label}}` in a legend resolves — both would otherwise render an empty panel
  rather than an error, which is the failure mode the scope split introduced
- `rate()` is applied only to counters, and every division is guarded with
  `clamp_min` to avoid divide-by-zero gaps
- aggregates were compared against ground truth measured from the filesystem and a
  full key scan: 1.07 GiB / 293,736 estimated entries against 1.1 GiB / 293,207
  actual
