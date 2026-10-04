package com.joeykot.dictate.i18n

import android.content.Context
import android.content.res.Configuration
import com.joeykot.dictate.model.AppLanguage
import java.util.Locale

object AppLocale {
    const val PREFERENCES_NAME = "settings"
    const val LANGUAGE_KEY = "app.language"

    fun readLanguage(context: Context): AppLanguage = AppLanguage.fromTag(
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .getString(LANGUAGE_KEY, null),
    ) ?: AppLanguage.ENGLISH

    fun wrap(context: Context): Context {
        val configuration = Configuration(context.resources.configuration)
        configuration.setLocale(Locale.forLanguageTag(readLanguage(context).tag))
        return context.createConfigurationContext(configuration)
    }
}
