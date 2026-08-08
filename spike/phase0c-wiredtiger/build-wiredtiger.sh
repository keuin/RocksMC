#!/usr/bin/env bash
#
# Build WiredTiger 11.3.1 with compression support and run the Phase 0c spike.
#
# The PyPI `wiredtiger` package is NOT sufficient. It is sdist-only, fails to
# compile on GCC 16, and even when coaxed into building it ships **no compressor
# extensions at all** -- producing a silently uncompressed database that measures
# 0.86x, i.e. larger than the input. See FINDINGS.md.
#
# Three workarounds are needed, all recorded here because they are themselves
# evidence for the packaging-burden argument against WiredTiger:
#
#   1. -Wno-error=incompatible-pointer-types  (SWIG wrapper const-correctness bug)
#   2. patch out -Werror in cmake/strict/strict_flags_helpers.cmake
#   3. -DENABLE_ZSTD=1 etc, then name each compressor .so in extensions=[...]
#
set -euo pipefail

WT_VERSION=11.3.1
WT_SDIST_URL="https://files.pythonhosted.org/packages/72/03/ee80bcb233d911af79fe6f67f6145541ce6b1f4cbfb88491b32dccaac4c0/wiredtiger-${WT_VERSION}.tar.gz"
WORK="${WORK:-/tmp/wtsrc}"
VENV="${VENV:-/tmp/wtvenv}"

for tool in cmake ninja swig gcc python3; do
	command -v "$tool" >/dev/null || { echo "missing required tool: $tool" >&2; exit 1; }
done

echo ">> Creating venv at $VENV (for cmake/ninja wheels)"
rm -rf "$VENV"
python3 -m venv "$VENV"
"$VENV/bin/pip" install -q --upgrade pip setuptools wheel
"$VENV/bin/pip" install -q cmake ninja

echo ">> Fetching WiredTiger $WT_VERSION source"
rm -rf "$WORK"
mkdir -p "$WORK"
cd "$WORK"
curl -sSL --max-time 600 -o wt.tar.gz "$WT_SDIST_URL"
tar xzf wt.tar.gz
cd "wiredtiger-${WT_VERSION}"

echo ">> Patch 1/1: removing -Werror (source predates GCC 16 diagnostics)"
sed -i 's/^\(\s*\)list(APPEND gnu_flags "-Werror")/\1# -Werror removed for Phase 0c: see build-wiredtiger.sh/' \
	cmake/strict/strict_flags_helpers.cmake

echo ">> Configuring with all compressors enabled"
mkdir -p build
cd build
export CFLAGS="-O2 -Wno-error=incompatible-pointer-types -Wno-incompatible-pointer-types"
export CXXFLAGS="-O2"
"$VENV/bin/cmake" -G Ninja \
	-DENABLE_ZSTD=1 \
	-DENABLE_SNAPPY=1 \
	-DENABLE_LZ4=1 \
	-DENABLE_ZLIB=1 \
	-DENABLE_PYTHON=1 \
	-DCMAKE_BUILD_TYPE=Release \
	-DCMAKE_C_FLAGS="$CFLAGS" \
	..

echo ">> Building"
ninja -j "$(nproc)"

echo ">> Verifying compressor extensions were actually built"
missing=0
for c in zstd snappy lz4 zlib; do
	so="ext/compressors/$c/libwiredtiger_$c.so"
	if [[ -f "$so" ]]; then
		echo "   ok      $so"
	else
		echo "   MISSING $so"
		missing=1
	fi
done
if [[ "$missing" == 1 ]]; then
	echo "!! Compressors missing -- the spike would silently measure an" >&2
	echo "!! uncompressed database. Aborting." >&2
	exit 1
fi

BUILD_DIR="$PWD"
echo
echo ">> Build complete: $BUILD_DIR"
echo
echo "Run the spike with:"
echo "  WT_BUILD=$BUILD_DIR PYTHONPATH=$BUILD_DIR/lang/python \\"
echo "    $VENV/bin/python wt_spike.py"
