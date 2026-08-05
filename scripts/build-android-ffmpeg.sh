#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BUILD_ROOT="${DICTATE_NATIVE_BUILD_DIR:-$ROOT_DIR/native-build-tmp}"
DOWNLOAD_DIR="${DICTATE_NATIVE_DOWNLOAD_DIR:-$BUILD_ROOT/downloads}"
SOURCE_DIR="$BUILD_ROOT/sources"
BUILD_DIR="$BUILD_ROOT/build"
PREFIX_DIR="$BUILD_ROOT/prefix/arm64-v8a"
OUTPUT_DIR="$ROOT_DIR/app/src/main/jniLibs/arm64-v8a"

ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
if [[ -z "$ANDROID_NDK_HOME" ]]; then
    echo "ANDROID_NDK_HOME or ANDROID_NDK_ROOT must point to an installed Android NDK." >&2
    exit 1
fi

HOST_TAG="linux-x86_64"
TOOLCHAIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$HOST_TAG"
if [[ ! -d "$TOOLCHAIN" ]]; then
    echo "Unsupported or missing NDK toolchain: $TOOLCHAIN" >&2
    exit 1
fi

API=26
TARGET=aarch64-linux-android
CC="$TOOLCHAIN/bin/${TARGET}${API}-clang"
CXX="$TOOLCHAIN/bin/${TARGET}${API}-clang++"
AR="$TOOLCHAIN/bin/llvm-ar"
RANLIB="$TOOLCHAIN/bin/llvm-ranlib"
STRIP="$TOOLCHAIN/bin/llvm-strip"
READELF="$TOOLCHAIN/bin/llvm-readelf"
JOBS="${JOBS:-$(getconf _NPROCESSORS_ONLN)}"

OPUS_VERSION=1.5.2
OPUS_ARCHIVE="opus-$OPUS_VERSION.tar.gz"
OPUS_URL="https://downloads.xiph.org/releases/opus/$OPUS_ARCHIVE"
OPUS_SHA256="65c1d2f78b9f2fb20082c38cbe47c951ad5839345876e46941612ee87f9a7ce1"

LAME_VERSION=3.100
LAME_ARCHIVE="lame-$LAME_VERSION.tar.gz"
LAME_URL="https://downloads.sourceforge.net/project/lame/lame/$LAME_VERSION/$LAME_ARCHIVE"
LAME_SHA256="ddfe36cab873794038ae2c1210557ad34857a4b6bdc515785d1da9e175b1da1e"

FFMPEG_VERSION=8.1
FFMPEG_ARCHIVE="ffmpeg-$FFMPEG_VERSION.tar.xz"
FFMPEG_URL="https://ffmpeg.org/releases/$FFMPEG_ARCHIVE"
FFMPEG_SHA256="b072aed6871998cce9b36e7774033105ca29e33632be5b6347f3206898e0756a"

mkdir -p "$DOWNLOAD_DIR" "$SOURCE_DIR" "$BUILD_DIR" "$PREFIX_DIR" "$OUTPUT_DIR"

download_and_verify() {
    local url="$1"
    local destination="$2"
    local sha256="$3"
    if [[ ! -f "$destination" ]]; then
        curl --fail --location --retry 3 --output "$destination" "$url"
    fi
    printf '%s  %s\n' "$sha256" "$destination" | sha256sum --check -
}

extract_once() {
    local archive="$1"
    local destination="$2"
    if [[ ! -d "$destination" ]]; then
        tar -xf "$archive" -C "$SOURCE_DIR"
    fi
}

download_and_verify "$OPUS_URL" "$DOWNLOAD_DIR/$OPUS_ARCHIVE" "$OPUS_SHA256"
extract_once "$DOWNLOAD_DIR/$OPUS_ARCHIVE" "$SOURCE_DIR/opus-$OPUS_VERSION"

mkdir -p "$BUILD_DIR/opus"
pushd "$BUILD_DIR/opus"
if [[ ! -f Makefile ]]; then
    "$SOURCE_DIR/opus-$OPUS_VERSION/configure" \
        --host="$TARGET" \
        --prefix="$PREFIX_DIR" \
        --disable-shared \
        --enable-static \
        --disable-doc \
        --disable-extra-programs \
        CC="$CC" \
        AR="$AR" \
        RANLIB="$RANLIB" \
        CFLAGS="-O2 -fPIC"
fi
make -j"$JOBS"
make install
popd

download_and_verify "$LAME_URL" "$DOWNLOAD_DIR/$LAME_ARCHIVE" "$LAME_SHA256"
extract_once "$DOWNLOAD_DIR/$LAME_ARCHIVE" "$SOURCE_DIR/lame-$LAME_VERSION"

