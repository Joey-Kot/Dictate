package com.joeykot.dictate.util

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.text.TextUtils
import com.joeykot.dictate.accessibility.DictateAccessibilityService

object AccessibilityStatus {
    fun isEnabled(context: Context): Boolean {
        val globallyEnabled = Settings.Secure.getInt(
            context.contentResolver,
            Settings.Secure.ACCESSIBILITY_ENABLED,
            0,
        ) == 1
        if (!globallyEnabled) return false

        val expected = ComponentName(context, DictateAccessibilityService::class.java)
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        val splitter = TextUtils.SimpleStringSplitter(':').apply { setString(enabledServices) }
        return splitter.any {
            ComponentName.unflattenFromString(it)?.let { component ->
                component == expected
            } == true
        }
    }
}

