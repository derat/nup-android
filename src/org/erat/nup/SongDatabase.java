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

    // Special string that we use to represent a blank field.
    // It should be something that doesn't legitimately appear in any fields,
    // so "[unknown]" is out (MusicBrainz uses it for unknown artists).
    public static final String UNSET_STRING = "[unset]";

    private static final String MAX_QUERY_RESULTS = "250";

    private static final String DATABASE_NAME = "NupSongs";
    private static final int DATABASE_VERSION = 9;

    // IMPORTANT NOTE: When updating any of these, you must replace all previous references in
    // upgradeFromPreviousVersion() with the hardcoded older version of the string.
    private static final String CREATE_SONGS_SQL =
        "CREATE TABLE Songs (" +
        "  SongId INTEGER PRIMARY KEY NOT NULL, " +
        "  Filename VARCHAR(256) NOT NULL, " +
        "  Artist VARCHAR(256) NOT NULL, " +
        "  Title VARCHAR(256) NOT NULL, " +
        "  Album VARCHAR(256) NOT NULL, " +
        "  TrackNumber INTEGER NOT NULL, " +
        "  Length INTEGER NOT NULL, " +
        "  Rating FLOAT NOT NULL)";
    private static final String CREATE_SONGS_ARTIST_INDEX_SQL =
        "CREATE INDEX Artist ON Songs (Artist)";
    private static final String CREATE_SONGS_ALBUM_INDEX_SQL =
        "CREATE INDEX Album ON Songs (Album)";
    private static final String CREATE_SONGS_FILENAME_INDEX_SQL =
        "CREATE INDEX Filename ON Songs (Filename)";

    private static final String CREATE_ARTIST_ALBUM_STATS_SQL =
        "CREATE TABLE ArtistAlbumStats (" +
        "  Artist VARCHAR(256) NOT NULL, " +
        "  Album VARCHAR(256) NOT NULL, " +
        "  NumSongs INTEGER NOT NULL, " +
        "  ArtistSortKey VARCHAR(256) NOT NULL, " +
        "  AlbumSortKey VARCHAR(256) NOT NULL)";
    private static final String CREATE_ARTIST_ALBUM_STATS_ARTIST_SORT_KEY_INDEX_SQL =
        "CREATE INDEX ArtistSortKey ON ArtistAlbumStats (ArtistSortKey)";
    private static final String CREATE_ARTIST_ALBUM_STATS_ALBUM_SORT_KEY_INDEX_SQL =
        "CREATE INDEX AlbumSortKey ON ArtistAlbumStats (AlbumSortKey)";

    private static final String CREATE_LAST_UPDATE_TIME_SQL =
        "CREATE TABLE LastUpdateTime (" +
        "  Timestamp INTEGER NOT NULL, " +
        "  MaxLastModifiedUsec INTEGER NOT NULL)";
    private static final String INSERT_LAST_UPDATE_TIME_SQL =
        "INSERT INTO LastUpdateTime " +
        "  (Timestamp, MaxLastModifiedUsec) " +
        "  VALUES(-1, -1)";

    private static final String CREATE_CACHED_SONGS_SQL =
        "CREATE TABLE CachedSongs (" +
        "  SongId INTEGER PRIMARY KEY NOT NULL)";

    private final Context mContext;

    private final SQLiteOpenHelper mOpener;

    // Update the database in a background thread.
    private final DatabaseUpdater mUpdater;
    private final Thread mUpdaterThread;

    private boolean mAggregateDataLoaded = false;
    private int mNumSongs = 0;
    private Date mLastSyncDate = null;
    private List<String> mArtistsSortedAlphabetically = new ArrayList<String>();
    private List<String> mAlbumsSortedAlphabetically = new ArrayList<String>();
    private List<String> mArtistsSortedByNumSongs = new ArrayList<String>();
    private HashMap<String,List<String>> mArtistAlbums = new HashMap<String,List<String>>();

    private final Listener mListener;
    private final FileCache mCache;

    interface Listener {
        void onAggregateDataUpdate();
    }

    public enum SyncState {
        UPDATING_SONGS,
        UPDATING_STATS
    }
    interface SyncProgressListener {
        void onSyncProgress(SyncState state, int numSongs);
    }

    public SongDatabase(Context context, Listener listener, FileCache cache) {
        mContext = context;
        mListener = listener;
        mCache = cache;
        mOpener = new SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {
            @Override
            public void onCreate(SQLiteDatabase db) {
                db.execSQL(CREATE_SONGS_SQL);
                db.execSQL(CREATE_SONGS_ARTIST_INDEX_SQL);
                db.execSQL(CREATE_SONGS_ALBUM_INDEX_SQL);
                db.execSQL(CREATE_SONGS_FILENAME_INDEX_SQL);
                db.execSQL(CREATE_ARTIST_ALBUM_STATS_SQL);
                db.execSQL(CREATE_ARTIST_ALBUM_STATS_ARTIST_SORT_KEY_INDEX_SQL);
                db.execSQL(CREATE_ARTIST_ALBUM_STATS_ALBUM_SORT_KEY_INDEX_SQL);
                db.execSQL(CREATE_LAST_UPDATE_TIME_SQL);
                db.execSQL(INSERT_LAST_UPDATE_TIME_SQL);
                db.execSQL(CREATE_CACHED_SONGS_SQL);
            }

            @Override
            public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
                Log.d(TAG, "onUpgrade: " + oldVersion + " -> " + newVersion);
                db.beginTransaction();
                try {
                    for (int nextVersion = oldVersion + 1; nextVersion <= newVersion; ++nextVersion)
                        upgradeFromPreviousVersion(db, nextVersion);
                    db.setTransactionSuccessful();
                } finally {
                    db.endTransaction();
                }
            }

            // Upgrade the database from the version before |newVersion| to |newVersion|.
            private void upgradeFromPreviousVersion(SQLiteDatabase db, int newVersion) {
                if (newVersion == 2) {
                    // Version 2: Create LastUpdateTime table.
                    db.execSQL("CREATE TABLE LastUpdateTime (" +
                               "  Timestamp INTEGER NOT NULL, " +
                               "  MaxLastModified INTEGER NOT NULL)");
                    db.execSQL("INSERT INTO LastUpdateTime " +
                               "  (Timestamp, MaxLastModified) " +
                               "  VALUES(0, 0)");
                } else if (newVersion == 3) {
                    // Version 3: Add Songs.Deleted column.
                    db.execSQL("ALTER TABLE Songs RENAME TO SongsTmp");
                    db.execSQL("CREATE TABLE Songs (" +
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
                               "  LastModifiedUsec INTEGER NOT NULL)");
                    db.execSQL("INSERT INTO Songs " +
                               "SELECT SongId, Sha1, Filename, Artist, Title, Album, " +
                               "    TrackNumber, Length, Rating, 0, LastModified " +
                               "FROM SongsTmp");
                    db.execSQL("DROP TABLE SongsTmp");
                } else if (newVersion == 4) {
                    // Version 4: Create ArtistAlbumStats table and indexes on Songs.Artist and Songs.Album.
                    db.execSQL("CREATE TABLE ArtistAlbumStats (" +
                               "  Artist VARCHAR(256) NOT NULL, " +
                               "  Album VARCHAR(256) NOT NULL, " +
                               "  NumSongs INTEGER NOT NULL)");
                    db.execSQL(CREATE_SONGS_ARTIST_INDEX_SQL);
                    db.execSQL(CREATE_SONGS_ALBUM_INDEX_SQL);
                } else if (newVersion == 5) {
                    // Version 5: LastModified -> LastModifiedUsec (seconds to microseconds).
                    db.execSQL("ALTER TABLE Songs RENAME TO SongsTmp");
                    db.execSQL("UPDATE SongsTmp SET LastModified = LastModified * 1000000");
                    db.execSQL("CREATE TABLE Songs (" +
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
                               "  LastModifiedUsec INTEGER NOT NULL)");
                    db.execSQL("INSERT INTO Songs SELECT * FROM SongsTmp");
                    db.execSQL("DROP TABLE SongsTmp");

                    db.execSQL("ALTER TABLE LastUpdateTime RENAME TO LastUpdateTimeTmp");
                    db.execSQL("UPDATE LastUpdateTimeTmp SET MaxLastModified = MaxLastModified * 1000000 WHERE MaxLastModified > 0");
                    db.execSQL(CREATE_LAST_UPDATE_TIME_SQL);
                    db.execSQL("INSERT INTO LastUpdateTime SELECT * FROM LastUpdateTimeTmp");
                    db.execSQL("DROP TABLE LastUpdateTimeTmp");
                } else if (newVersion == 6) {
                    // Version 6: Drop Sha1, Deleted, and LastModifiedUsec columns from Songs table.
                    db.execSQL("ALTER TABLE Songs RENAME TO SongsTmp");
                    db.execSQL(CREATE_SONGS_SQL);
                    db.execSQL("INSERT INTO Songs " +
                               "SELECT SongId, Filename, Artist, Title, Album, TrackNumber, Length, Rating " +
                               "FROM SongsTmp WHERE Deleted = 0");
                    db.execSQL("DROP TABLE SongsTmp");
                } else if (newVersion == 7) {
                    // Version 7: Add ArtistSortKey and AlbumSortKey columns to ArtistAlbumStats.
                    db.execSQL("ALTER TABLE ArtistAlbumStats RENAME TO ArtistAlbumStatsTmp");
                    db.execSQL(CREATE_ARTIST_ALBUM_STATS_SQL);
                    db.execSQL("INSERT INTO ArtistAlbumStats " +
                               "SELECT Artist, Album, NumSongs, Artist, Album FROM ArtistAlbumStatsTmp");
                    db.execSQL(CREATE_ARTIST_ALBUM_STATS_ARTIST_SORT_KEY_INDEX_SQL);
                    db.execSQL(CREATE_ARTIST_ALBUM_STATS_ALBUM_SORT_KEY_INDEX_SQL);
                    db.execSQL("DROP TABLE ArtistAlbumStatsTmp");
                } else if (newVersion == 8) {
                    // Version 8: Add CachedSongs table.
                    db.execSQL(CREATE_CACHED_SONGS_SQL);
                } else if (newVersion == 9) {
                    // Version 8: Add index on Songs.Filename.
                    db.execSQL(CREATE_SONGS_FILENAME_INDEX_SQL);
                } else {
                    throw new RuntimeException(
                        "Got request to upgrade database to unknown version " + newVersion);
                }
            }
        };

        // Get some info from the database in a background thread.
        new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... args) {
                loadAggregateData(mOpener.getReadableDatabase(), false);

                SQLiteDatabase db = mOpener.getWritableDatabase();
                db.beginTransaction();
                try {
                    updateCachedSongs(db);
                } finally {
                    db.endTransaction();
                }

                return (Void) null;
            }
        }.execute();

        mUpdater = new DatabaseUpdater(mOpener.getWritableDatabase());
        mUpdaterThread = new Thread(mUpdater, "SongDatabase.DatabaseUpdater");
        mUpdaterThread.start();
    }

    public boolean getAggregateDataLoaded() { return mAggregateDataLoaded; }
    public int getNumSongs() { return mNumSongs; }
    public Date getLastSyncDate() { return mLastSyncDate; }
    public List<String> getAlbumsSortedAlphabetically() { return mAlbumsSortedAlphabetically; }
    public List<String> getArtistsSortedAlphabetically() { return mArtistsSortedAlphabetically; }
    public List<String> getArtistsSortedByNumSongs() { return mArtistsSortedByNumSongs; }

    public List<String> getAlbumsByArtist(String artist) {
        String lowerArtist = artist.toLowerCase();
        return mArtistAlbums.containsKey(lowerArtist) ? mArtistAlbums.get(lowerArtist) : new ArrayList<String>();
    }

    public List<String> getCachedArtistsSortedAlphabetically() {
        return getSortedItems(
            "SELECT DISTINCT s.Artist FROM Songs s " +
            "JOIN CachedSongs cs ON(s.SongId = cs.SongId)",
            null);
    }

    public List<String> getCachedAlbumsSortedAlphabetically() {
        return getSortedItems(
            "SELECT DISTINCT s.Album FROM Songs s " +
            "JOIN CachedSongs cs ON(s.SongId = cs.SongId)",
            null);
    }

    public List<String> getCachedAlbumsByArtist(String artist) {
        return getSortedItems(
            "SELECT DISTINCT s.Album FROM Songs s " +
            "JOIN CachedSongs cs ON(s.SongId = cs.SongId) " +
            "WHERE LOWER(s.Artist) = LOWER(?)",
            new String[]{ artist });
    }

    public synchronized void quit() {
        mUpdater.quit();
        try { mUpdaterThread.join(); } catch (InterruptedException e) {}
    }

    public List<Song> query(String artist, String title, String album, String minRating, boolean shuffle, boolean substring, boolean onlyCached) {
        class QueryBuilder {
            public List<String> selections = new ArrayList<String>();
            public List<String> selectionArgs = new ArrayList<String>();

            public void add(String clause, String selectionArg, boolean substring) {
                if (selectionArg == null || selectionArg.isEmpty())
                    return;

                selections.add(clause);
                if (selectionArg.equals(UNSET_STRING))
                    selectionArgs.add("");
                else
                    selectionArgs.add(substring ? "%" + selectionArg + "%" : selectionArg);
            }

            // Get a WHERE clause (plus trailing space) if |selections| is non-empty, or just an empty string otherwise.
            public String getWhereClause() {
                if (selections.isEmpty())
                    return "";
                return "WHERE " + TextUtils.join(" AND ", selections) + " ";
            }
        }
        QueryBuilder builder = new QueryBuilder();
        builder.add("Artist LIKE ?", artist, substring);
        builder.add("Title LIKE ?", title, substring);
        builder.add("Album LIKE ?", album, substring);
        builder.add("Rating >= ?", minRating, false);

        String query =
            "SELECT Artist, Title, Album, Filename, Length, s.SongId, Rating " +
            "FROM Songs s " +
            (onlyCached ? "JOIN CachedSongs cs ON(s.SongId = cs.SongId) " : "") +
            builder.getWhereClause() +
            "ORDER BY " + (shuffle ? "RANDOM()" : "Album ASC, TrackNumber ASC") + " " +
            "LIMIT " + MAX_QUERY_RESULTS;
        Cursor cursor = mOpener.getReadableDatabase().rawQuery(
            query, builder.selectionArgs.toArray(new String[]{}));

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

    // Record the fact that a song has been successfully cached.
    public void handleSongCached(int songId) {
        mUpdater.postUpdate(
            "REPLACE INTO CachedSongs (SongId) VALUES(?)", new Object[]{ songId });
    }

    // Record the fact that a song has been evicted from the cache.
    public void handleSongEvicted(int songId) {
        mUpdater.postUpdate(
            "DELETE FROM CachedSongs WHERE SongId = ?", new Object[]{ songId });
    }

    public boolean syncWithServer(SyncProgressListener listener, String message[]) {
        SQLiteDatabase db = mOpener.getWritableDatabase();
        db.beginTransaction();

        int numSongsUpdated = 0;
        try {
            // Ask the server for the max last modified time before we fetch anything.  We'll use this as the starting
            // point the next sync, to handle the case where some songs in the server are updated while we're doing this
            // sync.
            String maxLastModifiedUsecStr = Download.downloadString(mContext, "/songs", "getMaxLastModifiedUsec", message);
            if (maxLastModifiedUsecStr == null)
                return false;
            long maxLastModifiedUsec = Long.valueOf(maxLastModifiedUsecStr);

            // Start where we left off last time.
            Cursor cursor = db.rawQuery("SELECT MaxLastModifiedUsec FROM LastUpdateTime", null);
            cursor.moveToFirst();
            long minLastModifiedUsec = cursor.getLong(0) + 1;
            cursor.close();

            // The server breaks its results up into batches instead of sending us a bunch of songs
            // at once, so we store the highest song ID that we've seen here so we'll know where to
            // start in the next request.
            int maxSongId = 0;

            while (true) {
                String response = Download.downloadString(
                    mContext, "/songs", String.format("minSongId=%d&minLastModifiedUsec=%d", maxSongId + 1, minLastModifiedUsec), message);
                if (response == null)
                    return false;

                try {
                    JSONArray jsonSongs = (JSONArray) new JSONTokener(response).nextValue();
                    if (jsonSongs.length() == 0)
                        break;

                    for (int i = 0; i < jsonSongs.length(); ++i) {
                        JSONArray jsonSong = jsonSongs.getJSONArray(i);
                        if (jsonSong.length() != 9) {
                            db.endTransaction();
                            message[0] = "Row " + numSongsUpdated + " from server had " + jsonSong.length() + " row(s); expected 11";
                            return false;
                        }

                        int songId = jsonSong.getInt(0);
                        boolean deleted = (jsonSong.getInt(8) != 0);

                        if (!deleted) {
                            ContentValues values = new ContentValues(8);
                            values.put("SongId", songId);
                            values.put("Filename", jsonSong.getString(1));
                            values.put("Artist", jsonSong.getString(2));
                            values.put("Title", jsonSong.getString(3));
                            values.put("Album", jsonSong.getString(4));
                            values.put("TrackNumber", jsonSong.getInt(5));
                            values.put("Length", jsonSong.getInt(6));
                            values.put("Rating", jsonSong.getDouble(7));
                            db.replace("Songs", "", values);
                        } else {
                            db.delete("Songs", "SongId = ?", new String[]{ Integer.toString(songId) });
                        }

                        numSongsUpdated++;
                        maxSongId = Math.max(maxSongId, songId);
                    }
                    listener.onSyncProgress(SyncState.UPDATING_SONGS, numSongsUpdated);
                } catch (org.json.JSONException e) {
                    message[0] = "Couldn't parse response: " + e;
                    return false;
                }
            }

            ContentValues values = new ContentValues(2);
            values.put("Timestamp", new Date().getTime() / 1000);
            values.put("MaxLastModifiedUsec", maxLastModifiedUsec);
            db.update("LastUpdateTime", values, null, null);

            if (numSongsUpdated > 0) {
                listener.onSyncProgress(SyncState.UPDATING_STATS, numSongsUpdated);
                updateStatsTables(db);
                updateCachedSongs(db);
            }

            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }

        loadAggregateData(db, numSongsUpdated > 0);
        message[0] = "Synchronization complete.";
        return true;
    }

    private void updateStatsTables(SQLiteDatabase db) {
        // Map from lowercased artist name to the first row that we saw in its original case.
        HashMap<String,String> artistCaseMap = new HashMap<String,String>();

        // Map from artist name to map from album name to number of songs.
        HashMap<String,HashMap<String,Integer>> artistAlbums = new HashMap<String,HashMap<String,Integer>>();

        Cursor cursor = db.rawQuery("SELECT Artist, Album, COUNT(*) FROM Songs GROUP BY 1, 2", null);
        cursor.moveToFirst();
        while (!cursor.isAfterLast()) {
            String origArtist = cursor.getString(0);
            if (origArtist.isEmpty())
                origArtist = UNSET_STRING;
            String lowerArtist = origArtist.toLowerCase();
            if (!artistCaseMap.containsKey(lowerArtist))
                artistCaseMap.put(lowerArtist, origArtist);
            String artist = artistCaseMap.get(lowerArtist);

            String album = cursor.getString(1);
            if (album.isEmpty())
                album = UNSET_STRING;

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

        db.beginTransaction();
        try {
            db.delete("ArtistAlbumStats", null, null);
            for (String artist : artistAlbums.keySet()) {
                String artistSortKey = Util.getSortingKey(artist);
                HashMap<String,Integer> albumMap = artistAlbums.get(artist);
                for (String album : albumMap.keySet()) {
                    ContentValues values = new ContentValues(5);
                    values.put("Artist", artist);
                    values.put("Album", album);
                    values.put("NumSongs", albumMap.get(album));
                    values.put("ArtistSortKey", artistSortKey);
                    values.put("AlbumSortKey", Util.getSortingKey(album));
                    db.insert("ArtistAlbumStats", "", values);
                }
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    // Clear the CachedSongs table and repopulate it with all of the fully-downloaded songs in the cache.
    private void updateCachedSongs(SQLiteDatabase db) {
        db.delete("CachedSongs", null, null);

        int numSongs = 0;
        for (FileCacheEntry entry : mCache.getAllFullyCachedEntries()) {
            if (!entry.isFullyCached())
                continue;

            ContentValues values = new ContentValues(1);
            values.put("SongId", entry.getSongId());
            db.replace("CachedSongs", null, values);
            numSongs++;
        }
        Log.d(TAG, "learned about " + numSongs + " cached song(s)");
    }

    private void loadAggregateData(SQLiteDatabase db, boolean songsUpdated) {
        Cursor cursor = db.rawQuery("SELECT Timestamp FROM LastUpdateTime", null);
        cursor.moveToFirst();
        Date lastSyncDate = (cursor.getInt(0) > -1) ? new Date((long) cursor.getInt(0) * 1000) : null;
        cursor.close();

        // If the song data didn't change and we've already loaded it, bail out early.
        if (!songsUpdated && mAggregateDataLoaded) {
            mLastSyncDate = lastSyncDate;
            mListener.onAggregateDataUpdate();
            return;
        }

        cursor = db.rawQuery("SELECT COUNT(*) FROM Songs", null);
        cursor.moveToFirst();
        int numSongs = cursor.getInt(0);
        cursor.close();

        HashSet<String> artistSet = new HashSet<String>();
        HashSet<String> albumSet = new HashSet<String>();
        final HashMap<String,Integer> artistSongCounts = new HashMap<String,Integer>();  // 'final' so it can be used in an inner class
        HashMap<String,List<String>> artistAlbums = new HashMap<String,List<String>>();

        List<String> artistsSortedAlphabetically = new ArrayList<String>();
        cursor = db.rawQuery("SELECT DISTINCT Artist FROM ArtistAlbumStats ORDER BY ArtistSortKey ASC", null);
        cursor.moveToFirst();
        while (!cursor.isAfterLast()) {
            artistsSortedAlphabetically.add(cursor.getString(0));
            cursor.moveToNext();
        }
        cursor.close();

        List<String> albumsSortedAlphabetically = new ArrayList<String>();
        cursor = db.rawQuery("SELECT DISTINCT Album FROM ArtistAlbumStats ORDER BY AlbumSortKey ASC", null);
        cursor.moveToFirst();
        while (!cursor.isAfterLast()) {
            albumsSortedAlphabetically.add(cursor.getString(0));
            cursor.moveToNext();
        }
        cursor.close();

        cursor = db.rawQuery("SELECT Artist, Album, NumSongs FROM ArtistAlbumStats ORDER BY AlbumSortKey ASC", null);
        cursor.moveToFirst();
        while (!cursor.isAfterLast()) {
            String artist = cursor.getString(0);
            String album = cursor.getString(1);
            int numSongsInAlbum = cursor.getInt(2);

            Integer totalSongsByArtist =
                artistSongCounts.containsKey(artist) ?
                (Integer) artistSongCounts.get(artist) : 0;
            totalSongsByArtist += numSongsInAlbum;
            artistSongCounts.put(artist, totalSongsByArtist);

            String lowerArtist = artist.toLowerCase();
            List<String> albums = artistAlbums.get(lowerArtist);
            if (albums == null) {
                albums = new ArrayList<String>();
                artistAlbums.put(lowerArtist, albums);
            }
            albums.add(album);

            cursor.moveToNext();
        }
        cursor.close();

        List<String> artistsSortedByNumSongs = new ArrayList<String>();
        artistsSortedByNumSongs.addAll(artistsSortedAlphabetically);
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

    // Given a query that returns strings in its first column, returns its results
    // in sorted order.
    private List<String> getSortedItems(String query, String[] selectionArgs) {
        Cursor cursor = mOpener.getReadableDatabase().rawQuery(query, selectionArgs);

        List<String> items = new ArrayList<String>();
        cursor.moveToFirst();
        while (!cursor.isAfterLast()) {
            String item = cursor.getString(0);
            if (item.isEmpty())
                item = UNSET_STRING;
            items.add(item);
            cursor.moveToNext();
        }
        cursor.close();

        Util.sortStringList(items);
        return items;
    }
}
