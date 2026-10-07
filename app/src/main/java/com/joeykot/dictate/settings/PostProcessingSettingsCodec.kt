package com.joeykot.dictate.settings

import com.joeykot.dictate.R
import com.joeykot.dictate.i18n.AppStrings
import com.joeykot.dictate.model.PostProcessingConfig
import com.joeykot.dictate.model.PostProcessingProvider
import com.joeykot.dictate.model.PromptConfig
import com.joeykot.dictate.network.AdditionalParameters
import org.json.JSONArray
import org.json.JSONObject

/** Configuration encoding retains null values: deletion is applied only when building requests. */
internal object PostProcessingSettingsCodec {
    fun encode(config: PostProcessingConfig): JSONObject = JSONObject()
        .put("provider", config.provider.name)
        .put("baseUrl", config.baseUrl.trim())
        .put("model", config.model.trim())
        .put("prompts", encodePrompts(config.prompts))

    fun encodePrompts(prompts: List<PromptConfig>): JSONArray = JSONArray().apply {
        prompts.forEach { prompt ->
            put(JSONObject()
                .put("id", prompt.id)
                .put("icon", prompt.icon)
                .put("customIcon", prompt.customIcon ?: JSONObject.NULL)
                .put("title", prompt.title.trim())
                .put("prompt", prompt.prompt)
                .put("provider", prompt.provider?.name ?: JSONObject.NULL)
                .put("baseUrl", prompt.baseUrl.trim())
                .put("model", prompt.model.trim())
                .put("additionalParameters", AdditionalParameters.parseObject(prompt.additionalJson)))
        }
    }

    fun decode(root: JSONObject): PostProcessingConfig {
        val providerName = string(root, "provider")
        val provider = PostProcessingProvider.entries.find { it.name == providerName }
            ?: throw IllegalArgumentException(AppStrings.get(R.string.val_post_provider_invalid, "Invalid postProcessing.provider"))
        return PostProcessingConfig(
            provider = provider,
            baseUrl = string(root, "baseUrl"),
            model = string(root, "model"),
            prompts = decodePrompts(root.opt("prompts") as? JSONArray
                ?: throw IllegalArgumentException(AppStrings.get(R.string.val_post_prompts_array, "postProcessing.prompts must be an array"))),
        )
    }

    fun decodePrompts(array: JSONArray): List<PromptConfig> = List(array.length()) { index ->
        val item = array.opt(index) as? JSONObject
            ?: throw IllegalArgumentException(AppStrings.get(R.string.val_prompt_object, "Prompt %1\$d must be a JSON object", index + 1))
        val extra = item.opt("additionalParameters") as? JSONObject
            ?: throw IllegalArgumentException(AppStrings.get(R.string.val_prompt_additional_object, "Additional parameters for prompt %1\$d must be a JSON object", index + 1))
        val customIcon = if (!item.has("customIcon") || item.isNull("customIcon")) {
            null
        } else {
            string(item, "customIcon")
        }
        PromptConfig(
            id = string(item, "id"),
            icon = string(item, "icon"),
            customIcon = customIcon,
            title = string(item, "title"),
            prompt = string(item, "prompt"),
            additionalJson = if (extra.length() == 0) "" else extra.toString(),
            provider = if (!item.has("provider") || item.isNull("provider")) null else {
                val name = string(item, "provider")
                PostProcessingProvider.entries.find { it.name == name }
                    ?: throw IllegalArgumentException(AppStrings.get(R.string.val_post_provider_invalid, "Invalid postProcessing.provider"))
            },
            baseUrl = if (item.has("baseUrl")) string(item, "baseUrl") else "",
            model = if (item.has("model")) string(item, "model") else "",
        )
    }

    fun validatePrompts(prompts: List<PromptConfig>): List<String> = buildList {
        val ids = mutableSetOf<String>()
        prompts.forEachIndexed { index, prompt ->
            if (prompt.id.isBlank() || !ids.add(prompt.id)) add(AppStrings.get(R.string.val_prompt_id_invalid, "Prompt %1\$d has an empty or duplicate ID", index + 1))
            if (prompt.title.isBlank()) add(AppStrings.get(R.string.val_prompt_title_empty, "The title of prompt %1\$d cannot be empty", index + 1))
            if (prompt.prompt.isBlank()) add(AppStrings.get(R.string.val_prompt_content_empty, "The content of prompt %1\$d cannot be empty", index + 1))
            if (prompt.customIcon != null && !PromptIconAssets.isSafeFileName(prompt.customIcon)) {
                add(AppStrings.get(R.string.val_prompt_icon_invalid, "Prompt %1\$d has an invalid custom icon filename", index + 1))
            }
            try {
                AdditionalParameters.parseObject(prompt.additionalJson)
            } catch (error: IllegalArgumentException) {
                add(AppStrings.get(R.string.val_prompt_error, "Prompt %1\$d: %2\$s", index + 1, error.message ?: AppStrings.get(R.string.val_additional_invalid, "Invalid additional parameters")))
            }
        }
    }

    private fun string(root: JSONObject, key: String): String = root.opt(key) as? String
        ?: throw IllegalArgumentException(AppStrings.get(R.string.val_post_field_string, "The post-processing field %1\$s must be a string", key))
}
