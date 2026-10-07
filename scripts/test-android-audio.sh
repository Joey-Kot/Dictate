#!/usr/bin/env bash
# Run the app's real command builder against its Android binary using QEMU + Android 8 libc.
# Prerequisites: JDK/SDK, qemu-aarch64, debugfs, a completed native build and
# sdkmanager 'system-images;android-26;default;arm64-v8a'. No emulator needs to boot.
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BUILD_ROOT="${DICTATE_NATIVE_BUILD_DIR:-$ROOT_DIR/native-build-tmp}"
SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:?Set ANDROID_HOME or ANDROID_SDK_ROOT}}"
SYSTEM_IMAGE="$SDK_ROOT/system-images/android-26/default/arm64-v8a/system.img"
RUNTIME_DIR="$BUILD_ROOT/android-runtime"
test -f "$SYSTEM_IMAGE"
mkdir -p "$RUNTIME_DIR/system/bin" "$RUNTIME_DIR/system/lib64"
# The API 26 ARM64 SDK image is a GPT disk image. Extract only its system
# partition into the build directory; no mounting, loop device or root is needed.
python3 - "$SYSTEM_IMAGE" "$RUNTIME_DIR/system.ext4" <<'PY'
import pathlib
import struct
import sys

source, destination = map(pathlib.Path, sys.argv[1:])
with source.open('rb') as image:
    image.seek(512)
    header = image.read(92)
    if header[:8] != b'EFI PART':
        raise SystemExit('Expected the Android 26 ARM64 GPT system image')
    table, count, size = struct.unpack_from('<QII', header, 72)
    image.seek(table * 512)
    entries = [image.read(size) for _ in range(count)]
    entry = next(e for e in entries if e[56:128].decode('utf-16-le').rstrip('\0') == 'system')
    first, last = struct.unpack_from('<QQ', entry, 32)
    remaining = (last - first + 1) * 512
    image.seek(first * 512)
    with destination.open('wb') as output:
        while remaining:
            block = image.read(min(remaining, 1024 * 1024))
            if not block:
                raise SystemExit('Truncated system image')
            output.write(block)
            remaining -= len(block)
PY
for relative in bin/linker64 lib64/libc.so lib64/libm.so lib64/libdl.so; do
    debugfs -R "dump /$relative $RUNTIME_DIR/system/$relative" "$RUNTIME_DIR/system.ext4"
    test -s "$RUNTIME_DIR/system/$relative"
done
chmod +x "$RUNTIME_DIR/system/bin/linker64"
export DICTATE_ANDROID_SYSROOT="$RUNTIME_DIR"
export DICTATE_FFMPEG="$ROOT_DIR/app/src/main/jniLibs/arm64-v8a/libffmpeg.so"
export DICTATE_FFMPEG_RUNNER="$ROOT_DIR/scripts/run-android-audio.sh"
"$DICTATE_FFMPEG_RUNNER" "$DICTATE_FFMPEG" -version

# Use an independent native decoder/probe from the same FFmpeg version. Older distro
# FFmpeg versions cannot inspect some PCM-in-MP4 files even when encoding is valid.
if [[ -z "${DICTATE_FFPROBE:-}" || -z "${DICTATE_FFMPEG_DECODER:-}" ]]; then
    PREFIX_DIR="$BUILD_ROOT/host-verifier/prefix"
    mkdir -p "$BUILD_ROOT/host-verifier/speex" "$BUILD_ROOT/host-verifier/ffmpeg"
    pushd "$BUILD_ROOT/host-verifier/speex"
    "$BUILD_ROOT/sources/speex-1.2.1/configure" --prefix="$PREFIX_DIR" \
        --disable-shared --enable-static --disable-binaries
    make -j"${JOBS:-4}"
    make install
    popd
    pushd "$BUILD_ROOT/host-verifier/ffmpeg"
    PKG_CONFIG_PATH="$PREFIX_DIR/lib/pkgconfig" PKG_CONFIG_LIBDIR="$PREFIX_DIR/lib/pkgconfig" \
    "$BUILD_ROOT/sources/ffmpeg-8.1/configure" \
        --disable-doc --disable-debug --disable-autodetect --disable-everything --disable-x86asm \
        --disable-avdevice --disable-swscale --disable-network --enable-ffmpeg --enable-ffprobe \
        --enable-libspeex --pkg-config-flags=--static \
        --extra-cflags="-I$PREFIX_DIR/include" --extra-ldflags="-L$PREFIX_DIR/lib" \
        --enable-protocol=file --enable-filter=aformat,aresample,anull \
        --enable-demuxer=wav,mp3,flac,ogg,mov,aac,matroska,wv,ac3,eac3,amr,avi,flv,mpegps,asf,aiff,pcm_s8,pcm_s16le,pcm_s24le,pcm_s32le,pcm_f32le,pcm_f64le,pcm_s16be,pcm_s24be,pcm_s32be,pcm_f32be,pcm_f64be,pcm_alaw,pcm_mulaw \
        --enable-parser=mpegaudio,flac,opus,vorbis,aac,aac_latm,ac3,amr \
        --enable-decoder=mp3,mp3float,mp2,flac,opus,vorbis,aac,alac,wavpack,ac3,eac3,amrnb,amrwb,libspeex,wmav1,wmav2,pcm_u8,pcm_s8,pcm_s16le,pcm_s24le,pcm_s32le,pcm_s64le,pcm_f32le,pcm_f64le,pcm_s16be,pcm_s24be,pcm_s32be,pcm_f32be,pcm_f64be,pcm_alaw,pcm_mulaw,adpcm_ms \
        --enable-encoder=pcm_s16le --enable-muxer=pcm_s16le
    make -j"${JOBS:-4}" ffmpeg ffprobe
    popd
    export DICTATE_FFPROBE="$BUILD_ROOT/host-verifier/ffmpeg/ffprobe"
    export DICTATE_FFMPEG_DECODER="$BUILD_ROOT/host-verifier/ffmpeg/ffmpeg"
fi
cd "$ROOT_DIR"
./gradlew --no-daemon --no-configuration-cache :app:testDebugUnitTest \
    --tests 'com.joeykot.dictate.audio.NativeAudioEncodingTest' --rerun-tasks
