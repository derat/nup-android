// Copyright 2011 Daniel Erat <dan@erat.org>
// All rights reserved.

package org.erat.nup;

import android.app.ProgressDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.os.AsyncTask;
import android.preference.DialogPreference;
import android.util.AttributeSet;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;

class YesNoPreference extends DialogPreference
                      implements NupService.SongDatabaseUpdateListener {
    Context mContext;

    public YesNoPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        mContext = context;
    }
    public YesNoPreference(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        mContext = context;
    }

    @Override
    protected void onDialogClosed(boolean positiveResult) {
        super.onDialogClosed(positiveResult);
        if (positiveResult) {
            if (NupPreferences.SYNC_SONG_LIST.equals(getKey())) {
                new SyncSongListTask().execute((Void) null);
            } else if (NupPreferences.CLEAR_CACHE.equals(getKey())) {
                NupActivity.getService().clearCache();
                setSummary(mContext.getString(R.string.cache_is_empty));
            }
        }
    }

    // Implements NupService.SongDatabaseUpdateListener.
    @Override
    public void onSongDatabaseUpdate() {
        if (NupPreferences.SYNC_SONG_LIST.equals(getKey()))
            updateSyncMessage();
    }

    public void updateSyncMessage() {
        SongDatabase db = NupActivity.getService().getSongDb();
        if (!db.getAggregateDataLoaded()) {
            setSummary(mContext.getString(R.string.loading_stats));
        } else if (db.getLastSyncDate() == null) {
            setSummary(mContext.getString(R.string.never_synced));
        } else {
            Calendar lastSyncCal = Calendar.getInstance(), todayCal = Calendar.getInstance();
            lastSyncCal.setTime(db.getLastSyncDate());
            todayCal.setTime(new Date());
            boolean lastSyncWasToday =
                lastSyncCal.get(Calendar.YEAR) == todayCal.get(Calendar.YEAR) &&
                lastSyncCal.get(Calendar.MONTH) == todayCal.get(Calendar.MONTH) &&
                lastSyncCal.get(Calendar.DAY_OF_MONTH) == todayCal.get(Calendar.DAY_OF_MONTH);
            setSummary(
                mContext.getResources().getQuantityString(
                    R.plurals.sync_status_fmt,
                    db.getNumSongs(),
                    db.getNumSongs(),
                    lastSyncWasToday ?
                        SimpleDateFormat.getTimeInstance().format(db.getLastSyncDate()) :
                        SimpleDateFormat.getDateInstance().format(db.getLastSyncDate())));
        }
    }

    private class SyncSongListTask extends AsyncTask<Void, Integer, String>
                                   implements DialogInterface.OnCancelListener,
                                              SongDatabase.SyncProgressListener {
        private ProgressDialog mDialog;

        @Override
        protected void onPreExecute() {
            mDialog = ProgressDialog.show(
                mContext,
                mContext.getString(R.string.syncing_song_list),
                mContext.getResources().getQuantityString(
                    R.plurals.sync_update_fmt, 0, 0),
                true,   // indeterminate
                true);  // cancelable
            // FIXME: Support canceling.
        }

        @Override
        protected String doInBackground(Void... args) {
            String[] message = new String[1];
            NupActivity.getService().getSongDb().syncWithServer(this, message);
            return message[0];
        }

        @Override
        protected void onProgressUpdate(Integer... progress) {
            SongDatabase.SyncState state = SongDatabase.SyncState.values()[progress[0]];
            int numSongs = progress[1];
            switch (state) {
                case UPDATING_SONGS:
                    mDialog.setProgress(numSongs);
                    mDialog.setMessage(
                        mContext.getResources().getQuantityString(
                            R.plurals.sync_update_fmt, numSongs, numSongs));
                    break;
                case DELETING_SONGS:
                    mDialog.setProgress(numSongs);
                    mDialog.setMessage(
                        mContext.getResources().getQuantityString(
                            R.plurals.sync_delete_fmt, numSongs, numSongs));
                    break;
                case UPDATING_STATS:
                    mDialog.setMessage(
                        mContext.getString(R.string.sync_progress_rebuilding_stats_tables));
                    break;
            }
        }

        @Override
        protected void onPostExecute(String message) {
            mDialog.dismiss();
            updateSyncMessage();
            Toast.makeText(mContext, message, Toast.LENGTH_SHORT).show();
        }

        // Implements DialogInterface.OnCancelListener.
        @Override
        public void onCancel(DialogInterface dialog) {
            // FIXME: Cancel the sync.
        }

        // Implements SongDatabase.SyncProgressListener.
        @Override
        public void onSyncProgress(SongDatabase.SyncState state, int numSongs) {
            publishProgress(state.ordinal(), numSongs);
        }
    }
}
