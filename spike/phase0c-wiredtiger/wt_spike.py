#!/usr/bin/env python3
"""
Phase 0c: does WiredTiger beat RocksDB+BlobDB for Minecraft chunk storage?

WHY THIS EXISTS
---------------
Phase 0 killed one of the two arguments against WiredTiger. The original claim
was "WiredTiger probably lacks ZSTD trained-dictionary support, so it forfeits
the cross-chunk compression win" -- but measurement showed RocksDB's *blob
files ignore dictionaries too*. That objection applies equally to both engines
and is therefore no longer a discriminator.

What remains in RocksDB's favour is non-technical (maintained Maven artifact
with prebuilt natives; Apache-2.0 licence) and one technical point that this
spike tests: a B-tree updates pages in place, so whole-value overwrites of
~8 KiB chunk blobs may cost more bytes written than BlobDB's key-value
separation, which measured only 233 KB of compaction traffic.

DECISION RULE (fixed BEFORE looking at any results, so the conclusion is not
fitted to the data):

    WiredTiger wins only if it beats RocksDB on compression ratio AND stays
    within ~2x on bytes written. Anything else, and the GPL licence plus
    build-from-source packaging burden settle the question.

COMPARABILITY
-------------
Corpus generation mirrors BlobDictSpike.java / WriteAmpSpike.java exactly:
same seed, same value size, same count, same tailFraction, same structure
(shared-vocabulary palette strings, run-heavy light analogue, near-uniform
biome analogue, unique incompressible tail).

Java's Random is a specified 48-bit LCG, reimplemented here bit-exactly so the
two harnesses see byte-identical corpora. Without this, any ratio difference
could just be a different random stream.

RocksDB reference numbers (rocksdbjni 10.10.1, recorded in
../phase0-blob-dict/FINDINGS.md):
    ratio test  (4000 x 8 KiB, tail=0.15): on-disk 6,799,207 blob + 87,625 sst
    write test  (8000 x 8 KiB, 12 rounds): compact 233,194   on-disk 11,180,892
"""

import os
import shutil
import struct
import sys
import tempfile

import wiredtiger

VALUE_SIZE = 8192
SEED = 20260809
TAIL_FRACTION = 0.15

PALETTE = [
    "minecraft:stone", "minecraft:granite", "minecraft:diorite", "minecraft:andesite",
    "minecraft:dirt", "minecraft:grass_block", "minecraft:sand", "minecraft:gravel",
    "minecraft:water", "minecraft:lava", "minecraft:bedrock", "minecraft:deepslate",
    "minecraft:oak_log", "minecraft:oak_leaves", "minecraft:birch_log", "minecraft:cobblestone",
    "minecraft:coal_ore", "minecraft:iron_ore", "minecraft:gold_ore", "minecraft:diamond_ore",
    "minecraft:redstone_ore", "minecraft:lapis_ore", "minecraft:emerald_ore", "minecraft:copper_ore",
]

PROPERTY_KV = [
    "axis=y", "axis=x", "axis=z", "waterlogged=false", "waterlogged=true",
    "snowy=false", "persistent=false", "distance=7", "level=0", "facing=north",
]


class JavaRandom:
    """Bit-exact java.util.Random: a specified 48-bit LCG, so both harnesses
    generate byte-identical corpora from the same seed."""

    _MULT = 0x5DEECE66D
    _ADD = 0xB
    _MASK = (1 << 48) - 1

    def __init__(self, seed):
        self._seed = (seed ^ self._MULT) & self._MASK

    def _next(self, bits):
        self._seed = (self._seed * self._MULT + self._ADD) & self._MASK
        return self._seed >> (48 - bits)

    def next_int(self, bound):
        if bound <= 0:
            raise ValueError("bound must be positive")
        # Power of two: Java takes the high bits directly.
        if (bound & -bound) == bound:
            return (bound * self._next(31)) >> 31
        while True:
            bits = self._next(31)
            val = bits % bound
            # Reject values that would make the distribution non-uniform.
            if bits - val + (bound - 1) < (1 << 31):
                return val


