/*
 * Copyright 2011 Daniel Erat <dan@erat.org>
 * All rights reserved.
 */

package org.erat.nup

import android.app.ProgressDialog
import android.content.Context
import android.preference.DialogPreference
import android.util.AttributeSet
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import org.erat.nup.NupActivity.Companion.service

/** Preference dialog containing a yes/no prompt. */
class YesNoPreference(context: Context, attrs: AttributeSet?) :
    DialogPreference(context, attrs),
    NupService.SongDatabaseUpdateListener {
    private val dateFormat = SimpleDateFormat("yyyyMMdd")

    override fun onDialogClosed(positiveResult: Boolean) {
        super.onDialogClosed(positiveResult)
        if (!positiveResult) return

        when (key) {
            NupPreferences.SYNC_SONG_LIST -> {
                GlobalScope.async(Dispatchers.Main) { syncSongList() }
            }
            NupPreferences.CLEAR_CACHE -> {
                service.clearCache()
                summary = context.getString(R.string.cache_is_empty)
            }
        }
    }

    override fun onSongDatabaseUpdate() {
        if (key == NupPreferences.SYNC_SONG_LIST) updateSyncMessage()
    }

    /** Update the summary text for [NupPreferences.SYNC_SONG_LIST]. */
    private fun updateSyncMessage() {
        val db = service.songDb
        when {
            !db.aggregateDataLoaded -> { summary = context.getString(R.string.loading_stats) }
            db.lastSyncDate == null -> { summary = context.getString(R.string.never_synced) }
            else -> {
                val last = db.lastSyncDate!!
                summary = context.resources
                    .getQuantityString(
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

    /** Sync songs with the server. */
    private suspend fun syncSongList() {
        // TODO: Support canceling the sync.
        val dialog = ProgressDialog.show(
            context,
            context.getString(R.string.syncing_song_list),
            context.resources.getQuantityString(R.plurals.sync_update_fmt, 0, 0),
            true, // indeterminate
            true, // cancelable
        )

        // Update the dialog to describe sync progress.
        val listener = object : SongDatabase.SyncProgressListener {
            override fun onSyncProgress(state: SongDatabase.SyncState, numSongs: Int) {
                assertOnMainThread()

                when (state) {
                    SongDatabase.SyncState.UPDATING_SONGS -> {
                        dialog.progress = numSongs
                        dialog.setMessage(
                            context.resources
                                .getQuantityString(R.plurals.sync_update_fmt, numSongs, numSongs)
                        )
                    }
                    SongDatabase.SyncState.DELETING_SONGS -> {
                        dialog.progress = numSongs
                        dialog.setMessage(
                            context.resources
                                .getQuantityString(R.plurals.sync_delete_fmt, numSongs, numSongs)
                        )
                    }
                    SongDatabase.SyncState.UPDATING_STATS -> {
                        dialog.setMessage(
                            context.getString(R.string.sync_progress_rebuilding_stats_tables)
                        )
                    }
                }
            }
        }

        val msg = GlobalScope.async(Dispatchers.IO) {
            val message = arrayOf("")
            service.songDb.syncWithServer(listener, context.mainExecutor, message)
            message[0]
        }.await()

        dialog.dismiss()
        updateSyncMessage()
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }
}
