package com.joeykot.dictate.ui

import android.app.Activity
import android.app.AlertDialog
import android.os.Looper
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import com.joeykot.dictate.DictateApplication
import com.joeykot.dictate.R
import com.joeykot.dictate.model.AdvancedAudioConfig
import com.joeykot.dictate.model.AdvancedRemoteAudioConfig
import com.joeykot.dictate.model.RemoteAudioCredentialIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = DictateApplication::class)
@LooperMode(LooperMode.Mode.PAUSED)
class RemoteAudioEditorTest {
    @Test
    @Suppress("DEPRECATION")
    fun newWebDavEditorStoresActualCredentialsUnderInternalIdsAndAcceptsKeyboardInput() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            val view = AdvancedAudioSettingsView(activity)
            activity.setContentView(view)
            view.load(
                AdvancedAudioConfig(
                    enabled = true,
                    workflowJson = remoteWorkflowFixture(),
                ),
            )
            assertTrue(view.validateAndApply())

            button(view, activity.getString(R.string.advanced_audio_configure_remote)).performClick()
            val dialog = checkNotNull(ShadowAlertDialog.getLatestAlertDialog())
            val root = checkNotNull(dialog.window).decorView
            findViews<Spinner>(root).single().setSelection(1)
            shadowOf(Looper.getMainLooper()).idle()

            val username = editTextForLabel(root, activity.getString(R.string.advanced_audio_username_secret))
            val password = editTextForLabel(root, activity.getString(R.string.advanced_audio_password_secret))
            assertEquals("", username.text.toString())
            assertEquals("", password.text.toString())
            assertEquals(
                InputType.TYPE_TEXT_VARIATION_PASSWORD,
                password.inputType and InputType.TYPE_MASK_VARIATION,
            )

            val attributes = checkNotNull(dialog.window).attributes
            assertEquals(
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
                attributes.softInputMode and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST,
            )
            assertEquals(0, attributes.flags and WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)

            username.setText(" storage-user ")
            password.setText(" storage-password ")
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()

            val remote = view.readConfig().remoteAudio as? AdvancedRemoteAudioConfig.WebDav
            assertNotNull(remote)
            assertEquals(RemoteAudioCredentialIds.WEB_DAV_USERNAME, remote!!.usernameSecretId)
            assertEquals(RemoteAudioCredentialIds.WEB_DAV_PASSWORD, remote.passwordSecretId)
            assertEquals(
                mapOf(
                    RemoteAudioCredentialIds.WEB_DAV_USERNAME to " storage-user ",
                    RemoteAudioCredentialIds.WEB_DAV_PASSWORD to " storage-password ",
                ),
                view.readSecrets(),
            )
        } finally {
            shadowOf(Looper.getMainLooper()).idle()
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun webDavCredentialsStayOutOfWorkflowSecretsAndMigrateLegacyIds() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            val view = AdvancedAudioSettingsView(activity)
            activity.setContentView(view)
            view.load(
                AdvancedAudioConfig(
                    enabled = true,
                    workflowJson = remoteWorkflowFixture(),
                    remoteAudio = AdvancedRemoteAudioConfig.WebDav(
                        usernameSecretId = "legacy_webdav_username",
                        passwordSecretId = "legacy_webdav_password",
                    ),
                ),
                secrets = mapOf(
                    "api_key" to "workflow-secret",
                    "legacy_webdav_username" to "alice",
                    "legacy_webdav_password" to "storage-password",
                ),
            )
            assertTrue(view.validateAndApply())

            assertNotNull(findByDescription(view, "advanced_secret:api_key"))
            assertNull(findByDescription(view, "advanced_secret:legacy_webdav_username"))
            assertNull(findByDescription(view, "advanced_secret:legacy_webdav_password"))

            button(view, activity.getString(R.string.advanced_audio_configure_remote)).performClick()
            val dialog = checkNotNull(ShadowAlertDialog.getLatestAlertDialog())
            val root = checkNotNull(dialog.window).decorView
            assertEquals("alice", editTextForLabel(root, activity.getString(R.string.advanced_audio_username_secret)).text.toString())
            assertEquals("storage-password", editTextForLabel(root, activity.getString(R.string.advanced_audio_password_secret)).text.toString())
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()

            val remote = view.readConfig().remoteAudio as? AdvancedRemoteAudioConfig.WebDav
            assertEquals(RemoteAudioCredentialIds.WEB_DAV_USERNAME, remote?.usernameSecretId)
            assertEquals(RemoteAudioCredentialIds.WEB_DAV_PASSWORD, remote?.passwordSecretId)
            assertEquals(
                mapOf(
                    "api_key" to "workflow-secret",
                    RemoteAudioCredentialIds.WEB_DAV_USERNAME to "alice",
                    RemoteAudioCredentialIds.WEB_DAV_PASSWORD to "storage-password",
                ),
                view.readSecrets(),
            )
            assertFalse(view.readSecrets().containsKey("legacy_webdav_username"))
            assertFalse(view.readSecrets().containsKey("legacy_webdav_password"))
            assertNull(findByDescription(view, "advanced_secret:${RemoteAudioCredentialIds.WEB_DAV_USERNAME}"))
            assertNull(findByDescription(view, "advanced_secret:${RemoteAudioCredentialIds.WEB_DAV_PASSWORD}"))
        } finally {
            shadowOf(Looper.getMainLooper()).idle()
            controller.pause().stop().destroy()
        }
    }

    private fun button(root: View, label: String): Button = findViews<Button>(root).single {
        it.text.toString() == label
    }

    private fun editTextForLabel(root: View, label: String): EditText {
        val text = findViews<TextView>(root).single { it.text.toString() == label }
        return findViews<EditText>(text.parent as ViewGroup).single()
    }

    private fun findByDescription(root: View, description: String): View? {
        if (root.contentDescription?.toString() == description) return root
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                findByDescription(root.getChildAt(index), description)?.let { return it }
            }
        }
        return null
    }

    private fun fixture(path: String): String = checkNotNull(javaClass.classLoader?.getResourceAsStream(path)) {
        "Missing test fixture $path"
    }.bufferedReader().use { it.readText() }

    private fun remoteWorkflowFixture(): String = fixture("advanced_audio/workflows/valid-v2-typed-request.json")
        .replace("\"type\": \"base64\"", "\"type\": \"public_https_url\"")
        .replace("{{audio:base64}}", "{{audio:public_url}}")

    private inline fun <reified T : View> findViews(root: View): List<T> = findViews(root, T::class.java)

    @Suppress("UNCHECKED_CAST")
    private fun <T : View> findViews(root: View, type: Class<T>): List<T> = buildList {
        if (type.isInstance(root)) add(root as T)
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) addAll(findViews(root.getChildAt(index), type))
        }
    }
}