def build_corpus(count, tail_fraction=TAIL_FRACTION):
    """Synthesise chunk-like payloads. Mirrors buildCorpus() in the Java spikes."""
    rnd = JavaRandom(SEED)
    tail_bytes = int(VALUE_SIZE * tail_fraction)
    structured_limit = VALUE_SIZE - tail_bytes
    out = []

    for _ in range(count):
        buf = bytearray(VALUE_SIZE)
        p = 0

        # --- palette: strings repeated verbatim across every value ---
        palette_entries = 24 + rnd.next_int(12)
        for _ in range(palette_entries):
            if p >= structured_limit - 64:
                break
            for s in ("Name", PALETTE[rnd.next_int(len(PALETTE))]):
                for ch in s:
                    if p < structured_limit:
                        buf[p] = ord(ch)
                        p += 1
            if rnd.next_int(3) == 0:
                for s in ("Properties", PROPERTY_KV[rnd.next_int(len(PROPERTY_KV))]):
                    for ch in s:
                        if p < structured_limit:
                            buf[p] = ord(ch)
                            p += 1

        # --- light-array analogue: long runs of one nibble pattern ---
        light_budget = int((structured_limit - p) * 0.6)
        for j in range(light_budget):
            if p >= structured_limit:
                break
            buf[p] = rnd.next_int(256) if j % 97 == 0 else 0xFF
            p += 1

        # --- biome-array analogue: near-uniform ---
        biome = 1 + rnd.next_int(4)
        while p < structured_limit:
            buf[p] = biome
            p += 1

        # --- unique tail: incompressible, stands in for entity data ---
        while p < VALUE_SIZE:
            buf[p] = rnd.next_int(256)
            p += 1

        out.append(bytes(buf))
    return out


def key_for(i):
    """12-byte key, loosely mirroring (dimension, morton) chunk addressing."""
    return b"\x00\x00\x00\x00" + struct.pack(">q", i)


def mutate(val, rnd):
    """Return a variant of a corpus value with a freshly randomised tail.

    The Java harness calls value(rnd) for every put, so each overwrite round
    writes different bytes. Rewriting a byte-identical value every round would
    let any dedup or unchanged-page optimisation understate bytes written.
    Only the incompressible tail changes, preserving the compression profile.
    """
    tail_bytes = int(VALUE_SIZE * TAIL_FRACTION)
    head = val[: VALUE_SIZE - tail_bytes]
    tail = bytes(rnd.next_int(256) for _ in range(tail_bytes))
    return head + tail


# WiredTiger loads compressors as runtime shared-object extensions rather than
# compiling them into the core library. WT_BUILD must point at a build tree that
# was configured with -DENABLE_ZSTD=1 etc.; otherwise `block_compressor=zstd`
# fails with "unknown compressor".
WT_BUILD = os.environ.get("WT_BUILD", "")


def extensions_config():
    """Build the extensions=[...] clause for wiredtiger_open, naming whichever
    compressor extensions actually exist in the build tree."""
    if not WT_BUILD:
        return ""
    paths = []
    for name in ("zstd", "snappy", "lz4", "zlib"):
        so = os.path.join(WT_BUILD, "ext", "compressors", name,
                          f"libwiredtiger_{name}.so")
        if os.path.exists(so):
            paths.append(f'"{so}"')
    if not paths:
        return ""
    return ",extensions=[" + ",".join(paths) + "]"


def dir_size(path, suffix=None):
    total = 0
    for root, _, files in os.walk(path):
        for f in files:
            if suffix and not f.endswith(suffix):
                continue
            try:
                total += os.path.getsize(os.path.join(root, f))
            except OSError:
                pass
    return total


def table_size(path):
    """On-disk bytes for the data table only, excluding WAL and metadata, to
    match how the RocksDB harness summed .sst + .blob."""
    f = os.path.join(path, "chunks.wt")
    return os.path.getsize(f) if os.path.exists(f) else 0


