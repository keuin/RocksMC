#!/usr/bin/env python3
"""
Measure real Minecraft chunk payloads directly from Anvil (.mca) region files.

WHY
---
Every size, ratio, and amplification figure in this project so far came from a
*synthetic* corpus. This tool reads real region files and reports what chunk
values actually look like: compressed and uncompressed size distributions,
compression ratio achieved by vanilla's DEFLATE, sector-allocation waste, and
fragmentation.

It depends only on the Python standard library and parses the Anvil format
directly, so it needs neither a JVM nor Minecraft on the classpath.

ANVIL FORMAT (as implemented by RegionFile.java in 1.16.5)
----------------------------------------------------------
  bytes 0..4095     : 1024 x uint32 packed sector entries
                      offset = value >> 8 (in 4096-byte sectors)
                      size   = value & 0xFF (count of 4096-byte sectors)
  bytes 4096..8191  : 1024 x uint32 last-modified timestamps
  chunk payload     : uint32 length, uint8 compression scheme, then data
                      scheme 1 = GZIP, 2 = ZLIB/DEFLATE (the default), 3 = none
                      high bit of scheme set => payload lives in an external
                      .mcc file (oversized chunk, >= 256 sectors)

Usage:
    ./mca_stats.py <world-dir-or-region-dir> [...]
"""

import os
import struct
import sys
import zlib
from collections import Counter

SECTOR = 4096
HEADER_SECTORS = 2


class ChunkRecord:
    __slots__ = ("region", "index", "sectors", "declared_len", "scheme",
                 "compressed", "raw", "external")

    def __init__(self, region, index, sectors, declared_len, scheme,
                 compressed, raw, external):
        self.region = region
        self.index = index
        self.sectors = sectors
        self.declared_len = declared_len
        self.scheme = scheme
        self.compressed = compressed
        self.raw = raw
        self.external = external


def read_region(path, decompress=True):
    """Yield ChunkRecord for every allocated chunk slot in a .mca file."""
    out = []
    size = os.path.getsize(path)
    if size == 0:
        # Normal, not corruption: vanilla creates a region file on demand and
        # leaves it empty until a chunk in that region is actually written.
        return out, Counter({"empty_region_file": 1})
    if size < SECTOR * HEADER_SECTORS:
        return out, Counter({"truncated_header": 1})

    problems = Counter()
    with open(path, "rb") as fh:
        header = fh.read(SECTOR)
        for i in range(1024):
            packed = struct.unpack_from(">I", header, i * 4)[0]
            if packed == 0:
                continue
            offset = packed >> 8
            sectors = packed & 0xFF
            if offset < HEADER_SECTORS or sectors == 0:
                problems["invalid_sector_entry"] += 1
                continue
            if offset * SECTOR >= size:
                problems["offset_past_eof"] += 1
                continue

            fh.seek(offset * SECTOR)
            head = fh.read(5)
            if len(head) < 5:
                problems["truncated_chunk_header"] += 1
                continue
            declared_len, scheme = struct.unpack(">IB", head)
            external = bool(scheme & 0x80)
            scheme &= 0x7F

            if external:
                problems["external_mcc"] += 1
                out.append(ChunkRecord(path, i, sectors, declared_len, scheme,
                                       0, 0, True))
                continue
            if declared_len == 0:
                problems["zero_length"] += 1
                continue

            payload_len = declared_len - 1
            if payload_len < 0 or payload_len > sectors * SECTOR:
                problems["length_exceeds_allocation"] += 1
                continue

            payload = fh.read(payload_len)
            if len(payload) < payload_len:
                problems["short_read"] += 1
                continue

            raw_len = 0
            if decompress:
                try:
                    if scheme == 2:
                        raw_len = len(zlib.decompress(payload))
                    elif scheme == 1:
                        raw_len = len(zlib.decompress(payload, 16 + zlib.MAX_WBITS))
                    elif scheme == 3:
                        raw_len = payload_len
                    else:
                        problems["unknown_scheme_%d" % scheme] += 1
                except zlib.error:
                    problems["decompress_failed"] += 1

            out.append(ChunkRecord(path, i, sectors, declared_len, scheme,
                                   payload_len, raw_len, False))
    return out, problems


def find_region_files(root):
    """Accept a world dir, a dimension dir, or a region dir. Group by category."""
    groups = {}
    if os.path.isfile(root) and root.endswith(".mca"):
        groups[os.path.dirname(root)] = [root]
        return groups
    for dirpath, _, filenames in os.walk(root):
        mcas = sorted(f for f in filenames if f.endswith(".mca"))
        if mcas:
            groups[dirpath] = [os.path.join(dirpath, f) for f in mcas]
    return groups


