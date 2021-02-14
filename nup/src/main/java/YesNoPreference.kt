// Copyright 2011 Daniel Erat <dan@erat.org>
// All rights reserved.
package org.erat.nup

import android.app.ProgressDialog
import android.content.Context
import android.content.DialogInterface
import android.os.AsyncTask
import android.preference.DialogPreference
import android.util.AttributeSet
import android.widget.Toast
import org.erat.nup.NupActivity.Companion.service
import org.erat.nup.NupService.SongDatabaseUpdateListener
import org.erat.nup.SongDatabase
import org.erat.nup.SongDatabase.SyncProgressListener
import java.text.SimpleDateFormat
import java.util.*

internal class YesNoPreference : DialogPreference, SongDatabaseUpdateListener {
    constructor(context: Context, attrs: AttributeSet?, defStyle: Int)
            : super(context, attrs, defStyle)
    
    override fun onDialogClosed(positiveResult: Boolean) {
        super.onDialogClosed(positiveResult)
        if (positiveResult) {
            if (NupPreferences.SYNC_SONG_LIST == key) {
                SyncSongListTask().executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR)
            } else if (NupPreferences.CLEAR_CACHE == key) {
                service!!.clearCache()
                summary = context.getString(R.string.cache_is_empty)
            }
        }
    }

    // Implements NupService.SongDatabaseUpdateListener.
    override fun onSongDatabaseUpdate() {
        if (NupPreferences.SYNC_SONG_LIST == key) updateSyncMessage()
    }

    fun updateSyncMessage() {
        val db = service!!.songDb
        if (!db!!.aggregateDataLoaded) {
            summary = context.getString(R.string.loading_stats)
        } else if (db.lastSyncDate == null) {
            summary = context.getString(R.string.never_synced)
        } else {
            val lastSyncCal = Calendar.getInstance()
            val todayCal = Calendar.getInstance()
            lastSyncCal.time = db.lastSyncDate
            todayCal.time = Date()
            val lastSyncWasToday = lastSyncCal[Calendar.YEAR] == todayCal[Calendar.YEAR] && lastSyncCal[Calendar.MONTH] == todayCal[Calendar.MONTH] && (lastSyncCal[Calendar.DAY_OF_MONTH]
                    == todayCal[Calendar.DAY_OF_MONTH])
            summary = context.resources
                    .getQuantityString(
                            R.plurals.sync_status_fmt,
                            db.numSongs,
                            db.numSongs,
                            if (lastSyncWasToday) SimpleDateFormat.getTimeInstance()
                                    .format(db.lastSyncDate) else SimpleDateFormat.getDateInstance()
                                    .format(db.lastSyncDate))
        }
    }

    private inner class SyncSongListTask : AsyncTask<Void?, Int?, String?>(), DialogInterface.OnCancelListener, SyncProgressListener {
        private var dialog: ProgressDialog? = null
        override fun onPreExecute() {
            dialog = ProgressDialog.show(
                    context,
                    context.getString(R.string.syncing_song_list),
                    context.resources
                            .getQuantityString(R.plurals.sync_update_fmt, 0, 0),
                    true,  // indeterminate
                    true) // cancelable
            // FIXME: Support canceling.
        }

        protected override fun doInBackground(vararg args: Void?): String? {
            val message = arrayOfNulls<String>(1)
            service!!.songDb!!.syncWithServer(this, message)
            return message[0]
        }

        protected override fun onProgressUpdate(vararg progress: Int?) {
            val state = SongDatabase.SyncState.values()[progress[0]!!]
            val numSongs = progress[1]!!
            when (state) {
                SongDatabase.SyncState.UPDATING_SONGS -> {
                    dialog!!.progress = numSongs
                    dialog!!.setMessage(
                            context.resources
                                    .getQuantityString(
                                            R.plurals.sync_update_fmt, numSongs, numSongs))
                }
                SongDatabase.SyncState.DELETING_SONGS -> {
                    dialog!!.progress = numSongs
                    dialog!!.setMessage(
                            context.resources
                                    .getQuantityString(
                                            R.plurals.sync_delete_fmt, numSongs, numSongs))
                }
                SongDatabase.SyncState.UPDATING_STATS -> dialog!!.setMessage(
                        context.getString(R.string.sync_progress_rebuilding_stats_tables))
            }
        }

        override fun onPostExecute(message: String?) {
            dialog!!.dismiss()
            updateSyncMessage()
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }

        // Implements DialogInterface.OnCancelListener.
        override fun onCancel(dialog: DialogInterface) {
            // FIXME: Cancel the sync.
        }

        // Implements SongDatabase.SyncProgressListener.
        override fun onSyncProgress(state: SongDatabase.SyncState?, numSongs: Int) {
            publishProgress(state!!.ordinal, numSongs)
        }
    }
}