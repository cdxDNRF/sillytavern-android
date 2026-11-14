#!/usr/bin/env bash
set -euo pipefail

# Self-written cloud cross-compilation script for embedding Node.js LTS as libnode.so
# targeting modern 64-bit Android (arm64-v8a, API 29+, 16 KB ELF page alignment, c++_shared STL).

NODE_VERSION="${NODE_VERSION:-v22.23.5}"
ANDROID_API="${ANDROID_API:-29}"
INTL_MODE="${INTL_MODE:-full-icu}"
DISABLE_SQLITE="${DISABLE_SQLITE:-0}"
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
WORK_DIR="${WORK_DIR:-$ROOT_DIR/.node-build-work}"
OUT_DIR="${OUT_DIR:-$ROOT_DIR/engine-ndk/dist}"

log() {
    echo "[build-libnode] $*"
}

resolve_ndk() {
    if [[ -n "${ANDROID_NDK_HOME:-}" && -d "${ANDROID_NDK_HOME}" ]]; then
        echo "$ANDROID_NDK_HOME"
        return 0
    fi
    local sdk_root="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
    if [[ -n "$sdk_root" && -d "$sdk_root/ndk/27.2.12479018" ]]; then
        echo "$sdk_root/ndk/27.2.12479018"
        return 0
    fi
    if [[ -n "${ANDROID_NDK_LATEST_HOME:-}" && -d "${ANDROID_NDK_LATEST_HOME}" ]]; then
        echo "$ANDROID_NDK_LATEST_HOME"
        return 0
    fi
    if [[ -n "$sdk_root" && -d "$sdk_root/ndk" ]]; then
        local latest
        latest="$(find "$sdk_root/ndk" -maxdepth 1 -mindepth 1 -type d | sort -V | tail -n 1 || true)"
        if [[ -n "$latest" ]]; then
            echo "$latest"
            return 0
        fi
    fi
    if [[ -n "$sdk_root" && -x "$sdk_root/cmdline-tools/latest/bin/sdkmanager" ]]; then
        log "Installing NDK 27.2.12479018 via sdkmanager..."
        (set +o pipefail; yes | "$sdk_root/cmdline-tools/latest/bin/sdkmanager" --install "ndk;27.2.12479018" >/dev/null)
        echo "$sdk_root/ndk/27.2.12479018"
        return 0
    fi
    return 1
}

NDK_HOME="$(resolve_ndk)" || {
    echo "ERROR: Could not locate Android NDK. Set ANDROID_NDK_HOME or ANDROID_SDK_ROOT." >&2
    exit 1
}

HOST_OS="$(uname -s | tr '[:upper:]' '[:lower:]')"
case "$HOST_OS" in
    linux) NDK_HOST_TAG="linux-x86_64" ;;
    darwin) NDK_HOST_TAG="darwin-x86_64" ;;
    *) echo "Unsupported host OS: $HOST_OS" >&2; exit 1 ;;
esac

TOOLCHAIN="$NDK_HOME/toolchains/llvm/prebuilt/$NDK_HOST_TAG"
if [[ ! -d "$TOOLCHAIN" ]]; then
    echo "ERROR: NDK LLVM toolchain not found at $TOOLCHAIN" >&2
    exit 1
fi

CC_TARGET="$TOOLCHAIN/bin/aarch64-linux-android${ANDROID_API}-clang"
CXX_TARGET="$TOOLCHAIN/bin/aarch64-linux-android${ANDROID_API}-clang++"
AR_TARGET="$TOOLCHAIN/bin/llvm-ar"
RANLIB_TARGET="$TOOLCHAIN/bin/llvm-ranlib"
STRIP_TARGET="$TOOLCHAIN/bin/llvm-strip"
READELF_TARGET="$TOOLCHAIN/bin/llvm-readelf"

for tool in "$CC_TARGET" "$CXX_TARGET" "$AR_TARGET" "$RANLIB_TARGET" "$STRIP_TARGET" "$READELF_TARGET"; do
    if [[ ! -x "$tool" ]]; then
        echo "ERROR: Required NDK tool missing: $tool" >&2
        exit 1
    fi
done

TARGET_CFLAGS="-fPIC"
TARGET_CXXFLAGS="-fPIC"
TARGET_LDFLAGS="-nostdlib++ -lc++_shared -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384"

log "Using NDK: $NDK_HOME (API $ANDROID_API, NODE $NODE_VERSION, intl=$INTL_MODE)"

# Fast preflight check: verify C++ shared STL linkage and 16KB ELF segment alignment
# before investing build time in Node/V8.
mkdir -p "$WORK_DIR" "$OUT_DIR"
PROBE_DIR="$WORK_DIR/preflight"
rm -rf "$PROBE_DIR"
mkdir -p "$PROBE_DIR"
cat > "$PROBE_DIR/probe.cc" <<'EOF'
#include <memory>
#include <string>
#include <vector>
extern "C" int st_ndk_probe(void) {
    auto v = std::make_shared<std::vector<std::string>>();
    v->emplace_back("sillytavern-android");
    return static_cast<int>(v->size());
}
EOF
"$CXX_TARGET" $TARGET_CXXFLAGS -std=gnu++17 -shared -Wl,-soname,libprobe.so \
    "$PROBE_DIR/probe.cc" $TARGET_LDFLAGS -o "$PROBE_DIR/libprobe.so"