def percentile(sorted_vals, q):
    if not sorted_vals:
        return 0
    k = (len(sorted_vals) - 1) * q
    lo = int(k)
    hi = min(lo + 1, len(sorted_vals) - 1)
    if lo == hi:
        return sorted_vals[lo]
    return sorted_vals[lo] + (sorted_vals[hi] - sorted_vals[lo]) * (k - lo)


def human(n):
    for unit in ("B", "KiB", "MiB", "GiB", "TiB"):
        if abs(n) < 1024 or unit == "TiB":
            return f"{n:.1f} {unit}" if unit != "B" else f"{int(n)} B"
        n /= 1024.0


def report(label, records, problems, file_count, on_disk):
    if not records:
        print(f"\n### {label}: no chunks found")
        return None

    comp = sorted(r.compressed for r in records if not r.external)
    raw = sorted(r.raw for r in records if not r.external and r.raw)
    alloc = sum(r.sectors * SECTOR for r in records)
    total_comp = sum(comp)
    total_raw = sum(raw)

    print(f"\n### {label}")
    print(f"  region files          {file_count}")
    print(f"  chunks                {len(records)}")
    print(f"  on-disk (.mca total)  {human(on_disk)}")

    print()
    print("  compressed chunk value size (what a KV store would store as-is):")
    print(f"    min {human(comp[0])}   p50 {human(percentile(comp, .5))}   "
          f"p90 {human(percentile(comp, .9))}   p99 {human(percentile(comp, .99))}   "
          f"max {human(comp[-1])}")
    print(f"    mean {human(total_comp / len(comp))}   total {human(total_comp)}")

    if raw:
        print()
        print("  UNCOMPRESSED chunk NBT size (what we would hand to the engine):")
        print(f"    min {human(raw[0])}   p50 {human(percentile(raw, .5))}   "
              f"p90 {human(percentile(raw, .9))}   p99 {human(percentile(raw, .99))}   "
              f"max {human(raw[-1])}")
        print(f"    mean {human(total_raw / len(raw))}   total {human(total_raw)}")
        print(f"    vanilla DEFLATE ratio: {total_raw / total_comp:.2f}x")

    # Sector rounding waste: Anvil allocates in whole 4 KiB sectors, and every
    # read allocates a sector-rounded heap buffer (RegionFile.java:106-108).
    waste = alloc - total_comp
    print()
    print("  sector allocation:")
    print(f"    allocated {human(alloc)} for {human(total_comp)} of payload")
    print(f"    rounding waste {human(waste)} ({waste * 100.0 / alloc:.1f}% of allocation)")

    # Fragmentation: file size beyond what live chunks occupy is dead space that
    # Anvil never reclaims (SectorMap has no compaction).
    frag = on_disk - alloc - file_count * SECTOR * HEADER_SECTORS
    if frag > 0:
        print(f"    unreclaimed/fragmented {human(frag)} "
              f"({frag * 100.0 / on_disk:.1f}% of on-disk)")

    schemes = Counter(r.scheme for r in records)
    names = {1: "gzip", 2: "zlib/deflate", 3: "uncompressed"}
    print()
    print("  compression schemes in use: " +
          ", ".join(f"{names.get(k, k)}={v}" for k, v in sorted(schemes.items())))

    if problems:
        print("  anomalies: " + ", ".join(f"{k}={v}" for k, v in sorted(problems.items())))

    return {"chunks": len(records), "comp_total": total_comp,
            "raw_total": total_raw, "alloc": alloc, "on_disk": on_disk}


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(2)

    grand = Counter()
    for root in sys.argv[1:]:
        print(f"\n{'=' * 70}\n{root}\n{'=' * 70}")
        groups = find_region_files(root)
        if not groups:
            print("  no .mca files found")
            continue
        for dirpath in sorted(groups):
            files = groups[dirpath]
            records = []
            problems = Counter()
            on_disk = 0
            for f in files:
                recs, probs = read_region(f)
                records.extend(recs)
                problems.update(probs)
                on_disk += os.path.getsize(f)
            label = os.path.relpath(dirpath, root) or os.path.basename(dirpath)
            stats = report(label, records, problems, len(files), on_disk)
            if stats:
                for k, v in stats.items():
                    grand[k] += v

    if grand["chunks"]:
        print(f"\n{'=' * 70}\nGRAND TOTAL\n{'=' * 70}")
        print(f"  chunks         {grand['chunks']}")
        print(f"  on-disk        {human(grand['on_disk'])}")
        print(f"  compressed     {human(grand['comp_total'])} "
              f"(mean {human(grand['comp_total'] / grand['chunks'])}/chunk)")
        if grand["raw_total"]:
            print(f"  uncompressed   {human(grand['raw_total'])} "
                  f"(mean {human(grand['raw_total'] / grand['chunks'])}/chunk)")
            print(f"  DEFLATE ratio  {grand['raw_total'] / grand['comp_total']:.2f}x")


if __name__ == "__main__":
    main()
