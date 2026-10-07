package com.joeykot.dictate.model

import java.util.UUID

enum class PostProcessingProvider(val label: String) {
    OPENAI_COMPATIBLE("OpenAI-Compatible"),
    OPENAI_RESPONSES("OpenAI Responses"),
    OPENAI_COMPLETIONS("OpenAI Completions"),
    GOOGLE("Google"),
    ANTHROPIC("Anthropic"),
    DEEPSEEK("DeepSeek"),
    QWEN("Qwen"),
    GLM("GLM"),
}

data class PromptConfig(
    val id: String = UUID.randomUUID().toString(),
    val icon: String = "document",
    val customIcon: String? = null,
    val title: String = "",
    val prompt: String = "",
    val additionalJson: String = "",
    /** Null means Same as main provider, including all of its API fields. */
    val provider: PostProcessingProvider? = null,
    val baseUrl: String = "",
    val model: String = "",
) {
    fun effectiveApi(main: PostProcessingConfig, mainKey: String, promptKey: String): ResolvedPostProcessingApi =
        if (provider == null) {
            ResolvedPostProcessingApi(main.copy(prompts = emptyList()), mainKey)
        } else {
            ResolvedPostProcessingApi(PostProcessingConfig(provider, baseUrl, model), promptKey)
        }
}

/** Immutable per-job API snapshot. Keep credentials out of generated toString output. */
class ResolvedPostProcessingApi(val config: PostProcessingConfig, val apiKey: String)

data class PostProcessingConfig(
    val provider: PostProcessingProvider = PostProcessingProvider.OPENAI_COMPATIBLE,
    val baseUrl: String = "",
    val model: String = "",
    val prompts: List<PromptConfig> = emptyList(),
)