PROBE_DYN="$("$READELF_TARGET" -d "$PROBE_DIR/libprobe.so")"
if ! grep -q "libc++_shared\.so" <<<"$PROBE_DYN"; then
    echo "ERROR: Preflight failed — target shared library did not link libc++_shared.so" >&2
    echo "$PROBE_DYN" >&2
    exit 1
fi
PROBE_SEG="$("$READELF_TARGET" -l "$PROBE_DIR/libprobe.so")"
if ! python3 -c '
import sys
loads = [l.split()[-1] for l in sys.stdin.read().splitlines() if l.strip().startswith("LOAD ")]
assert loads and all(int(a, 16) >= 0x4000 for a in loads), f"Bad LOAD alignments: {loads}"
' <<<"$PROBE_SEG"; then
    echo "ERROR: Preflight failed — ELF PT_LOAD alignment is below 16 KB (0x4000)" >&2
    echo "$PROBE_SEG" >&2
    exit 1
fi
log "Preflight check PASSED (libc++_shared.so + 16KB ELF alignment verified)."

SRC_ARCHIVE="$WORK_DIR/node-${NODE_VERSION}.tar.xz"
SHASUMS_FILE="$WORK_DIR/SHASUMS256.txt"
NODE_SRC_DIR="$WORK_DIR/node-${NODE_VERSION}"

if [[ ! -d "$NODE_SRC_DIR" ]]; then
    log "Downloading Node.js $NODE_VERSION source tarball & SHASUMS256..."
    curl -fsSL "https://nodejs.org/dist/${NODE_VERSION}/node-${NODE_VERSION}.tar.xz" -o "$SRC_ARCHIVE"
    curl -fsSL "https://nodejs.org/dist/${NODE_VERSION}/SHASUMS256.txt" -o "$SHASUMS_FILE"
    (
        cd "$WORK_DIR"
        grep " node-${NODE_VERSION}.tar.xz$" "$SHASUMS_FILE" | sha256sum -c -
    )
    log "Extracting Node.js source..."
    tar -xJf "$SRC_ARCHIVE" -C "$WORK_DIR"
fi

pushd "$NODE_SRC_DIR" >/dev/null

# Ensure V8 trap handler is disabled for Android cross-compilation across V8 revisions.
python3 - <<'PY'
from pathlib import Path
import re

trap_path = Path("deps/v8/src/trap-handler/trap-handler.h")
src = trap_path.read_text(encoding="utf-8")

# Replace the platform detection block cleanly so V8_TRAP_HANDLER_SUPPORTED is always false on Android.
pattern = re.compile(
    r"// X64 on Linux, Windows, MacOS, FreeBSD\.\s*\n#if V8_HOST_ARCH_X64.*?#define V8_TRAP_HANDLER_SUPPORTED false\s*\n#endif",
    re.DOTALL,
)
if pattern.search(src):
    patched = pattern.sub("#define V8_TRAP_HANDLER_SUPPORTED false", src, count=1)
    trap_path.write_text(patched, encoding="utf-8")
    print("[build-libnode] Patched deps/v8/src/trap-handler/trap-handler.h cleanly.")
elif "#define V8_TRAP_HANDLER_SUPPORTED false" in src and "#error \"The V8 trap handler should not be enabled on Android\"" in src:
    print("[build-libnode] trap-handler.h already patched.")
else:
    raise SystemExit("ERROR: Unexpected layout in deps/v8/src/trap-handler/trap-handler.h")
PY

export PATH="$TOOLCHAIN/bin:$PATH"
export CC="$CC_TARGET"
export CXX="$CXX_TARGET"
export AR="$AR_TARGET"
export RANLIB="$RANLIB_TARGET"
export LINK="$CXX_TARGET"
export CFLAGS="$TARGET_CFLAGS"
export CXXFLAGS="$TARGET_CXXFLAGS"
export LDFLAGS="$TARGET_LDFLAGS"

export CC_host="${CC_host:-gcc}"
export CXX_host="${CXX_host:-g++}"
export AR_host="${AR_host:-ar}"
export LINK_host="${LINK_host:-g++}"
export CFLAGS_host=""
export CXXFLAGS_host=""
export LDFLAGS_host=""

export GYP_DEFINES="target_arch=arm64 v8_target_arch=arm64 android_target_arch=arm64 host_os=$HOST_OS OS=android android_ndk_path=$NDK_HOME"

