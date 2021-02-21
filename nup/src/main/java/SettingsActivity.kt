/*
 * Copyright 2011 Daniel Erat <dan@erat.org>
 * All rights reserved.
 */

package org.erat.nup

import android.app.ProgressDialog
import android.content.Context
import android.os.Bundle
import android.os.StrictMode
import android.text.InputType
import android.util.AttributeSet
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.DialogPreference
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.Preference.SummaryProvider
import androidx.preference.PreferenceDialogFragmentCompat
import androidx.preference.PreferenceFragmentCompat
import java.text.SimpleDateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import org.erat.nup.NupActivity.Companion.service

/** Displays a list of settings. */
class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        singleton = this
        setTitle(R.string.settings)
        supportFragmentManager
            .beginTransaction()
            .replace(android.R.id.content, SettingsFragment())
            .commit()
    }

    override fun onDestroy() {
        super.onDestroy()
        singleton = null
    }

    companion object {
        // Huge hack to give YesNoPreferenceDialogFragment a context
        // it can use even after its dialog has been dismissed.
        var singleton: SettingsActivity? = null
            private set
    }
}

/** Creates and configures preferences for [SettingsActiity]. */
class SettingsFragment :
    PreferenceFragmentCompat(),
    NupService.SongDatabaseUpdateListener,
    NupService.FileCacheSizeChangeListener {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        service.addSongDatabaseUpdateListener(this)
        service.addFileCacheSizeChangeListener(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        service.removeSongDatabaseUpdateListener(this)
        service.removeFileCacheSizeChangeListener(this)
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        // https://stackoverflow.com/a/49843629/6882947
        val origPolicy = StrictMode.allowThreadDiskReads()
        try {
            setPreferencesFromResource(R.xml.settings, rootKey)
        } finally {
            StrictMode.setThreadPolicy(origPolicy)
        }

        val decimal = InputType.TYPE_NUMBER_FLAG_DECIMAL
        val noSuggest = InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        val number = InputType.TYPE_CLASS_NUMBER
        val password = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        val signed = InputType.TYPE_NUMBER_FLAG_SIGNED

        configEditText(NupPreferences.SERVER_URL, simpleSummary, noSuggest, false)
        configEditText(NupPreferences.USERNAME, simpleSummary, noSuggest, false)
        configEditText(NupPreferences.PASSWORD, null, password, false)
        configEditText(NupPreferences.ACCOUNT, simpleSummary, noSuggest, false)
        configEditText(NupPreferences.PRE_AMP_GAIN, gainSummary, number or decimal or signed, true)
        configEditText(NupPreferences.CACHE_SIZE, cacheSizeSummary, number, false)
        configEditText(NupPreferences.SONGS_TO_PRELOAD, songsSummary, number, true)
        configEditText(NupPreferences.DOWNLOAD_RATE, rateSummary, number, true)

        findPreference<YesNoPreference>(NupPreferences.SYNC_SONG_LIST)!!
            .setSummaryProvider(syncSummary)
        findPreference<YesNoPreference>(NupPreferences.CLEAR_CACHE)!!
            .setSummaryProvider(clearCacheSummary)
    }

    override fun onDisplayPreferenceDialog(preference: Preference) {
        val fragment: PreferenceDialogFragmentCompat? = when (preference) {
            is YesNoPreference -> YesNoPreferenceDialogFragment.create(preference.key)
            else -> null
        }

        if (fragment != null) {
            fragment.setTargetFragment(this, 0)
            fragment.show(parentFragmentManager, null)
        } else {
            super.onDisplayPreferenceDialog(preference)
        }
    }

    override fun onSongDatabaseUpdate() {
        // Force the pref's summary (showing the last sync date) to be updated.
        findPreference<YesNoPreference>(NupPreferences.SYNC_SONG_LIST)!!.notifyChanged()
    }
    override fun onFileCacheSizeChange() {
        // Force the pref's summary (showing the current cache size) to be updated.
        findPreference<YesNoPreference>(NupPreferences.CLEAR_CACHE)!!.notifyChanged()
    }

    /**
     * Configure an [EditTextPreference].
     *
     * @param key preference to configure
     * @param summaryProvider provides dynamic textual summary of pref's current value
     * @param inputType bitfield of [android.text.InputType] values for text field
     * @param select start with current value selected
     */
    private fun configEditText(
        key: String,
        summaryProvider: SummaryProvider<EditTextPreference>?,
        inputType: Int,
        select: Boolean,
    ) {
        val pref = findPreference<EditTextPreference>(key)!!
        if (summaryProvider != null) pref.setSummaryProvider(summaryProvider)
        pref.setOnBindEditTextListener {
            it.setInputType(inputType)
            // setSingleLine() doesn't work in conjunction with TYPE_TEXT_VARIATION_PASSWORD;
            // the password remains visible: https://stackoverflow.com/a/47827280/6882947
            it.maxLines = 1
            if (select) {
                it.selectAll()
            } else {
                it.setSelection(it.getText().length)
            }
        }
    }

    // Custom providers for producing summaries of different prefs' current values.
    val simpleSummary = EditTextPreference.SimpleSummaryProvider.getInstance()
    val syncSummary = object : SummaryProvider<YesNoPreference> {
        override fun provideSummary(preference: YesNoPreference): String {
            val db = service.songDb
            return when {
                !db.aggregateDataLoaded -> getString(R.string.loading_stats)
                db.lastSyncDate == null -> getString(R.string.never_synced)
                else -> {
                    val last = db.lastSyncDate!!
                    val dateFormat = SimpleDateFormat("yyyyMMdd")
                    return resources.getQuantityString(
                        R.plurals.sync_status_fmt,
                        db.numSongs,
                        db.numSongs,
                        if (dateFormat.format(last) == dateFormat.format(Date())) {
                            SimpleDateFormat.getTimeInstance().format(last)
                        } else {
                            SimpleDateFormat.getDateInstance().format(last)
                        }
                    )
                }
            }
        }
    }
    val gainSummary = object : SummaryProvider<EditTextPreference> {
        override fun provideSummary(preference: EditTextPreference) =
            getString(R.string.pre_amp_gain_value, preference.getText().toDouble())
    }
    val cacheSizeSummary = object : SummaryProvider<EditTextPreference> {
        override fun provideSummary(preference: EditTextPreference) =
            getString(R.string.cache_size_value, preference.getText().toInt())
    }
    val clearCacheSummary = object : SummaryProvider<YesNoPreference> {
        override fun provideSummary(preference: YesNoPreference): String {
            val bytes = service.totalCachedBytes
            return if (bytes > 0) {
                getString(R.string.cache_current_usage, bytes / (1024 * 1024).toDouble())
            } else {
                getString(R.string.cache_is_empty)
            }
        }
    }
    val songsSummary = object : SummaryProvider<EditTextPreference> {
        override fun provideSummary(preference: EditTextPreference): String {
            val songs = preference.getText().toInt()
            return resources.getQuantityString(R.plurals.songs_to_preload_fmt, songs, songs)
        }
    }
    val rateSummary = object : SummaryProvider<EditTextPreference> {
        override fun provideSummary(preference: EditTextPreference): String {
            val rate = preference.getText().toInt()
            return if (rate <= 0) {
                getString(R.string.unlimited)
            } else {
                getString(R.string.download_rate_value, rate)
            }
        }
    }
}

