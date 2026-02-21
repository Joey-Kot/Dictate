package net.devemperor.asr.settings;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.preference.EditTextPreference;
import androidx.preference.ListPreference;
import androidx.preference.MultiSelectListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.SeekBarPreference;
import androidx.preference.SwitchPreference;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import net.devemperor.asr.BuildConfig;
import net.devemperor.asr.DictateUtils;
import net.devemperor.asr.R;
import net.devemperor.asr.rewording.PromptModel;
import net.devemperor.asr.rewording.PromptsDatabaseHelper;
import net.devemperor.asr.rewording.PromptsOverviewActivity;
import net.devemperor.asr.usage.UsageActivity;
import net.devemperor.asr.usage.UsageDatabaseHelper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class PreferencesFragment extends PreferenceFragmentCompat {

    private static final String GLOBAL_CONFIG_APP_ID = "net.devemperor.asr";
    private static final int GLOBAL_CONFIG_VERSION = 1;
    private static final Set<String> SENSITIVE_PREFERENCE_KEYS = new LinkedHashSet<>(Arrays.asList(
            "net.devemperor.asr.api_key",
            "net.devemperor.asr.transcription_api_key",
            "net.devemperor.asr.transcription_api_key_openai",
            "net.devemperor.asr.transcription_api_key_groq",
            "net.devemperor.asr.transcription_api_key_custom",
            "net.devemperor.asr.rewording_api_key",
            "net.devemperor.asr.rewording_api_key_openai",
            "net.devemperor.asr.rewording_api_key_groq",
            "net.devemperor.asr.rewording_api_key_custom"
    ));

    SharedPreferences sp;
    UsageDatabaseHelper usageDatabaseHelper;
    private ActivityResultLauncher<String> exportGlobalConfigLauncher;
    private ActivityResultLauncher<String[]> importGlobalConfigLauncher;
    private boolean exportIncludeSecrets;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        getPreferenceManager().setSharedPreferencesName("net.devemperor.asr");
        setPreferencesFromResource(R.xml.fragment_preferences, null);
        sp = getPreferenceManager().getSharedPreferences();
        usageDatabaseHelper = new UsageDatabaseHelper(requireContext());
        setupGlobalConfigLaunchers();
        setupGlobalConfigPreferences();

        Preference editPromptsPreference = findPreference("net.devemperor.asr.edit_custom_rewording_prompts");
        if (editPromptsPreference != null) {
            editPromptsPreference.setOnPreferenceClickListener(preference -> {
                startActivity(new Intent(requireContext(), PromptsOverviewActivity.class));
                return true;
            });
        }

        MultiSelectListPreference inputLanguagesPreference = findPreference("net.devemperor.asr.input_languages");
        if (inputLanguagesPreference != null) {
            inputLanguagesPreference.setSummaryProvider((Preference.SummaryProvider<MultiSelectListPreference>) preference -> {
                String[] selectedLanguagesValues = preference.getValues().toArray(new String[0]);
                return Arrays.stream(selectedLanguagesValues).map(DictateUtils::translateLanguageToEmoji).collect(Collectors.joining(" "));
            });

            inputLanguagesPreference.setOnPreferenceChangeListener((preference, newValue) -> {
                Set<String> selectedLanguages = (Set<String>) newValue;
                if (selectedLanguages.isEmpty()) {
                    Toast.makeText(requireContext(), R.string.dictate_input_languages_empty, Toast.LENGTH_SHORT).show();
                    return false;
                }
                sp.edit().putInt("net.devemperor.asr.input_language_pos", 0).apply();
                return true;
            });
        }

        ListPreference appLanguagePreference = findPreference("net.devemperor.asr.app_language");
        if (appLanguagePreference != null) {
            appLanguagePreference.setOnPreferenceChangeListener((preference, newValue) -> {
                DictateUtils.applyApplicationLocale((String) newValue);
                requireActivity().recreate();
                return true;
            });
        }

        EditTextPreference overlayCharactersPreference = findPreference("net.devemperor.asr.overlay_characters");
        if (overlayCharactersPreference != null) {
            overlayCharactersPreference.setSummaryProvider((Preference.SummaryProvider<EditTextPreference>) preference -> {
                String text = preference.getText();
                if (TextUtils.isEmpty(text)) {
                    return getString(R.string.dictate_default_overlay_characters);
                }
                return text.chars().mapToObj(c -> String.valueOf((char) c)).collect(Collectors.joining(" "));
            });

            overlayCharactersPreference.setOnBindEditTextListener(editText -> {
                editText.setInputType(InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
                editText.setSingleLine(true);
                editText.setHint(R.string.dictate_default_overlay_characters);
                editText.setFilters(new InputFilter[] {new InputFilter.LengthFilter(8)});
                editText.setSelection(editText.getText().length());
            });

            overlayCharactersPreference.setOnPreferenceChangeListener((preference, newValue) -> {
                String text = (String) newValue;
                if (text.isEmpty()) {
                    Toast.makeText(requireContext(), R.string.dictate_overlay_characters_empty, Toast.LENGTH_SHORT).show();
                    return false;
                }
                return true;
            });
        }

        SwitchPreference instantOutputPreference = findPreference("net.devemperor.asr.instant_output");
        SeekBarPreference outputSpeedPreference = findPreference("net.devemperor.asr.output_speed");
        if (instantOutputPreference != null && outputSpeedPreference != null) {
            instantOutputPreference.setOnPreferenceChangeListener((preference, newValue) -> {
                outputSpeedPreference.setEnabled(!(Boolean) newValue);
                return true;
            });
            outputSpeedPreference.setEnabled(!instantOutputPreference.isChecked());
        }

        Preference usagePreference = findPreference("net.devemperor.asr.usage");
        if (usagePreference != null) {
            usagePreference.setSummary(getString(R.string.dictate_usage_total_cost, usageDatabaseHelper.getTotalCost()));

            usagePreference.setOnPreferenceClickListener(preference -> {
                Intent intent = new Intent(requireContext(), UsageActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                return true;
            });
        }

        Preference apiSettingsPreference = findPreference("net.devemperor.asr.api_settings");
        if (apiSettingsPreference != null) {
            apiSettingsPreference.setOnPreferenceClickListener(preference -> {
                Intent intent = new Intent(requireContext(), APISettingsActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                return true;
            });
        }

        Preference promptPreference = findPreference("net.devemperor.asr.prompts");
        if (promptPreference != null) {
            promptPreference.setOnPreferenceClickListener(preference -> {
                Intent intent = new Intent(requireContext(), SystemPromptsActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                return true;
            });
        }

        EditTextPreference proxyHostPreference = findPreference("net.devemperor.asr.proxy_host");
        if (proxyHostPreference != null) {
            proxyHostPreference.setSummaryProvider((Preference.SummaryProvider<EditTextPreference>) preference -> {
                String host = preference.getText();
                if (TextUtils.isEmpty(host)) return getString(R.string.dictate_settings_proxy_hint);
                return host;
            });

            proxyHostPreference.setOnBindEditTextListener(editText -> {
                editText.setInputType(InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType.TYPE_TEXT_VARIATION_URI);
                editText.setSingleLine(true);
                editText.setHint(R.string.dictate_settings_proxy_hint);
            });

            proxyHostPreference.setOnPreferenceChangeListener((preference, newValue) -> {
                String host = (String) newValue;
                if (DictateUtils.isValidProxy(host)) return true;
                else {
                    new MaterialAlertDialogBuilder(requireContext())
                            .setTitle(R.string.dictate_proxy_invalid_title)
                            .setMessage(R.string.dictate_proxy_invalid_message)
                            .setPositiveButton(R.string.dictate_okay, null)
                            .show();
                    return false;
                }
            });
        }

        Preference howToPreference = findPreference("net.devemperor.asr.how_to");
        if (howToPreference != null) {
            howToPreference.setOnPreferenceClickListener(preference -> {
                Intent intent = new Intent(requireContext(), HowToActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                return true;
            });
        }

        Preference cachePreference = findPreference("net.devemperor.asr.cache");
        File[] cacheFiles = requireContext().getCacheDir().listFiles();
        if (cachePreference != null) {
            if (cacheFiles != null) {
                long cacheSize = Arrays.stream(cacheFiles).mapToLong(File::length).sum();
                cachePreference.setTitle(getString(R.string.dictate_settings_cache, cacheFiles.length, cacheSize / 1024f / 1024f));
            }

            cachePreference.setOnPreferenceClickListener(preference -> {
                new MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.dictate_cache_clear_title)
                        .setMessage(R.string.dictate_cache_clear_message)
                        .setPositiveButton(R.string.dictate_yes, (dialog, which) -> {
                            if (cacheFiles != null) {
                                for (File file : cacheFiles) {
                                    file.delete();
                                }
                            }
                            cachePreference.setTitle(getString(R.string.dictate_settings_cache, 0, 0f));
                            Toast.makeText(requireContext(), R.string.dictate_cache_cleared, Toast.LENGTH_SHORT).show();
                        })
                        .setNegativeButton(R.string.dictate_no, null)
                        .show();
                return true;
            });
        }

        Preference feedbackPreference = findPreference("net.devemperor.asr.feedback");
        if (feedbackPreference != null) {
            feedbackPreference.setOnPreferenceClickListener(preference -> {
                Intent emailIntent = new Intent(Intent.ACTION_SENDTO);
                emailIntent.setData(Uri.parse("mailto:contact@devemperor.net"));
                emailIntent.putExtra(Intent.EXTRA_SUBJECT, getString(R.string.dictate_feedback_subject));
                emailIntent.putExtra(Intent.EXTRA_TEXT, getString(R.string.dictate_feedback_body)
                        + "\n\nDictate User-ID: " + sp.getString("net.devemperor.asr.user_id", "null"));
                startActivity(Intent.createChooser(emailIntent, getString(R.string.dictate_feedback_title)));
                return true;
            });
        }

        Preference githubPreference = findPreference("net.devemperor.asr.github");
        if (githubPreference != null) {
            githubPreference.setOnPreferenceClickListener(preference -> {
                Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/DevEmperor/Dictate"));
                startActivity(browserIntent);
                return true;
            });
        }

        Preference aboutPreference = findPreference("net.devemperor.asr.about");
        if (aboutPreference != null) {
            aboutPreference.setTitle(getString(R.string.dictate_about, BuildConfig.VERSION_NAME));
            aboutPreference.setOnPreferenceClickListener(preference -> {
                Toast.makeText(requireContext(), "User-ID: " + sp.getString("net.devemperor.asr.user_id", "null"), Toast.LENGTH_LONG).show();
                return true;
            });
        }
    }

    private void setupGlobalConfigLaunchers() {
        exportGlobalConfigLauncher = registerForActivityResult(
                new ActivityResultContracts.CreateDocument("application/json"),
                uri -> {
                    if (uri != null) {
                        exportGlobalConfig(uri, exportIncludeSecrets);
                    }
                }
        );

        importGlobalConfigLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(),
                uri -> {
                    if (uri != null) {
                        importGlobalConfig(uri);
                    }
                }
        );
    }

    private void setupGlobalConfigPreferences() {
        Preference exportPreference = findPreference("net.devemperor.asr.global_config_export");
        if (exportPreference != null) {
            exportPreference.setOnPreferenceClickListener(preference -> {
                showSensitiveDataDialog();
                return true;
            });
        }

        Preference importPreference = findPreference("net.devemperor.asr.global_config_import");
        if (importPreference != null) {
            importPreference.setOnPreferenceClickListener(preference -> {
                importGlobalConfigLauncher.launch(new String[]{"application/json"});
                return true;
            });
        }
    }

    private void showSensitiveDataDialog() {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.dictate_global_config_export_secrets_title)
                .setMessage(R.string.dictate_global_config_export_secrets_message)
                .setPositiveButton(R.string.dictate_global_config_export_include_secrets, (dialog, which) -> {
                    exportIncludeSecrets = true;
                    exportGlobalConfigLauncher.launch(getString(R.string.dictate_global_config_export_filename));
                })
                .setNegativeButton(R.string.dictate_global_config_export_exclude_secrets, (dialog, which) -> {
                    exportIncludeSecrets = false;
                    exportGlobalConfigLauncher.launch(getString(R.string.dictate_global_config_export_filename));
                })
                .setNeutralButton(R.string.dictate_cancel, null)
                .show();
    }

    private void exportGlobalConfig(Uri uri, boolean includeSecrets) {
        JSONObject root = new JSONObject();
        try {
            root.put("version", GLOBAL_CONFIG_VERSION);
            root.put("appId", GLOBAL_CONFIG_APP_ID);
            root.put("exportedAt", System.currentTimeMillis());
            root.put("includeSecrets", includeSecrets);
            root.put("preferences", buildPreferencesJson(includeSecrets));
            root.put("prompts", buildPromptsJson());
        } catch (JSONException e) {
            showToast(R.string.dictate_global_config_export_failed);
            return;
        }

        try (OutputStream outputStream = requireContext().getContentResolver().openOutputStream(uri)) {
            if (outputStream == null) {
                showToast(R.string.dictate_global_config_export_failed);
                return;
            }
            try (OutputStreamWriter writer = new OutputStreamWriter(outputStream, StandardCharsets.UTF_8)) {
                writer.write(root.toString(2));
                writer.flush();
            }
            showToast(R.string.dictate_global_config_export_success);
        } catch (IOException e) {
            showToast(R.string.dictate_global_config_export_failed);
        }
    }

    private JSONObject buildPreferencesJson(boolean includeSecrets) throws JSONException {
        JSONObject preferences = new JSONObject();
        Map<String, ?> allPreferences = sp.getAll();
        for (Map.Entry<String, ?> entry : allPreferences.entrySet()) {
            String key = entry.getKey();
            if (!includeSecrets && isSensitiveKey(key)) continue;
            JSONObject serializedValue = serializePreferenceValue(entry.getValue());
            if (serializedValue != null) {
                preferences.put(key, serializedValue);
            }
        }
        return preferences;
    }

    private JSONArray buildPromptsJson() throws JSONException {
        PromptsDatabaseHelper promptsDb = new PromptsDatabaseHelper(requireContext());
        try {
            List<PromptModel> prompts = promptsDb.getAll();
            JSONArray promptArray = new JSONArray();
            for (PromptModel model : prompts) {
                JSONObject promptObject = new JSONObject();
                promptObject.put("name", model.getName());
                promptObject.put("prompt", model.getPrompt());
                promptObject.put("requiresSelection", model.requiresSelection());
                promptObject.put("autoApply", model.isAutoApply());
                promptArray.put(promptObject);
            }
            return promptArray;
        } finally {
            promptsDb.close();
        }
    }

    @SuppressWarnings("unchecked")
    private JSONObject serializePreferenceValue(Object value) throws JSONException {
        JSONObject typedValue = new JSONObject();
        if (value instanceof Boolean) {
            typedValue.put("type", "boolean");
            typedValue.put("value", (Boolean) value);
            return typedValue;
        } else if (value instanceof Integer) {
            typedValue.put("type", "int");
            typedValue.put("value", (Integer) value);
            return typedValue;
        } else if (value instanceof Long) {
            typedValue.put("type", "long");
            typedValue.put("value", (Long) value);
            return typedValue;
        } else if (value instanceof Float) {
            typedValue.put("type", "float");
            typedValue.put("value", ((Float) value).doubleValue());
            return typedValue;
        } else if (value instanceof String) {
            typedValue.put("type", "string");
            typedValue.put("value", (String) value);
            return typedValue;
        } else if (value instanceof Set) {
            Set<?> rawSet = (Set<?>) value;
            JSONArray array = new JSONArray();
            for (Object item : rawSet) {
                if (!(item instanceof String)) return null;
                array.put(item);
            }
            typedValue.put("type", "string_set");
            typedValue.put("value", array);
            return typedValue;
        }
        return null;
    }

    private void importGlobalConfig(Uri uri) {
        Map<String, Object> currentPreferences = new HashMap<>(sp.getAll());
        PromptsDatabaseHelper promptsDb = new PromptsDatabaseHelper(requireContext());
        List<PromptModel> currentPrompts = promptsDb.getAll();

        try (InputStream inputStream = requireContext().getContentResolver().openInputStream(uri)) {
            if (inputStream == null) {
                showToast(R.string.dictate_global_config_import_failed);
                return;
            }
            String json = readStream(inputStream);
            ImportPayload payload = parseImportPayload(json);
            if (!applyPreferences(payload.preferences)) {
                showToast(R.string.dictate_global_config_import_failed);
                return;
            }
            promptsDb.replaceAll(payload.prompts);
            showToast(R.string.dictate_global_config_import_success);
            Toast.makeText(requireContext(), R.string.dictate_global_config_import_apply_notice, Toast.LENGTH_LONG).show();
            requireActivity().recreate();
        } catch (Exception e) {
            try {
                applyPreferences(currentPreferences);
                promptsDb.replaceAll(currentPrompts);
            } catch (Exception ignored) {
            }
            showToast(R.string.dictate_global_config_import_failed);
        } finally {
            promptsDb.close();
        }
    }

    private ImportPayload parseImportPayload(String json) throws JSONException {
        JSONObject root = new JSONObject(json);
        int version = root.optInt("version", -1);
        String appId = root.optString("appId", "");
        if (version != GLOBAL_CONFIG_VERSION || !GLOBAL_CONFIG_APP_ID.equals(appId)) {
            throw new JSONException("Invalid config metadata");
        }

        JSONObject preferencesObject = root.optJSONObject("preferences");
        if (preferencesObject == null) {
            throw new JSONException("Missing preferences");
        }
        JSONArray promptsArray = root.optJSONArray("prompts");
        if (promptsArray == null) {
            throw new JSONException("Missing prompts");
        }

        Map<String, Object> preferences = parsePreferences(preferencesObject);
        List<PromptModel> prompts = parsePrompts(promptsArray);
        return new ImportPayload(preferences, prompts);
    }

    private Map<String, Object> parsePreferences(JSONObject preferencesObject) throws JSONException {
        Map<String, Object> preferences = new HashMap<>();
        Iterator<String> keys = preferencesObject.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            JSONObject typedObject = preferencesObject.optJSONObject(key);
            if (typedObject == null) {
                throw new JSONException("Invalid preference object: " + key);
            }
            String type = typedObject.optString("type", "");
            if (type.isEmpty()) {
                throw new JSONException("Missing preference type: " + key);
            }

            switch (type) {
                case "boolean":
                    preferences.put(key, typedObject.getBoolean("value"));
                    break;
                case "int":
                    preferences.put(key, typedObject.getInt("value"));
                    break;
                case "long":
                    preferences.put(key, typedObject.getLong("value"));
                    break;
                case "float":
                    preferences.put(key, (float) typedObject.getDouble("value"));
                    break;
                case "string":
                    preferences.put(key, typedObject.getString("value"));
                    break;
                case "string_set":
                    JSONArray valueArray = typedObject.optJSONArray("value");
                    if (valueArray == null) {
                        throw new JSONException("Invalid string_set value: " + key);
                    }
                    Set<String> valueSet = new LinkedHashSet<>();
                    for (int i = 0; i < valueArray.length(); i++) {
                        if (valueArray.isNull(i)) throw new JSONException("Null string_set item: " + key);
                        valueSet.add(valueArray.getString(i));
                    }
                    preferences.put(key, valueSet);
                    break;
                default:
                    throw new JSONException("Unknown preference type: " + type);
            }
        }
        return preferences;
    }

    private List<PromptModel> parsePrompts(JSONArray promptsArray) throws JSONException {
        List<PromptModel> prompts = new ArrayList<>();
        for (int i = 0; i < promptsArray.length(); i++) {
            JSONObject promptObject = promptsArray.optJSONObject(i);
            if (promptObject == null) {
                throw new JSONException("Invalid prompt item");
            }
            String name = promptObject.optString("name", "");
            String prompt = promptObject.optString("prompt", "");
            if (name.isEmpty() || prompt.isEmpty()) {
                throw new JSONException("Invalid prompt fields");
            }
            boolean requiresSelection = promptObject.optBoolean("requiresSelection", false);
            boolean autoApply = promptObject.optBoolean("autoApply", false);
            prompts.add(new PromptModel(0, prompts.size(), name, prompt, requiresSelection, autoApply));
        }
        return prompts;
    }

    @SuppressWarnings("unchecked")
    private boolean applyPreferences(Map<String, ?> preferences) {
        SharedPreferences.Editor editor = sp.edit().clear();
        for (Map.Entry<String, ?> entry : preferences.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (value instanceof Boolean) {
                editor.putBoolean(key, (Boolean) value);
            } else if (value instanceof Integer) {
                editor.putInt(key, (Integer) value);
            } else if (value instanceof Long) {
                editor.putLong(key, (Long) value);
            } else if (value instanceof Float) {
                editor.putFloat(key, (Float) value);
            } else if (value instanceof String) {
                editor.putString(key, (String) value);
            } else if (value instanceof Set) {
                editor.putStringSet(key, new LinkedHashSet<>((Set<String>) value));
            }
        }
        return editor.commit();
    }

    private String readStream(InputStream inputStream) throws IOException {
        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            char[] buffer = new char[4096];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                builder.append(buffer, 0, read);
            }
        }
        return builder.toString();
    }

    private boolean isSensitiveKey(String key) {
        return SENSITIVE_PREFERENCE_KEYS.contains(key) || key.contains("api_key");
    }

    private void showToast(int resId) {
        Toast.makeText(requireContext(), resId, Toast.LENGTH_SHORT).show();
    }

    private static class ImportPayload {
        final Map<String, Object> preferences;
        final List<PromptModel> prompts;

        ImportPayload(Map<String, Object> preferences, List<PromptModel> prompts) {
            this.preferences = preferences;
            this.prompts = prompts;
        }
    }
}