CONFIGURE_ARGS=(
    "--dest-cpu=arm64"
    "--dest-os=android"
    "--cross-compiling"
    "--shared"
    "--openssl-no-asm"
    "--without-npm"
    "--without-corepack"
    "--without-inspector"
    "--with-intl=${INTL_MODE}"
    "--prefix=/"
)
if [[ "$DISABLE_SQLITE" == "1" ]]; then
    CONFIGURE_ARGS+=("--without-sqlite")
fi

log "Configuring Node.js (${CONFIGURE_ARGS[*]})..."
python3 ./configure.py "${CONFIGURE_ARGS[@]}"

JOBS="$(nproc 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null || echo 4)"
log "Generating out/Makefile and building target libnode using $JOBS jobs..."
make out/Makefile
make -C out BUILDTYPE=Release -j"$JOBS" libnode

LIBNODE_CANDIDATES=(
    "out/Release/lib.target/libnode.so"
    "out/Release/libnode.so"
    "out/Release/lib/libnode.so"
)
BUILT_LIBNODE=""
for candidate in "${LIBNODE_CANDIDATES[@]}"; do
    if [[ -f "$candidate" ]]; then
        BUILT_LIBNODE="$candidate"
        break
    fi
done
if [[ -z "$BUILT_LIBNODE" ]]; then
    BUILT_LIBNODE="$(find out/Release -name "libnode.so" -type f | head -n 1 || true)"
fi
if [[ -z "$BUILT_LIBNODE" || ! -f "$BUILT_LIBNODE" ]]; then
    echo "ERROR: libnode.so not found under out/Release after build!" >&2
    exit 1
fi
log "Built $BUILT_LIBNODE ($(du -h "$BUILT_LIBNODE" | cut -f1))"

STAGE_DIR="$WORK_DIR/stage"
rm -rf "$STAGE_DIR"
mkdir -p "$STAGE_DIR/lib/arm64-v8a" "$STAGE_DIR/include"

cp "$BUILT_LIBNODE" "$STAGE_DIR/lib/arm64-v8a/libnode.so"
"$STRIP_TARGET" --strip-unneeded "$STAGE_DIR/lib/arm64-v8a/libnode.so"

# Copy matching libc++_shared.so from the exact same NDK used to link libnode.so
LIBCXX_SHARED="$TOOLCHAIN/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so"
if [[ ! -f "$LIBCXX_SHARED" ]]; then
    echo "ERROR: libc++_shared.so not found at $LIBCXX_SHARED" >&2
    exit 1
fi
cp "$LIBCXX_SHARED" "$STAGE_DIR/lib/arm64-v8a/libc++_shared.so"

log "Installing Node/V8/libuv/OpenSSL headers into staging dir..."
python3 tools/install.py install --headers-only --dest-dir "$STAGE_DIR" --prefix /

log "Verifying built libnode.so ELF headers (SONAME, libc++_shared, 16KB page align)..."
NODE_DYN="$("$READELF_TARGET" -d "$STAGE_DIR/lib/arm64-v8a/libnode.so")"
grep -E "SONAME|NEEDED" <<<"$NODE_DYN" || true
if ! grep -q "SONAME.*\[libnode\.so\]" <<<"$NODE_DYN"; then
    echo "ERROR: libnode.so SONAME must be unversioned libnode.so" >&2
    exit 1
fi
if ! grep -q "libc++_shared\.so" <<<"$NODE_DYN"; then
    echo "ERROR: libnode.so must dynamically depend on libc++_shared.so" >&2
    exit 1
fi
NODE_SEG="$("$READELF_TARGET" -l "$STAGE_DIR/lib/arm64-v8a/libnode.so")"
grep -E "LOAD|Align" <<<"$NODE_SEG" || true
if ! python3 -c '
import sys
loads = [l.split()[-1] for l in sys.stdin.read().splitlines() if l.strip().startswith("LOAD ")]
assert loads and all(int(a, 16) >= 0x4000 for a in loads), f"Bad LOAD alignments: {loads}"
' <<<"$NODE_SEG"; then
    echo "ERROR: libnode.so ELF PT_LOAD alignment is below 16 KB (0x4000)" >&2
    exit 1
fi

cat > "$STAGE_DIR/BUILD_INFO.txt" <<EOF
node_version=$NODE_VERSION
android_api=$ANDROID_API
abi=arm64-v8a
intl_mode=$INTL_MODE
ndk_home=$NDK_HOME
built_at=$(date -u +"%Y-%m-%dT%H:%M:%SZ")
EOF

ARCHIVE_NAME="libnode-android-arm64.tar.gz"
tar -C "$STAGE_DIR" -czf "$OUT_DIR/$ARCHIVE_NAME" lib include BUILD_INFO.txt
(
    cd "$OUT_DIR"
    sha256sum "$ARCHIVE_NAME" > "${ARCHIVE_NAME}.sha256"
)

popd >/dev/null
log "Finished! Output archive: $OUT_DIR/$ARCHIVE_NAME ($(du -h "$OUT_DIR/$ARCHIVE_NAME" | cut -f1))"
