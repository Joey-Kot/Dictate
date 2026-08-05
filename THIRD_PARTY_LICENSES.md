# Third-party licenses

Dictate is licensed under `GPL-3.0-or-later`. Runtime, build-only, and test-only components are identified below.

| Component | Exact version | Purpose | Upstream | License | License text | Modifications and build |
|---|---:|---|---|---|---|---|
| FFmpeg | `n8.1` | Embedded arm64 CLI for audio normalization | <https://ffmpeg.org/> | `GPL-2.0-or-later` for this `--enable-gpl` build | [`GPL-2.0.txt`](THIRD_PARTY_LICENSES/GPL-2.0.txt) | No source patches. Minimal static Android CLI built by `scripts/build-android-ffmpeg.sh`, stripped and renamed `libffmpeg.so`. |
| Opus | `1.5.2` | Opus encoder statically linked into FFmpeg | <https://opus-codec.org/> | 3-clause BSD-style license and patent notices | [`Opus-BSD-3-Clause.txt`](THIRD_PARTY_LICENSES/Opus-BSD-3-Clause.txt) | No patches; position-independent static build. |
| LAME | `3.100` | MP3 encoder statically linked into FFmpeg | <https://lame.sourceforge.io/> | `LGPL-2.0-or-later` | [`LGPL-2.0.txt`](THIRD_PARTY_LICENSES/LGPL-2.0.txt) | No patches; frontend and decoder disabled, static encoder linked. |
| CMU Flite `cmu_us_slt` voice | `2.2` | Source voice for the packaged synthetic connectivity-test clip | <https://github.com/festvox/flite> | CMU permissive notice | [`CMU-Flite.txt`](THIRD_PARTY_LICENSES/CMU-Flite.txt) | No Flite code or voice database is packaged. The word “test” was rendered to 16 kHz mono PCM by `scripts/generate-connectivity-test-audio.sh`; the reference asset used Ubuntu Flite `2.2-6build3` through FFmpeg `6.1.1-3ubuntu5`. |
| Kotlin standard library | `2.1.10` | Kotlin runtime packaged in the APK | <https://kotlinlang.org/> | `Apache-2.0` | [`Apache-2.0.txt`](THIRD_PARTY_LICENSES/Apache-2.0.txt) | Unmodified. |
| Android Gradle Plugin | `8.9.2` | Build-only Android tooling | <https://developer.android.com/build/releases/gradle-plugin> | `Apache-2.0` | [`Apache-2.0.txt`](THIRD_PARTY_LICENSES/Apache-2.0.txt) | Build-only; not packaged. |
| Gradle Wrapper | `8.11.1` | Reproducible build launcher | <https://gradle.org/> | `Apache-2.0` | [`Apache-2.0.txt`](THIRD_PARTY_LICENSES/Apache-2.0.txt) | Build-only; unmodified. |
| JUnit | `4.13.2` | JVM unit tests | <https://junit.org/junit4/> | `EPL-1.0` | [`EPL-1.0.txt`](THIRD_PARTY_LICENSES/EPL-1.0.txt) | Test-only; not packaged. |

The source build verifies:

```text
FFmpeg 8.1  b072aed6871998cce9b36e7774033105ca29e33632be5b6347f3206898e0756a
Opus 1.5.2  65c1d2f78b9f2fb20082c38cbe47c951ad5839345876e46941612ee87f9a7ce1
LAME 3.100  ddfe36cab873794038ae2c1210557ad34857a4b6bdc515785d1da9e175b1da1e
Connectivity test PCM  4e66d520dc28cc6eef1279b297d05173b48fb63887ed20ca9a7cca1835eafdab
```

AndroidX is not used. No third-party networking library or Provider SDK is used; requests use Android platform `HttpURLConnection`. Android platform APIs and `org.json` are supplied by the OS rather than redistributed as standalone libraries.
