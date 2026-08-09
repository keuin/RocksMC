#!/usr/bin/env bash
#
# Phase 1c: steady-state write amplification measurement for flash endurance.
#
# Self-contained. Assumes nothing is pre-installed except a JDK, Python 3 and
# curl. Downloads its own RocksDB jar, builds the harness, checks that the
# environment can actually produce valid measurements, and writes results to a
# JSON file you can send back verbatim.
#
# WHAT THIS FIXES
#   Every earlier write measurement in this project is invalid for endurance:
#   the test database never reached LSM level 1 (so leveled compaction was never
#   exercised), the write-ahead log was disabled (though it is written on every
#   put), and the arithmetic derived from it was wrong by 15x. This harness
#   forces real LSM depth, counts WAL bytes, and cross-checks RocksDB's counters
#   against the kernel's own view via /proc/self/io.
#
# USAGE
#   ./run_endurance.sh --world /path/to/minecraft/world --work /path/on/ssd
#   ./run_endurance.sh --world /path/to/world --work /data/bench --quick
#
# COST
#   --quick  ~15-20 minutes, writes a few GB.  Use this first to validate setup.
#   full     ~4 hours, writes on the order of tens to low hundreds of GB.
#
#   That is a rounding error against a 150-600 TB drive rating, but it is real
#   wear. Choose the target device deliberately.
#
set -euo pipefail

ROCKSDB_VERSION="10.10.1"
ROCKSDB_SHA256="" # verified against Maven Central's published .sha1 at runtime
MAVEN_BASE="https://repo1.maven.org/maven2/org/rocksdb/rocksdbjni"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORLD=""
WORK=""
QUICK=0
PER_STRATUM=8000

die() { printf '\nERROR: %s\n' "$*" >&2; exit 1; }
note() { printf '  %s\n' "$*"; }
step() { printf '\n== %s\n' "$*"; }

usage() {
	sed -n '2,30p' "${BASH_SOURCE[0]}" | sed 's/^#//; s/^ //'
	exit 0
}

while [[ $# -gt 0 ]]; do
	case "$1" in
		--world) WORLD="${2:-}"; shift 2 ;;
		--work) WORK="${2:-}"; shift 2 ;;
		--per-stratum) PER_STRATUM="${2:-}"; shift 2 ;;
		--quick) QUICK=1; shift ;;
		-h|--help) usage ;;
		*) die "unknown argument: $1 (try --help)" ;;
	esac
done

# ---------------------------------------------------------------- preflight

step "Preflight checks"

[[ -n "$WORLD" ]] || die "--world is required.

