package com.joeykot.dictate.network

import com.joeykot.dictate.R
import com.joeykot.dictate.i18n.AppStrings
import com.joeykot.dictate.model.PostProcessingConfig
import com.joeykot.dictate.model.PostProcessingProvider
import com.joeykot.dictate.model.PromptConfig
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Provider-specific wire formats share one final, non-mutating parameter merge. */
object PostProcessingRequest {
    data class Request(val endpoint: String, val headers: Map<String, String>, val body: JSONObject)

    fun build(config: PostProcessingConfig, apiKey: String, prompt: PromptConfig, input: String): Request {
        require(apiKey.isNotBlank()) { AppStrings.get(R.string.val_post_api_key_empty, "The post-processing API key cannot be empty") }
        require(prompt.prompt.isNotBlank()) { AppStrings.get(R.string.val_prompt_empty, "Prompt content cannot be empty") }
        require(input.isNotEmpty()) { AppStrings.get(R.string.val_input_empty, "Text to process cannot be empty") }
        val base = JSONObject().put("model", config.model.trim())
        when (config.provider) {
            PostProcessingProvider.OPENAI_RESPONSES -> base
                .put("instructions", prompt.prompt)
                .put("input", JSONArray().put(message("user", input)))
            PostProcessingProvider.GOOGLE -> base
                .put("systemInstruction", JSONObject().put("parts", parts(prompt.prompt)))
                .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts(input))))
            PostProcessingProvider.ANTHROPIC -> base
                .put("system", prompt.prompt)
                .put("max_tokens", 4096)
                .put("messages", JSONArray().put(message("user", input)))
            else -> base.put("messages", JSONArray().put(message("system", prompt.prompt)).put(message("user", input)))
        }
        val body = AdditionalParameters.merge(base, AdditionalParameters.parseObject(prompt.additionalJson))
        val model = body.opt("model")
        require(model is String && model.isNotBlank()) { AppStrings.get(R.string.val_merged_model_type, "The merged model must be a nonempty string") }
        val endpoint = BaseUrl.postProcessingEndpoint(config.baseUrl, config.provider, model)
        val headers = when (config.provider) {
            PostProcessingProvider.ANTHROPIC -> mapOf("x-api-key" to apiKey, "anthropic-version" to "2023-06-01")
            PostProcessingProvider.GOOGLE -> mapOf("x-goog-api-key" to apiKey)
            else -> mapOf("Authorization" to "Bearer $apiKey")
        }
        // generateContent specifies the model in the URL, not the JSON body.
        if (config.provider == PostProcessingProvider.GOOGLE) body.remove("model")
        return Request(endpoint, headers, body)
    }

    fun parseText(provider: PostProcessingProvider, response: String): String {
        val root = try {
            JSONObject(response)
        } catch (_: JSONException) {
            throw InvalidResponseException(AppStrings.get(R.string.val_post_response_object, "The post-processing response is not a valid JSON object"))
        }
        val text = when (provider) {
            PostProcessingProvider.OPENAI_RESPONSES -> {
                val direct = root.opt("output_text") as? String
                direct ?: buildString {
                    val output = root.optJSONArray("output") ?: return@buildString
                    for (index in 0 until output.length()) {
                        val item = output.optJSONObject(index) ?: continue
                        if (item.optString("type") == "message") {
                            append(textBlocks(item.optJSONArray("content"), setOf("output_text")))
                        }
                    }
                }
            }
            PostProcessingProvider.ANTHROPIC -> textBlocks(root.optJSONArray("content"), setOf("text"))
            PostProcessingProvider.GOOGLE -> {
                val content = root.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")
                val parts = content?.optJSONArray("parts")
                buildString {
                    if (parts != null) for (index in 0 until parts.length()) {
                        val part = parts.optJSONObject(index) ?: continue
                        if (!part.optBoolean("thought", false)) append(part.opt("text") as? String ?: "")
                    }
                }
            }
            else -> {
                val content = root.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.opt("content")
                when (content) {
                    is String -> content
                    is JSONArray -> textBlocks(content, setOf("text"))
                    else -> ""
                }
            }
        }
        if (text.isBlank()) throw InvalidResponseException(AppStrings.get(R.string.val_post_response_empty, "The post-processing response contains no text to insert"))
        return text
    }

    private fun message(role: String, content: String): JSONObject =
        JSONObject().put("role", role).put("content", content)

    private fun parts(text: String): JSONArray = JSONArray().put(JSONObject().put("text", text))

    private fun textBlocks(blocks: JSONArray?, types: Set<String>): String = buildString {
        if (blocks != null) for (index in 0 until blocks.length()) {
            val block = blocks.optJSONObject(index) ?: continue
            if (block.optString("type") in types) append(block.opt("text") as? String ?: "")
        }
    }

    class InvalidResponseException(message: String) : Exception(message)
}
