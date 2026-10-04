package com.joeykot.dictate.model

enum class AppLanguage(val tag: String, val nativeName: String) {
    ENGLISH("en", "English"),
    CHINESE("zh", "中文"),
    JAPANESE("ja", "日本語"),
    GERMAN("de", "Deutsch"),
    FRENCH("fr", "Français"),
    RUSSIAN("ru", "Русский");

    companion object {
        fun fromTag(tag: String?): AppLanguage? = entries.find { it.tag == tag }
    }
}
