package com.joeykot.dictate.settings

import com.joeykot.dictate.R
import com.joeykot.dictate.i18n.AppStrings
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Portable copies of custom icons. Import preview never changes files or existing settings. */
internal object PromptIconAssets {
    const val MAX_ICON_BYTES = 1024 * 1024
    const val MAX_TOTAL_BYTES = 8 * 1024 * 1024
    private val FILE_NAME = Regex("[A-Za-z0-9_-]{1,128}\\.(png|jpg|jpeg|svg)", RegexOption.IGNORE_CASE)

    fun isSafeFileName(value: String): Boolean = FILE_NAME.matches(value)

    fun encode(directory: File, names: Set<String>): JSONObject = JSONObject().apply {
        var totalBytes = 0L
        names.forEach { name ->
            require(isSafeFileName(name)) { AppStrings.get(R.string.val_icon_filename_invalid, "Invalid custom icon filename") }
            val file = File(directory, name)
            // A deleted or unavailable custom image falls back to its preset icon.
            if (!file.isFile) return@forEach
            require(file.length() <= MAX_ICON_BYTES) { AppStrings.get(R.string.val_icon_size, "Custom icons cannot exceed 1 MiB") }
            totalBytes += file.length()
            require(totalBytes <= MAX_TOTAL_BYTES) { AppStrings.get(R.string.val_icons_total_size, "Custom icons cannot exceed 8 MiB in total") }
            put(name, Base64.encodeToString(file.readBytes(), Base64.NO_WRAP))
        }
    }

    fun decode(root: JSONObject?, referencedNames: Set<String>): Map<String, ByteArray> {
        if (root == null) return emptyMap()
        val result = linkedMapOf<String, ByteArray>()
        var totalBytes = 0L
        val names = root.keys()
        while (names.hasNext()) {
            val name = names.next()
            require(isSafeFileName(name)) { AppStrings.get(R.string.val_icon_filename_invalid, "Invalid custom icon filename") }
            require(name in referencedNames) { AppStrings.get(R.string.val_icon_not_referenced, "The configuration contains an icon not used by any prompt") }
            val encoded = root.opt(name) as? String
                ?: throw IllegalArgumentException(AppStrings.get(R.string.val_icon_base64_type, "Custom icon data must be a Base64 string"))
            require(encoded.length <= ((MAX_ICON_BYTES + 2) / 3) * 4) { AppStrings.get(R.string.val_icon_size, "Custom icons cannot exceed 1 MiB") }
            val bytes = try {
                Base64.decode(encoded, Base64.NO_WRAP)
            } catch (_: IllegalArgumentException) {
                throw IllegalArgumentException(AppStrings.get(R.string.val_icon_base64_invalid, "Invalid Base64 data for the custom icon"))
            }
            require(bytes.isNotEmpty() && bytes.size <= MAX_ICON_BYTES) { AppStrings.get(R.string.val_icon_empty_or_large, "The custom icon is empty or exceeds 1 MiB") }
            totalBytes += bytes.size
            require(totalBytes <= MAX_TOTAL_BYTES) { AppStrings.get(R.string.val_icons_total_size, "Custom icons cannot exceed 8 MiB in total") }
            result[name] = bytes
        }
        return result
    }

    /** Unique destinations prevent an import from overwriting icons used by existing settings. */
    fun write(directory: File, assets: Map<String, ByteArray>): Map<String, String> {
        if (assets.isEmpty()) return emptyMap()
        require(assets.values.sumOf { it.size.toLong() } <= MAX_TOTAL_BYTES) {
            AppStrings.get(R.string.val_icons_total_size, "Custom icons cannot exceed 8 MiB in total")
        }
        check(directory.isDirectory || directory.mkdirs()) { AppStrings.get(R.string.val_icon_directory, "Cannot create the icon directory") }
        val installed = linkedMapOf<String, String>()
        try {
            assets.forEach { (name, bytes) ->
                require(isSafeFileName(name)) { AppStrings.get(R.string.val_icon_filename_invalid, "Invalid custom icon filename") }
                require(bytes.isNotEmpty() && bytes.size <= MAX_ICON_BYTES) { AppStrings.get(R.string.val_icon_empty_or_large, "The custom icon is empty or exceeds 1 MiB") }
                val newName = "${UUID.randomUUID()}.${name.substringAfterLast('.').lowercase()}"
                installed[name] = newName
                File(directory, newName).writeBytes(bytes)
            }
        } catch (error: Exception) {
            installed.values.forEach { File(directory, it).delete() }
            throw error
        }
        return installed
    }
}
