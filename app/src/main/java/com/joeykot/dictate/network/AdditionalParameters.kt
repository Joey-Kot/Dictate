package com.joeykot.dictate.network

import com.joeykot.dictate.R
import com.joeykot.dictate.i18n.AppStrings
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

object AdditionalParameters {
    fun parseObject(json: String): JSONObject {
        if (json.isBlank()) return JSONObject()
        return try {
            val tokenizer = JSONTokener(json)
            val value = tokenizer.nextValue()
            require(value is JSONObject && tokenizer.nextClean() == '\u0000') {
                AppStrings.get(R.string.val_additional_object, "Additional parameters must be a valid JSON object")
            }
            value
        } catch (_: JSONException) {
            throw IllegalArgumentException(AppStrings.get(R.string.val_additional_object, "Additional parameters must be a valid JSON object"))
        }
    }

    /** Objects merge recursively; an explicit null deletes a field. Inputs are never mutated. */
    fun merge(base: JSONObject, extra: JSONObject): JSONObject {
        val result = JSONObject(base.toString())
        val keys = extra.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = extra.get(key)
            when {
                value === JSONObject.NULL -> result.remove(key)
                value is JSONObject -> result.put(key, merge(result.optJSONObject(key) ?: JSONObject(), value))
                else -> result.put(key, copyReplacement(value))
            }
        }
        return result
    }

    /** Serializes complete JSON values for multipart fields, after processing deletion markers. */
    fun parse(json: String): LinkedHashMap<String, String> =
        toFields(merge(JSONObject(), parseObject(json)))

    fun transcriptionFields(model: String, json: String): LinkedHashMap<String, String> {
        val additional = parseObject(json)
        // The binary audio part cannot be represented by a JSON value.
        require(!additional.has("file")) { AppStrings.get(R.string.val_additional_file_conflict, "The additional file parameter conflicts with the audio upload") }
        val merged = merge(JSONObject().put("model", model), additional)
        require(merged.opt("model") is String && merged.getString("model").isNotBlank()) {
            AppStrings.get(R.string.val_merged_model_type, "The merged model must be a nonempty string")
        }
        return toFields(merged)
    }

    internal fun multipartFieldName(name: String): String = name
        .replace("\r", "%0D")
        .replace("\n", "%0A")
        .replace("\"", "%22")

    private fun toFields(root: JSONObject): LinkedHashMap<String, String> = linkedMapOf<String, String>().apply {
        val keys = root.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            put(key, root.get(key).toString())
        }
    }

    private fun copyReplacement(value: Any): Any = when (value) {
        is JSONObject -> merge(JSONObject(), value)
        is JSONArray -> JSONArray().apply {
            for (index in 0 until value.length()) put(copyReplacement(value.get(index)))
        }
        else -> value
    }
}
