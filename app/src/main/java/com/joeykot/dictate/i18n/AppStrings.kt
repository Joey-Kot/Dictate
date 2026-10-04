package com.joeykot.dictate.i18n

import android.content.Context
import java.util.Locale

/** Localized messages for services and validation code without an Activity context. */
object AppStrings {
    @Volatile
    private var localizedContext: Context? = null

    fun refresh(context: Context) {
        // Keep Activity resources independent so its saved-locale check can recreate the UI.
        localizedContext = AppLocale.wrap(context.applicationContext)
    }

    fun get(resourceId: Int, englishFallback: String, vararg args: Any?): String {
        val context = localizedContext
        return if (context != null) {
            if (args.isEmpty()) context.getString(resourceId) else context.getString(resourceId, *args)
        } else {
            // Pure JVM validation tests have no Android Application.
            if (args.isEmpty()) englishFallback else String.format(Locale.ENGLISH, englishFallback, *args)
        }
    }
}
