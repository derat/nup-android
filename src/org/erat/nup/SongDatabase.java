// Copyright 2011 Daniel Erat <dan@erat.org>
// All rights reserved.

package org.erat.nup;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.AsyncTask;
import android.text.TextUtils;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONTokener;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

class SongDatabase {
    private static final String TAG = "SongDatabase";

    public static final String UNKNOWN_ALBUM = "[unknown]";

    private static final String DATABASE_NAME = "NupSongs";
    private static final int DATABASE_VERSION = 4;

    private static final String MAX_QUERY_RESULTS = "250";

    private static final String CREATE_SONGS_SQL =
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
        "  Deleted BOOLEAN NOT NULL, " +
        "  LastModified INTEGER NOT NULL)";
    private static final String ADD_SONGS_ARTIST_INDEX_SQL =
        "CREATE INDEX Artist ON Songs (Artist)";
    private static final String ADD_SONGS_ALBUM_INDEX_SQL =
        "CREATE INDEX Album ON Songs (Album)";

    private static final String CREATE_ARTIST_ALBUM_STATS_SQL =
        "CREATE TABLE ArtistAlbumStats (" +
        "  Artist VARCHAR(256) NOT NULL, " +
        "  Album VARCHAR(256) NOT NULL, " +
        "  NumSongs INTEGER NOT NULL)";

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

    private boolean mAggregateDataLoaded = false;
    private int mNumSongs = 0;
    private Date mLastSyncDate = null;
    private List<String> mArtistsSortedAlphabetically = new ArrayList<String>();
    private List<String> mAlbumsSortedAlphabetically = new ArrayList<String>();
    private List<String> mArtistsSortedByNumSongs = new ArrayList<String>();
    private HashMap mArtistAlbums = new HashMap();

    private Listener mListener = null;

    interface Listener {
        void onAggregateDataUpdate();
    }

    interface SyncProgressListener {
        void onSyncProgress(int numSongs);
    }

    public SongDatabase(Context context, Listener listener) {
        mContext = context;
        mListener = listener;
        mOpener = new SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {
            @Override
            public void onCreate(SQLiteDatabase db) {
                db.execSQL(CREATE_SONGS_SQL);
                db.execSQL(ADD_SONGS_ARTIST_INDEX_SQL);
                db.execSQL(ADD_SONGS_ALBUM_INDEX_SQL);
                db.execSQL(CREATE_ARTIST_ALBUM_STATS_SQL);
                db.execSQL(CREATE_LAST_UPDATE_TIME_SQL);
                db.execSQL(INSERT_LAST_UPDATE_TIME_SQL);
            }

            @Override
            public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
                Log.d(TAG, "onUpgrade: " + oldVersion + " -> " + newVersion);
                if (newVersion == 2) {
                    // Version 2: Create LastUpdateTime table.
                    if (oldVersion != 1)
                        throw new RuntimeException(
                            "Got request to upgrade database from " + oldVersion + " to " + newVersion);
                    db.execSQL(CREATE_LAST_UPDATE_TIME_SQL);
                    db.execSQL(INSERT_LAST_UPDATE_TIME_SQL);
                } else if (newVersion == 3) {
                    // Version 3: Add Songs.Deleted column.
                    if (oldVersion < 2)
                        onUpgrade(db, oldVersion, newVersion - 1);
                    db.execSQL("ALTER TABLE Songs RENAME TO SongsTmp");
                    db.execSQL(CREATE_SONGS_SQL);
                    db.execSQL(
                        "INSERT INTO Songs " +
                        "SELECT SongId, Sha1, Filename, Artist, Title, Album, " +
                        "    TrackNumber, Length, Rating, 0, LastModified " +
                        "FROM SongsTmp");
                    db.execSQL("DROP TABLE SongsTmp");
                } else if (newVersion == 4) {
                    // Version 4: Create ArtistAlbumStats table and indexes on Songs.Artist and Songs.Album.
                    if (oldVersion < 3)
                        onUpgrade(db, oldVersion, newVersion - 1);
                    db.execSQL(CREATE_ARTIST_ALBUM_STATS_SQL);
                    db.execSQL(ADD_SONGS_ARTIST_INDEX_SQL);
                    db.execSQL(ADD_SONGS_ALBUM_INDEX_SQL);
                } else {
                    throw new RuntimeException(
                        "Got request to upgrade database from " + oldVersion + " to " + newVersion);
                }
            }
        };

