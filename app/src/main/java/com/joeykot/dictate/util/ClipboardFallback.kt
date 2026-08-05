package com.joeykot.dictate.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

object ClipboardFallback {
    fun copy(context: Context, text: String): Boolean = try {
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return false
        clipboard.setPrimaryClip(ClipData.newPlainText("Dictate transcription", text))
        true
    } catch (_: Exception) {
        false
    }
}

