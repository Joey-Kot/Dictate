#!/usr/bin/env bash
set -euo pipefail
: "${DICTATE_ANDROID_SYSROOT:?Set DICTATE_ANDROID_SYSROOT to the extracted Android runtime}"
exec qemu-aarch64 -L "$DICTATE_ANDROID_SYSROOT" \
    -E "LD_LIBRARY_PATH=$DICTATE_ANDROID_SYSROOT/system/lib64" "$@"
