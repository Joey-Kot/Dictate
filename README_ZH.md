[English](README.md) | 简体中文

# Dictate

Dictate 是 Android 语音转写与选中文本后处理工具，通过不抢焦点的无障碍悬浮按钮操作。音频或选中文本直接发送到用户配置的指定服务，每次执行一个任务。转写和后处理共用同一个写入规则：实际写入时没有选区，就在当前光标处插入；有可编辑选区，就替换该段文本。

## 操作演示与配置

| [<img src="demo/Demo-ZH.gif" alt="Dictate 中文操作演示" width="320">](demo/Demo-ZH.gif) |
| :---: |
| **实际操作**<br>通过悬浮按钮录音转写，再选中文本，使用自定义提示词进行改写、翻译等操作。 |

| [![权限设置](demo/1.%20Permissions.png)](demo/1.%20Permissions.png) | [![界面语言](demo/2.%20UI%20language.png)](demo/2.%20UI%20language.png) |
| :---: | :---: |
| **1. 权限设置**<br>启用无障碍服务并授予麦克风权限。 | **2. 界面语言**<br>支持英语、中文、日语、德语、法语和俄语，选择后保存生效。 |
| [![录音设置](demo/3.%20Audio%20Record%20Settings.png)](demo/3.%20Audio%20Record%20Settings.png) | [![音频 API 设置](demo/4.%20Audio%20API%20Settings.png)](demo/4.%20Audio%20API%20Settings.png) |
| **3. 录音设置**<br>选择输出采样率、编码、容器和比特率。 | **4. 音频 API 设置**<br>配置转写服务的 Base URL、API Key、模型及附加 JSON 参数，并通过真实转写请求测试连接。 |
| [![改写 API 设置](demo/5.%20Rewrite%20API%20Settings.png)](demo/5.%20Rewrite%20API%20Settings.png) | [![提示词编辑](demo/6.%20Prompt%20Edit.png)](demo/6.%20Prompt%20Edit.png) |
| **5. 改写 API 设置**<br>配置文本后处理的服务商、Base URL、API Key 和模型，管理提示词顺序并测试连接。 | **6. 提示词编辑**<br>设置服务商、图标、标题、内容和附加 JSON；默认继承主 Provider，也可配置并测试独立 API。 |
| [![重试设置](demo/7.%20Retry%20Settings.png)](demo/7.%20Retry%20Settings.png) | [![交互设置](demo/8.%20Interaction%20Settings.png)](demo/8.%20Interaction%20Settings.png) |
| **7. 重试设置**<br>开启自动重试，设置最大重试次数和初始等待时间。 | **8. 交互设置**<br>设置是否始终复制结果到剪贴板，以及长按阈值和双击最大间隔。 |
| [![显示设置](demo/9.%20Display%20Settings.png)](demo/9.%20Display%20Settings.png) | [![配置保存与导入导出](demo/10.%20Configuration%20Saving%20and%20Import%26Export.png)](demo/10.%20Configuration%20Saving%20and%20Import%26Export.png) |
| **9. 显示设置**<br>调整悬浮按钮大小、不透明度和各状态颜色，并管理通知设置。 | **10. 配置保存与导入导出**<br>保存设置，通过 JSON 导入或导出配置，以及查看或清空诊断信息。 |

## 音频转录功能介绍

