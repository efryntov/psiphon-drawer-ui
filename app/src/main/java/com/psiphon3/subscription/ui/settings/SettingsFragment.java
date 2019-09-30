package com.psiphon3.subscription.ui.settings;

import android.content.Intent;
import android.os.Bundle;

import androidx.preference.ListPreference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceScreen;

import com.psiphon3.LocaleManager;
import com.psiphon3.subscription.MainActivity;
import com.psiphon3.subscription.R;

public class SettingsFragment extends PreferenceFragmentCompat {

    public static final String LANGUAGE_SETTINGS_RESET_ACTION = "LANGUAGE_SETTINGS_RESET_ACTION";
    private SettingsViewModel settingsViewModel;
    ListPreference languageSelectorListPreference;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.preferences, rootKey);
        PreferenceScreen preferences = getPreferenceScreen();
        setupLanguageSelector(preferences);
    }

    private void setupLanguageSelector(PreferenceScreen preferences) {
        languageSelectorListPreference = (ListPreference) preferences.findPreference(getString(R.string.preferenceLanguageSelection));

        // Collect the string array of <language name>,<language code>
        String[] locales = getResources().getStringArray(R.array.languages);
        CharSequence[] languageNames = new CharSequence[locales.length + 1];
        CharSequence[] languageCodes = new CharSequence[locales.length + 1];

        // Setup the "Default" locale
        languageNames[0] = getString(R.string.preference_language_default_language);
        languageCodes[0] = "";

        LocaleManager localeManager = LocaleManager.getInstance(getContext());
        String currentLocaleLanguageCode = localeManager.getLanguage();
        int currentLocaleLanguageIndex = -1;

        if (localeManager.isSystemLocale(currentLocaleLanguageCode)) {
            currentLocaleLanguageIndex = 0;
        }

        for (int i = 1; i <= locales.length; ++i) {
            // Split the string on the comma
            String[] localeArr = locales[i - 1].split(",");
            languageNames[i] = localeArr[0];
            languageCodes[i] = localeArr[1];

            if (localeArr[1] != null && localeArr[1].equals(currentLocaleLanguageCode)) {
                currentLocaleLanguageIndex = i;
            }
        }

        // Entries are displayed to the user, codes are the value used in the backend
        languageSelectorListPreference.setEntries(languageNames);
        languageSelectorListPreference.setEntryValues(languageCodes);

        // If current locale is on the list set it selected
        if (currentLocaleLanguageIndex >= 0) {
            languageSelectorListPreference.setValueIndex(currentLocaleLanguageIndex);
            languageSelectorListPreference.setSummary(languageNames[currentLocaleLanguageIndex]);
        }

        languageSelectorListPreference.setOnPreferenceChangeListener((preference, newLanguageCode) -> {
            ListPreference listPreference = (ListPreference) preference;
            if(listPreference.getValue() != newLanguageCode) {
                if (newLanguageCode.equals("")) {
                    localeManager.resetToSystemLocale(getContext());
                } else {
                    localeManager.setNewLocale(getContext(), (String)newLanguageCode);
                }
                getActivity().finish();
                Intent intent = new Intent(getContext(), MainActivity.class);
                intent.setAction(LANGUAGE_SETTINGS_RESET_ACTION);
                startActivity(intent);
                System.exit(1);
            }
            return false;
        });
    }
}