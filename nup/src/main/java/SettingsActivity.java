// Copyright 2011 Daniel Erat <dan@erat.org>
// All rights reserved.

package org.erat.nup;

import android.content.SharedPreferences;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceManager;
import android.os.Bundle;
import android.widget.Toast;

import java.net.MalformedURLException;
import java.net.URL;

public class SettingsActivity extends PreferenceActivity implements Preference.OnPreferenceChangeListener {
    private static final String TAG = "SettingsActivity";

    SharedPreferences mPrefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(R.string.settings);
        addPreferencesFromResource(R.xml.settings);

        mPrefs = PreferenceManager.getDefaultSharedPreferences(this);

        Preference pref = findPreference(NupPreferences.SERVER_URL);
        pref.setOnPreferenceChangeListener(this);
        pref.setSummary(mPrefs.getString(NupPreferences.SERVER_URL, ""));

        pref = findPreference(NupPreferences.USERNAME);
        pref.setOnPreferenceChangeListener(this);
        pref.setSummary(mPrefs.getString(NupPreferences.USERNAME, ""));

        pref = findPreference(NupPreferences.ACCOUNT);
        pref.setOnPreferenceChangeListener(this);
        pref.setSummary(mPrefs.getString(NupPreferences.ACCOUNT, ""));

        pref = findPreference(NupPreferences.SYNC_SONG_LIST);
        NupActivity.getService().addSongDatabaseUpdateListener((YesNoPreference) pref);
        ((YesNoPreference) pref).onSongDatabaseUpdate();

        pref = findPreference(NupPreferences.CACHE_SIZE);
        pref.setOnPreferenceChangeListener(this);
        int maxCacheMb = Integer.valueOf(
            mPrefs.getString(NupPreferences.CACHE_SIZE,
                             NupPreferences.CACHE_SIZE_DEFAULT));
        pref.setSummary(getString(R.string.cache_size_value, maxCacheMb));

        pref = findPreference(NupPreferences.CLEAR_CACHE);
        long cachedBytes = NupActivity.getService().getTotalCachedBytes();
        pref.setSummary(cachedBytes > 0 ?
                        getString(R.string.cache_current_usage, cachedBytes / (double) (1024 * 1024)) :
                        getString(R.string.cache_is_empty));

        pref = findPreference(NupPreferences.SONGS_TO_PRELOAD);
        pref.setOnPreferenceChangeListener(this);
        int songsToPreload = Integer.valueOf(
            mPrefs.getString(NupPreferences.SONGS_TO_PRELOAD,
                             NupPreferences.SONGS_TO_PRELOAD_DEFAULT));
        pref.setSummary(
            getResources().getQuantityString(
                R.plurals.songs_to_preload_fmt,
                songsToPreload,
                songsToPreload));

        pref = findPreference(NupPreferences.DOWNLOAD_RATE);
        pref.setOnPreferenceChangeListener(this);
        int downloadRate = Integer.valueOf(
            mPrefs.getString(NupPreferences.DOWNLOAD_RATE,
                             NupPreferences.DOWNLOAD_RATE_DEFAULT));
        pref.setSummary(downloadRate == 0 ?
                        getString(R.string.unlimited) :
                        getString(R.string.download_rate_value, downloadRate));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        NupActivity.getService().removeSongDatabaseUpdateListener(
            (YesNoPreference) findPreference(NupPreferences.SYNC_SONG_LIST));
    }

    @Override
    public boolean onPreferenceChange(Preference pref, Object value) {
        if (pref.getKey().equals(NupPreferences.SERVER_URL)) {
            // Validate the URL now to avoid heartbreak later.
            String strValue = (String) value;
            try {
                if (!strValue.isEmpty()) {
                    new URL(strValue);
                }
            } catch (MalformedURLException e) {
                Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
                return false;
            }
            findPreference(NupPreferences.SERVER_URL).setSummary(strValue);
            return true;
        } else if (pref.getKey().equals(NupPreferences.USERNAME)) {
            findPreference(NupPreferences.USERNAME).setSummary((String) value);
            return true;
        } else if (pref.getKey().equals(NupPreferences.ACCOUNT)) {
            findPreference(NupPreferences.ACCOUNT).setSummary((String) value);
            NupActivity.getService().authenticateInBackground();
            return true;
        } else if (pref.getKey().equals(NupPreferences.CACHE_SIZE)) {
            int intValue = parseNonNegativeInt((String) value);
            if (intValue < NupPreferences.CACHE_SIZE_MINIMUM) {
                Toast.makeText(this, getString(R.string.cache_size_invalid, NupPreferences.CACHE_SIZE_MINIMUM), Toast.LENGTH_LONG).show();
                return false;
            }
            findPreference(NupPreferences.CACHE_SIZE).setSummary(
                getString(R.string.cache_size_value, intValue));
            return true;
        } else if (pref.getKey().equals(NupPreferences.SONGS_TO_PRELOAD)) {
            int intValue = parseNonNegativeInt((String) value);
            if (intValue < 0)
                return false;
            findPreference(NupPreferences.SONGS_TO_PRELOAD).setSummary(
                getResources().getQuantityString(
                    R.plurals.songs_to_preload_fmt,
                    intValue,
                    intValue));
            return true;
        } else if (pref.getKey().equals(NupPreferences.DOWNLOAD_RATE)) {
            int intValue = parseNonNegativeInt((String) value);
            if (intValue < 0)
                return false;
            findPreference(NupPreferences.DOWNLOAD_RATE).setSummary(
                intValue == 0 ?
                getString(R.string.unlimited) :
                getString(R.string.download_rate_value, intValue));
            return true;
        }
        return false;
    }

    // Parse a string containing a integer that should be positive.
    // -1 is returned if the string can't be parsed.
    private int parseNonNegativeInt(String strValue) {
        try {
            return Integer.parseInt(strValue);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