/**
 * Preference showing an okay/cancel dialog and performing an action.
 *
 * All of this preference fragment stuff is completely ridiculous.
 * https://medium.com/@JakobUlbrich/building-a-settings-screen-for-android-part-3-ae9793fd31ec
 * was helpful in getting this working.
 */
class YesNoPreference(
    context: Context,
    attrs: AttributeSet?,
    defStyleAttr: Int,
    defStyleRes: Int,
) : DialogPreference(context, attrs, defStyleAttr, defStyleRes) {
    // Sigh, this is so stupid. Is there some way to not need to define all of these?
    constructor(context: Context, attrs: AttributeSet, defStyleAttr: Int) :
        this(context, attrs, defStyleAttr, 0)
    constructor(context: Context, attrs: AttributeSet) : this(context, attrs, 0, 0)
    constructor(context: Context) : this(context, null, 0, 0)

    public override fun notifyChanged() = super.notifyChanged()
}

/** Performs actions on behalf of [YesNoPreference]. */
class YesNoPreferenceDialogFragment : PreferenceDialogFragmentCompat() {
    override fun onDialogClosed(positiveResult: Boolean) {
        if (!positiveResult) return
        val key = getPreference().key
        when (key) {
            NupPreferences.SYNC_SONG_LIST -> GlobalScope.launch(Dispatchers.Main) { syncSongList() }
            NupPreferences.CLEAR_CACHE -> service.clearCache()
            else -> throw RuntimeException("Unhandled preference $key")
        }
    }

    /** Sync songs from the server. */
    private suspend fun syncSongList() {
        // TODO: ProgressDialog is deprecated; stop using it. Also get rid of this ridiculous
        // context hack -- we can't use our own context since the dialog is getting closed.
        val ctx = SettingsActivity.singleton!!
        val dialog = ProgressDialog.show(
            ctx,
            ctx.getString(R.string.syncing_song_list),
            ctx.resources.getQuantityString(R.plurals.sync_update_fmt, 0, 0),
            true, // indeterminate
            false, // cancelable
        )

        // Create a listener that updates the dialog to describe sync progress.
        val listener = object : SongDatabase.SyncProgressListener {
            override fun onSyncProgress(state: SongDatabase.SyncState, numSongs: Int) {
                assertOnMainThread()

                when (state) {
                    SongDatabase.SyncState.UPDATING_SONGS -> {
                        dialog.progress = numSongs
                        dialog.setMessage(
                            ctx.resources
                                .getQuantityString(R.plurals.sync_update_fmt, numSongs, numSongs)
                        )
                    }
                    SongDatabase.SyncState.DELETING_SONGS -> {
                        dialog.progress = numSongs
                        dialog.setMessage(
                            ctx.resources
                                .getQuantityString(R.plurals.sync_delete_fmt, numSongs, numSongs)
                        )
                    }
                    SongDatabase.SyncState.UPDATING_STATS -> {
                        dialog.setMessage(
                            ctx.getString(R.string.sync_progress_rebuilding_stats_tables)
                        )
                    }
                }
            }
        }

        // Actually perform the sync.
        val msg = GlobalScope.async(Dispatchers.IO) {
            val message = arrayOf("")
            service.songDb.syncWithServer(listener, ctx.mainExecutor, message)
            message[0]
        }.await()
        dialog.dismiss()
        Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
    }

    companion object {
        // We annoyingly need a static method since [ARG_KEY] is protected.
        fun create(key: String): YesNoPreferenceDialogFragment {
            // The key arg passed via this bundle seems to be automatically used to look
            // up the appropriate [Preference] in [onDialogClosed].
            val fragment = YesNoPreferenceDialogFragment()
            val bundle = Bundle(1)
            bundle.putString(ARG_KEY, key)
            fragment.setArguments(bundle)
            return fragment
        }
    }
}
