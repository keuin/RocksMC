#!/usr/bin/env python3
"""
Stratified corpus extractor for the Phase 1b codec sweep.

WHY STRATIFY
------------
Real chunk data does not have "a" compression ratio. Measured on a real server
world with vanilla DEFLATE:

    DIM1/region  (End)        16.06x   mean 11,626 B/chunk
    DIM-1/region (Nether)      8.47x   mean 29,477 B/chunk
    region       (Overworld)   7.49x   mean 30,601 B/chunk
    poi                        5.52x   mean    706 B/chunk
    DIM1/poi                   2.87x   mean    385 B/chunk

That is a 5.6x spread. A single blended corpus would hide it and produce a
number that describes no real workload. So each dimension gets its own dump.

A separate `large` stratum captures the upper tail (the real world contains a
2.1 MiB chunk, ~8x the largest in a freshly generated world). Those chunks are
rare enough that uniform sampling usually misses them, but they matter because
codec behaviour changes with input size.

OUTPUT FORMAT
-------------
Matches spike/phase1a-real-corpus/extract_corpus.py so the Java harnesses can
share a loader:

    uint32 count, then count x (uint32 length, length bytes)

Values are decompressed chunk NBT -- exactly what would be handed to a storage
engine, and exactly what vanilla's DEFLATE was applied to.

Usage:
    ./extract_strata.py <world-dir> <out-dir> [--per-stratum N] [--large-threshold BYTES]
"""

import os
import struct
import sys
import zlib

SECTOR = 4096

# Directory name -> stratum name. Anything not listed is skipped, so the strata
# stay interpretable rather than becoming a catch-all.
STRATA = {
    "region": "overworld",
    "DIM-1/region": "nether",
    "DIM1/region": "end",
    "poi": "poi_overworld",
    "DIM-1/poi": "poi_nether",
    "DIM1/poi": "poi_end",
}


def iter_chunks(mca_path):
    """Yield decompressed chunk NBT blobs from one .mca file.

    Anvil layout (see RegionFile.java in 1.16.5):
      bytes 0..4095    1024 x uint32 packed sector entries
                       offset = value >> 8 (in 4096-byte sectors)
                       size   = value & 0xFF (sector count)
      bytes 4096..8191 timestamps (unused here)
      payload          uint32 length, uint8 scheme, then data
                       scheme 1 = gzip, 2 = zlib/deflate, 3 = none
                       high bit set => payload is in an external .mcc file
    """
    size = os.path.getsize(mca_path)
    if size < SECTOR * 2:
        # Vanilla creates region files on demand and leaves them empty until a
        # chunk in that region is written. Not corruption.
        return
    with open(mca_path, "rb") as fh:
        header = fh.read(SECTOR)
        for i in range(1024):
            packed = struct.unpack_from(">I", header, i * 4)[0]
            if packed == 0:
                continue
            offset, sectors = packed >> 8, packed & 0xFF
            if offset < 2 or sectors == 0 or offset * SECTOR >= size:
                continue
            fh.seek(offset * SECTOR)
            head = fh.read(5)
            if len(head) < 5:
                continue
            declared, scheme = struct.unpack(">IB", head)
            if scheme & 0x80 or declared <= 1:
                continue  # external .mcc, or empty
            payload = fh.read(declared - 1)
            if len(payload) < declared - 1:
                continue
            scheme &= 0x7F
            try:
                if scheme == 2:
                    yield zlib.decompress(payload)
                elif scheme == 1:
                    yield zlib.decompress(payload, 16 + zlib.MAX_WBITS)
                elif scheme == 3:
                    yield payload
            except zlib.error:
                continue


def region_files(world, rel):
    d = os.path.join(world, rel)
    if not os.path.isdir(d):
        return []
    return [os.path.join(d, f) for f in sorted(os.listdir(d)) if f.endswith(".mca")]


def write_dump(path, blobs):
    with open(path, "wb") as fh:
        fh.write(struct.pack(">I", len(blobs)))
        for b in blobs:
            fh.write(struct.pack(">I", len(b)))
            fh.write(b)
    return os.path.getsize(path)


def human(n):
    for unit in ("B", "KiB", "MiB", "GiB"):
        if abs(n) < 1024 or unit == "GiB":
            return f"{n:.1f} {unit}" if unit != "B" else f"{int(n)} B"
        n /= 1024.0


def main():
    positional = [a for a in sys.argv[1:] if not a.startswith("--")]
    if len(positional) < 2:
        print(__doc__)
        sys.exit(2)

    world, out_dir = positional[0], positional[1]

    # Accept both "--flag value" and "--flag=value". The Phase 1a extractor only
    # handled the "=" form and silently produced None for the other, which meant
    # "--limit 5000" was ignored without warning.
    def opt(name, default):
        argv = sys.argv[1:]
        for i, a in enumerate(argv):
            if a == "--" + name and i + 1 < len(argv):
                return int(argv[i + 1])
            if a.startswith("--" + name + "="):
                return int(a.split("=", 1)[1])
        return default

    per_stratum = opt("per-stratum", 20000)
    large_threshold = opt("large-threshold", 262144)  # 256 KiB

    os.makedirs(out_dir, exist_ok=True)
    print(f"world:            {world}")
    print(f"out:              {out_dir}")
    print(f"per-stratum cap:  {per_stratum} chunks")
    print(f"large threshold:  {human(large_threshold)}")
    print()

    large = []
    summary = []

    for rel, stratum in STRATA.items():
        files = region_files(world, rel)
        if not files:
            continue

        blobs = []
        total_seen = 0
        # Sample evenly across region files so a capped stratum still covers the
        # whole map. Taking the first N chunks in filename order would sample one
        # spatial corner -- and since region files correspond to map areas, that
        # would bias the ratio toward whatever happens to be built there.
        quota = max(1, per_stratum // max(1, len(files)))
        for path in files:
            taken = 0
            for blob in iter_chunks(path):
                total_seen += 1
                if len(blob) >= large_threshold:
                    large.append(blob)
                if taken < quota and len(blobs) < per_stratum:
                    blobs.append(blob)
                    taken += 1

        if not blobs:
            continue

        sizes = sorted(len(b) for b in blobs)
        logical = sum(sizes)
        deflated = sum(len(zlib.compress(b, 6)) for b in blobs)
        path = os.path.join(out_dir, stratum + ".bin")
        write_dump(path, blobs)

        summary.append((stratum, len(blobs), total_seen, logical, deflated, sizes))
        print(f"{stratum:16s} {len(blobs):>7,} chunks (of {total_seen:,} seen)  "
              f"logical {human(logical):>10s}  deflate6 {logical / deflated:5.2f}x  "
              f"mean {human(logical / len(blobs)):>9s}  max {human(sizes[-1]):>9s}")

    if large:
        # Deduplicate is unnecessary (chunks are distinct), but cap the stratum so
        # a few thousand multi-MiB blobs do not dominate runtime.
        large = large[:2000]
        logical = sum(len(b) for b in large)
        deflated = sum(len(zlib.compress(b, 6)) for b in large)
        write_dump(os.path.join(out_dir, "large.bin"), large)
        sizes = sorted(len(b) for b in large)
        print(f"{'large':16s} {len(large):>7,} chunks               "
              f"logical {human(logical):>10s}  deflate6 {logical / deflated:5.2f}x  "
              f"mean {human(logical / len(large)):>9s}  max {human(sizes[-1]):>9s}")

    print()
    print("Note: deflate6 above is vanilla's own codec and level, i.e. the real")
    print("      baseline any replacement has to beat.")


if __name__ == "__main__":
    main()