def run(label, corpus, table_config, rounds=1, home=None, fresh_each_round=False,
        checkpoint_each_round=False):
    home = home or tempfile.mkdtemp(prefix="wt-")
    conn = None
    try:
        # log=(enabled=false): WAL disabled, matching disableWAL on the RocksDB side
        # so we measure stored form rather than log traffic.
        #
        # extensions=[...]: WiredTiger compressors are *runtime-loaded shared
        # objects*, not built into the core library, and must be named explicitly.
        # This is why the PyPI wheel silently supports no compression at all --
        # it ships none of these extensions. See FINDINGS.md.
        conn = wiredtiger.wiredtiger_open(
            home,
            "create,cache_size=512M,log=(enabled=false),statistics=(all)"
            + extensions_config(),
        )
        session = conn.open_session()
        session.create("table:chunks", table_config)

        cursor = session.open_cursor("table:chunks", None)
        # A fresh JavaRandom continues the corpus stream when fresh_each_round is
        # set, so each round writes genuinely different data. The Java harness
        # calls value(rnd) per put with a shared Random, so rewriting the
        # identical corpus every round would let dedup/no-op-write optimisations
        # flatter WiredTiger unfairly.
        rnd = JavaRandom(SEED + 1)
        for _ in range(rounds):
            for i, val in enumerate(corpus):
                cursor.set_key(key_for(i))
                cursor.set_value(mutate(val, rnd) if fresh_each_round else val)
                cursor.insert()
            if checkpoint_each_round:
                # Match RocksDB flushing once per round: without this, a 512 MB
                # cache absorbs all 12 rounds and only the final state is ever
                # written to disk.
                session.checkpoint()
        cursor.close()

        # Force everything to disk so the size measurement is meaningful.
        session.checkpoint()
        session.close()

        stats = read_stats(conn)
        conn.close()
        conn = None

        tbl = table_size(home)
        total = dir_size(home)
        print(f"{label:52s} table={tbl:<12d} totalDir={total:<12d} "
              f"written={stats.get('bytes written', 0)}")
        return {"label": label, "table": tbl, "total": total, "stats": stats}
    finally:
        if conn is not None:
            try:
                conn.close()
            except Exception:
                pass
        shutil.rmtree(home, ignore_errors=True)


def read_stats(conn):
    """Pull the connection-level statistics cursor into a dict."""
    out = {}
    try:
        session = conn.open_session()
        cur = session.open_cursor("statistics:", None, None)
        while cur.next() == 0:
            desc = cur.get_value()
            # (desc, printable_value, numeric_value)
            if isinstance(desc, (list, tuple)) and len(desc) >= 3:
                name = desc[0]
                if ":" in name:
                    name = name.split(":", 1)[1].strip()
                out[name] = desc[2]
        cur.close()
        session.close()
    except Exception as exc:
        print(f"  (statistics unavailable: {exc})", file=sys.stderr)
    return out


