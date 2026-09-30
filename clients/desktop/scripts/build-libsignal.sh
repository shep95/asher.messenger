#!/usr/bin/env bash
# Copyright 2026 Asher Messenger contributors
# SPDX-License-Identifier: AGPL-3.0-only
#
# Builds the vendored libsignal Node package (libs/libsignal/node) that
# clients/desktop depends on through `"@signalapp/libsignal-client":
# "file:../../libs/libsignal/node"` in package.json. The published npm build
# does not carry the meshlink bridge (`Native.MeshNode_*`, docs/offline-mesh.md);
# this one does.
#
# Produces, inside libs/libsignal/node:
#   dist/                                        (tsc output: index.js, Native.js, ...)
#   prebuilds/<platform>-<arch>/@signalapp+libsignal-client.node
# which is exactly what `node-gyp-build` looks for at runtime and what the
# package's `files` list ships. Run `pnpm install` in clients/desktop AFTER this
# script: pnpm copies a `file:` dependency at install time, so a rebuilt
# prebuild or dist only reaches node_modules on the next install.
#
# Mirrors libs/libsignal/node/build_node_bridge.py (same cargo invocation,
# RUSTFLAGS and objcopy strip) without Python, plus a TypeScript build that
# needs only typescript/type-fest/@types/node instead of the package's full
# dev dependency set.
#
# Environment:
#   PROTOC                        protoc binary (libsignal's build scripts need >= 3.20)
#   CARGO_PROFILE_RELEASE_DEBUG   default 0 (no debug info; keeps the build small)
#   LIBSIGNAL_SKIP_CARGO=1        only rebuild dist/ (TypeScript)
#   LIBSIGNAL_SKIP_TSC=1          only rebuild the native library
set -euo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)
LIBSIGNAL=$ROOT/libs/libsignal
NODE_DIR=$LIBSIGNAL/node

NODE_OS=$(node -p 'process.platform')
NODE_ARCH=$(node -p 'process.arch')
case "$NODE_ARCH" in
  x64) CARGO_ARCH=x86_64 ;;
  arm64) CARGO_ARCH=aarch64 ;;
  ia32) CARGO_ARCH=i686 ;;
  *) CARGO_ARCH=$NODE_ARCH ;;
esac
case "$NODE_OS" in
  linux) CARGO_SUFFIX=unknown-linux-gnu; LIB=libsignal_node.so ;;
  darwin) CARGO_SUFFIX=apple-darwin; LIB=libsignal_node.dylib ;;
  win32) CARGO_SUFFIX=pc-windows-msvc; LIB=signal_node.dll ;;
  *) echo "unsupported platform $NODE_OS" >&2; exit 1 ;;
esac
CARGO_TARGET=${CARGO_TARGET:-$CARGO_ARCH-$CARGO_SUFFIX}
TARGET_DIR=${CARGO_BUILD_TARGET_DIR:-$LIBSIGNAL/target}
PREBUILD_DIR=$NODE_DIR/prebuilds/$NODE_OS-$NODE_ARCH
PREBUILD=$PREBUILD_DIR/@signalapp+libsignal-client.node

echo "libsignal: $LIBSIGNAL"
echo "target:    $CARGO_TARGET (release)"

# ---- native library --------------------------------------------------------
if [ "${LIBSIGNAL_SKIP_CARGO:-0}" != 1 ]; then
  # Same flags as build_node_bridge.py / java/build_jni.sh.
  REMAP=$(cd "$LIBSIGNAL" && python3 bin/build_helpers.py print-rust-paths-to-remap |
    while read -r prefix; do printf -- '--remap-path-prefix %s= ' "$prefix"; done)
  export RUSTFLAGS="${RUSTFLAGS:-} --cfg aes_armv8 --cfg tokio_unstable $REMAP"
  export CARGO_BUILD_TARGET_DIR=$TARGET_DIR
  export CARGO_INCREMENTAL=${CARGO_INCREMENTAL:-0}
  export CARGO_PROFILE_RELEASE_DEBUG=${CARGO_PROFILE_RELEASE_DEBUG:-0}
  export CMAKE_ARGS='-DCMAKE_SYSTEM_NAME='
  export MACOSX_DEPLOYMENT_TARGET=12

  (cd "$NODE_DIR" && cargo build --target "$CARGO_TARGET" -p libsignal-node \
    --features log/release_max_level_info --release)

  SRC=$TARGET_DIR/$CARGO_TARGET/release/$LIB
  test -r "$SRC" || { echo "missing $SRC" >&2; exit 1; }
  if grep -q -- '-LEVEL LOGS ENABLED' "$SRC"; then
    echo "debug-level logs found in a release build" >&2; exit 1
  fi
  mkdir -p "$PREBUILD_DIR"
  if [ "$NODE_OS" = linux ] && command -v objcopy >/dev/null; then
    objcopy -S "$SRC" "$PREBUILD"
  else
    cp "$SRC" "$PREBUILD"
  fi
  echo "prebuild:  $PREBUILD ($(du -h "$PREBUILD" | cut -f1))"
fi

# ---- TypeScript (dist/) ----------------------------------------------------
if [ "${LIBSIGNAL_SKIP_TSC:-0}" != 1 ]; then
  cd "$NODE_DIR"
  if [ ! -x node_modules/.bin/tsc ] || [ ! -d node_modules/type-fest ] ||
     [ ! -d node_modules/@types/node ]; then
    # The package's own (locked) dev dependencies; node_modules there is
    # gitignored and is not what pnpm copies into clients/desktop.
    npm ci --ignore-scripts --no-audit --no-fund
  fi
  # ts/test and ts/bench pull in chai/sinon/mocha; dist/ does not need them.
  TSCONFIG=$(mktemp -t libsignal-node-tsconfig.XXXXXX.json)
  cat > "$TSCONFIG" <<JSON
{
  "extends": "$NODE_DIR/tsconfig.json",
  "compilerOptions": {
    "rootDir": "$NODE_DIR/ts",
    "outDir": "$NODE_DIR/dist",
    "typeRoots": ["$NODE_DIR/node_modules/@types"],
    "types": ["node", "mocha"],
    "noEmitOnError": false
  },
  "include": ["$NODE_DIR/ts/**/*.ts"],
  "exclude": ["$NODE_DIR/ts/test/**", "$NODE_DIR/ts/bench/**"]
}
JSON
  node_modules/.bin/tsc -p "$TSCONFIG"
  rm -f "$TSCONFIG"
  echo "dist:      $NODE_DIR/dist ($(ls dist/*.js | wc -l) js files)"
fi

# The package must look complete for pnpm's file: copy.
test -f "$NODE_DIR/dist/index.js" || { echo "dist/index.js missing" >&2; exit 1; }
test -f "$PREBUILD" || { echo "prebuild missing; run without LIBSIGNAL_SKIP_CARGO" >&2; exit 1; }
echo "ok"