mkdir -p "$BUILD_DIR/lame"
pushd "$BUILD_DIR/lame"
if [[ ! -f Makefile ]]; then
    "$SOURCE_DIR/lame-$LAME_VERSION/configure" \
        --host="$TARGET" \
        --prefix="$PREFIX_DIR" \
        --disable-shared \
        --enable-static \
        --disable-frontend \
        --disable-decoder \
        CC="$CC" \
        AR="$AR" \
        RANLIB="$RANLIB" \
        CFLAGS="-O2 -fPIC"
fi
make -j"$JOBS"
make install
popd

download_and_verify "$FFMPEG_URL" "$DOWNLOAD_DIR/$FFMPEG_ARCHIVE" "$FFMPEG_SHA256"
extract_once "$DOWNLOAD_DIR/$FFMPEG_ARCHIVE" "$SOURCE_DIR/ffmpeg-$FFMPEG_VERSION"
FFMPEG_SOURCE="$SOURCE_DIR/ffmpeg-$FFMPEG_VERSION"

mkdir -p "$BUILD_DIR/ffmpeg"
pushd "$BUILD_DIR/ffmpeg"
if [[ ! -f config.h ]]; then
    PKG_CONFIG_PATH="$PREFIX_DIR/lib/pkgconfig" \
    "$FFMPEG_SOURCE/configure" \
        --prefix="$PREFIX_DIR/ffmpeg" \
        --target-os=android \
        --arch=aarch64 \
        --cpu=armv8-a \
        --enable-cross-compile \
        --cc="$CC" \
        --cxx="$CXX" \
        --ar="$AR" \
        --ranlib="$RANLIB" \
        --strip="$STRIP" \
        --sysroot="$TOOLCHAIN/sysroot" \
        --pkg-config-flags=--static \
        --extra-cflags="-O2 -fPIC -I$PREFIX_DIR/include" \
        --extra-ldflags="-L$PREFIX_DIR/lib -Wl,-z,max-page-size=16384" \
        --extra-libs="-lm" \
        --enable-pic \
        --enable-gpl \
        --enable-small \
        --disable-shared \
        --enable-static \
        --disable-doc \
        --disable-debug \
        --disable-network \
        --disable-autodetect \
        --disable-everything \
        --enable-ffmpeg \
        --disable-ffprobe \
        --enable-avcodec \
        --enable-avformat \
        --enable-avfilter \
        --enable-avutil \
        --enable-swresample \
        --disable-avdevice \
        --disable-swscale \
        --enable-protocol=file \
        --enable-demuxer=pcm_s16le \
        --enable-decoder=pcm_s16le \
        --enable-muxer=opus \
        --enable-muxer=ogg \
        --enable-muxer=mp3 \
        --enable-muxer=ipod \
        --enable-muxer=wav \
        --enable-encoder=libopus \
        --enable-encoder=libmp3lame \
        --enable-encoder=aac \
        --enable-encoder=pcm_u8 \
        --enable-encoder=pcm_s16le \
        --enable-encoder=pcm_s24le \
        --enable-encoder=pcm_s32le \
        --enable-filter=aformat \
        --enable-filter=anull \
        --enable-filter=aresample \
        --enable-libopus \
        --enable-libmp3lame
fi

require_ffmpeg_component() {
    local symbol="$1"
    if ! grep -q "#define $symbol 1" config_components.h; then
        echo "Required FFmpeg component is missing: $symbol" >&2
        exit 1
    fi
}

for symbol in \
    CONFIG_PCM_S16LE_DEMUXER \
    CONFIG_LIBOPUS_ENCODER \
    CONFIG_LIBMP3LAME_ENCODER \
    CONFIG_AAC_ENCODER \
    CONFIG_PCM_U8_ENCODER \
    CONFIG_PCM_S16LE_ENCODER \
    CONFIG_PCM_S24LE_ENCODER \
    CONFIG_PCM_S32LE_ENCODER \
    CONFIG_OPUS_MUXER \
    CONFIG_OGG_MUXER \
    CONFIG_MP3_MUXER \
    CONFIG_IPOD_MUXER \
    CONFIG_WAV_MUXER \
    CONFIG_FILE_PROTOCOL \
    CONFIG_ARESAMPLE_FILTER
do
    require_ffmpeg_component "$symbol"
done

make -j"$JOBS" ffmpeg
popd

install -m 0755 "$BUILD_DIR/ffmpeg/ffmpeg" "$OUTPUT_DIR/libffmpeg.so"
"$READELF" -h "$OUTPUT_DIR/libffmpeg.so"
sha256sum "$OUTPUT_DIR/libffmpeg.so"
echo "Built FFmpeg $FFMPEG_VERSION with Opus $OPUS_VERSION and LAME $LAME_VERSION"
echo "Output: $OUTPUT_DIR/libffmpeg.so"
