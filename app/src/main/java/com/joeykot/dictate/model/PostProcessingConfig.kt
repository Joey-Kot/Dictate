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
)

data class PostProcessingConfig(
    val provider: PostProcessingProvider = PostProcessingProvider.OPENAI_COMPATIBLE,
    val baseUrl: String = "",
    val model: String = "",
    val prompts: List<PromptConfig> = emptyList(),
)