- 非 IME 形式，没有键盘、候选栏、历史列表、云账号或代理服务。
- `AudioRecord` PCM 录音：按当前输入路由选择采样率，固定 16-bit 单声道；支持暂停/恢复、前台麦克风服务、唤醒锁和取消。
- 无障碍悬浮按钮不抢焦点，可实时拖动并保存位置；可配置大小、不透明度，以及录制／暂停／处理中三种状态的预置或自定义色系，保存后无需重启无障碍服务；恢复时会按系统边距、刘海和可见屏幕重新裁剪，拖动不改变当前语音任务。
- 已开始的录音在熄屏后继续；不提供锁屏控制或锁屏上屏。
- 从经过校验的源码包构建 FFmpeg `8.1` 及 Opus、LAME、Vorbis、AMR-NB/WB、Speex，目前只提供 `arm64-v8a` 二进制。
- 提供 28 项音频编码选项，覆盖无损格式、语音编码、WMA、ADPCM、整数及浮点 PCM。设置按编码器联动采样率、位深度、容器和码率，录音结束后统一转换；自动输出采样率适配编码器与录音输入，最高 48 kHz。
- 直接 multipart 请求 OpenAI Compatible `/v1/audio/transcriptions`；成功响应必须含顶层非空字符串 `text`；目前暂不考虑支持 OpenAI Compatible 以外的其他 API，如有其他厂商服务需求，可使用任意兼容转换服务转换为 OpenAI Compatible 使用。
- 默认保留结果的剪贴板副本；关闭该开关后，只有明确写入失败才使用剪贴板兜底。
- FFmpeg、HTTP 和指数退避等待均可取消，所有回调受递增任务 ID 保护。
- Keystore 支持的 API Key 加密、脱敏诊断、真实端点测试、完整校验的 JSON 导入导出。

### 选中文本后处理功能介绍

- **选区操作菜单**：选中文本后长按空闲悬浮按钮，展开提示词列表，点击条目即可执行；没有选中文本或尚未保存提示词时，长按继续重发上一条录音。
- **独立服务配置**：在「改写 API 设置」中单独配置 Provider、Base URL、API Key 和 Model。支持 OpenAI-Compatible、OpenAI Responses、OpenAI Completions、Google、Anthropic、DeepSeek、Qwen、GLM。
- **每条提示词独立选择 Provider**：默认「与主 Provider 相同」（Same as main provider），继承整套改写 API 设置；也可设置独立 Provider、Base URL、API Key 和 Model，并在保存前测试连接。
- **自定义提示词与图标**：每项包含图标、标题、实际提示词及独立附加 JSON，支持新增、编辑、删除和排序。图标可使用 8 个内置选项，也可导入 SVG、PNG、JPG。
- **按提示词覆盖模型和参数**：各条目的附加 JSON 可覆盖所选 API 的 Model 及其他请求字段；嵌套对象逐层合并，数组整体替换，其他值直接覆盖，显式为 `null` 的对象字段在请求中删除。
- **按需处理文本**：通过自己编写的提示词，实现润色、改写、翻译、总结等操作；这些用途由用户配置，可处理已有文本或转写后的文本，无需先开始新的录音。
- **共用写入与任务控制**：后处理与转写共用处理中状态、双击取消及自动重试。结果按实际写入时的焦点和选区插入或替换；后处理完成、失败或取消均不替换上一条录音。

### 悬浮按钮操作

| 状态 | 单击 | 长按 | 快速双击 |
|---|---|---|---|
| 空闲 | 录制 | 有选中文本且提示词列表非空时展开菜单；其他情况重发上一条录音 | 无操作 |
| 录制中 | 停止并转写 | 暂停 | 取消并丢弃 |
| 已暂停 | 无操作 | 恢复 | 取消并丢弃 |
| 处理中 | 无操作 | 无操作 | 取消当前任务，保留已有录音 |

手势优先级固定为 `拖拽 > 长按 > 双击 > 单击`。长按在抬起时确认，因此只要抬起前任何时刻的移动超过系统触摸阈值，本次触摸就只会被判为拖动。短按会等待配置的双击判定窗口后再确认，保证快速双击始终只有一个明确结果。

提示词菜单是可纵向滚动的圆角半透明面板，从按钮位置平滑展开；点击菜单外区域收回空闲麦克风按钮。点击条目后收回圆形按钮并进入处理中状态。新安装或尚未添加提示词时，即使选中了文本，长按也继续重发上一条录音。

## 下载

