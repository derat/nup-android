// Copyright 2011 Daniel Erat <dan@erat.org>
// All rights reserved.

package org.erat.nup;

import android.content.SharedPreferences;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceManager;
import android.os.Bundle;

public class NupPreferenceActivity extends PreferenceActivity implements Preference.OnPreferenceChangeListener {
    SharedPreferences mPrefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.preferences);

        mPrefs = PreferenceManager.getDefaultSharedPreferences(this);

        Preference pref = findPreference(NupPreferences.SERVER_URL);
        pref.setOnPreferenceChangeListener(this);
        pref.setSummary(mPrefs.getString(NupPreferences.SERVER_URL, ""));

        pref = findPreference(NupPreferences.USERNAME);
        pref.setOnPreferenceChangeListener(this);
        pref.setSummary(mPrefs.getString(NupPreferences.USERNAME, ""));
    }

    @Override
    public boolean onPreferenceChange(Preference pref, Object value) {
        if (pref.getKey().equals(NupPreferences.SERVER_URL)) {
            findPreference(NupPreferences.SERVER_URL).setSummary((String) value);
            return true;
        } else if (pref.getKey().equals(NupPreferences.USERNAME)) {
            findPreference(NupPreferences.USERNAME).setSummary((String) value);
            return true;
        }
        return false;
    }
}
