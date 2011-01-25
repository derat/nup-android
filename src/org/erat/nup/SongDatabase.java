// Copyright 2011 Daniel Erat <dan@erat.org>
// All rights reserved.

package org.erat.nup;

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

    public boolean syncDatabase(String message[]) {
        SQLiteDatabase db = mOpener.getWritableDatabase();

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
                    db.execSQL(
                        "INSERT INTO Songs " +
                        "(SongId, Sha1, Filename, Artist, Title, Album, TrackNumber, Length, Rating, LastModified) " +
                        "VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        new Object[]{
                            jsonSong.getInt(0),     // SongId
                            jsonSong.getString(1),  // Sha1
                            jsonSong.getString(2),  // Filename
                            jsonSong.getString(3),  // Artist
                            jsonSong.getString(4),  // Title
                            jsonSong.getString(5),  // Album
                            jsonSong.getInt(6),     // TrackNumber
                            jsonSong.getInt(7),     // Length
                            jsonSong.getDouble(8),  // Rating
                            jsonSong.getInt(9)});   // LastModified
                    numSongs++;
                    minSongId = Math.max(minSongId, jsonSong.getInt(0) + 1);
                }
            } catch (org.json.JSONException e) {
                message[0] = "Unable to parse response: " + e;
                return false;
            }
        }

        message[0] = "Got " + numSongs + " song" + (numSongs == 1 ? "" : "s");
        return true;
    }
}
