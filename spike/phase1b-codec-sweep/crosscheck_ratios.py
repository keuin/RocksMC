#!/usr/bin/env python3
"""
Ratio cross-check for the Phase 1b codec sweep.

The Java harness (CodecSweep.java) is authoritative for *speed*, because the mod
runs on the JVM and JNI transitions and array copies are part of the real cost.
But an independent implementation is needed to confirm the *ratios*, since a
harness bug -- a mis-sized buffer, a wrong level constant, a truncated write --
would otherwise look exactly like a compression result.

Python and Java bind the same underlying C libraries (zlib, libzstd, lz4,
snappy), so compressed output sizes should match closely. Small deviations are
expected where the two bindings choose different framing (for example zstd frame
headers with or without content-size fields); large ones indicate a bug.

Usage:
    ./crosscheck_ratios.py <corpus-dir> [stratum ...]
"""

import os
import struct
import sys
import zlib

try:
    import zstandard
except ImportError:
    zstandard = None
try:
    import lz4.block
except ImportError:
    lz4 = None
try:
    import snappy
except ImportError:
    snappy = None


def load(path):
    blobs = []
    with open(path, "rb") as fh:
        count = struct.unpack(">I", fh.read(4))[0]
        for _ in range(count):
            n = struct.unpack(">I", fh.read(4))[0]
            blobs.append(fh.read(n))
    return blobs


def total(fn, blobs):
    return sum(len(fn(b)) for b in blobs)


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(2)

    corpus_dir = sys.argv[1]
    strata = sys.argv[2:]
    if not strata:
        strata = sorted(f[:-4] for f in os.listdir(corpus_dir) if f.endswith(".bin"))

    print("Python ratio cross-check (Java harness is authoritative for speed)")
    print()

    for stratum in strata:
        path = os.path.join(corpus_dir, stratum + ".bin")
        if not os.path.isfile(path):
            continue
        blobs = load(path)
        if not blobs:
            continue
        logical = sum(len(b) for b in blobs)

        print(f"{stratum}: {len(blobs):,} chunks, {logical / 2**20:.1f} MiB logical")

        rows = []
        for level in (1, 6, 9):
            rows.append((f"deflate-{level}",
                         total(lambda b, lv=level: zlib.compress(b, lv), blobs)))

        if zstandard is not None:
            for level in (1, 3, 9, 19):
                # write_content_size=False keeps framing closest to zstd-jni's
                # raw byte-array API, which does not embed the original size.
                cctx = zstandard.ZstdCompressor(level=level, write_content_size=False)
                rows.append((f"zstd-{level}", total(cctx.compress, blobs)))

        if lz4 is not None:
            rows.append(("lz4", total(
                lambda b: lz4.block.compress(b, store_size=False), blobs)))
            rows.append(("lz4hc", total(
                lambda b: lz4.block.compress(b, mode="high_compression",
                                             store_size=False), blobs)))

        if snappy is not None:
            rows.append(("snappy", total(snappy.compress, blobs)))

        for name, compressed in rows:
            print(f"  {name:12s} {logical / compressed:6.2f}x  {compressed:>12,} bytes")
        print()


if __name__ == "__main__":
    main()