This experiment refuses to run on synthetic data. Synthetic corpora have
already produced two wrong conclusions in this project: they were 6x off on
chunk size and materially wrong about compressibility. Point --world at any
Minecraft 1.16.5 world directory (one containing region/*.mca)."

[[ -d "$WORLD" ]] || die "--world is not a directory: $WORLD"

if ! compgen -G "$WORLD/**/*.mca" >/dev/null 2>&1 && ! compgen -G "$WORLD/region/*.mca" >/dev/null 2>&1; then
	die "no .mca region files found under $WORLD
Expected a world directory containing region/ with .mca files."
fi
note "world: $WORLD"

[[ -n "$WORK" ]] || die "--work is required (a directory on the SSD to benchmark)"
mkdir -p "$WORK" || die "cannot create work directory: $WORK"
WORK="$(cd "$WORK" && pwd)"
note "work: $WORK"

# The whole point is measuring writes to a real device. On tmpfs, /proc/self/io
# reports memory traffic and every number would be meaningless.
FSTYPE="$(stat -f -c '%T' "$WORK" 2>/dev/null || echo unknown)"
note "filesystem: $FSTYPE"
case "$FSTYPE" in
	tmpfs|ramfs)
		die "work directory is on $FSTYPE (RAM-backed).

Writes would never reach a device, so /proc/self/io would measure memory
traffic and the entire measurement would be void. Choose a directory on the
SSD you want to characterise."
		;;
	btrfs|zfs)
		note "NOTE: $FSTYPE is copy-on-write. Its own metadata and checksum writes"
		note "      are included in /proc/self/io, so the kernel figure will exceed"
		note "      RocksDB's counters by more than it would on ext4/xfs. Both are"
		note "      reported separately."
		;;
esac

# Space: fill + measure + corpus, with headroom for compaction transients.
AVAIL_KB="$(df -Pk "$WORK" | awk 'NR==2 {print $4}')"
NEED_GB=$(( QUICK == 1 ? 8 : 24 ))
AVAIL_GB=$(( AVAIL_KB / 1024 / 1024 ))
note "free space: ${AVAIL_GB} GB (need ~${NEED_GB} GB)"
[[ "$AVAIL_GB" -ge "$NEED_GB" ]] || die "not enough free space in $WORK"

# JDK: honour JAVA_HOME, then PATH, then the usual install locations.
JAVA_BIN=""
if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" ]]; then
	JAVA_BIN="$JAVA_HOME/bin/java"
elif command -v java >/dev/null 2>&1; then
	JAVA_BIN="$(command -v java)"
else
	for candidate in /usr/lib/jvm/*/bin/java /usr/java/*/bin/java \
			/Library/Java/JavaVirtualMachines/*/Contents/Home/bin/java; do
		[[ -x "$candidate" ]] && { JAVA_BIN="$candidate"; break; }
	done
fi
[[ -n "$JAVA_BIN" ]] || die "no JDK found. Install Java 11+ or set JAVA_HOME."

JAVAC_BIN="${JAVA_BIN%/java}/javac"
[[ -x "$JAVAC_BIN" ]] || die "found a JRE but not a JDK at ${JAVA_BIN%/java}
javac is required to build the harness. Install a full JDK."

JAVA_VER="$("$JAVA_BIN" -version 2>&1 | head -1 | grep -oE '[0-9]+' | head -1)"
[[ "$JAVA_VER" -ge 11 ]] || die "Java 11+ required, found version $JAVA_VER"
note "java: $JAVA_BIN (version $JAVA_VER)"

command -v python3 >/dev/null 2>&1 || die "python3 is required (corpus extraction)"
note "python3: $(command -v python3)"

command -v curl >/dev/null 2>&1 || command -v wget >/dev/null 2>&1 \
	|| die "curl or wget is required (to fetch the RocksDB jar)"

if [[ ! -r /proc/self/io ]]; then
	note "WARNING: /proc/self/io unavailable (non-Linux?). The kernel cross-check"
	note "         will be skipped and only RocksDB's own counters reported."
fi

# ---------------------------------------------------------------- fetch jar

step "Fetching RocksDB $ROCKSDB_VERSION"
JAR="$SCRIPT_DIR/rocksdbjni-$ROCKSDB_VERSION.jar"
if [[ -f "$JAR" ]]; then
	note "already present: $JAR"
else
	URL="$MAVEN_BASE/$ROCKSDB_VERSION/rocksdbjni-$ROCKSDB_VERSION.jar"
	note "downloading $URL"
	if command -v curl >/dev/null 2>&1; then
		curl -fsSL --retry 3 -o "$JAR.tmp" "$URL" || die "download failed"
		curl -fsSL --retry 3 -o "$JAR.sha1" "$URL.sha1" || true
	else
		wget -q -O "$JAR.tmp" "$URL" || die "download failed"
		wget -q -O "$JAR.sha1" "$URL.sha1" || true
	fi
	if [[ -s "$JAR.sha1" ]] && command -v sha1sum >/dev/null 2>&1; then
		EXPECTED="$(tr -d ' \n\r' < "$JAR.sha1" | cut -c1-40)"
		ACTUAL="$(sha1sum "$JAR.tmp" | cut -d' ' -f1)"
		[[ "$EXPECTED" == "$ACTUAL" ]] \
			|| die "checksum mismatch: expected $EXPECTED, got $ACTUAL"
		note "sha1 verified"
	else
		note "WARNING: could not verify checksum"
	fi
	mv "$JAR.tmp" "$JAR"
	rm -f "$JAR.sha1"
fi

# ---------------------------------------------------------------- corpus

step "Extracting chunk corpus from world"
CORPUS="$WORK/corpus"
if [[ -d "$CORPUS" ]] && compgen -G "$CORPUS/*.bin" >/dev/null 2>&1; then
	note "reusing existing corpus in $CORPUS"
else
	mkdir -p "$CORPUS"
	python3 "$SCRIPT_DIR/extract_strata.py" "$WORLD" "$CORPUS" \
		--per-stratum "$PER_STRATUM" || die "corpus extraction failed"
fi

CORPUS_BYTES="$(du -sb "$CORPUS" 2>/dev/null | cut -f1 || echo 0)"
[[ "$CORPUS_BYTES" -gt 1000000 ]] \
	|| die "corpus is suspiciously small ($CORPUS_BYTES bytes). Is the world empty?"
note "corpus: $(( CORPUS_BYTES / 1024 / 1024 )) MB across $(ls "$CORPUS"/*.bin | wc -l) strata"

# ---------------------------------------------------------------- build

step "Building harness"
OUT="$SCRIPT_DIR/out"
mkdir -p "$OUT"
"$JAVAC_BIN" -cp "$JAR" -d "$OUT" "$SCRIPT_DIR/EnduranceSweep.java" \
	|| die "compilation failed"
note "built into $OUT"

# ---------------------------------------------------------------- run

step "Running measurement"
STAMP="$(date +%Y%m%d-%H%M%S)"
RESULTS="$SCRIPT_DIR/results-$STAMP.json"
LOG="$SCRIPT_DIR/results-$STAMP.log"

if [[ "$QUICK" == 1 ]]; then
	note "QUICK mode: validation only, results are NOT publishable"
	QUICK_FLAG="--quick"
else
	note "FULL mode: expect roughly 4 hours"
	QUICK_FLAG=""
fi
note "log:     $LOG"
note "results: $RESULTS"
echo

DB_ROOT="$WORK/db"
mkdir -p "$DB_ROOT"

set +e
"$JAVA_BIN" -Xmx4G \
	-cp "$OUT:$JAR" EnduranceSweep \
	--corpus "$CORPUS" \
	--db-root "$DB_ROOT" \
	--out "$RESULTS" \
	$QUICK_FLAG 2>&1 | tee "$LOG"
STATUS="${PIPESTATUS[0]}"
set -e

rm -rf "$DB_ROOT"

echo
if [[ "$STATUS" -ne 0 ]]; then
	die "measurement failed (exit $STATUS). See $LOG"
fi

step "Done"
note "Send back these two files:"
note "  $RESULTS"
note "  $LOG"
if [[ "$QUICK" == 1 ]]; then
	echo
	note "This was a QUICK run. Re-run without --quick for real numbers."
fi
