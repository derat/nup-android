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

class YesNoPreference extends DialogPreference {
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

    private class SyncSongListTask extends AsyncTask<Void, Integer, String>
                                   implements DialogInterface.OnCancelListener,
                                              SongDatabase.SyncProgressListener {
        private static final String MESSAGE_FMT = "Synced %d songs.";

        private ProgressDialog mDialog;

        @Override
        protected void onPreExecute() {
            mDialog = ProgressDialog.show(
                mContext,
                "Syncing song list",
                String.format(MESSAGE_FMT, 0),
                true,   // indeterminate
                true);  // cancelable
        }

        @Override
        protected String doInBackground(Void... args) {
            String[] message = new String[1];
            NupActivity.getService().getSongDb().syncWithServer(this, message);
            return message[0];
        }

        @Override
        protected void onProgressUpdate(Integer... progress) {
            int numSongs = progress[0];
            mDialog.setProgress(numSongs);
            mDialog.setMessage(String.format(MESSAGE_FMT, numSongs));
        }

        @Override
        protected void onPostExecute(String message) {
            mDialog.dismiss();
            Toast.makeText(mContext, message, Toast.LENGTH_LONG).show();
        }

        // Implements DialogInterface.OnCancelListener.
        @Override
        public void onCancel(DialogInterface dialog) {
            // FIXME: Cancel the sync.
        }

        // Implements SongDatabase.SyncProgressListener.
        @Override
        public void onSyncProgress(int numSongs) {
            publishProgress(numSongs);
        }
    }
}