| Platform | Download | SHA-256 |
|---|---|---|
| arm64-v8a | [arm64-v8a](https://github.com/Joey-Kot/Dictate/releases/download/Latest/Dictate-latest-arm64-v8a.apk) | [sha256](https://github.com/Joey-Kot/Dictate/releases/download/Latest/Dictate-latest-arm64-v8a.apk.sha256) |

## 架构

```mermaid
flowchart LR
  A["无障碍服务"] --> B["不抢焦点悬浮按钮"]
  B --> C["唯一 VoiceJobController"]
  B --> L["当前选中文本与提示词菜单"]
  L --> C
  C --> D["AudioRecord"]
  C --> E["内嵌 FFmpeg CLI"]
  C --> F["HttpURLConnection"]
  F --> G["用户 Base URL"]
  C --> H["后处理 Provider 适配与 JSON 合并"]
  H --> F
  C --> T["TextDelivery"]
  T --> W["无障碍文本写入管线"]
  W -->|"Android 13+"| M["AccessibilityInputConnection<br/>commitText + 写入验证"]
  W -->|"Android 8–12<br/>或明确失败回退"| S["ACTION_SET_TEXT<br/>光标插入或选区替换"]
  M --> X["当前编辑器"]
  S --> X
  T -->|"开启始终复制<br/>或直接写入明确失败"| I["剪贴板"]
  I -->|"直接写入明确失败"| P["ACTION_PASTE"]
  P --> X
  J["配置页"] --> K["Preferences + Keystore"]
```

## 请求流程

```mermaid
sequenceDiagram
  participant U as 用户
  participant O as 悬浮按钮
  participant J as VoiceJobController
  participant R as AudioRecord
  participant F as FFmpeg
  participant P as 端点
  participant D as TextDelivery
  participant A as 无障碍服务
  participant E as 当前编辑器
  participant C as 剪贴板
  alt 语音转写
    U->>O: 单击
    O->>J: 开始录音
    J->>R: 按当前输入路由采样率录制单声道原始 PCM
    U->>O: 单击
    O->>J: 停止并转写
    J->>R: 停止并保留原始录音
    J->>F: 按当前设置转码
    J->>P: POST /v1/audio/transcriptions
  else 已选中文本且有提示词
    U->>O: 长按空闲按钮
    O->>A: 读取当前选中文本
    A-->>O: 本次输入文本快照
    O-->>U: 展开提示词菜单
    U->>O: 点击提示词
    O->>J: 提交输入文本与条目
    J->>P: 合并条目 JSON 后发送后处理请求
  end
  P-->>J: 返回文本
  J->>J: 保持“请求中”，进入写入阶段
  J->>D: 投递文本（受任务 ID 和取消状态保护）
  D->>A: 按此刻的焦点和选区插入或替换
  alt Android 13+
    A->>E: 读取写入前的周边文本
    A->>E: commitText(text)
    loop 最多三轮：0 / 100 / 300 ms
      A->>E: 读取周边文本并验证结果
    end
    alt 已确认
      Note over A,E: 返回 confirmed
    else 明确未写入
      A->>E: ACTION_SET_TEXT
    else 无法确认
      Note over A,E: 关闭始终复制时不重试或走剪贴板兜底，避免重复插入
    end
  else Android 8–12
    A->>E: ACTION_SET_TEXT（忽略显示中的占位符）
  end
  A-->>D: confirmed / failed / unconfirmed
  opt 开启始终复制，或直接写入明确失败
    D->>C: 复制结果文本
  end
  opt 直接写入明确失败且复制成功
    D->>A: ACTION_PASTE
    A->>E: 粘贴到当前焦点
  end
  D-->>J: 完成回调（仅任务仍有效）
  J-->>U: 完成结果或失败提示
```

后处理在展开菜单前捕获选中文本作为本次输入。转写与后处理的输出位置都在实际写入时确定，不绑定请求开始时的输入框或选区；等待期间更换输入框、光标或选区后，使用写入时的状态。两类任务沿用同一套写入验证和剪贴板兜底策略。

## 任务状态机

```mermaid
stateDiagram-v2
  [*] --> 空闲
  空闲 --> 录制中
  空闲 --> 转码中: 重发
  空闲 --> 请求中: 选择后处理提示词
  录制中 --> 已暂停
  已暂停 --> 录制中
  录制中 --> 转码中
  转码中 --> 请求中
  请求中 --> 重试等待中
  重试等待中 --> 请求中
  请求中 --> 空闲: 请求或投递结束
  转码中 --> 空闲
  录制中 --> 空闲: 取消并丢弃
  已暂停 --> 空闲: 取消并丢弃
  转码中 --> 空闲: 取消并保留原始录音
  请求中 --> 空闲: 取消并保留原始录音
  重试等待中 --> 空闲: 取消并保留原始录音
  note right of 请求中
    包含 HTTP 请求、写入验证、
    始终复制副本，或明确失败时的
    剪贴板与粘贴兜底
  end note
```

内部任务状态仍为空闲、录制中、已暂停、转码中、请求中和重试等待中。菜单展开属于空闲时的界面状态，不启动请求。后处理直接进入请求中，跳过录音和转码；完成、失败或取消均不替换上一条录音。

## 使用要求

- Android 8.0+（`minSdk 26`）和 `arm64-v8a` 设备。
- 已启用的 Dictate 无障碍服务；录音还需要麦克风权限。
- 转写需要 OpenAI Compatible `POST /v1/audio/transcriptions` 端点、Base URL、API Key 和模型。
- 后处理需要有效的 Provider、Base URL、API Key、模型配置及至少一条提示词；API 配置可使用公共设置，也可按提示词单独设置。

选区读取及无障碍写入依赖目标应用提供的能力。只读文本可以作为后处理输入；密码框、受保护界面和自绘控件可能不暴露选区或拒绝写入。无可写入焦点时沿用剪贴板兜底，项目不做逐 App 专项兼容。

Android 13 及以上版本在无障碍节点未提供选中文本时，还会通过当前编辑器的输入连接在后台读取选区，再决定展开提示词菜单还是重发录音。读取不会改变剪贴板；等待期间若编辑器、选区或当前任务发生变化，则丢弃过期结果。

## 从源码构建

使用 JDK 17、Android SDK Platform 35、Build Tools 35.0.0、NDK `27.2.12479018`。

```bash
export ANDROID_HOME=/path/to/android-sdk
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/27.2.12479018"
export GRADLE_USER_HOME=/tmp/gradle-user-home
./scripts/build-android-ffmpeg.sh
./gradlew :app:assembleDebug
```

脚本生成 `app/src/main/jniLibs/arm64-v8a/libffmpeg.so`，语音转写运行时需要此文件；仅完成 Gradle Debug 构建不会自动生成 FFmpeg。签名 release 还需 `ANDROID_KEYSTORE_PATH`、`ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_ALIAS`、`ANDROID_KEY_PASSWORD`。

Release 构建启用 R8 代码压缩、优化、混淆和资源缩减。CI 将 `mapping.txt` 保存为工作流产物，用于还原崩溃堆栈。Debug 构建保持不压缩。

原生构建需要 Linux、`make`、`pkg-config`、`curl`、`tar` 和 XZ 支持。脚本用 SHA-256 核验 FFmpeg `8.1`、Opus `1.5.2`、LAME `3.100`、libogg `1.3.5`、libvorbis `1.3.7`、OpenCORE AMR `0.1.6`、VisualOn AMR-WB `0.1.3`、Speex `1.2.1` 的源码包，只构建 AArch64，逐项检查必需的 demuxer、编码器、容器和滤镜，并验证生成的 Android PIE 可执行文件采用 16 KiB 加载对齐。此 FFmpeg 构建启用 `--enable-gpl --enable-version3`，依赖许可和校验值记录在 [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md)。

完成原生构建后，可用 QEMU 配合 Android 8 运行库执行实际打包的 Android 二进制进行音频集成测试。额外需要 `qemu-user`（`qemu-aarch64`）、`e2fsprogs`（`debugfs`）、Python 3 和主机 C 编译器。脚本直接从 SDK 镜像提取运行库，无需挂载镜像，并使用已下载的源码构建独立的 FFmpeg 8.1 解码器和探测工具。

```bash
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" 'system-images;android-26;default;arm64-v8a'
DICTATE_AUDIO_FULL=1 ./scripts/test-android-audio.sh
./gradlew :app:testDebugUnitTest :app:lintDebug
```

完整音频矩阵覆盖所有可选编码与容器、采样率预设和可选位深度，检查默认、最低、最高码率，以及 AMR/Speex 的全部码率档位。验证内容包括解码结果、流参数、裸 PCM 长度和字节序，以及单声道向双声道、六声道逐样本复制。CI 在发布打包前执行此矩阵；QEMU 验证不能替代麦克风和真机测试。

随 APK 分发的连通性测试音频是单词 “test” 的 16 kHz 单声道合成语音，使用 Flite `2.2` 的 `cmu_us_slt` 语音生成。可复现命令位于 `scripts/generate-connectivity-test-audio.sh`，来源与 CMU 许可记录在 `THIRD_PARTY_LICENSES.md`。

GitHub Release 签名使用以下仓库 Secrets：

```text
ANDROID_KEYSTORE_BASE64
ANDROID_KEYSTORE_PASSWORD
ANDROID_KEY_ALIAS
ANDROID_KEY_PASSWORD
```

GitHub Actions 是默认发布路径。推送到 `main`、`dev` 或使用可选 ref 手动触发后，会构建签名 arm64 APK、标准 SHA-256 文件，强制更新 `Latest` tag，删除 Release 旧附件并只上传当前两个文件。所有发布任务共用一个并发组，避免较旧的重叠构建反向覆盖较新的 `Latest`。

## 使用方法（配置）

1. 授予麦克风权限，在 Android 无障碍设置中启用 Dictate。通知可通过「显示设置」中的开关开启或关闭。
2. 填写 Base URL（`https://example.com` 或 `https://example.com/v1`）、API Key 和模型。
3. 可选填写完整附加 JSON 对象，按下述合并规则生成 multipart 参数；对象和数组序列化为 JSON 字段值。`model` 可以覆盖，`file` 属于二进制音频附件，同名附加参数会报告冲突。
4. 执行真实端点测试；应用按当前音频设置转码内置短语音并调用转写端点，不使用 `/v1/models`。
5. 可选配置悬浮按钮大小、不透明度，以及预置或 Custom 三状态色系；保存配置后，在普通可编辑输入框中保留光标并使用悬浮按钮。

「录音设置」上方设有语言选项，默认英文，不随系统语言变化。列表依次为 English、中文、日本語、Deutsch、Français、Русский，选择后点击「保存设置」生效，覆盖界面、弹窗、状态提示和录音通知。此选项不改变语音转写的语言，也不修改用户填写的提示词和 API 参数。

各设置区域不带编号，依次为：录音设置、音频 API 设置、改写 API 设置、重试设置、交互设置、显示设置。标题随界面语言切换；底部「关于」展示作者、邮箱、许可证、仓库地址和当前安装版本的版本号。

「显示通知」开关反映系统中的应用通知及录音通知渠道状态。点击后进入系统通知设置，返回时自动同步；关闭通知不影响录音。Android 13 及以上版本关闭通知栏通知后，系统任务管理器仍可能显示正在运行的前台服务。此项由系统管理，不随应用配置导出。

### 音频编码设置

录音保持 16-bit 单声道 PCM，采样率由当前输入路由决定。编码时由 FFmpeg 转换采样格式、位深度并重采样。目前所有可选编码器都接受单声道；如果编码器要求更多声道，转换流程会选择其支持的最小声道布局，将原始单声道复制到每个声道。

默认仍为 MP3、自动采样率和 128 kbps。自动模式先将录音采样率限制到最高 48 kHz，再选择编码器支持的最近采样率，距离相同时取较低值。如果保存的码率与实际采样率不兼容，本次录音采用该采样率下的默认码率，保留已保存的配置。手动采样率预设包括 7.35、8、11.025、12、16、22.05、24、32、44.1、48、64、88.2、96、176.4、192 kHz，按编码器过滤。

| 编码选项 | 输出容器 | 采样率／位深度限制 |
|---|---|---|
| Opus | OPUS、OGG、WEBM、MP4、MKV、MKA | 8 / 12 / 16 / 24 / 48 kHz |
| MP3 | MP3、WAV、AVI、MKV、MKA、MPEG；满足条件时提供 MP4/FLV | 8～48 kHz 的预设；容器限制见下文 |
| AAC | M4A、MP4、AAC（ADTS）、FLV、MKV、MKA、MOV | 最高 96 kHz；AAC/WAV 无法可靠完成编码后解码，因此不提供 |
| Vorbis | OGG、WEBM、MKV、MKA | 8～48 kHz 的预设 |
| FLAC | FLAC、OGG、MKV、MKA | 16 / 24-bit 输出 |
| ALAC | M4A、MP4、MOV | 16 / 24-bit 输出 |
| AC-3 | AC3、M4A、MP4、WAV、AVI、MKV、MKA、MPEG | 32 / 44.1 / 48 kHz |
| E-AC-3 | EAC3、MP4、MKV、MKA | 32 / 44.1 / 48 kHz |
| MP2 | WAV、MP4、MPEG | 16 / 22.05 / 24 / 32 / 44.1 / 48 kHz |
| ADPCM（MS） | WAV | 从 PCM16 转换 |
| AMR-NB | AMR、WAV | 8 kHz；精确的 4.75～12.2 kbps 档位 |
| AMR-WB | AMR | 16 kHz；精确的 6.6～23.85 kbps 档位 |
| Speex | SPX、OGG | 8 / 16 / 32 kHz；采用对应采样率的实际码率档位 |
| WavPack | WV | 16 / 24 / 32-bit 输出 |
| WMA v1 / v2 | WMA、ASF | 8～48 kHz 的预设 |
| PCM | WAV；16/24/32-bit 可用 MP4、MOV 及对应的 S16LE/S24LE/S32LE 裸流；16-bit 另可用 AVI | 8 / 16 / 24 / 32-bit；无符号 8-bit 使用 WAV |
| PCM 8-bit（有符号） | AIFF、S8 | 固定有符号 8-bit |
| PCM A-law / μ-law | WAV、对应的 ALAW/MULAW 裸流 | 固定压扩格式 |
| PCM Float 32/64-bit（LE） | WAV、MP4、对应的 F32LE/F64LE 裸流 | 固定浮点格式 |
| PCM 64-bit（LE） | WAV | 固定有符号 64-bit |
| PCM 16/24/32-bit（BE） | AIFF、MP4、对应的 S16BE/S24BE/S32BE 裸流 | 固定大端整数格式 |
| PCM Float 32/64-bit（BE） | AIFF、MP4、对应的 F32BE/F64BE 裸流 | 固定大端浮点格式 |

MP3 使用 MP4 时要求至少 16 kHz；使用 FLV 时只提供 11.025、22.05、44.1、48 kHz。自动采样率下的 MP3 采用公共容器集合，不提供 MP4/FLV，以保证切换输入路由后仍保留所选容器。表中未限制采样率的编码器使用完整预设列表。

位深度控件用于 PCM、FLAC、ALAC、WavPack；码率控件只用于接受目标码率的编码器。码率按精确的 bps 保存并传给 FFmpeg，因此 AMR-NB 的 4.75 kbps、AMR-WB 的 23.85 kbps 等值不会被取整。文件扩展名和上传 MIME 类型统一取自实际编码设置，AMR-WB 使用 `audio/amr-wb`，裸 PCM 使用 `application/octet-stream`。

上表表示本地输出能力，所配置的转写服务还需接受对应编码、容器和采样率。裸 PCM 没有格式头，接收方需要获知匹配的采样格式、采样率和声道数；可使用目标设置执行端点测试，确认服务兼容性。

### 后处理配置

公共配置依次为 Provider、Base URL、API Key、Model，下方是「新增提示词」、已保存条目和「测试连接」。支持 OpenAI-Compatible、OpenAI Responses、OpenAI Completions、Google、Anthropic、DeepSeek、Qwen、GLM。OpenAI Completions 使用 `/chat/completions` 接口。提示词放入对应 Provider 的系统／开发者级指令位置，选中文本作为用户输入。

点击「新增提示词」或已有条目，在弹窗内配置 Provider、图标、标题、提示词内容和附加 JSON。点击保存后，提示词及其独立 API Key 一并持久化。保存时校验标题、内容和附加 JSON，允许 API 字段尚未填完，测试或执行时再检查。编辑弹窗支持删除，列表的上下箭头调整菜单顺序；公共配置通过「保存设置」提交。

新增提示词和升级前已保存的提示词默认选择「与主 Provider 相同」（Same as main provider）。这里的「主」指「改写 API 设置」中的公共配置，整套继承 Provider、Base URL、API Key 和 Model。选择具体 Provider 后完全使用该条目的 API 字段，即使它与主 Provider 相同，空字段也不会借用公共值。切回继承模式会隐藏并保留独立字段及密钥，但请求不使用这些隐藏值。

图标提供 8 个内置选项，也可导入 SVG、PNG、JPG。自定义图标复制到应用私有目录，单个文件最多 1 MiB，总计最多 8 MiB；配置导出携带被引用的图标，缺失或无效资源回退到内置图标。

公共「测试连接」使用当前公共配置发送最小文本请求，遵循「重试设置」。转写和正常执行提示词也遵循该设置。每次执行提示词时只解析一次实际 API 配置；任务运行期间修改设置，不会改变该任务及其重试所用的 API、输入、附加 JSON 和重试参数。

选择独立 Provider 后，提示词编辑弹窗会显示「测试连接」。它使用尚未保存的 Provider、Base URL、API Key 和 Model，以及固定的最小指令和输入，忽略条目的标题、提示词正文与附加 JSON。测试只发一次请求，不自动重试，不保存设置，也不写入编辑器或剪贴板。修改 API 字段、切换 Provider、关闭弹窗或销毁页面都会取消该测试；旋转屏幕会保留草稿并取消测试，恢复后不自动重测。公共连接测试同样不写入编辑器或剪贴板。

### 附加 JSON 合并

输入应为 JSON 对象，可留空。先生成基础请求，再应用附加 JSON：对象逐层合并，数组整体替换，其他值覆盖同层同名字段。附加 JSON 中对象字段的值为 `null` 时删除该字段，原来不存在也不创建；数组中的 `null` 元素仍是数组值。保存、编辑和导出保留删除指令，只在生成请求时执行。

例如某条提示词配置以下参数，将使用 `another-model`，并删除基础请求中的 `temperature`：

```json
{
  "model": "another-model",
  "temperature": null,
  "metadata": { "task": "summary" }
}
```

所选公共或独立 API 配置中的 Model 是默认值，最终请求按合并后的 Model 校验。Google 的最终模型用于请求 URL。附加参数应符合所选 Provider 的接口格式；字段删除后缺少接口必需参数时会收到配置或服务端错误。

配置导出使用 `schemaVersion: 6`，音频码率使用精确的 `audioOutput.bitrateBps`，同时包含界面语言 `language` 标签、后处理公共配置、有序提示词及可选的独立 API 配置、自定义图标。提示词的 `provider` 为 `null` 表示继承，独立配置保存 `provider`、`baseUrl` 和 `model`。继续支持导入旧版 1～5 配置，其中 1～4 版的 `bitrateKbps` 换算为 bps；保留已有 PCM/WAV 设置，包括无符号 8-bit。旧版 Opus 的 32/44.1 kHz 选择迁移为 48 kHz，与此前实际编码输出一致。1～3 版配置的提示词默认为空列表，提示词缺少 `provider` 时继承主 API，没有 `language` 字段时默认英文。无效编码组合会在应用导入前被拒绝。

“始终复制到剪贴板”默认开启。关闭后恢复为仅兜底模式：只有当前焦点明确写入失败时才复制。无法确认写入结果时，不会自动重试或复制，因为文本仍可能已写入编辑器。

应使用 HTTPS。音频和待处理文本分别直接发送到配置的 Base URL；Dictate 不提供 API、代理或账号系统。转写、公共改写 API 和各提示词的独立 API Key 使用 Android Keystore 中的 AES 密钥分别加密，导出不含密钥。提示词密钥按稳定的条目 ID 保存，改名和排序不改变归属，删除条目时一并清理。

导入密钥需要明确确认，包括提示词中可选的 `apiKey`。导入条目未提供 `apiKey` 时，只有条目 ID、Provider 和去掉首尾空白的 Base URL 均与现有条目一致，才保留原密钥；否则密钥为空。显式提供空字符串 `apiKey` 会清除已有密钥。

## 许可证

`GPL-3.0-or-later`。详见 [LICENSE](LICENSE)、[NOTICE](NOTICE)、[THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md)。
