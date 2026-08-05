package com.joeykot.dictate.job

import android.content.Context
import android.util.Base64
import java.io.File

object ConnectivityTestAudio {
    private const val ASSET_NAME = "connectivity_test.pcm.b64"

    fun writeTo(context: Context, destination: File) {
        destination.parentFile?.mkdirs()
        val encoded = context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
        destination.writeBytes(Base64.decode(encoded, Base64.DEFAULT))
    }
}

