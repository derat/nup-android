// Copyright 2011 Daniel Erat <dan@erat.org>
// All rights reserved.
package org.erat.nup

import android.content.SharedPreferences
import android.os.Bundle
import android.preference.Preference
import android.preference.Preference.OnPreferenceChangeListener
import android.preference.PreferenceActivity
import android.preference.PreferenceManager
import android.widget.Toast
import org.erat.nup.NupActivity.Companion.service
import java.net.MalformedURLException
import java.net.URL

class SettingsActivity : PreferenceActivity(), OnPreferenceChangeListener {
    var prefs: SharedPreferences? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTitle(R.string.settings)
        addPreferencesFromResource(R.xml.settings)
        prefs = PreferenceManager.getDefaultSharedPreferences(this)
        var pref = findPreference(NupPreferences.SERVER_URL)
        pref.onPreferenceChangeListener = this
        pref.summary = prefs!!.getString(NupPreferences.SERVER_URL, "")
        pref = findPreference(NupPreferences.USERNAME)
        pref.onPreferenceChangeListener = this
        pref.summary = prefs!!.getString(NupPreferences.USERNAME, "")
        pref = findPreference(NupPreferences.ACCOUNT)
        pref.onPreferenceChangeListener = this
        pref.summary = prefs!!.getString(NupPreferences.ACCOUNT, "")
        pref = findPreference(NupPreferences.SYNC_SONG_LIST)
        service!!.addSongDatabaseUpdateListener((pref as YesNoPreference))
        pref.onSongDatabaseUpdate()
        pref = findPreference(NupPreferences.PRE_AMP_GAIN)
        pref.onPreferenceChangeListener = this
        val gain =
                prefs!!.getString(
                        NupPreferences.PRE_AMP_GAIN, NupPreferences.PRE_AMP_GAIN_DEFAULT)!!.toDouble()
        pref.summary = getString(R.string.pre_amp_gain_value, gain)
        pref = findPreference(NupPreferences.CACHE_SIZE)
        pref.onPreferenceChangeListener = this
        val maxCacheMb =
                prefs!!.getString(
                        NupPreferences.CACHE_SIZE, NupPreferences.CACHE_SIZE_DEFAULT)!!.toInt()
        pref.summary = getString(R.string.cache_size_value, maxCacheMb)
        pref = findPreference(NupPreferences.CLEAR_CACHE)
        val cachedBytes = service!!.totalCachedBytes
        pref.summary = if (cachedBytes > 0) getString(
                R.string.cache_current_usage, cachedBytes / (1024 * 1024).toDouble()) else getString(R.string.cache_is_empty)
        pref = findPreference(NupPreferences.SONGS_TO_PRELOAD)
        pref.onPreferenceChangeListener = this
        val songsToPreload =
                prefs!!.getString(
                        NupPreferences.SONGS_TO_PRELOAD,
                        NupPreferences.SONGS_TO_PRELOAD_DEFAULT)!!.toInt()
        pref.summary = resources
                .getQuantityString(
                        R.plurals.songs_to_preload_fmt, songsToPreload, songsToPreload)
        pref = findPreference(NupPreferences.DOWNLOAD_RATE)
        pref.onPreferenceChangeListener = this
        val downloadRate =
                prefs!!.getString(
                        NupPreferences.DOWNLOAD_RATE,
                        NupPreferences.DOWNLOAD_RATE_DEFAULT)!!.toInt()
        pref.summary = if (downloadRate == 0) getString(R.string.unlimited) else getString(R.string.download_rate_value, downloadRate)
    }

    override fun onDestroy() {
        super.onDestroy()
        service!!
                .removeSongDatabaseUpdateListener(
                        (findPreference(NupPreferences.SYNC_SONG_LIST) as YesNoPreference))
    }

    override fun onPreferenceChange(pref: Preference, value: Any): Boolean {
        if (pref.key == NupPreferences.SERVER_URL) {
            // Validate the URL now to avoid heartbreak later.
            val strValue = value as String
            try {
                if (!strValue.isEmpty()) {
                    URL(strValue)
                }
            } catch (e: MalformedURLException) {
                Toast.makeText(this, e.message, Toast.LENGTH_LONG).show()
                return false
            }
            findPreference(NupPreferences.SERVER_URL).summary = strValue
            return true
        } else if (pref.key == NupPreferences.USERNAME) {
            findPreference(NupPreferences.USERNAME).summary = value as String
            return true
        } else if (pref.key == NupPreferences.ACCOUNT) {
            findPreference(NupPreferences.ACCOUNT).summary = value as String
            service!!.authenticateInBackground()
            return true
        } else if (pref.key == NupPreferences.PRE_AMP_GAIN) {
            return try {
                val gain: Double = (value as String).toDouble()
                findPreference(NupPreferences.PRE_AMP_GAIN).summary = getString(R.string.pre_amp_gain_value, gain)
                true
            } catch (e: NumberFormatException) {
                false
            }
        } else if (pref.key == NupPreferences.CACHE_SIZE) {
            val intValue = parseNonNegativeInt(value as String)
            if (intValue < NupPreferences.CACHE_SIZE_MINIMUM) {
                val msg = getString(R.string.cache_size_invalid, NupPreferences.CACHE_SIZE_MINIMUM)
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                return false
            }
            findPreference(NupPreferences.CACHE_SIZE).summary = getString(R.string.cache_size_value, intValue)
            return true
        } else if (pref.key == NupPreferences.SONGS_TO_PRELOAD) {
            val intValue = parseNonNegativeInt(value as String)
            if (intValue < 0) return false
            findPreference(NupPreferences.SONGS_TO_PRELOAD).summary = resources
                    .getQuantityString(
                            R.plurals.songs_to_preload_fmt, intValue, intValue)
            return true
        } else if (pref.key == NupPreferences.DOWNLOAD_RATE) {
            val intValue = parseNonNegativeInt(value as String)
            if (intValue < 0) return false
            findPreference(NupPreferences.DOWNLOAD_RATE).summary = if (intValue == 0) getString(R.string.unlimited) else getString(R.string.download_rate_value, intValue)
            return true
        }
        return false
    }

    // Parse a string containing a integer that should be positive.
    // -1 is returned if the string can't be parsed.
    private fun parseNonNegativeInt(strValue: String): Int {
        return try {
            strValue.toInt()
        } catch (e: NumberFormatException) {
            -1
        }
    }

    companion object {
        private const val TAG = "SettingsActivity"
    }
}
