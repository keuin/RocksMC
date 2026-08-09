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
| `dimension` | Filter by dimension; compression and load differ enormously between them |

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
   count, on-disk size, chunk count. All single stats, colour-coded so a problem is
   visible without reading numbers.
2. **Write pressure** — compaction backlog, throttling, background work, memtable
   memory. This is where trouble appears *before* players notice tick lag.
3. **Chunk activity** — read/write rates, NBT throughput, mean chunk size, and the
   failure counters over time.
4. **Storage and compression** — bytes by dimension, obsolete SST awaiting reclaim,
   stored bytes per chunk, block cache usage.
5. **Diagnostics** (collapsed) — dimension ordinal table, scrape rate.

## The four panels that matter

| Panel | Meaning |
|---|---|
| **Writes stopped** | Any non-zero value is an incident. Compaction has fallen behind and chunk writes are fully blocked, which stalls the tick loop. Raise `max-background-jobs` |
| **Verify failures** | Must stay 0. Any increase means the storage layer read back something different from what it wrote — stop the server and roll back to `backend=anvil`. Only populated when `verify-on-read=true` |
| **Pending compaction backlog** | A steady climb means background work is losing to incoming writes. It ends in throttling, then a stop |
| **Write throttling** | Non-zero is the early warning before writes stop |

Suggested alerts:

```promql
max(rocksmc_write_stopped) > 0
increase(rocksmc_verify_failures_total[5m]) > 0
increase(rocksmc_write_failures_total[5m]) > 0
increase(rocksmc_read_failures_total[5m]) > 0
```

## Reading the numbers

**Blob files hold nearly all the bytes.** Key-value separation is enabled, so SST
files contain only keys and blob pointers and will look tiny next to blob files.
That is the mechanism working, not a misconfiguration.

**Block cache does not cache chunk values.** It holds index and filter blocks only;
RocksDB 10.x has no per-column-family blob cache, so chunk reads rely on this cache
plus the OS page cache.

**Dimensions are not comparable.** Measured on a real world, vanilla's own
compression spans 2.87×–24.66× across dimensions — the End compresses far better
than a built-up Overworld, and POI data barely compresses at all. Use the
`dimension` variable rather than reading aggregates.

**Stores appear lazily.** A dimension's store only opens when something loads that
dimension, so expect the store count to climb from 2 to 6 as players visit the
Nether and End.

## Validation

The JSON is checked in CI-style fashion by the repo's own tooling rather than by
eye:

- parses as JSON, no duplicate panel IDs
- every panel and target references `${datasource}`, never a literal UID
- every metric referenced exists in `MetricsExporter`, and every exported metric is
  used by at least one panel
- all 30 expressions parse against the real PromQL grammar (`promql-parser`)
- `rate()` is applied only to counters, and every division is guarded with
  `clamp_min` to avoid divide-by-zero gaps