        // Get some info from the database in a background thread.
        // FIXME: I think that this sometimes isn't finishing until after we've
        // already displayed SettingsActivity (which is surprising).
        new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... args) {
                loadAggregateData(mOpener.getReadableDatabase());
                return (Void) null;
            }
        }.execute();

        // Make sure that we have a writable database when we try to do the upgrade.
        mOpener.getWritableDatabase();
    }

    public boolean getAggregateDataLoaded() { return mAggregateDataLoaded; }
    public int getNumSongs() { return mNumSongs; }
    public Date getLastSyncDate() { return mLastSyncDate; }
    public List<String> getArtistsSortedAlphabetically() { return mArtistsSortedAlphabetically; }
    public List<String> getAlbumsSortedAlphabetically() { return mAlbumsSortedAlphabetically; }
    public List<String> getArtistsSortedByNumSongs() { return mArtistsSortedByNumSongs; }

    public List<String> getAlbumsByArtist(String artist) {
        String lowerArtist = artist.toLowerCase();
        return mArtistAlbums.containsKey(lowerArtist) ?
            (List<String>) mArtistAlbums.get(lowerArtist) : null;
    }

    public List<Song> query(String artist, String title, String album, String minRating, boolean shuffle, boolean substring) {
        class QueryBuilder {
            public List<String> selections = new ArrayList<String>();
            public List<String> selectionArgs = new ArrayList<String>();

            public void add(String selection, String selectionArg, boolean useLike, boolean substring) {
                addRaw(selection + (useLike ? " LIKE ?" : " = ?"), selectionArg, substring);
            }

            public void addRaw(String clause, String selectionArg, boolean substring) {
                if (selectionArg == null || selectionArg.isEmpty())
                    return;
                selections.add(clause);
                selectionArgs.add(substring ? "%" + selectionArg + "%" : selectionArg);
            }
        }
        QueryBuilder builder = new QueryBuilder();
        builder.add("Artist", artist, true, substring);
        builder.add("Title", title, true, substring);
        builder.add("Album", album, true, substring);
        builder.addRaw("Rating >= ?", minRating, false);
        builder.add("Deleted", "0", false, false);

        Cursor cursor = mOpener.getReadableDatabase().query(
            "Songs",
            TextUtils.split("Artist Title Album Filename Length SongId Rating", " "),
            TextUtils.join(" AND ", builder.selections),
            builder.selectionArgs.toArray(new String[]{}),
            null,  // groupBy
            null,  // having
            shuffle ? "RANDOM()" : "Album ASC, TrackNumber ASC",  // orderBy
            MAX_QUERY_RESULTS);  // limit

        List<Song> songs = new ArrayList<Song>();
        cursor.moveToFirst();
        while (!cursor.isAfterLast()) {
            Song song = new Song(
                cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getString(3),
                cursor.getInt(4), cursor.getInt(5), cursor.getFloat(6));
            songs.add(song);
            cursor.moveToNext();
        }
        cursor.close();
        return songs;
    }

    public boolean syncWithServer(SyncProgressListener listener, String message[]) {
        SQLiteDatabase db = mOpener.getWritableDatabase();
        db.beginTransaction();

        try {
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
                if (response == null)
                    return false;

                try {
                    JSONArray jsonSongs = (JSONArray) new JSONTokener(response).nextValue();
                    if (jsonSongs.length() == 0)
                        break;

                    for (int i = 0; i < jsonSongs.length(); ++i) {
                        JSONArray jsonSong = jsonSongs.getJSONArray(i);
                        if (jsonSong.length() != 11) {
                            db.endTransaction();
                            message[0] = "Row " + numSongs + " from server had " + jsonSong.length() + " row(s); expected 11";
                            return false;
                        }
                        ContentValues values = new ContentValues(11);
                        values.put("SongId", jsonSong.getInt(0));
                        values.put("Sha1", jsonSong.getString(1));
                        values.put("Filename", jsonSong.getString(2));
                        values.put("Artist", jsonSong.getString(3));
                        values.put("Title", jsonSong.getString(4));
                        values.put("Album", jsonSong.getString(5));
                        values.put("TrackNumber", jsonSong.getInt(6));
                        values.put("Length", jsonSong.getInt(7));
                        values.put("Rating", jsonSong.getDouble(8));
                        values.put("Deleted", jsonSong.getInt(9));
                        values.put("LastModified", jsonSong.getInt(10));
                        db.replace("Songs", "", values);

                        numSongs++;
                        maxSongId = Math.max(maxSongId, jsonSong.getInt(0));
                        maxLastModified = Math.max(maxLastModified, jsonSong.getInt(9));
                    }
                    listener.onSyncProgress(numSongs);
                } catch (org.json.JSONException e) {
                    message[0] = "Couldn't parse response: " + e;
                    return false;
                }
            }

            ContentValues values = new ContentValues(2);
            values.put("Timestamp", new Date().getTime() / 1000);
            values.put("MaxLastModified", maxLastModified);
            db.update("LastUpdateTime", values, null, null);

            updateStatsTables(db);

            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }

        loadAggregateData(db);
        message[0] = "Synchronization complete.";
        return true;
    }

    private void updateStatsTables(SQLiteDatabase db) {
        // Map from lowercased artist name to the first row that we saw in its original case.
        HashMap<String,String> artistCaseMap = new HashMap<String,String>();

        // Map from artist name to map from album name to number of songs.
        HashMap<String,HashMap<String,Integer>> artistAlbums = new HashMap<String,HashMap<String,Integer>>();

        Cursor cursor = db.rawQuery("SELECT Artist, Album, COUNT(*) FROM Songs WHERE Deleted = 0 GROUP BY 1, 2", null);
        cursor.moveToFirst();
        while (!cursor.isAfterLast()) {
            String lowerArtist = cursor.getString(0).toLowerCase();
            if (!artistCaseMap.containsKey(lowerArtist))
                artistCaseMap.put(lowerArtist, cursor.getString(0));

            String artist = artistCaseMap.get(lowerArtist);
            String album = cursor.getString(1);
            if (album.isEmpty())
                album = UNKNOWN_ALBUM;
            int numSongsInAlbum = cursor.getInt(2);

            HashMap<String,Integer> albumMap;
            if (artistAlbums.containsKey(artist)) {
                albumMap = artistAlbums.get(artist);
            } else {
                albumMap = new HashMap<String,Integer>();
                artistAlbums.put(artist, albumMap);
            }

            int totalSongsInAlbum = (albumMap.containsKey(album) ? albumMap.get(album) : 0) + numSongsInAlbum;
            albumMap.put(album, totalSongsInAlbum);

            cursor.moveToNext();
        }
        cursor.close();

        db.delete("ArtistAlbumStats", null, null);
        for (String artist : artistAlbums.keySet()) {
            HashMap<String,Integer> albumMap = artistAlbums.get(artist);
            for (String album : albumMap.keySet()) {
                ContentValues values = new ContentValues(3);
                values.put("Artist", artist);
                values.put("Album", album);
                values.put("NumSongs", albumMap.get(album));
                db.insert("ArtistAlbumStats", "", values);
            }
        }
    }

    private void loadAggregateData(SQLiteDatabase db) {
        Cursor cursor = db.rawQuery("SELECT Timestamp FROM LastUpdateTime", null);
        cursor.moveToFirst();
        Date lastSyncDate = (cursor.getInt(0) > -1) ? new Date((long) cursor.getInt(0) * 1000) : null;
        cursor.close();

        cursor = db.rawQuery("SELECT COUNT(*) FROM Songs", null);
        cursor.moveToFirst();
        int numSongs = cursor.getInt(0);
        cursor.close();


        HashSet<String> artistSet = new HashSet<String>();
        HashSet<String> albumSet = new HashSet<String>();
        final HashMap<String,Integer> artistSongCounts = new HashMap<String,Integer>();  // 'final' so it can be used in an inner class
        HashMap<String,List<String>> artistAlbums = new HashMap<String,List<String>>();

        cursor = db.rawQuery("SELECT Artist, Album, NumSongs FROM ArtistAlbumStats", null);
        cursor.moveToFirst();
        while (!cursor.isAfterLast()) {
            String artist = cursor.getString(0);
            String lowerArtist = artist.toLowerCase();
            String album = cursor.getString(1);
            int numSongsInAlbum = cursor.getInt(2);

            artistSet.add(artist);
            albumSet.add(album);

            Integer totalSongsByArtist =
                artistSongCounts.containsKey(artist) ?
                (Integer) artistSongCounts.get(artist) : 0;
            totalSongsByArtist += numSongsInAlbum;
            artistSongCounts.put(artist, totalSongsByArtist);

            List<String> albums;
            if (artistAlbums.containsKey(lowerArtist)) {
                albums = artistAlbums.get(lowerArtist);
            } else {
                albums = new ArrayList<String>();
                artistAlbums.put(lowerArtist, albums);
            }
            albums.add(album);

            cursor.moveToNext();
        }
        cursor.close();

        List<String> artistsSortedAlphabetically = new ArrayList<String>();
        artistsSortedAlphabetically.addAll(artistSet);
        Collections.sort(artistsSortedAlphabetically);

        List<String> albumsSortedAlphabetically = new ArrayList<String>();
        albumsSortedAlphabetically.addAll(albumSet);
        Collections.sort(albumsSortedAlphabetically);

        List<String> artistsSortedByNumSongs = new ArrayList<String>();
        artistsSortedByNumSongs.addAll(artistSet);
        Collections.sort(artistsSortedByNumSongs, new Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                int aNum = (Integer) artistSongCounts.get(a);
                int bNum = (Integer) artistSongCounts.get(b);
                return (aNum == bNum) ? 0 : (aNum > bNum) ? -1 : 1;
            }
        });

        mLastSyncDate = lastSyncDate;
        mNumSongs = numSongs;
        mArtistsSortedAlphabetically = artistsSortedAlphabetically;
        mAlbumsSortedAlphabetically = albumsSortedAlphabetically;
        mArtistsSortedByNumSongs = artistsSortedByNumSongs;
        mArtistAlbums = artistAlbums;
        mAggregateDataLoaded = true;

        mListener.onAggregateDataUpdate();
    }
}
