#!/usr/bin/env python3
"""
Phase 1a: re-test the RocksDB configuration against REAL chunk NBT.

WHY THIS EXISTS
---------------
Every earlier measurement in this project used a synthetic corpus of 8 KiB
values, chosen because RegionFile.ChunkBuffer allocates 8096 bytes initially,
which looked like Mojang's own size expectation.

Reading a real generated world with tools/mca_stats.py showed that assumption is
wrong in an important way:

    compressed  (as Anvil stores it):   mean  3.5 KiB, p50  3.2 KiB
    UNCOMPRESSED (as we would store):   mean 51.0 KiB, p50 46.4 KiB
    vanilla DEFLATE ratio:              14.56x

So the 8096-byte hint tracks the *compressed* size. Uncompressed chunk NBT is
roughly 6x larger than the synthetic corpus, and vanilla's DEFLATE achieves a far
better ratio (14.56x) than the synthetic data suggested (4.76x). Chunk NBT is
much more redundant than modelled: mostly long runs in light arrays, near-uniform
biome arrays, and repeated palette strings.

Two design decisions depended on the old number and must be rechecked:

  1. min_blob_size. At 1 KiB, 51 KiB values certainly land in blob files. But
     does that remain the right choice when values are this large and this
     compressible?
  2. The plan stores NBT *uncompressed* and lets RocksDB compress. With real
     values, does ZSTD on 51 KiB blobs actually beat vanilla's 14.56x DEFLATE?
     If not, the whole compression rationale collapses further.

This harness answers both with real bytes, extracted straight from .mca files.

Usage:
    ./phase1a_real_corpus.py <world-dir> [--limit N]
"""

import os
import shutil
import struct
import sys
import tempfile
import zlib

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "tools"))

SECTOR = 4096


def load_real_chunks(world_dir, limit=None):
    """Extract decompressed chunk NBT blobs from every .mca under world_dir."""
    blobs = []
    for dirpath, _, filenames in os.walk(world_dir):
        for fn in sorted(f for f in filenames if f.endswith(".mca")):
            path = os.path.join(dirpath, fn)
            if os.path.getsize(path) < SECTOR * 2:
                continue
            with open(path, "rb") as fh:
                header = fh.read(SECTOR)
                for i in range(1024):
                    packed = struct.unpack_from(">I", header, i * 4)[0]
                    if packed == 0:
                        continue
                    offset, sectors = packed >> 8, packed & 0xFF
                    if offset < 2 or sectors == 0:
                        continue
                    fh.seek(offset * SECTOR)
                    head = fh.read(5)
                    if len(head) < 5:
                        continue
                    declared, scheme = struct.unpack(">IB", head)
                    if scheme & 0x80 or declared <= 1:
                        continue
                    payload = fh.read(declared - 1)
                    try:
                        if (scheme & 0x7F) == 2:
                            blobs.append(zlib.decompress(payload))
                        elif (scheme & 0x7F) == 1:
                            blobs.append(zlib.decompress(payload, 16 + zlib.MAX_WBITS))
                        elif (scheme & 0x7F) == 3:
                            blobs.append(payload)
                    except zlib.error:
                        pass
                    if limit and len(blobs) >= limit:
                        return blobs
    return blobs


def human(n):
    for unit in ("B", "KiB", "MiB", "GiB"):
        if abs(n) < 1024 or unit == "GiB":
            return f"{n:.1f} {unit}" if unit != "B" else f"{int(n)} B"
        n /= 1024.0


def vanilla_deflate_size(blobs):
    """What vanilla Anvil would store: per-chunk DEFLATE, no shared context."""
    return sum(len(zlib.compress(b, 6)) for b in blobs)


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    limit = None
    for a in sys.argv[1:]:
        if a.startswith("--limit"):
            limit = int(a.split("=", 1)[1]) if "=" in a else None
    if not args:
        print(__doc__)
        sys.exit(2)

    print("Loading real chunk NBT from", args[0])
    blobs = load_real_chunks(args[0], limit)
    if not blobs:
        print("No chunks found.")
        sys.exit(1)

    logical = sum(len(b) for b in blobs)
    sizes = sorted(len(b) for b in blobs)
    print(f"chunks: {len(blobs)}")
    print(f"uncompressed total: {human(logical)}  "
          f"mean {human(logical / len(blobs))}  p50 {human(sizes[len(sizes) // 2])}")

    # Baseline: what vanilla actually achieves today.
    van = vanilla_deflate_size(blobs)
    print(f"\nvanilla per-chunk DEFLATE: {human(van)}  ratio {logical / van:.2f}x")

    try:
        import rocksdb_shim  # noqa: F401
    except ImportError:
        pass

    print("\nNOTE: RocksDB comparison runs in the Java harness "
          "(spike/phase1a-real-corpus/RealCorpusSpike.java);")
    print("      this script establishes the vanilla baseline and dumps the "
          "corpus for it.")

    # Dump the corpus so the Java harness measures byte-identical data.
    out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "corpus.bin")
    with open(out, "wb") as fh:
        fh.write(struct.pack(">I", len(blobs)))
        for b in blobs:
            fh.write(struct.pack(">I", len(b)))
            fh.write(b)
    print(f"\nwrote corpus: {out} ({human(os.path.getsize(out))})")
    print(f"  format: uint32 count, then count x (uint32 len, len bytes)")


if __name__ == "__main__":
    main()
