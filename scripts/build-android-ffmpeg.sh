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
        curl --fail --location --retry 3 --output "$destination.part" "$url"
        mv "$destination.part" "$destination"
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
make -j"$JOBS"
make install
popd

download_and_verify "$LAME_URL" "$DOWNLOAD_DIR/$LAME_ARCHIVE" "$LAME_SHA256"
extract_once "$DOWNLOAD_DIR/$LAME_ARCHIVE" "$SOURCE_DIR/lame-$LAME_VERSION"

mkdir -p "$BUILD_DIR/lame"
pushd "$BUILD_DIR/lame"
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
make -j"$JOBS"
make install
popd

# Build every dependency with the same NDK and isolated pkg-config prefix.
export PKG_CONFIG_PATH="$PREFIX_DIR/lib/pkgconfig"
export PKG_CONFIG_LIBDIR="$PREFIX_DIR/lib/pkgconfig"
build_dependency() {
    local name="$1" archive="$2" url="$3" checksum="$4"
    shift 4
    download_and_verify "$url" "$DOWNLOAD_DIR/$archive" "$checksum"
    extract_once "$DOWNLOAD_DIR/$archive" "$SOURCE_DIR/$name"
    mkdir -p "$BUILD_DIR/$name"
    pushd "$BUILD_DIR/$name"
    "$SOURCE_DIR/$name/configure" --host="$TARGET" --prefix="$PREFIX_DIR" \
        --disable-shared --enable-static "$@" \
        CC="$CC" CXX="$CXX" AR="$AR" RANLIB="$RANLIB" \
        CFLAGS="-O2 -fPIC -I$PREFIX_DIR/include" CXXFLAGS="-O2 -fPIC" LDFLAGS="-L$PREFIX_DIR/lib"
    make -j"$JOBS"
    make install
    popd
}

build_dependency libogg-1.3.5 libogg-1.3.5.tar.xz \
    https://downloads.xiph.org/releases/ogg/libogg-1.3.5.tar.xz \
    c4d91be36fc8e54deae7575241e03f4211eb102afb3fc0775fbbc1b740016705
build_dependency libvorbis-1.3.7 libvorbis-1.3.7.tar.xz \
    https://downloads.xiph.org/releases/vorbis/libvorbis-1.3.7.tar.xz \
    b33cc4934322bcbf6efcbacf49e3ca01aadbea4114ec9589d1b1e9d20f72954b --disable-oggtest
build_dependency opencore-amr-0.1.6 opencore-amr-0.1.6.tar.gz \
    https://downloads.sourceforge.net/project/opencore-amr/opencore-amr/opencore-amr-0.1.6.tar.gz \
    483eb4061088e2b34b358e47540b5d495a96cd468e361050fae615b1809dc4a1 --disable-amrwb
build_dependency vo-amrwbenc-0.1.3 vo-amrwbenc-0.1.3.tar.gz \
    https://downloads.sourceforge.net/project/opencore-amr/vo-amrwbenc/vo-amrwbenc-0.1.3.tar.gz \
    5652b391e0f0e296417b841b02987d3fd33e6c0af342c69542cbb016a71d9d4e
build_dependency speex-1.2.1 speex-1.2.1.tar.gz \
    https://downloads.xiph.org/releases/speex/speex-1.2.1.tar.gz \
    4b44d4f2b38a370a2d98a78329fefc56a0cf93d1c1be70029217baae6628feea --disable-binaries

# Configure names differ from CLI muxer names (e.g. pcm_s16le vs s16le).
encoders=libopus,libmp3lame,aac,libvorbis,flac,alac,ac3,eac3,mp2,adpcm_ms,libopencore_amrnb,libvo_amrwbenc,libspeex,wavpack,wmav1,wmav2,pcm_u8,pcm_s8,pcm_alaw,pcm_mulaw,pcm_s16le,pcm_s24le,pcm_s32le,pcm_s64le,pcm_f32le,pcm_f64le,pcm_s16be,pcm_s24be,pcm_s32be,pcm_f32be,pcm_f64be
muxers=opus,ogg,mp3,ipod,wav,webm,mp4,matroska,adts,flv,mov,avi,mpeg1system,flac,ac3,eac3,amr,spx,wv,asf,aiff,pcm_s8,pcm_s16le,pcm_s24le,pcm_s32le,pcm_f32le,pcm_f64le,pcm_s16be,pcm_s24be,pcm_s32be,pcm_f32be,pcm_f64be,pcm_alaw,pcm_mulaw
filters=aformat,anull,aresample,pan

download_and_verify "$FFMPEG_URL" "$DOWNLOAD_DIR/$FFMPEG_ARCHIVE" "$FFMPEG_SHA256"
extract_once "$DOWNLOAD_DIR/$FFMPEG_ARCHIVE" "$SOURCE_DIR/ffmpeg-$FFMPEG_VERSION"
FFMPEG_SOURCE="$SOURCE_DIR/ffmpeg-$FFMPEG_VERSION"

mkdir -p "$BUILD_DIR/ffmpeg"
pushd "$BUILD_DIR/ffmpeg"
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
        --enable-version3 \
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
        --enable-muxer="$muxers" \
        --enable-encoder="$encoders" \
        --enable-filter="$filters" \
        --enable-libopus \
        --enable-libmp3lame \
        --enable-libvorbis \
        --enable-libopencore-amrnb \
        --enable-libvo-amrwbenc \
        --enable-libspeex

require_ffmpeg_component() {
    local symbol="$1"
    if ! grep -q "#define $symbol 1" config_components.h; then
        echo "Required FFmpeg component is missing: $symbol" >&2
        exit 1
    fi
}

for symbol in CONFIG_PCM_S16LE_DEMUXER CONFIG_PCM_S16LE_DECODER CONFIG_FILE_PROTOCOL; do
    require_ffmpeg_component "$symbol"
done
check_components() {
    local kind="$1" component
    local -a components
    IFS=, read -r -a components <<< "$2"
    for component in "${components[@]}"; do
        require_ffmpeg_component "CONFIG_${component^^}_${kind}"
    done
}
check_components ENCODER "$encoders"
check_components MUXER "$muxers"
check_components FILTER "$filters"

make -j"$JOBS" ffmpeg
popd

install -m 0755 "$BUILD_DIR/ffmpeg/ffmpeg" "$OUTPUT_DIR/libffmpeg.so"
"$READELF" -h "$OUTPUT_DIR/libffmpeg.so"
"$READELF" -lW "$OUTPUT_DIR/libffmpeg.so"
"$READELF" -h "$OUTPUT_DIR/libffmpeg.so" | grep -q 'Type:.*DYN'
"$READELF" -h "$OUTPUT_DIR/libffmpeg.so" | grep -q 'Machine:.*AArch64'
"$READELF" -lW "$OUTPUT_DIR/libffmpeg.so" | awk '
    $1 == "LOAD" { count++; if ($NF != "0x4000") bad = 1 }
    END { exit (count == 0 || bad) }
'
sha256sum "$OUTPUT_DIR/libffmpeg.so"
echo "Built FFmpeg $FFMPEG_VERSION with Opus, LAME, Vorbis, AMR-NB/WB and Speex"
echo "Output: $OUTPUT_DIR/libffmpeg.so"
