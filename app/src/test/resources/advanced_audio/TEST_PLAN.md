# Advanced Audio API 跨端基线测试清单

本目录是 Android 对 Windows Advanced Audio API Workflow Schema v1/v2 的可复用协议基线。每个 workflow 文件都是原始、可独立解析的工作流 JSON；`manifest.json` 指定验证动作和预期结果。当前 Kotlin 实现已提供稳定的 codec、校验、渲染和执行器；这些文件同时作为跨端回归样本，测试可按协议边界选取样本，而不得改写黄金 JSON。

## 读取与解码

- 逐一读取 `manifest.json` 列出的 JSON 文件，确保 JSON 可解析。
- 工作流 JSON 必须按 Windows 的字段名、`type`/`mode` tagged union 和枚举值解码；不得引入 Android 私有字段。
- v1 参数没有 `type`、`options`、`visible_when`；v2 每个参数都必须有 `type`。
- `values` 和参数 `default` 都保持字符串。JSON 对象、数组、数字和布尔值不得在存储时自动改写为其他 JSON 类型。

## 工作流验证

- 对 `workflow_cases` 逐项运行 workflow 验证器；合法样本必须通过，非法样本必须失败，并包含 `manifest.json` 的 `error_contains` 片段。
- 覆盖四个固定模式：`request`、`request_stream`、`async_poll`、`realtime_session`。
- 验证精确的 `{{capture:id}}` 可作为动态完整结果 URL，且只能引用先前 stage 产生的 capture；任何非精确 capture 模板 URL 必须具有字面量 `http://` 或 `https://` 前缀。
- 验证 `async_poll` 的 poll 不能携带或重新引用任何音频；提交阶段不应由测试或重试策略重新提交。
- 验证实时会话只在逐块 `audio_message` 中可使用 `{{audio:chunk_base64}}`；连接、初始消息和结束消息没有音频模板值。

## 配置与条件显示

- 对 `config_cases` 逐项运行 Advanced 配置验证器。
- `enabled=false` 必须使工作流草稿、未知 values/secrets 和远程配置保持惰性，保证普通 OpenAI-compatible 音频 API 可以继续工作。
- `visible_when` 只计算控件可见性：隐藏时不得清除值、不得改变请求分支、不得放宽 required 校验。`invalid-hidden-required-missing.json` 即使 `mode=fast` 使字段隐藏，也必须失败。
- UI 侧改变条件源时仅更新已有控件可见性；不要重建整页或清除控件状态，以免输入和焦点丢失。

## 模板与类型化 JSON 渲染

- `templates/cases.json` 覆盖五个固定模板命名空间及缺失值、未知命名空间、未知固定占位符、表达式、非法标识符和括号边界。模板不能读取环境、文件或执行代码。
- `typed_rendering/v1-text-only-leaf.json`：即使变量是完整 JSON 字符串叶子，v1 也必须输出 JSON string。
- `typed_rendering/v2-native-json-leaves.json`：只有精确的 `{{var:id}}` JSON string 叶子按 v2 类型输出原生 number、boolean、array 或 object；`text` 和 `select` 仍输出 JSON string；嵌入字符串的标量按文本输出。
- `typed_rendering/v2-structured-string-context-rejections.json`：`multi_select`、`json_object`、`json_array` 不得在 URL、query、header、form、multipart text、raw bytes、实时 text/binary 或混合 JSON 字符串中被自动序列化；必须在网络 I/O 前拒绝。

## 当前自动化覆盖

- `WorkflowCoreTest` 覆盖 codec、v1/v2 工作流与配置校验、模板及类型化 JSON 渲染；非法工作流在创建网络连接前被拒绝。
- `AdvancedAudioClientTest`、`StreamFramingTest`、`ResponseExtractionTest` 和 `OkHttpAdvancedTransportTest` 覆盖 HTTP、SSE/NDJSON/JSON chunks、异步轮询、取消及流式音频上传；本地 fake 只验证协议行为，不代表厂商互操作认证。
- `RemoteAudioPublisherTest` 和 `RequestSignerTest` 覆盖 WebDAV、S3-compatible、OSS 的发布/清理与内建签名。
- `RealtimeSessionRunnerTest`、`OkHttpRealtimeWebSocketFactoryTest`、`LivePcmTeeSourceTest` 和 `PcmCaptureTeeTest` 覆盖 WebSocket 会话、握手/取消、PCM 转换、背压和完整本地 PCM 回放降级。
