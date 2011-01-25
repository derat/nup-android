// Copyright 2011 Daniel Erat <dan@erat.org>
// All rights reserved.

package org.erat.nup;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONArray;
import org.json.JSONTokener;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

class SongDatabase {
    private static final String TAG = "SongDatabase";
    private static final String DATABASE_NAME = "NupSongs";
    private static final int DATABASE_VERSION = 1;

    private final Context mContext;

    private final SQLiteOpenHelper mOpener;

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
            }

            @Override
            public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
                throw new RuntimeException(
                    "Got request to upgrade database from " + oldVersion + " to " + newVersion);
            }
        };

        // Make sure that we have a writable database when we try to do the upgrade.
        mOpener.getWritableDatabase();
    }

    public boolean syncWithServer(SyncProgressListener listener, String message[]) {
        SQLiteDatabase db = mOpener.getWritableDatabase();
        db.beginTransaction();

        int minSongId = 0, numSongs = 0;
        while (true) {
            String response = Download.downloadString(mContext, "/songs", "minSongId=" + minSongId, message);
            if (response == null)
                return false;

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
                    minSongId = Math.max(minSongId, jsonSong.getInt(0) + 1);
                }
                listener.onSyncProgress(numSongs);
            } catch (org.json.JSONException e) {
                db.endTransaction();
                message[0] = "Couldn't parse response: " + e;
                return false;
            }
        }
        db.setTransactionSuccessful();
        db.endTransaction();

        message[0] = "Synced " + numSongs + " song" + (numSongs == 1 ? "" : "s") + ".";
        return true;
    }
}
