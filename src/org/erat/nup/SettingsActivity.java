// Copyright 2011 Daniel Erat <dan@erat.org>
// All rights reserved.

package org.erat.nup;

import android.content.SharedPreferences;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceManager;
import android.os.Bundle;
import android.widget.Toast;

public class SettingsActivity extends PreferenceActivity implements Preference.OnPreferenceChangeListener {
    private static final String TAG = "SettingsActivity";

    SharedPreferences mPrefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.settings);

        mPrefs = PreferenceManager.getDefaultSharedPreferences(this);

        Preference pref = findPreference(NupPreferences.SERVER_URL);
        pref.setOnPreferenceChangeListener(this);
        pref.setSummary(mPrefs.getString(NupPreferences.SERVER_URL, ""));

        pref = findPreference(NupPreferences.USERNAME);
        pref.setOnPreferenceChangeListener(this);
        pref.setSummary(mPrefs.getString(NupPreferences.USERNAME, ""));

        pref = findPreference(NupPreferences.CLEAR_CACHE);
        pref.setSummary(getString(R.string.cache_size, NupActivity.getService().getCacheDataBytes() / (double) (1024 * 1024)));
    }

    @Override
    public boolean onPreferenceChange(Preference pref, Object value) {
        if (pref.getKey().equals(NupPreferences.SERVER_URL)) {
            // Validate the URL now to avoid heartbreak later.
            String strValue = (String) value;
            try {
                if (!strValue.isEmpty())
                    DownloadRequest.parseServerUrlIntoUri(strValue, null, null);
            } catch (DownloadRequest.PrefException e) {
                Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
                return false;
            }
            findPreference(NupPreferences.SERVER_URL).setSummary(strValue);
            return true;
        } else if (pref.getKey().equals(NupPreferences.USERNAME)) {
            findPreference(NupPreferences.USERNAME).setSummary((String) value);
            return true;
        }
        return false;
    }
}
