package com.joeykot.dictate.ui

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.joeykot.dictate.R
import com.joeykot.dictate.job.VoiceJobController
import com.joeykot.dictate.model.PostProcessingConfig
import com.joeykot.dictate.model.PostProcessingProvider
import com.joeykot.dictate.model.PromptConfig
import com.joeykot.dictate.network.AdditionalParameters
import com.joeykot.dictate.settings.SettingsRepository
import com.joeykot.dictate.util.PromptIconStore
import java.lang.ref.WeakReference
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Keeps prompt edits immediately persisted without saving unrelated form drafts. */
class PostProcessingSettingsView(
    private val activity: Activity,
    private val repository: SettingsRepository,
    private val enqueueWrite: (operation: () -> Unit, onSuccess: () -> Unit, onFailure: (Exception) -> Unit) -> Boolean,
    private val chooseIcon: (String) -> Unit,
    private val testConnection: (PostProcessingConfig, String, (VoiceJobController.ConnectionTestResult) -> Unit) -> Boolean,
) : LinearLayout(activity) {
    private val providerInput = Spinner(activity).apply {
        adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item,
            PostProcessingProvider.entries.map { it.label })
    }
    private val baseUrlInput = input("https://example.com/v1")
    private val apiKeyInput = input(activity.getString(R.string.prompt_api_key)).apply {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
    }
    private val modelInput = input(activity.getString(R.string.prompt_model_hint))
    private val promptList = LinearLayout(activity).apply { orientation = VERTICAL }
    private val testButton = Button(activity).apply { text = activity.getString(R.string.prompt_test_connection) }
    private val testResult = TextView(activity).apply {
        setTextIsSelectable(true)
        setPadding(dp(4), dp(8), dp(4), 0)
    }
    private var editor: AlertDialog? = null
    private var editorId: String? = null
    private var captureEditor: (() -> Bundle?)? = null
    private var deliverIcon: ((Result<PromptIconStore.ImportedIcon>) -> Unit)? = null
    private var beginImport: ((Uri) -> Unit)? = null
    private var iconImport: IconImport? = null
    private var preserveEditorDraft = false
    private var savedDraftIcons = emptySet<String>()

    private class IconImport(val editorId: String, val uri: Uri) {
        val cancelled = AtomicBoolean(false)
    }

    init {
        orientation = VERTICAL
        setPadding(0, dp(2), 0, dp(8))
        addView(LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(activity).apply { text = activity.getString(R.string.prompt_provider); textSize = 15f },
                LayoutParams(0, LayoutParams.WRAP_CONTENT, .42f))
            addView(providerInput, LayoutParams(0, LayoutParams.WRAP_CONTENT, .58f))
        })
        addView(labeled(activity.getString(R.string.prompt_base_url), baseUrlInput))
        addView(labeled(activity.getString(R.string.prompt_api_key), apiKeyInput))
        addView(labeled(activity.getString(R.string.prompt_model), modelInput))
        addView(Button(activity).apply {
            text = activity.getString(R.string.prompt_add)
            setOnClickListener { openEditor(null) }
        }, matchWrap(10))
        addView(promptList, matchWrap())
        addView(testButton, matchWrap(10))
        addView(testResult, matchWrap())
        testButton.setOnClickListener { runTest() }
    }

    fun load(config: PostProcessingConfig, apiKey: String) {
        providerInput.setSelection(config.provider.ordinal)
        baseUrlInput.setText(config.baseUrl)
        apiKeyInput.setText(apiKey)
        modelInput.setText(config.model)
        refreshPrompts()
    }

    fun readConfig(): PostProcessingConfig = PostProcessingConfig(
        provider = PostProcessingProvider.entries[providerInput.selectedItemPosition.coerceAtLeast(0)],
        baseUrl = baseUrlInput.text.toString().trim(),
        model = modelInput.text.toString().trim(),
        // Prompts are saved independently; never overwrite them with the form's initial snapshot.
        prompts = repository.get().postProcessing.prompts,
    )

    fun readApiKey(): String = apiKeyInput.text.toString().trim()

    fun saveEditorState(): Bundle? = captureEditor?.invoke()?.also {
        savedDraftIcons = it.getStringArrayList("importedIcons")?.toSet().orEmpty()
    }

    fun restoreEditorState(state: Bundle) {
        val initial = PromptConfig(
            id = state.getString("id") ?: return,
            icon = state.getString("icon") ?: "document",
            customIcon = state.getString("customIcon"),
            title = state.getString("title").orEmpty(),
            prompt = state.getString("prompt").orEmpty(),
            additionalJson = state.getString("json").orEmpty(),
        )
        openEditor(initial, state)
    }

    fun onIconPicked(id: String, uri: Uri) {
        if (editorId == id && editor?.isShowing == true) beginImport?.invoke(uri)
    }

    fun closeEditor(preserveDraft: Boolean = false) {
        preserveEditorDraft = preserveDraft
        cancelIconImport()
        editor?.dismiss()
    }

    private fun cancelIconImport() {
        iconImport?.cancelled?.set(true)
        iconImport = null
    }

    private fun importIcon(id: String, uri: Uri) {
        cancelIconImport()
        val work = IconImport(id, uri)
        iconImport = work
        val receiver = WeakReference(this)
        val context = activity.applicationContext
        ICON_EXECUTOR.execute {
            val result = runCatching { PromptIconStore.importDecoded(context, uri) }
            if (work.cancelled.get()) {
                result.getOrNull()?.let {
                    PromptIconStore.deleteIfUnused(context, it.name, SettingsRepository(context).get().postProcessing.prompts)
                }
                return@execute
            }
            ICON_HANDLER.post {
                val view = receiver.get()
                if (view == null || view.iconImport !== work || view.editorId != work.editorId ||
                    view.editor?.isShowing != true || view.activity.isDestroyed
                ) {
                    result.getOrNull()?.let { imported ->
                        ICON_EXECUTOR.execute {
                            PromptIconStore.deleteIfUnused(context, imported.name,
                                SettingsRepository(context).get().postProcessing.prompts)
                        }
                    }
                } else {
                    view.iconImport = null
                    view.deliverIcon?.invoke(result)
                }
            }
        }
    }

    private fun refreshPrompts() {
        promptList.removeAllViews()
        val prompts = repository.get().postProcessing.prompts
        prompts.forEachIndexed { index, prompt ->
            promptList.addView(LinearLayout(activity).apply {
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(52)
                val open = LinearLayout(activity).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(4), dp(6), dp(4), dp(6))
                    isClickable = true
                    isFocusable = true
                    contentDescription = activity.getString(R.string.prompt_edit_description, prompt.title)
                    addView(iconView(prompt), LayoutParams(dp(34), dp(34)))
                    addView(TextView(activity).apply {
                        text = prompt.title
                        textSize = 16f
                        setPadding(dp(10), 0, 0, 0)
                    }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
                    setOnClickListener { openEditor(prompt) }
                }
                addView(open, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
                addView(moveButton("↑", activity.getString(R.string.prompt_move_up, prompt.title), index > 0) { move(prompt.id, -1) },
                    LayoutParams(dp(44), dp(48)))
                addView(moveButton("↓", activity.getString(R.string.prompt_move_down, prompt.title), index < prompts.lastIndex) { move(prompt.id, 1) },
                    LayoutParams(dp(44), dp(48)))
            }, matchWrap())
            promptList.addView(View(activity).apply { setBackgroundColor(0x22808080) },
                LayoutParams(LayoutParams.MATCH_PARENT, dp(1)))
        }
    }

    private fun moveButton(label: String, description: String, enabled: Boolean, action: () -> Unit) =
        Button(activity).apply {
            text = label
            contentDescription = description
            minWidth = 0
            minimumWidth = 0
            setPadding(0, 0, 0, 0)
            isEnabled = enabled
            setOnClickListener { action() }
        }

    private fun move(id: String, offset: Int) {
        val prompts = repository.get().postProcessing.prompts.toMutableList()
        val source = prompts.indexOfFirst { it.id == id }
        val target = source + offset
        if (source < 0 || target !in prompts.indices) return
        val prompt = prompts.removeAt(source)
        prompts.add(target, prompt)
        enqueueWrite({ repository.savePrompts(prompts) }, { refreshPrompts() }, { toast(it.message ?: activity.getString(R.string.prompt_order_save_failed)) })
    }

    private fun openEditor(original: PromptConfig?, restored: Bundle? = null) {
        if (editor?.isShowing == true) return
        val initial = original ?: PromptConfig()
        val editingExisting = restored?.getBoolean("editingExisting") ?: (original != null)
        val sessionId = restored?.getString("editorId") ?: UUID.randomUUID().toString()
        editorId = sessionId
        preserveEditorDraft = false
        var selectedIcon = initial.icon
        var customIcon = initial.customIcon
        val importedIcons = restored?.getStringArrayList("importedIcons")?.toMutableSet() ?: mutableSetOf()
        var saving = false
        val title = input(activity.getString(R.string.prompt_title_hint)).apply { setText(initial.title) }
        val prompt = multiline(activity.getString(R.string.prompt_content_hint), 4).apply { setText(initial.prompt) }
        val json = multiline(activity.getString(R.string.prompt_json_hint), 3).apply {
            typeface = Typeface.MONOSPACE
            setText(initial.additionalJson)
        }
        val iconRow = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        val choices = mutableListOf<Pair<String, ImageView>>()
        val customPreview = iconView(initial).apply {
            contentDescription = activity.getString(R.string.prompt_custom_icon)
            visibility = if (customIcon == null) GONE else VISIBLE
        }
        var previewDrawable: Drawable? = customPreview.drawable.takeIf { customIcon != null }
        fun updateSelection() {
            choices.forEach { (id, view) ->
                view.background = selectionBackground(customIcon == null && selectedIcon == id)
            }
            customPreview.visibility = if (customIcon == null) GONE else VISIBLE
            customPreview.setImageDrawable(if (customIcon == null) null else previewDrawable)
            customPreview.background = selectionBackground(customIcon != null)
        }
        PromptIconStore.builtins.forEach { builtin ->
            val image = iconView(PromptConfig(icon = builtin.id)).apply {
                contentDescription = builtin.label
                isClickable = true
                isFocusable = true
                setPadding(dp(5), dp(5), dp(5), dp(5))
                setOnClickListener {
                    selectedIcon = builtin.id
                    customIcon = null
                    updateSelection()
                }
            }
            choices += builtin.id to image
            iconRow.addView(image, LayoutParams(dp(52), dp(52)).apply { rightMargin = dp(4) })
        }
        iconRow.addView(customPreview, LayoutParams(dp(52), dp(52)))
        val importButton = Button(activity).apply {
            text = activity.getString(R.string.prompt_import_icon)
            setOnClickListener { chooseIcon(sessionId) }
        }
        val content = LinearLayout(activity).apply {
            orientation = VERTICAL
            setPadding(dp(20), dp(4), dp(20), dp(10))
            addView(labeled(activity.getString(R.string.prompt_icon), HorizontalScrollView(activity).apply { addView(iconRow) }))
            addView(importButton, matchWrap())
            addView(labeled(activity.getString(R.string.prompt_title), title))
            addView(labeled(activity.getString(R.string.prompt_content), prompt))
            addView(labeled(activity.getString(R.string.prompt_additional_json), json))
            addView(TextView(activity).apply {
                text = activity.getString(R.string.prompt_json_help)
                textSize = 12f
                setTextColor(Color.GRAY)
            }, matchWrap(4))
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle(if (editingExisting) activity.getString(R.string.prompt_edit) else activity.getString(R.string.prompt_add))
            .setView(ScrollView(activity).apply { addView(content) })
            .setNegativeButton(activity.getString(R.string.prompt_cancel), null)
            .setPositiveButton(activity.getString(R.string.prompt_save), null)
            .apply { if (editingExisting) setNeutralButton(activity.getString(R.string.prompt_delete), null) }
            .create()
        editor = dialog
        captureEditor = {
            // Saving/deleting has already been handed off; restoring this draft could undo that write.
            if (saving) null else Bundle().apply {
                putString("editorId", sessionId)
                putBoolean("editingExisting", editingExisting)
                putString("id", initial.id)
                putString("icon", selectedIcon)
                putString("customIcon", customIcon)
                putString("title", title.text.toString())
                putString("prompt", prompt.text.toString())
                putString("json", json.text.toString())
                putStringArrayList("importedIcons", ArrayList(importedIcons))
                putString("importUri", iconImport?.takeIf { it.editorId == sessionId }?.uri?.toString())
            }
        }
        updateSelection()
        dialog.setOnDismissListener {
            val retained = if (preserveEditorDraft && editorId == sessionId) savedDraftIcons else emptySet()
            // Dismiss callbacks are queued; an older dialog must not detach a newly opened editor.
            if (editorId == sessionId) {
                cancelIconImport()
                editor = null
                editorId = null
                captureEditor = null
                beginImport = null
                deliverIcon = null
            }
            if (!saving) {
                val prompts = repository.get().postProcessing.prompts
                val unused = importedIcons - retained
                val context = activity.applicationContext
                ICON_EXECUTOR.execute { unused.forEach { PromptIconStore.deleteIfUnused(context, it, prompts) } }
            }
        }
        dialog.show()
        // Give multiline editors room when the keyboard is visible, including on small displays.
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        fun showImporting(importing: Boolean) {
            importButton.isEnabled = !importing
            importButton.text = if (importing) activity.getString(R.string.prompt_importing_icon) else activity.getString(R.string.prompt_import_icon)
            choices.forEach { it.second.isEnabled = !importing }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = !importing && !saving
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.isEnabled = !importing && !saving
        }
        beginImport = { uri ->
            showImporting(true)
            importIcon(sessionId, uri)
        }
        deliverIcon = { result ->
            showImporting(false)
            result.fold(
                onSuccess = { imported ->
                    importedIcons += imported.name
                    customIcon = imported.name
                    previewDrawable = imported.drawable
                    updateSelection()
                },
                onFailure = { toast(it.message ?: activity.getString(R.string.prompt_import_icon_failed)) },
            )
        }
        restored?.getString("importUri")?.let { beginImport?.invoke(Uri.parse(it)) }

        fun persist(prompts: List<PromptConfig>) {
            saving = true
            dialog.setCancelable(false)
            listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL)
                .forEach { dialog.getButton(it)?.isEnabled = false }
            val accepted = enqueueWrite(
                {
                    repository.savePrompts(prompts)
                    importedIcons.forEach { PromptIconStore.deleteIfUnused(activity, it, prompts) }
                },
                {
                    saving = false
                    dialog.dismiss()
                    refreshPrompts()
                },
                {
                    saving = false
                    dialog.setCancelable(true)
                    listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL)
                        .forEach { button -> dialog.getButton(button)?.isEnabled = true }
                    toast(it.message ?: activity.getString(R.string.prompt_save_failed))
                },
            )
            if (!accepted) {
                saving = false
                dialog.setCancelable(true)
                listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL)
                    .forEach { dialog.getButton(it)?.isEnabled = true }
            }
        }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            title.error = null
            prompt.error = null
            json.error = null
            when {
                title.text.isBlank() -> { title.error = activity.getString(R.string.prompt_title_required); title.requestFocus() }
                prompt.text.isBlank() -> { prompt.error = activity.getString(R.string.prompt_content_required); prompt.requestFocus() }
                else -> {
                    try {
                        AdditionalParameters.parseObject(json.text.toString())
                    } catch (error: IllegalArgumentException) {
                        json.error = error.message ?: activity.getString(R.string.prompt_invalid_json)
                        json.requestFocus()
                        return@setOnClickListener
                    }
                    val updated = initial.copy(icon = selectedIcon, customIcon = customIcon,
                        title = title.text.toString().trim(), prompt = prompt.text.toString().trim(),
                        additionalJson = json.text.toString().trim())
                    val prompts = repository.get().postProcessing.prompts.toMutableList()
                    val index = prompts.indexOfFirst { it.id == updated.id }
                    if (index >= 0) prompts[index] = updated else prompts.add(updated)
                    persist(prompts)
                }
            }
        }
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
            persist(repository.get().postProcessing.prompts.filterNot { it.id == initial.id })
        }
    }

    private fun runTest() {
        testButton.isEnabled = false
        testResult.text = activity.getString(R.string.prompt_testing)
        val accepted = testConnection(readConfig(), readApiKey()) { result ->
            if (activity.isDestroyed) return@testConnection
            testButton.isEnabled = true
            testResult.text = buildString {
                append(if (result.success) activity.getString(R.string.prompt_test_success) else activity.getString(R.string.prompt_test_failure))
                result.statusCode?.let { append("\n" + activity.getString(R.string.prompt_test_http, it)) }
                result.elapsedMillis?.let { append("\n" + activity.getString(R.string.prompt_test_elapsed, it)) }
                if (result.text.isNotBlank()) append("\n" + activity.getString(R.string.prompt_test_text, result.text))
                if (result.message.isNotBlank()) append("\n" + activity.getString(R.string.prompt_test_result, result.message))
                if (result.serverSummary.isNotBlank()) append("\n" + activity.getString(R.string.prompt_test_server_summary, result.serverSummary))
            }
        }
        if (!accepted) {
            testButton.isEnabled = true
        }
    }

    private fun selectionBackground(selected: Boolean) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(if (selected) 0x222563EB else Color.TRANSPARENT)
        if (selected) setStroke(dp(2), 0xFF2563EB.toInt())
    }

    private fun iconView(prompt: PromptConfig) = ImageView(activity).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        setImageDrawable(PromptIconStore.load(activity, prompt))
    }

    private fun labeled(label: String, control: View) = LinearLayout(activity).apply {
        orientation = VERTICAL
        addView(TextView(activity).apply { text = label; setPadding(0, dp(8), 0, dp(2)) })
        addView(control, matchWrap())
    }

    private fun input(placeholder: String) = EditText(activity).apply {
        hint = placeholder
        setSingleLine(true)
    }

    private fun multiline(placeholder: String, lines: Int) = input(placeholder).apply {
        setSingleLine(false)
        minLines = lines
        maxLines = 8
        gravity = Gravity.TOP
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
    }

    private fun matchWrap(top: Int = 0) = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        .apply { topMargin = dp(top) }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun toast(message: String) = Toast.makeText(activity, message, Toast.LENGTH_LONG).show()

    private companion object {
        val ICON_HANDLER = Handler(Looper.getMainLooper())
        val ICON_EXECUTOR = Executors.newFixedThreadPool(2) { task ->
            Thread(task, "dictate-icon-import").apply { isDaemon = true }
        }
    }
}