def main():
    print(f"WiredTiger {wiredtiger.wiredtiger_version()[0]}")
    print(f"corpus: value_size={VALUE_SIZE} tail_fraction={TAIL_FRACTION} seed={SEED}")
    print()

    # ---------------- Experiment 1: compression ratio ----------------
    print("=== Experiment 1: compression ratio (4000 x 8 KiB, 1 round) ===")
    corpus = build_corpus(4000)
    logical = len(corpus) * VALUE_SIZE
    print(f"logical: {logical} bytes ({logical / 1048576:.1f} MiB)")

    configs = [
        ("A  zstd, no dict",
         "key_format=u,value_format=u,block_compressor=zstd"),
        ("B  zstd + dictionary=1000 (page-level dedup)",
         "key_format=u,value_format=u,block_compressor=zstd,dictionary=1000"),
        ("C  zstd + prefix_compression",
         "key_format=u,value_format=u,block_compressor=zstd,prefix_compression=true"),
        ("D  zstd, leaf_page_max=32KB (wider scope)",
         "key_format=u,value_format=u,block_compressor=zstd,leaf_page_max=32KB"),
        ("E  zstd, leaf_page_max=128KB (widest scope)",
         "key_format=u,value_format=u,block_compressor=zstd,leaf_page_max=128KB"),
        ("F  no compression (baseline)",
         "key_format=u,value_format=u"),
    ]

    results = []
    for label, cfg in configs:
        try:
            results.append(run(label, corpus, cfg))
        except Exception as exc:
            print(f"{label:52s} FAILED: {exc}")

    print()
    print(f"{'config':52s} {'table bytes':>13s} {'ratio':>9s}")
    for r in results:
        ratio = logical / r["table"] if r["table"] else 0
        print(f"{r['label']:52s} {r['table']:>13d} {ratio:>8.2f}x")

    best = max((r for r in results if r["table"]), key=lambda r: logical / r["table"], default=None)

    # RocksDB reference: blob 6,799,207 + sst 87,625 for the same corpus shape.
    rocks_ratio_bytes = 6_799_207 + 87_625
    print()
    print(f"RocksDB+BlobDB reference (same corpus): {rocks_ratio_bytes} bytes "
          f"= {logical / rocks_ratio_bytes:.2f}x")
    if best:
        print(f"WiredTiger best:                        {best['table']} bytes "
              f"= {logical / best['table']:.2f}x  [{best['label'].strip()}]")
        delta = (best["table"] - rocks_ratio_bytes) * 100.0 / rocks_ratio_bytes
        print(f"WiredTiger vs RocksDB on size: {delta:+.1f}%")

    # ---------------- Experiment 2: bytes written under overwrite ----------------
    print()
    print("=== Experiment 2: bytes written (8000 x 8 KiB, 12 overwrite rounds) ===")
    corpus2 = build_corpus(8000)
    logical2 = len(corpus2) * VALUE_SIZE * 12
    print(f"logical writes: {logical2} bytes ({logical2 / 1048576:.1f} MiB)")
    print("each round writes freshly randomised tails; checkpoint per round to")
    print("match RocksDB flushing per round")

    w = run("zstd, 12 overwrite rounds", corpus2,
            "key_format=u,value_format=u,block_compressor=zstd", rounds=12,
            fresh_each_round=True, checkpoint_each_round=True)

    written = w["stats"].get("bytes written", 0)

    # FAIR COMPARISON. RocksDB's 233,194 figure is COMPACTION ONLY; its
    # comparable total is flush + compact = 134,347,024 + 233,194. Comparing
    # WiredTiger's total against RocksDB's compaction-only number would
    # exaggerate the gap by more than two orders of magnitude.
    rocks_flush = 134_347_024
    rocks_compact = 233_194
    rocks_total = rocks_flush + rocks_compact

    print()
    print("RocksDB+BlobDB reference (same logical volume):")
    print(f"  flush   {rocks_flush:>12d}")
    print(f"  compact {rocks_compact:>12d}   <- the number NOT to compare against alone")
    print(f"  TOTAL   {rocks_total:>12d}")
    print(f"  on-disk {11_180_892:>12d}")
    print()
    print("WiredTiger:")
    print(f"  written {written:>12d}")
    print(f"  on-disk {w['table']:>12d}")

    print()
    print("=" * 62)
    print("PRE-REGISTERED DECISION RULE")
    print("  WiredTiger wins ONLY IF better ratio AND within ~2x bytes written.")
    print("=" * 62)

    ratio_win = False
    if best:
        ratio_win = (logical / best["table"]) > (logical / rocks_ratio_bytes)
        print(f"  ratio:         WT {logical / best['table']:.2f}x vs "
              f"RocksDB {logical / rocks_ratio_bytes:.2f}x  -> "
              f"{'WT WINS' if ratio_win else 'RocksDB wins'}")

    write_factor = written / rocks_total if rocks_total else 0
    within_2x = write_factor <= 2.0
    print(f"  bytes written: WT / RocksDB = {write_factor:.2f}x  -> "
          f"{'within 2x' if within_2x else 'EXCEEDS 2x'}")

    print()
    if ratio_win and within_2x:
        print("  VERDICT: WiredTiger passes the rule on technical merit.")
        print("           Remaining objections are non-technical: GPL-only licence,")
        print("           no prebuilt Java artifact, build-from-source burden.")
    else:
        print("  VERDICT: WiredTiger fails the rule. Question settled.")


if __name__ == "__main__":
    main()
