// Copyright 2011 Daniel Erat <dan@erat.org>
// All rights reserved.

package org.erat.nup;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.AsyncTask;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONTokener;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

class SongDatabase {
    private static final String TAG = "SongDatabase";
    private static final String DATABASE_NAME = "NupSongs";
    private static final int DATABASE_VERSION = 2;

    private static final String CREATE_LAST_UPDATE_TIME_SQL =
        "CREATE TABLE LastUpdateTime (" +
        "  Timestamp INTEGER NOT NULL, " +
        "  MaxLastModified INTEGER NOT NULL)";
    private static final String INSERT_LAST_UPDATE_TIME_SQL =
        "INSERT INTO LastUpdateTime " +
        "  (Timestamp, MaxLastModified) " +
        "  VALUES(-1, -1)";

    private final Context mContext;

    private final SQLiteOpenHelper mOpener;

    private int mNumSongs = 0;
    private Date mLastSyncDate = null;

    interface SyncProgressListener {
        void onSyncProgress(int numSongs);
    }

    public SongDatabase(Context context) {
        mContext = context;
        mOpener = new SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {
            @Override
            public void onCreate(SQLiteDatabase db) {
                db.execSQL(
                    "CREATE TABLE Songs (" +
                    "  SongId INTEGER PRIMARY KEY NOT NULL, " +
                    "  Sha1 CHAR(40) NOT NULL, " +
                    "  Filename VARCHAR(256) NOT NULL, " +
                    "  Artist VARCHAR(256) NOT NULL, " +
                    "  Title VARCHAR(256) NOT NULL, " +
                    "  Album VARCHAR(256) NOT NULL, " +
                    "  TrackNumber INTEGER NOT NULL, " +
                    "  Length INTEGER NOT NULL, " +
                    "  Rating FLOAT NOT NULL, " +
                    "  LastModified INTEGER NOT NULL)");
                db.execSQL(CREATE_LAST_UPDATE_TIME_SQL);
                db.execSQL(INSERT_LAST_UPDATE_TIME_SQL);
            }

            @Override
            public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
                if (oldVersion == 1 && newVersion == 2) {
                    // Create LastUpdateTime table.
                    db.execSQL(CREATE_LAST_UPDATE_TIME_SQL);
                    db.execSQL(INSERT_LAST_UPDATE_TIME_SQL);
                } else {
                    throw new RuntimeException(
                        "Got request to upgrade database from " + oldVersion + " to " + newVersion);
                }
            }
        };

        // Get some info from the database in a background thread.
        new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... args) {
                refreshStats();
                return (Void) null;
            }
        }.execute((Void) null);

        // Make sure that we have a writable database when we try to do the upgrade.
        mOpener.getWritableDatabase();
    }

    public int getNumSongs() { return mNumSongs; }
    public Date getLastSyncDate() { return mLastSyncDate; }

    public boolean syncWithServer(SyncProgressListener listener, String message[]) {
        SQLiteDatabase db = mOpener.getWritableDatabase();
        db.beginTransaction();

        Cursor cursor = db.rawQuery("SELECT MaxLastModified FROM LastUpdateTime", null);
        cursor.moveToFirst();
        int maxLastModified = cursor.getInt(0);
        cursor.close();

        // The server breaks its results up into batches instead of sending us a bunch of songs
        // at once, so we store the highest song ID that we've seen here so we'll know where to
        // start in the next request.
        int maxSongId = 0;

        // FIXME: This is completely braindead:
        // - If an update, our previous sync, and a second update all happen in the same second
        //   and in that order, then we'll miss the second update the next time we sync.
        // - If an update happens to a song with an ID that we've already gone past while we're
        //   in the middle of an update and then a second update happens to a song with a later
        //   ID, we'll miss the earlier song.
        int minLastModified = maxLastModified + 1;

        int numSongs = 0;
        while (true) {
            String response = Download.downloadString(
                mContext, "/songs", String.format("minSongId=%d&minLastModified=%d", maxSongId + 1, minLastModified), message);
            if (response == null) {
                db.endTransaction();
                return false;
            }

            try {
                JSONArray jsonSongs = (JSONArray) new JSONTokener(response).nextValue();
                if (jsonSongs.length() == 0)
                    break;

                for (int i = 0; i < jsonSongs.length(); ++i) {
                    JSONArray jsonSong = jsonSongs.getJSONArray(i);
                    ContentValues values = new ContentValues(10);
                    values.put("SongId", jsonSong.getInt(0));
                    values.put("Sha1", jsonSong.getString(1));
                    values.put("Filename", jsonSong.getString(2));
                    values.put("Artist", jsonSong.getString(3));
                    values.put("Title", jsonSong.getString(4));
                    values.put("Album", jsonSong.getString(5));
                    values.put("TrackNumber", jsonSong.getInt(6));
                    values.put("Length", jsonSong.getInt(7));
                    values.put("Rating", jsonSong.getDouble(8));
                    values.put("LastModified", jsonSong.getInt(9));
                    db.replace("Songs", "", values);

                    numSongs++;
                    maxSongId = Math.max(maxSongId, jsonSong.getInt(0));
                    maxLastModified = Math.max(maxLastModified, jsonSong.getInt(9));
                }
                listener.onSyncProgress(numSongs);
            } catch (org.json.JSONException e) {
                db.endTransaction();
                message[0] = "Couldn't parse response: " + e;
                return false;
            }
        }

        ContentValues values = new ContentValues(2);
        values.put("Timestamp", new Date().getTime() / 1000);
        values.put("MaxLastModified", maxLastModified);
        db.update("LastUpdateTime", values, null, null);

        db.setTransactionSuccessful();
        db.endTransaction();

        refreshStats();
        message[0] = "Synchronization complete.";
        return true;
    }

    private void refreshStats() {
        SQLiteDatabase db = mOpener.getReadableDatabase();
        Cursor cursor = db.rawQuery("SELECT Timestamp FROM LastUpdateTime", null);
        cursor.moveToFirst();
        mLastSyncDate = (cursor.getInt(0) > -1) ? new Date((long) cursor.getInt(0) * 1000) : null;
        cursor.close();

        cursor = db.rawQuery("SELECT COUNT(*) FROM Songs", null);
        cursor.moveToFirst();
        mNumSongs = cursor.getInt(0);
        Log.d(TAG, "got " + mNumSongs + " songs");
        cursor.close();
    }
}
