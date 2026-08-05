package com.joeykot.dictate.network

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

object AdditionalParameters {
    private val reserved = setOf("file", "model")

    fun parse(json: String): LinkedHashMap<String, String> {
        if (json.isBlank()) return linkedMapOf()

        val root = try {
            JSONObject(json)
        } catch (_: JSONException) {
            throw IllegalArgumentException("附加参数必须是有效的 JSON 对象")
        }

        val result = linkedMapOf<String, String>()
        val keys = root.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            require(!isReservedField(key)) {
                "附加参数不能覆盖 $key"
            }
            require(key.isNotBlank()) { "附加参数字段名不能为空" }
            require(FIELD_NAME.matches(key)) {
                "附加参数字段名 $key 只能包含字母、数字、点、下划线和连字符"
            }

            val value = root.get(key)
            when (value) {
                is JSONObject, is JSONArray -> {
                    throw IllegalArgumentException("附加参数 $key 不支持数组或嵌套对象")
                }
                JSONObject.NULL -> {
                    throw IllegalArgumentException("附加参数 $key 不能为 null")
                }
                is String, is Boolean, is Number -> result[key] = value.toString()
                else -> throw IllegalArgumentException("附加参数 $key 的类型不受支持")
            }
        }
        return result
    }

    internal fun isReservedField(name: String): Boolean =
        reserved.any { it.equals(name, ignoreCase = true) }

    private val FIELD_NAME = Regex("[A-Za-z0-9_.-]{1,128}")
}
