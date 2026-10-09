package com.joeykot.dictate.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import com.joeykot.dictate.advanced_audio.AudioDeliveryType
import com.joeykot.dictate.R
import com.joeykot.dictate.advanced_audio.AdvancedAudioWorkflow
import com.joeykot.dictate.advanced_audio.AdvancedAudioWorkflowCodec
import com.joeykot.dictate.advanced_audio.JsonValue
import com.joeykot.dictate.advanced_audio.JsonValueCodec
import com.joeykot.dictate.advanced_audio.ParameterDefinition
import com.joeykot.dictate.advanced_audio.ParameterType
import com.joeykot.dictate.advanced_audio.WorkflowValidator
import com.joeykot.dictate.model.AdvancedAudioConfig
import com.joeykot.dictate.model.AdvancedRemoteAudioConfig
import com.joeykot.dictate.model.RemoteAudioCredentialIds

/**
 * Standalone settings content for the opt-in declarative Advanced Audio API.
 *
 * Persistence, workflow generation, and protocol execution intentionally stay
 * outside this view. A host loads the stored configuration, reads it back when
 * saving, and supplies callbacks for generation and test execution.
 */
class AdvancedAudioSettingsView(
    private val activity: Activity,
    private val callbacks: Callbacks = Callbacks(),
) : LinearLayout(activity) {
    data class WorkflowGenerationRequest(
        val userRequirements: String,
        val vendorMaterial: String,
    )

    data class WorkflowTestRequest(
        val config: AdvancedAudioConfig,
        val secrets: Map<String, String>,
    )

    data class WorkflowTestResult(
        val success: Boolean,
        val message: String = "",
    )

    /**
     * The host owns Rewrite/network work. The document inputs are deliberately
     * separate and are passed through verbatim, with no UI-side length cap.
     */
    open class Callbacks {
        open fun onGenerateWorkflow(
            request: WorkflowGenerationRequest,
            completion: (Result<String>) -> Unit,
        ) {
            completion(Result.failure(UnsupportedOperationException("Workflow generation is not configured")))
        }

        open fun onTestWorkflow(
            request: WorkflowTestRequest,
            completion: (WorkflowTestResult) -> Unit,
        ) {
            completion(WorkflowTestResult(false, "Workflow testing is not configured"))
        }

        /** Called after Reset clears the in-memory draft. */
        open fun onResetRequested() = Unit

        /** Cancels an in-flight generation request when its draft is discarded. */
        open fun onGenerationCancelled() = Unit

        /** Called for structural changes; text typing remains local to the view. */
        open fun onDraftChanged() = Unit
    }

    private data class SecretField(
        val id: String,
        val label: String,
        val required: Boolean,
        val description: String? = null,
    )

    private data class SelectChoice(
        val value: String?,
        val label: String,
    ) {
        override fun toString(): String = label
    }

    private enum class RemoteKind {
        NONE,
        WEBDAV,
        S3_COMPATIBLE,
        ALIYUN_OSS,
    }

    private val enabledSwitch = Switch(activity).apply {
        text = activity.getString(R.string.advanced_audio_enabled)
    }
    /**
     * Advanced Audio API is an opt-in replacement for the regular audio API.
     * Keep its draft views attached while disabled, so toggling the option does
     * not discard an unfinished workflow or any entered values.
     */
    private val advancedContentContainer = LinearLayout(activity).apply {
        orientation = VERTICAL
        tag = "advanced_audio_content"
    }
    private val workflowJsonInput = multiline(
        activity.getString(R.string.advanced_audio_workflow_hint),
        minimumLines = 6,
        maximumLines = 12,
    ).apply {
        typeface = Typeface.MONOSPACE
        contentDescription = activity.getString(R.string.advanced_audio_workflow)
    }
    private val validateButton = Button(activity).apply {
        text = activity.getString(R.string.advanced_audio_validate_workflow)
    }
    private val summaryText = TextView(activity).apply {
        text = activity.getString(R.string.advanced_audio_summary_empty)
        setTextIsSelectable(true)
        setPadding(dp(8), dp(8), dp(8), dp(8))
    }
    private val statusText = TextView(activity).apply {
        setTextIsSelectable(true)
        setPadding(dp(8), dp(8), dp(8), dp(8))
        visibility = GONE
    }
    /**
     * Status messages are usually only one or two lines. Keep the box out of
     * the layout while empty, then let short messages use their natural height.
     * Longer validation and transport errors remain readable in a bounded,
     * scrollable area instead of pushing the rest of Settings off screen.
     */
    private val statusContainer = boundedScrollBox(statusText, dp(120)).apply {
        tag = "advanced_audio_status"
        visibility = GONE
    }
    private val parameterContainer = LinearLayout(activity).apply { orientation = VERTICAL }
    private val secretContainer = LinearLayout(activity).apply { orientation = VERTICAL }
    private val remoteSummary = TextView(activity).apply {
        setPadding(0, dp(2), 0, 0)
        setTextColor(Color.GRAY)
    }
    private val remoteAudioButton = Button(activity).apply {
        text = activity.getString(R.string.advanced_audio_configure_remote)
    }
    private val remoteAudioContainer = LinearLayout(activity).apply {
        orientation = VERTICAL
        tag = "advanced_audio_remote_audio"
        addView(sectionLabel(activity.getString(R.string.advanced_audio_remote_audio)), matchWrap(10))
        addView(remoteSummary, matchWrap())
        addView(remoteAudioButton, matchWrap(4))
    }
    private val userRequirementsInput = multiline(
        activity.getString(R.string.advanced_audio_user_requirements_hint),
        minimumLines = 4,
        maximumLines = 8,
    )
    private val vendorMaterialInput = multiline(
        activity.getString(R.string.advanced_audio_vendor_material_hint),
        minimumLines = 5,
        maximumLines = 10,
    )
    private val generateButton = Button(activity).apply {
        text = activity.getString(R.string.advanced_audio_generate)
    }
    private val resetButton = Button(activity).apply {
        text = activity.getString(R.string.advanced_audio_reset)
    }
    private val testButton = Button(activity).apply {
        text = activity.getString(R.string.advanced_audio_test_workflow)
    }

    private val valueState = linkedMapOf<String, String>()
    private val secretState = linkedMapOf<String, String>()
    private val parameterRows = linkedMapOf<String, View>()
    private var conditionalSourceIds: Set<String> = emptySet()
    private var currentWorkflow: AdvancedAudioWorkflow? = null
    private var appliedWorkflowJson: String? = null
    private var remoteAudio: AdvancedRemoteAudioConfig = AdvancedRemoteAudioConfig.None
    private var loading = false
    private var generationSequence = 0L
    private var testSequence = 0L
    private var draftRevision = 0L
    private var generationInProgress = false
    private var lastValidationMessage: String = ""

    init {
        orientation = VERTICAL
        setPadding(0, dp(2), 0, dp(8))

        addView(enabledSwitch, matchWrap())
        addView(advancedContentContainer, matchWrap())
        advancedContentContainer.apply {
            addView(labeled(activity.getString(R.string.advanced_audio_workflow), workflowJsonInput))
            addView(validateButton, matchWrap(8))
            addView(sectionLabel(activity.getString(R.string.advanced_audio_summary)), matchWrap(10))
            addView(scrollBox(summaryText, dp(112)), matchHeight(dp(112), top = 2))
            addView(sectionLabel(activity.getString(R.string.advanced_audio_values)), matchWrap(10))
            addView(parameterContainer, matchWrap())
            addView(sectionLabel(activity.getString(R.string.advanced_audio_secrets)), matchWrap(10))
            addView(secretContainer, matchWrap())
            addView(remoteAudioContainer, matchWrap())
            addView(sectionLabel(activity.getString(R.string.advanced_audio_generator)), matchWrap(12))
            addView(labeled(activity.getString(R.string.advanced_audio_user_requirements), userRequirementsInput))
            addView(labeled(activity.getString(R.string.advanced_audio_vendor_material), vendorMaterialInput))
            addView(generateButton, matchWrap(8))
            addView(actionRow(), matchWrap(8))
            addView(statusContainer, matchWrap(6))
        }

        enabledSwitch.setOnCheckedChangeListener { _, _ ->
            updateAdvancedContentVisibility()
            markDraftChanged()
        }
        workflowJsonInput.afterTextChanged {
            updateRemoteAudioVisibility()
            markDraftChanged()
        }
        userRequirementsInput.afterTextChanged { markDraftChanged() }
        vendorMaterialInput.afterTextChanged { markDraftChanged() }
        validateButton.setOnClickListener { validateAndApply(showSuccess = true) }
        remoteAudioButton.setOnClickListener { openRemoteAudioEditor() }
        generateButton.setOnClickListener { generateWorkflow() }
        resetButton.setOnClickListener { resetDraft() }
        testButton.setOnClickListener { testWorkflow() }

        renderEmptyDynamicForm()
        updateRemoteSummary()
        updateRemoteAudioVisibility()
        updateAdvancedContentVisibility()
    }

    /** Loads a persisted non-secret config plus its Keystore-backed secret snapshot. */
    fun load(config: AdvancedAudioConfig, secrets: Map<String, String>) {
        cancelPendingOperations()
        loading = true
        try {
            valueState.clear()
            valueState.putAll(config.values)
            secretState.clear()
            secretState.putAll(secrets)
            remoteAudio = config.remoteAudio
            enabledSwitch.isChecked = config.enabled
            workflowJsonInput.setText(config.workflowJson.orEmpty())
            currentWorkflow = null
            appliedWorkflowJson = null
            renderEmptyDynamicForm()
            updateRemoteSummary()
            updateRemoteAudioVisibility()
            if (!config.workflowJson.isNullOrBlank()) {
                validateAndApply(showSuccess = false)
            }
            // Disabled/incomplete drafts remain editable and no result from a
            // replaced test or generation may survive a load/import.
            hideStatus()
            updateAdvancedContentVisibility()
        } finally {
            loading = false
        }
    }

    /** Convenience overload for a new, empty Advanced Audio API draft. */
    fun load(config: AdvancedAudioConfig) = load(config, emptyMap())

    fun readConfig(): AdvancedAudioConfig = AdvancedAudioConfig(
        enabled = enabledSwitch.isChecked,
        workflowJson = workflowJsonInput.text.toString().takeIf { it.isNotBlank() },
        values = readableValues(),
        remoteAudio = remoteAudio,
    )

    /** Returns only values that can belong to the currently applied workflow. */
    fun readSecrets(): Map<String, String> = readableSecrets()

    /** True while a generated document could otherwise race with Save or Import. */
    fun isGenerationInProgress(): Boolean = generationInProgress

    /**
     * Invalidates all asynchronous work associated with this draft.
     *
     * The host is also asked to cancel generation.  A network call that is
     * already unwinding may still complete, but its sequence can no longer
     * update this view.
     */
    fun cancelPendingOperations() {
        val hadGeneration = generationInProgress
        generationSequence++
        testSequence++
        generationInProgress = false
        updateGenerationControls()
        testButton.isEnabled = true
        if (hadGeneration) callbacks.onGenerationCancelled()
    }

    /** Lets a host place generated or imported JSON into the editor without rebuilding on each character. */
    fun setWorkflowJson(document: String, apply: Boolean = false) {
        if (generationInProgress) cancelPendingOperations()
        workflowJsonInput.setText(document)
        if (apply) validateAndApply(showSuccess = true)
    }

    /** Explicit local validation and form refresh. It never clears values hidden by visible_when. */
    fun validateAndApply(showSuccess: Boolean = true): Boolean {
        val document = workflowJsonInput.text.toString().trim()
        if (document.isEmpty()) {
            lastValidationMessage = activity.getString(R.string.advanced_audio_workflow_empty)
            showStatus(lastValidationMessage, isError = true)
            updateRemoteAudioVisibility()
            return false
        }
        val workflow = try {
            AdvancedAudioWorkflowCodec.parse(document)
        } catch (error: IllegalArgumentException) {
            lastValidationMessage = error.message ?: error.javaClass.simpleName
            showStatus(activity.getString(R.string.advanced_audio_validation_failed, lastValidationMessage), isError = true)
            updateRemoteAudioVisibility()
            return false
        }
        val errors = validateWorkflowDraft(workflow)
        if (errors.isNotEmpty()) {
            lastValidationMessage = errors.joinToString("\n")
            showStatus(activity.getString(R.string.advanced_audio_validation_failed, lastValidationMessage), isError = true)
            updateRemoteAudioVisibility()
            return false
        }

        currentWorkflow = workflow
        appliedWorkflowJson = document
        renderParameterInputs(workflow)
        renderSecretInputs(workflow)
        renderSummary(workflow)
        updateRemoteSummary()
        updateRemoteAudioVisibility()
        lastValidationMessage = ""
        if (showSuccess) {
            showStatus(activity.getString(R.string.advanced_audio_workflow_valid), isError = false)
        } else {
            hideStatus()
        }
        markDraftChanged()
        return true
    }

    private fun actionRow(): LinearLayout = LinearLayout(activity).apply {
        orientation = HORIZONTAL
        addView(resetButton, weighted())
        addView(testButton, weighted(left = 8))
    }

    private fun renderEmptyDynamicForm() {
        parameterRows.clear()
        conditionalSourceIds = emptySet()
        parameterContainer.removeAllViews()
        parameterContainer.addView(TextView(activity).apply {
            text = activity.getString(R.string.advanced_audio_values_empty)
            setTextColor(Color.GRAY)
            setPadding(0, dp(2), 0, dp(2))
        })
        renderSecretInputs(null)
        summaryText.text = activity.getString(R.string.advanced_audio_summary_empty)
    }

    private fun renderParameterInputs(workflow: AdvancedAudioWorkflow) {
        parameterRows.clear()
        conditionalSourceIds = workflow.parameters.mapNotNull { it.visibleWhen?.parameter }.toSet()
        parameterContainer.removeAllViews()
        if (workflow.parameters.isEmpty()) {
            parameterContainer.addView(TextView(activity).apply {
                text = activity.getString(R.string.advanced_audio_values_empty)
                setTextColor(Color.GRAY)
                setPadding(0, dp(2), 0, dp(2))
            })
            return
        }
        workflow.parameters.forEach { definition ->
            val row = parameterRow(definition, workflow.schemaVersion)
            parameterRows[definition.id] = row
            parameterContainer.addView(row, matchWrap(4))
        }
        updateParameterVisibility()
    }

    private fun parameterRow(definition: ParameterDefinition, schemaVersion: Int): View {
        val title = buildString {
            append(definition.label)
            if (definition.required) append(" · ").append(activity.getString(R.string.advanced_audio_required))
        }
        val container = LinearLayout(activity).apply {
            orientation = VERTICAL
            contentDescription = "advanced_parameter:${definition.id}"
            addView(TextView(activity).apply {
                text = title
                setPadding(0, dp(6), 0, dp(2))
            })
            definition.description?.takeIf { it.isNotBlank() }?.let { description ->
                addView(TextView(activity).apply {
                    text = description
                    textSize = 12f
                    setTextColor(Color.GRAY)
                    setPadding(0, 0, 0, dp(2))
                })
            }
        }
        val type = runCatching { definition.effectiveType(schemaVersion) }.getOrElse { ParameterType.TEXT }
        container.addView(createParameterControl(definition, type), matchWrap())
        return container
    }

    private fun createParameterControl(definition: ParameterDefinition, type: ParameterType): View = when (type) {
        ParameterType.TEXT -> textParameterInput(definition, InputType.TYPE_CLASS_TEXT, emptyMeansUnset = false)
        ParameterType.INTEGER -> textParameterInput(
            definition,
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED,
            emptyMeansUnset = true,
        )
        ParameterType.NUMBER -> textParameterInput(
            definition,
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED or InputType.TYPE_NUMBER_FLAG_DECIMAL,
            emptyMeansUnset = true,
        )
        ParameterType.BOOLEAN -> booleanParameterInput(definition)
        ParameterType.SELECT -> selectParameterInput(definition)
        ParameterType.MULTI_SELECT -> multiSelectParameterInput(definition)
        ParameterType.JSON_OBJECT,
        ParameterType.JSON_ARRAY,
        -> jsonParameterInput(definition)
    }

    private fun textParameterInput(
        definition: ParameterDefinition,
        inputType: Int,
        emptyMeansUnset: Boolean,
    ): EditText = EditText(activity).apply {
        setSingleLine(true)
        this.inputType = inputType
        val initial = storedOrDefault(definition).orEmpty()
        setText(initial)
        setSelection(text.length)
        afterTextChanged { value ->
            if (value.isEmpty() && emptyMeansUnset) {
                valueState.remove(definition.id)
            } else {
                valueState[definition.id] = value
            }
            markDraftChanged()
            updateVisibilityFrom(definition.id)
        }
    }

    private fun jsonParameterInput(definition: ParameterDefinition): EditText = multiline(
        hint = if (definition.parameterType == ParameterType.JSON_OBJECT) "{}" else "[]",
        minimumLines = 3,
        maximumLines = 8,
    ).apply {
        typeface = Typeface.MONOSPACE
        storedOrDefault(definition)?.let {
            setText(it)
            setSelection(text.length)
        }
        afterTextChanged { value ->
            if (value.isBlank()) valueState.remove(definition.id) else valueState[definition.id] = value
            markDraftChanged()
            updateVisibilityFrom(definition.id)
        }
    }

    private fun booleanParameterInput(definition: ParameterDefinition): Switch = Switch(activity).apply {
        val raw = storedOrDefault(definition)
        isChecked = raw == "true"
        setOnCheckedChangeListener { _, checked ->
            if (!loading) {
                valueState[definition.id] = checked.toString()
                markDraftChanged()
                updateVisibilityFrom(definition.id)
            }
        }
    }

    private fun selectParameterInput(definition: ParameterDefinition): Spinner {
        val initial = storedOrDefault(definition)
        val choices = buildList {
            add(SelectChoice(null, activity.getString(R.string.advanced_audio_not_set)))
            definition.options.forEach { add(SelectChoice(it.value, it.label)) }
            if (initial != null && definition.options.none { it.value == initial }) {
                add(SelectChoice(initial, initial))
            }
        }
        return Spinner(activity).apply {
            adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_item, choices).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            setSelection(choices.indexOfFirst { it.value == initial }.takeIf { it >= 0 } ?: 0)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (loading) return
                    val selected = choices.getOrNull(position)?.value
                    if (selected == null) valueState.remove(definition.id) else valueState[definition.id] = selected
                    markDraftChanged()
                    updateVisibilityFrom(definition.id)
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }
    }

    private fun multiSelectParameterInput(definition: ParameterDefinition): LinearLayout {
        val selected = parseMultiSelect(storedOrDefault(definition))
        return LinearLayout(activity).apply {
            orientation = VERTICAL
            definition.options.forEach { option ->
                addView(CheckBox(activity).apply {
                    text = option.label
                    isChecked = option.value in selected
                    setOnCheckedChangeListener { _, _ ->
                        if (loading) return@setOnCheckedChangeListener
                        val checked = (0 until childCount)
                            .mapNotNull { getChildAt(it) as? CheckBox }
                            .filter { it.isChecked }
                            .mapNotNull { it.tag as? String }
                        // An explicit empty selection is distinct from an
                        // absent value: removing it would make a nonempty
                        // workflow default appear again on the next render.
                        // Persist `[]` and let required-value validation
                        // report an empty selection where applicable.
                        valueState[definition.id] = JsonValueCodec.stringify(
                            JsonValue.Array(checked.map { JsonValue.Text(it) }),
                        )
                        markDraftChanged()
                        updateVisibilityFrom(definition.id)
                    }
                    tag = option.value
                }, matchWrap())
            }
        }
    }

    private fun renderSecretInputs(workflow: AdvancedAudioWorkflow?) {
        secretContainer.removeAllViews()
        val fields = declaredSecretFields(workflow)
        if (fields.isEmpty()) {
            secretContainer.addView(TextView(activity).apply {
                text = activity.getString(R.string.advanced_audio_secrets_empty)
                setTextColor(Color.GRAY)
                setPadding(0, dp(2), 0, dp(2))
            })
            return
        }
        fields.forEach { field ->
            secretContainer.addView(LinearLayout(activity).apply {
                orientation = VERTICAL
                contentDescription = "advanced_secret:${field.id}"
                addView(TextView(activity).apply {
                    text = buildString {
                        append(field.label)
                        if (field.required) append(" · ").append(activity.getString(R.string.advanced_audio_required))
                    }
                    setPadding(0, dp(6), 0, dp(2))
                })
                field.description?.takeIf { it.isNotBlank() }?.let { description ->
                    addView(TextView(activity).apply {
                        text = description
                        textSize = 12f
                        setTextColor(Color.GRAY)
                        setPadding(0, 0, 0, dp(2))
                    })
                }
                addView(EditText(activity).apply {
                    hint = activity.getString(R.string.advanced_audio_secret_hint, field.label)
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                    setSingleLine(true)
                    setText(secretState[field.id].orEmpty())
                    setSelection(text.length)
                    afterTextChanged { value ->
                        secretState[field.id] = value
                        markDraftChanged()
                    }
                }, matchWrap())
            }, matchWrap(4))
        }
    }

    private fun declaredSecretFields(workflow: AdvancedAudioWorkflow?): List<SecretField> {
        val fields = LinkedHashMap<String, SecretField>()
        workflow?.secrets.orEmpty().forEach {
            fields[it.id] = SecretField(it.id, it.label, it.required, it.description)
        }
        return fields.values.toList()
    }

    private fun renderSummary(workflow: AdvancedAudioWorkflow) {
        summaryText.text = activity.getString(
            R.string.advanced_audio_summary_format,
            workflow.schemaVersion,
            workflow.name,
            workflow.recognition.modeName,
            workflow.audio.delivery.wireName,
            workflow.parameters.size,
            declaredSecretFields(workflow).size,
        )
    }

    private fun updateParameterVisibility() {
        val workflow = currentWorkflow ?: return
        workflow.parameters.forEach { definition ->
            parameterRows[definition.id]?.visibility = if (isVisible(definition, workflow)) VISIBLE else GONE
        }
    }

    private fun updateVisibilityFrom(parameterId: String) {
        if (parameterId in conditionalSourceIds) updateParameterVisibility()
    }

    /** visible_when only projects the form; it never clears values or changes required semantics. */
    private fun isVisible(definition: ParameterDefinition, workflow: AdvancedAudioWorkflow): Boolean {
        val condition = definition.visibleWhen ?: return true
        val source = workflow.parameter(condition.parameter) ?: return true
        val value = valueState[source.id] ?: source.defaultValue ?: return false
        return condition.equals?.let { value == it } ?: value in condition.oneOf
    }

    private fun storedOrDefault(definition: ParameterDefinition): String? =
        valueState[definition.id] ?: definition.defaultValue

    private fun parseMultiSelect(value: String?): Set<String> = try {
        val array = value?.let(JsonValueCodec::parse) as? JsonValue.Array ?: return emptySet()
        array.values.mapNotNull { (it as? JsonValue.Text)?.value }.toSet()
    } catch (_: IllegalArgumentException) {
        emptySet()
    }

    private fun validateWorkflowDraft(workflow: AdvancedAudioWorkflow): List<String> =
        WorkflowValidator.validateWorkflow(workflow).map { it.toString() }

    private fun validateCurrentValues(workflow: AdvancedAudioWorkflow): List<String> = buildList {
        workflow.parameters.forEach { definition ->
            val value = valueState[definition.id] ?: definition.defaultValue
            if (value == null) {
                if (definition.required) add(activity.getString(R.string.advanced_audio_required_value_missing, definition.label))
                return@forEach
            }
            runCatching { definition.parseValue(workflow.schemaVersion, value) }
                .onFailure { error -> add("${definition.label}: ${error.message}") }
            if (definition.required && isEmptyRequired(definition, workflow.schemaVersion, value)) {
                add(activity.getString(R.string.advanced_audio_required_value_missing, definition.label))
            }
        }
        declaredSecretFields(workflow).forEach { field ->
            if (field.required && secretState[field.id].isNullOrEmpty()) {
                add(activity.getString(R.string.advanced_audio_required_secret_missing, field.label))
            }
        }
    }

    private fun isEmptyRequired(definition: ParameterDefinition, schemaVersion: Int, value: String): Boolean = when (
        runCatching { definition.effectiveType(schemaVersion) }.getOrDefault(ParameterType.TEXT)
    ) {
        ParameterType.TEXT -> value.isEmpty()
        ParameterType.MULTI_SELECT -> parseMultiSelect(value).isEmpty()
        else -> false
    }

    private fun testWorkflow() {
        val workflow = if (currentWorkflow != null && appliedWorkflowJson == workflowJsonInput.text.toString().trim()) {
            currentWorkflow
        } else {
            if (!validateAndApply(showSuccess = false)) return
            currentWorkflow
        } ?: return
        val inputErrors = validateCurrentValues(workflow)
        if (inputErrors.isNotEmpty()) {
            showStatus(
                activity.getString(R.string.advanced_audio_invalid_values, inputErrors.joinToString("\n")),
                isError = true,
            )
            return
        }
        val token = ++testSequence
        val revision = draftRevision
        testButton.isEnabled = false
        showStatus(activity.getString(R.string.advanced_audio_testing), isError = false)
        callbacks.onTestWorkflow(WorkflowTestRequest(readConfig(), readSecrets())) { result ->
            post {
                if (token != testSequence || activity.isFinishing || activity.isDestroyed) return@post
                testButton.isEnabled = true
                if (revision != draftRevision) return@post
                if (result.success) {
                    showStatus(
                        listOf(activity.getString(R.string.advanced_audio_test_success), result.message)
                            .filter { it.isNotBlank() }
                            .joinToString("\n"),
                        isError = false,
                    )
                } else {
                    showStatus(
                        activity.getString(R.string.advanced_audio_test_failed, result.message.ifBlank { "Unknown error" }),
                        isError = true,
                    )
                }
            }
        }
    }

    private fun generateWorkflow() {
        // Generation freezes the draft, so an earlier asynchronous test result
        // must not re-enable its button or replace the generating status.
        testSequence++
        val token = ++generationSequence
        generationInProgress = true
        updateGenerationControls()
        showStatus(activity.getString(R.string.advanced_audio_generating), isError = false)
        callbacks.onGenerateWorkflow(
            WorkflowGenerationRequest(
                userRequirements = userRequirementsInput.text.toString(),
                vendorMaterial = vendorMaterialInput.text.toString(),
            ),
        ) { result ->
            post {
                if (token != generationSequence || activity.isFinishing || activity.isDestroyed) return@post
                generationInProgress = false
                updateGenerationControls()
                result.fold(
                    onSuccess = { document ->
                        workflowJsonInput.setText(document)
                        if (!validateAndApply(showSuccess = false)) {
                            showStatus(
                                activity.getString(
                                    R.string.advanced_audio_generation_invalid,
                                    lastValidationMessage.ifBlank { "Unknown validation error" },
                                ),
                                isError = true,
                            )
                        } else {
                            showStatus(activity.getString(R.string.advanced_audio_workflow_valid), isError = false)
                        }
                    },
                    onFailure = { error ->
                        showStatus(
                            activity.getString(
                                R.string.advanced_audio_generation_failed,
                                error.message ?: error.javaClass.simpleName,
                            ),
                            isError = true,
                        )
                    },
                )
            }
        }
    }

    private fun resetDraft() {
        cancelPendingOperations()
        testButton.isEnabled = true
        loading = true
        try {
            enabledSwitch.isChecked = false
            workflowJsonInput.setText("")
            userRequirementsInput.setText("")
            vendorMaterialInput.setText("")
            valueState.clear()
            secretState.clear()
            remoteAudio = AdvancedRemoteAudioConfig.None
            currentWorkflow = null
            appliedWorkflowJson = null
            renderEmptyDynamicForm()
            updateRemoteSummary()
            updateRemoteAudioVisibility()
            updateAdvancedContentVisibility()
        } finally {
            loading = false
        }
        showStatus(activity.getString(R.string.advanced_audio_reset_complete), isError = false)
        callbacks.onResetRequested()
        markDraftChanged()
    }

    @Suppress("DEPRECATION") // SOFT_INPUT_ADJUST_RESIZE remains necessary for legacy Dialog windows.
    private fun openRemoteAudioEditor() {
        val choices = listOf(
            RemoteKind.NONE to activity.getString(R.string.advanced_audio_remote_none),
            RemoteKind.WEBDAV to activity.getString(R.string.advanced_audio_remote_webdav),
            RemoteKind.S3_COMPATIBLE to activity.getString(R.string.advanced_audio_remote_s3),
            RemoteKind.ALIYUN_OSS to activity.getString(R.string.advanced_audio_remote_oss),
        )
        val typeInput = Spinner(activity).apply {
            adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_item, choices.map { it.second }).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
        }
        val fields = LinearLayout(activity).apply { orientation = VERTICAL }
        var inputs: RemoteEditorInputs? = null
        fun selectedKind(): RemoteKind = choices.getOrNull(typeInput.selectedItemPosition)?.first ?: RemoteKind.NONE
        fun renderFields(kind: RemoteKind) {
            fields.removeAllViews()
            inputs = createRemoteEditorInputs(fields, kind)
        }
        typeInput.setSelection(choices.indexOfFirst { it.first == remoteKind(remoteAudio) }.coerceAtLeast(0))
        renderFields(selectedKind())
        typeInput.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                renderFields(selectedKind())
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        val content = LinearLayout(activity).apply {
            orientation = VERTICAL
            setPadding(dp(20), dp(4), dp(20), dp(8))
            addView(labeled(activity.getString(R.string.advanced_audio_remote_type), typeInput))
            addView(fields, matchWrap())
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.advanced_audio_remote_audio))
            .setView(ScrollView(activity).apply { addView(content) })
            .setNegativeButton(activity.getString(R.string.advanced_audio_cancel), null)
            .setPositiveButton(activity.getString(R.string.advanced_audio_save)) { _, _ ->
                val previousCredentialIds = RemoteAudioCredentialIds.forConfig(remoteAudio)
                val result = inputs?.toResult(selectedKind())
                    ?: RemoteEditorResult(AdvancedRemoteAudioConfig.None, emptyMap())
                remoteAudio = result.config
                result.credentialValues.forEach { (id, value) -> secretState[id] = value }
                // Migrate legacy custom credential IDs once this dialog is
                // saved. A workflow may independently declare one of those
                // old IDs, so leave that entry intact for the workflow editor
                // rather than silently deleting a user-managed secret.
                val workflowSecretIds = currentWorkflow?.secrets.orEmpty().mapTo(linkedSetOf()) { it.id }
                (previousCredentialIds - RemoteAudioCredentialIds.forConfig(result.config) - workflowSecretIds)
                    .forEach(secretState::remove)
                renderSecretInputs(currentWorkflow)
                updateRemoteSummary()
                markDraftChanged()
            }
            .show()
        // This editor contains text inputs.  Explicitly make its window an IME
        // target and resize it above the keyboard so its lower fields and
        // action buttons remain reachable on compact screens.
        dialog.window?.apply {
            clearFlags(android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
            setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }

    private class RemoteEditorInputs(
        private val text: Map<String, EditText>,
        private val toggles: Map<String, Switch>,
        private val credentials: Map<String, EditText>,
    ) {
        fun toResult(kind: RemoteKind): RemoteEditorResult = when (kind) {
            RemoteKind.NONE -> RemoteEditorResult(AdvancedRemoteAudioConfig.None, emptyMap())
            RemoteKind.WEBDAV -> {
                val usernameId = RemoteAudioCredentialIds.WEB_DAV_USERNAME
                val passwordId = RemoteAudioCredentialIds.WEB_DAV_PASSWORD
                RemoteEditorResult(
                    AdvancedRemoteAudioConfig.WebDav(
                        uploadBaseUrl = value("uploadBaseUrl"),
                        usernameSecretId = usernameId,
                        passwordSecretId = passwordId,
                        remotePathPrefix = value("remotePathPrefix"),
                        publicDownloadBaseUrl = value("publicDownloadBaseUrl"),
                        deleteAfterRecognition = checked("deleteAfterRecognition"),
                    ),
                    mapOf(
                        usernameId to credential("username"),
                        passwordId to credential("password"),
                    ),
                )
            }
            RemoteKind.S3_COMPATIBLE -> {
                val accessKeyId = RemoteAudioCredentialIds.S3_ACCESS_KEY
                val secretKeyId = RemoteAudioCredentialIds.S3_SECRET_KEY
                RemoteEditorResult(
                    AdvancedRemoteAudioConfig.S3Compatible(
                        endpoint = value("endpoint"),
                        region = value("region"),
                        bucket = value("bucket"),
                        accessKeySecretId = accessKeyId,
                        secretKeySecretId = secretKeyId,
                        prefix = value("prefix"),
                        publicUrlBase = value("publicUrlBase").ifBlank { null },
                        presigned = checked("presigned"),
                        deleteAfterRecognition = checked("deleteAfterRecognition"),
                    ),
                    mapOf(
                        accessKeyId to credential("accessKey"),
                        secretKeyId to credential("secretKey"),
                    ),
                )
            }
            RemoteKind.ALIYUN_OSS -> {
                val accessKeyId = RemoteAudioCredentialIds.OSS_ACCESS_KEY
                val secretKeyId = RemoteAudioCredentialIds.OSS_SECRET_KEY
                RemoteEditorResult(
                    AdvancedRemoteAudioConfig.AliyunOss(
                        endpoint = value("endpoint"),
                        bucket = value("bucket"),
                        accessKeySecretId = accessKeyId,
                        secretKeySecretId = secretKeyId,
                        prefix = value("prefix"),
                        publicUrlBase = value("publicUrlBase").ifBlank { null },
                        presigned = checked("presigned"),
                        deleteAfterRecognition = checked("deleteAfterRecognition"),
                    ),
                    mapOf(
                        accessKeyId to credential("accessKey"),
                        secretKeyId to credential("secretKey"),
                    ),
                )
            }
        }

        private fun value(key: String): String = text[key]?.text?.toString()?.trim().orEmpty()
        private fun credential(key: String): String = credentials[key]?.text?.toString().orEmpty()
        private fun checked(key: String): Boolean = toggles[key]?.isChecked ?: false
    }

    private data class RemoteEditorResult(
        val config: AdvancedRemoteAudioConfig,
        val credentialValues: Map<String, String>,
    )

    private fun createRemoteEditorInputs(container: LinearLayout, kind: RemoteKind): RemoteEditorInputs {
        val inputs = linkedMapOf<String, EditText>()
        val toggles = linkedMapOf<String, Switch>()
        val credentials = linkedMapOf<String, EditText>()
        fun textField(key: String, label: String, value: String) {
            val input = EditText(activity).apply {
                setSingleLine(true)
                setText(value)
                setSelection(this.text.length)
            }
            inputs[key] = input
            container.addView(labeled(label, input))
        }
        fun credentialField(key: String, label: String, value: String, concealed: Boolean) {
            val input = EditText(activity).apply {
                setSingleLine(true)
                inputType = InputType.TYPE_CLASS_TEXT or if (concealed) {
                    InputType.TYPE_TEXT_VARIATION_PASSWORD
                } else {
                    InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                }
                setText(value)
                setSelection(this.text.length)
            }
            credentials[key] = input
            container.addView(labeled(label, input))
        }
        fun toggle(key: String, label: String, value: Boolean) {
            val input = Switch(activity).apply {
                text = label
                isChecked = value
            }
            toggles[key] = input
            container.addView(input, matchWrap(4))
        }
        when (kind) {
            RemoteKind.NONE -> Unit
            RemoteKind.WEBDAV -> {
                val settings = remoteAudio as? AdvancedRemoteAudioConfig.WebDav ?: AdvancedRemoteAudioConfig.WebDav()
                textField("uploadBaseUrl", activity.getString(R.string.advanced_audio_upload_base_url), settings.uploadBaseUrl)
                credentialField(
                    "username",
                    activity.getString(R.string.advanced_audio_username_secret),
                    secretState[settings.usernameSecretId].orEmpty(),
                    concealed = false,
                )
                credentialField(
                    "password",
                    activity.getString(R.string.advanced_audio_password_secret),
                    secretState[settings.passwordSecretId].orEmpty(),
                    concealed = true,
                )
                textField("remotePathPrefix", activity.getString(R.string.advanced_audio_remote_path_prefix), settings.remotePathPrefix)
                textField(
                    "publicDownloadBaseUrl",
                    activity.getString(R.string.advanced_audio_public_download_base_url),
                    settings.publicDownloadBaseUrl,
                )
                toggle(
                    "deleteAfterRecognition",
                    activity.getString(R.string.advanced_audio_delete_after_recognition),
                    settings.deleteAfterRecognition,
                )
            }
            RemoteKind.S3_COMPATIBLE -> {
                val settings = remoteAudio as? AdvancedRemoteAudioConfig.S3Compatible ?: AdvancedRemoteAudioConfig.S3Compatible()
                textField("endpoint", activity.getString(R.string.advanced_audio_endpoint), settings.endpoint)
                textField("region", activity.getString(R.string.advanced_audio_region), settings.region)
                textField("bucket", activity.getString(R.string.advanced_audio_bucket), settings.bucket)
                credentialField(
                    "accessKey",
                    activity.getString(R.string.advanced_audio_access_key_secret),
                    secretState[settings.accessKeySecretId].orEmpty(),
                    concealed = false,
                )
                credentialField(
                    "secretKey",
                    activity.getString(R.string.advanced_audio_secret_key_secret),
                    secretState[settings.secretKeySecretId].orEmpty(),
                    concealed = true,
                )
                textField("prefix", activity.getString(R.string.advanced_audio_prefix), settings.prefix)
                textField("publicUrlBase", activity.getString(R.string.advanced_audio_public_url_base), settings.publicUrlBase.orEmpty())
                toggle("presigned", activity.getString(R.string.advanced_audio_presigned), settings.presigned)
                toggle(
                    "deleteAfterRecognition",
                    activity.getString(R.string.advanced_audio_delete_after_recognition),
                    settings.deleteAfterRecognition,
                )
            }
            RemoteKind.ALIYUN_OSS -> {
                val settings = remoteAudio as? AdvancedRemoteAudioConfig.AliyunOss ?: AdvancedRemoteAudioConfig.AliyunOss()
                textField("endpoint", activity.getString(R.string.advanced_audio_endpoint), settings.endpoint)
                textField("bucket", activity.getString(R.string.advanced_audio_bucket), settings.bucket)
                credentialField(
                    "accessKey",
                    activity.getString(R.string.advanced_audio_access_key_secret),
                    secretState[settings.accessKeySecretId].orEmpty(),
                    concealed = false,
                )
                credentialField(
                    "secretKey",
                    activity.getString(R.string.advanced_audio_secret_key_secret),
                    secretState[settings.secretKeySecretId].orEmpty(),
                    concealed = true,
                )
                textField("prefix", activity.getString(R.string.advanced_audio_prefix), settings.prefix)
                textField("publicUrlBase", activity.getString(R.string.advanced_audio_public_url_base), settings.publicUrlBase.orEmpty())
                toggle("presigned", activity.getString(R.string.advanced_audio_presigned), settings.presigned)
                toggle(
                    "deleteAfterRecognition",
                    activity.getString(R.string.advanced_audio_delete_after_recognition),
                    settings.deleteAfterRecognition,
                )
            }
        }
        return RemoteEditorInputs(inputs, toggles, credentials)
    }

    private fun remoteKind(config: AdvancedRemoteAudioConfig): RemoteKind = when (config) {
        AdvancedRemoteAudioConfig.None -> RemoteKind.NONE
        is AdvancedRemoteAudioConfig.WebDav -> RemoteKind.WEBDAV
        is AdvancedRemoteAudioConfig.S3Compatible -> RemoteKind.S3_COMPATIBLE
        is AdvancedRemoteAudioConfig.AliyunOss -> RemoteKind.ALIYUN_OSS
    }

    private fun updateRemoteSummary() {
        val description = when (remoteAudio) {
            AdvancedRemoteAudioConfig.None -> activity.getString(R.string.advanced_audio_remote_none)
            is AdvancedRemoteAudioConfig.WebDav -> activity.getString(R.string.advanced_audio_remote_webdav)
            is AdvancedRemoteAudioConfig.S3Compatible -> activity.getString(R.string.advanced_audio_remote_s3)
            is AdvancedRemoteAudioConfig.AliyunOss -> activity.getString(R.string.advanced_audio_remote_oss)
        }
        remoteSummary.text = activity.getString(R.string.advanced_audio_remote_summary, description)
    }

    /** Remote storage is relevant only after the currently displayed workflow is validated. */
    private fun updateRemoteAudioVisibility() {
        val workflow = currentWorkflow
        val isCurrentValidatedWorkflow = workflow != null &&
            appliedWorkflowJson == workflowJsonInput.text.toString().trim()
        val needsRemoteAudio = isCurrentValidatedWorkflow && (
            workflow.audio.delivery == AudioDeliveryType.PUBLIC_HTTPS_URL ||
                workflow.audio.delivery == AudioDeliveryType.CLOUD_URI
            )
        remoteAudioContainer.visibility = if (needsRemoteAudio) VISIBLE else GONE
    }

    /** Every editable draft input routes here so stale test callbacks are never shown. */
    private fun markDraftChanged() {
        if (loading) return
        draftRevision++
        callbacks.onDraftChanged()
    }

    /** Generation uses a snapshot, so source and target inputs remain immutable until it ends or is cancelled. */
    private fun updateGenerationControls() {
        val enabled = !generationInProgress
        enabledSwitch.isEnabled = enabled
        workflowJsonInput.isEnabled = enabled
        validateButton.isEnabled = enabled
        remoteAudioButton.isEnabled = enabled
        userRequirementsInput.isEnabled = enabled
        vendorMaterialInput.isEnabled = enabled
        generateButton.isEnabled = enabled
        testButton.isEnabled = enabled
        setEnabledRecursively(parameterContainer, enabled)
        setEnabledRecursively(secretContainer, enabled)
    }

    private fun updateAdvancedContentVisibility() {
        advancedContentContainer.visibility = if (enabledSwitch.isChecked) VISIBLE else GONE
    }

    private fun setEnabledRecursively(view: View, enabled: Boolean) {
        view.isEnabled = enabled
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                setEnabledRecursively(view.getChildAt(index), enabled)
            }
        }
    }

    private fun readableValues(): Map<String, String> {
        val workflow = currentWorkflow
        if (workflow == null || appliedWorkflowJson != workflowJsonInput.text.toString().trim()) return valueState.toMap()
        val declared = workflow.parameters.map { it.id }.toSet()
        return valueState.filterKeys(declared::contains)
    }

    private fun readableSecrets(): Map<String, String> {
        val workflow = currentWorkflow
        if (workflow == null || appliedWorkflowJson != workflowJsonInput.text.toString().trim()) return secretState.toMap()
        val declared = declaredSecretFields(workflow).mapTo(linkedSetOf()) { it.id }
        declared += RemoteAudioCredentialIds.forConfig(remoteAudio)
        return secretState.filterKeys(declared::contains)
    }

    private fun showStatus(message: String, isError: Boolean) {
        statusText.text = message
        statusText.setTextColor(if (isError) 0xFFB3261E.toInt() else Color.GRAY)
        statusText.visibility = VISIBLE
        statusContainer.visibility = VISIBLE
    }

    private fun hideStatus() {
        statusText.text = ""
        statusText.visibility = GONE
        statusContainer.visibility = GONE
    }

    private fun sectionLabel(text: String): TextView = TextView(activity).apply {
        this.text = text
        textSize = 16f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(2), 0, dp(2))
    }

    private fun labeled(label: String, control: View): LinearLayout = LinearLayout(activity).apply {
        orientation = VERTICAL
        addView(TextView(activity).apply { text = label; setPadding(0, dp(8), 0, dp(2)) })
        addView(control, matchWrap())
    }

    /** maxLines limits the visible area only; it never truncates a pasted document. */
    private fun multiline(hint: String, minimumLines: Int, maximumLines: Int): EditText = EditText(activity).apply {
        this.hint = hint
        setSingleLine(false)
        minLines = minimumLines
        maxLines = maximumLines
        gravity = Gravity.TOP
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        isVerticalScrollBarEnabled = true
        scrollBarStyle = View.SCROLLBARS_INSIDE_INSET
        overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
    }

    private fun scrollBox(content: TextView, height: Int): ScrollView = ScrollView(activity).apply {
        isFillViewport = false
        isVerticalScrollBarEnabled = true
        addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, height)
    }

    private fun boundedScrollBox(content: TextView, maximumHeight: Int): ScrollView =
        BoundedHeightScrollView(activity, maximumHeight).apply {
            isFillViewport = false
            isVerticalScrollBarEnabled = true
            addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

    private fun EditText.afterTextChanged(callback: (String) -> Unit) {
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(editable: Editable?) {
                if (!loading) callback(editable?.toString().orEmpty())
            }
        })
    }

    private fun matchWrap(top: Int = 0): LayoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        .apply { topMargin = dp(top) }

    private fun matchHeight(height: Int, top: Int = 0): LayoutParams = LayoutParams(LayoutParams.MATCH_PARENT, height)
        .apply { topMargin = dp(top) }

    private fun weighted(left: Int = 0): LayoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        .apply { leftMargin = dp(left) }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

}

/** A ScrollView that uses content height until the configured maximum is reached. */
private class BoundedHeightScrollView(
    context: Context,
    private val maximumHeight: Int,
) : ScrollView(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val parentMode = View.MeasureSpec.getMode(heightMeasureSpec)
        val parentMaximum = when (parentMode) {
            View.MeasureSpec.UNSPECIFIED -> maximumHeight
            else -> minOf(View.MeasureSpec.getSize(heightMeasureSpec), maximumHeight)
        }
        super.onMeasure(
            widthMeasureSpec,
            View.MeasureSpec.makeMeasureSpec(parentMaximum, View.MeasureSpec.AT_MOST),
        )
    }
}
