// Copyright 2011 Daniel Erat <dan@erat.org>
// All rights reserved.

package org.erat.nup;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.AsyncTask;
import android.text.TextUtils;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.net.URISyntaxException;
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
    private static final int DATABASE_VERSION = 10;

    private static final int SERVER_SONG_BATCH_SIZE = 100;

    // IMPORTANT NOTE: When updating any of these, you must replace all previous references in
    // upgradeFromPreviousVersion() with the hardcoded older version of the string.
    private static final String CREATE_SONGS_SQL =
        "CREATE TABLE Songs (" +
        "  SongId INTEGER PRIMARY KEY NOT NULL, " +
        "  Url VARCHAR(256) NOT NULL, " +
        "  CoverUrl VARCHAR(256) NOT NULL, " +
        "  Artist VARCHAR(256) NOT NULL, " +
        "  Title VARCHAR(256) NOT NULL, " +
        "  Album VARCHAR(256) NOT NULL, " +
        "  TrackNumber INTEGER NOT NULL, " +
        "  DiscNumber INTEGER NOT NULL, " +
        "  Length INTEGER NOT NULL, " +
        "  Rating FLOAT NOT NULL)";
    private static final String CREATE_SONGS_ARTIST_INDEX_SQL =
        "CREATE INDEX Artist ON Songs (Artist)";
    private static final String CREATE_SONGS_ALBUM_INDEX_SQL =
        "CREATE INDEX Album ON Songs (Album)";

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
        "  LocalTimeNsec INTEGER NOT NULL, " +
        "  ServerTimeNsec INTEGER NOT NULL)";
    private static final String INSERT_LAST_UPDATE_TIME_SQL =
        "INSERT INTO LastUpdateTime (LocalTimeNsec, ServerTimeNsec) VALUES(0, 0)";

    private static final String CREATE_CACHED_SONGS_SQL =
        "CREATE TABLE CachedSongs (" +
        "  SongId INTEGER PRIMARY KEY NOT NULL)";

    private static final String CREATE_PENDING_PLAYBACK_REPORTS_SQL =
        "CREATE TABLE PendingPlaybackReports (" +
        "  SongId INTEGER NOT NULL, " +
        "  StartTime INTEGER NOT NULL, " +
        "  PRIMARY KEY (SongId, StartTime))";

    private final Context mContext;

    private final DatabaseOpener mOpener;

    // Update the database in a background thread.
    private final DatabaseUpdater mUpdater;
    private final Thread mUpdaterThread;

    private boolean mAggregateDataLoaded = false;
    private int mNumSongs = 0;
    private Date mLastSyncDate = null;
    private List<StringIntPair> mArtistsSortedAlphabetically = new ArrayList<StringIntPair>();
    private List<StringIntPair> mAlbumsSortedAlphabetically = new ArrayList<StringIntPair>();
    private List<StringIntPair> mArtistsSortedByNumSongs = new ArrayList<StringIntPair>();
    private HashMap<String,List<StringIntPair>> mArtistAlbums = new HashMap<String,List<StringIntPair>>();

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

        SQLiteOpenHelper helper = new SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {
            @Override
            public void onCreate(SQLiteDatabase db) {
                db.execSQL(CREATE_SONGS_SQL);
                db.execSQL(CREATE_SONGS_ARTIST_INDEX_SQL);
                db.execSQL(CREATE_SONGS_ALBUM_INDEX_SQL);
                db.execSQL(CREATE_ARTIST_ALBUM_STATS_SQL);
                db.execSQL(CREATE_ARTIST_ALBUM_STATS_ARTIST_SORT_KEY_INDEX_SQL);
                db.execSQL(CREATE_ARTIST_ALBUM_STATS_ALBUM_SORT_KEY_INDEX_SQL);
                db.execSQL(CREATE_LAST_UPDATE_TIME_SQL);
                db.execSQL(INSERT_LAST_UPDATE_TIME_SQL);
                db.execSQL(CREATE_CACHED_SONGS_SQL);
                db.execSQL(CREATE_PENDING_PLAYBACK_REPORTS_SQL);
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
                    db.execSQL("CREATE TABLE Songs (" +
                               "  SongId INTEGER PRIMARY KEY NOT NULL, " +
                               "  Filename VARCHAR(256) NOT NULL, " +
                               "  Artist VARCHAR(256) NOT NULL, " +
                               "  Title VARCHAR(256) NOT NULL, " +
                               "  Album VARCHAR(256) NOT NULL, " +
                               "  TrackNumber INTEGER NOT NULL, " +
                               "  Length INTEGER NOT NULL, " +
                               "  Rating FLOAT NOT NULL)");
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
                    // Version 9: Add index on Songs.Filename.
                    db.execSQL("CREATE INDEX Filename ON Songs (Filename)");
                } else if (newVersion == 10) {
                    // Version 10: Add PendingPlaybackReports table.
                    db.execSQL(CREATE_PENDING_PLAYBACK_REPORTS_SQL);
                } else if (newVersion == 11) {
                    // Version 11: Change way too much stuff for AppEngine backend.
                    throw new RuntimeException("Sorry, you need to delete everything and start over. :-(");
                } else {
                    throw new RuntimeException(
                        "Got request to upgrade database to unknown version " + newVersion);
                }
            }
        };
        mOpener = new DatabaseOpener(mContext, DATABASE_NAME, helper);

        // Get some info from the database in a background thread.
        new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... args) {
                loadAggregateData(false);

                SQLiteDatabase db = mOpener.getDb();
                db.beginTransaction();
                try {
                    updateCachedSongs(db);
                } finally {
                    db.endTransaction();
                }

                return (Void) null;
            }
        }.execute();

        mUpdater = new DatabaseUpdater(mOpener);
        mUpdaterThread = new Thread(mUpdater, "SongDatabase.DatabaseUpdater");
        mUpdaterThread.start();
    }

    public boolean getAggregateDataLoaded() { return mAggregateDataLoaded; }
    public int getNumSongs() { return mNumSongs; }
    public Date getLastSyncDate() { return mLastSyncDate; }

    public List<StringIntPair> getAlbumsSortedAlphabetically() { return mAlbumsSortedAlphabetically; }
    public List<StringIntPair> getArtistsSortedAlphabetically() { return mArtistsSortedAlphabetically; }
    public List<StringIntPair> getArtistsSortedByNumSongs() { return mArtistsSortedByNumSongs; }

    public List<StringIntPair> getAlbumsByArtist(String artist) {
        String lowerArtist = artist.toLowerCase();
        return mArtistAlbums.containsKey(lowerArtist) ? mArtistAlbums.get(lowerArtist) : new ArrayList<StringIntPair>();
    }

    public List<StringIntPair> getCachedArtistsSortedAlphabetically() {
        return getSortedItems(
            "SELECT s.Artist, COUNT(*) FROM Songs s " +
            "JOIN CachedSongs cs ON(s.SongId = cs.SongId) " +
            "GROUP BY 1",
            null);
    }

    public List<StringIntPair> getCachedAlbumsSortedAlphabetically() {
        return getSortedItems(
            "SELECT s.Album, COUNT(*) FROM Songs s " +
            "JOIN CachedSongs cs ON(s.SongId = cs.SongId) " +
            "GROUP BY 1",
            null);
    }

    public List<StringIntPair> getCachedAlbumsByArtist(String artist) {
        return getSortedItems(
            "SELECT s.Album, COUNT(*) FROM Songs s " +
            "JOIN CachedSongs cs ON(s.SongId = cs.SongId) " +
            "WHERE LOWER(s.Artist) = LOWER(?) " +
            "GROUP BY 1",
            new String[]{ artist });
    }

    public synchronized void quit() {
        mUpdater.quit();
        try { mUpdaterThread.join(); } catch (InterruptedException e) {}
        mOpener.close();
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
            "SELECT s.SongId, Artist, Title, Album, Url, CoverUrl, Length, s.TrackNumber, s.DiscNumber, Rating " +
            "FROM Songs s " +
            (onlyCached ? "JOIN CachedSongs cs ON(s.SongId = cs.SongId) " : "") +
            builder.getWhereClause() +
            "ORDER BY " + (shuffle ? "RANDOM()" : "Album ASC, TrackNumber ASC") + " " +
            "LIMIT " + MAX_QUERY_RESULTS;
        SQLiteDatabase db = mOpener.getDb();
        Cursor cursor = db.rawQuery(query, builder.selectionArgs.toArray(new String[]{}));

        List<Song> songs = new ArrayList<Song>();
        cursor.moveToFirst();
        while (!cursor.isAfterLast()) {
            try {
                Song song = new Song(
                    cursor.getLong(0), cursor.getString(1), cursor.getString(2), cursor.getString(3),
                    cursor.getString(4), cursor.getString(5), cursor.getInt(6), cursor.getInt(7), cursor.getInt(8),
                    cursor.getFloat(9));
                songs.add(song);
            } catch (URISyntaxException e) {
                Log.d(TAG, "skipping song " + cursor.getLong(0) + " with malformed URL(s) \"" +
                      cursor.getString(4) + "\" and \"" + cursor.getString(5) + "\"");
            }
            cursor.moveToNext();
        }
        cursor.close();
        return songs;
    }

    // Record the fact that a song has been successfully cached.
    public void handleSongCached(long songId) {
        mUpdater.postUpdate(
            "REPLACE INTO CachedSongs (SongId) VALUES(?)", new Object[]{ songId });
    }

    // Record the fact that a song has been evicted from the cache.
    public void handleSongEvicted(long songId) {
        mUpdater.postUpdate(
            "DELETE FROM CachedSongs WHERE SongId = ?", new Object[]{ songId });
    }

    // Add an entry to the PendingPlaybackReports table.
    public void addPendingPlaybackReport(long songId, Date startDate) {
        mUpdater.postUpdate(
            "REPLACE INTO PendingPlaybackReports (SongId, StartTime) VALUES(?, ?)",
            new Object[]{ songId, startDate.getTime() / 1000 });
    }

    // Remove an entry from the PendingPlaybackReports table.
    public void removePendingPlaybackReport(long songId, Date startDate) {
        mUpdater.postUpdate(
            "DELETE FROM PendingPlaybackReports WHERE SongId = ? AND StartTime = ?",
            new Object[]{ songId, startDate.getTime() / 1000 });
    }

    // Simple struct representing a queued report of a song being played.
    public static class PendingPlaybackReport {
        public long songId;
        public Date startDate;

        PendingPlaybackReport(long songId, Date startDate) {
            this.songId = songId;
            this.startDate = startDate;
        }
    }

    // Get all pending playback reports from the PendingPlaybackReports table.
    public List<PendingPlaybackReport> getAllPendingPlaybackReports() {
        String query = "SELECT SongId, StartTime FROM PendingPlaybackReports";
        SQLiteDatabase db = mOpener.getDb();
        Cursor cursor = db.rawQuery(query, null);
        cursor.moveToFirst();
        List<PendingPlaybackReport> reports = new ArrayList<PendingPlaybackReport>();
        while (!cursor.isAfterLast()) {
            reports.add(new PendingPlaybackReport(cursor.getLong(0), new Date(cursor.getLong(1) * 1000)));
            cursor.moveToNext();
        }
        cursor.close();
        return reports;
    }

    public boolean syncWithServer(SyncProgressListener listener, String message[]) {
        if (!Util.isNetworkAvailable(mContext)) {
            message[0] = mContext.getString(R.string.network_is_unavailable);
            return false;
        }

        int numSongsUpdated = 0;
        SQLiteDatabase db = mOpener.getDb();
        db.beginTransaction();
        try {
            // Ask the server for the current time before we fetch anything.  We'll use this as the starting point for
            // the next sync, to handle the case where some songs in the server are updated while we're doing this sync.
            String startTimeStr = Download.downloadString(mContext, "/now_nsec", "", message);
            if (startTimeStr == null)
                return false;
            long startTimeNsec = 0;
            try {
                startTimeNsec = Long.valueOf(startTimeStr);
            } catch (NumberFormatException e) {
                message[0] = "Unable to parse time: " + startTimeStr;
                return false;
            }

            // Start where we left off last time.
            Cursor dbCursor = db.rawQuery("SELECT ServerTimeNsec FROM LastUpdateTime", null);
            dbCursor.moveToFirst();
            long prevStartTimeNsec = dbCursor.getLong(0);
            dbCursor.close();

            // The server breaks its results up into batches instead of sending us a bunch of songs
            // at once, so use the cursor that it returns to start in the correct place in the next request.
            String serverCursor = "";

            while (numSongsUpdated == 0 || !serverCursor.isEmpty()) {
                String response = Download.downloadString(
                    mContext, "/songs",
                    String.format("minLastModifiedNsec=%d&max=%d&cursor=%s", prevStartTimeNsec, SERVER_SONG_BATCH_SIZE, serverCursor), message);
                if (response == null)
                    return false;

                serverCursor = "";

                try {
                    JSONArray objects = (JSONArray) new JSONTokener(response).nextValue();
                    if (objects.length() == 0)
                        break;

                    for (int i = 0; i < objects.length(); ++i) {
                        JSONObject jsonSong = objects.optJSONObject(i);
                        if (jsonSong == null) {
                            if (i == objects.length() - 1) {
                                serverCursor = objects.getString(i);
                                break;
                            } else {
                                message[0] = "Item " + i + " from server isn't a JSON object";
                                return false;
                            }
                        }

                        long songId = jsonSong.getLong("songId");
                        boolean deleted = false;  // TODO: Figure out how to do deletions for App Engine.

                        if (!deleted) {
                            ContentValues values = new ContentValues(10);
                            values.put("SongId", songId);
                            values.put("Url", jsonSong.getString("url"));
                            values.put("CoverUrl", jsonSong.has("coverUrl") ? jsonSong.getString("coverUrl") : "");
                            values.put("Artist", jsonSong.getString("artist"));
                            values.put("Title", jsonSong.getString("title"));
                            values.put("Album", jsonSong.getString("album"));
                            values.put("TrackNumber", jsonSong.getInt("track"));
                            values.put("DiscNumber", jsonSong.getInt("disc"));
                            values.put("Length", jsonSong.getDouble("length"));
                            values.put("Rating", jsonSong.getDouble("rating"));
                            db.replace("Songs", "", values);
                        } else {
                            db.delete("Songs", "SongId = ?", new String[]{ Long.toString(songId) });
                        }
                        numSongsUpdated++;
                    }
                } catch (org.json.JSONException e) {
                    message[0] = "Couldn't parse response: " + e;
                    return false;
                }

                listener.onSyncProgress(SyncState.UPDATING_SONGS, numSongsUpdated);
            }

            ContentValues values = new ContentValues(2);
            values.put("LocalTimeNsec", new Date().getTime()*1000*1000);
            values.put("ServerTimeNsec", startTimeNsec);
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

        loadAggregateData(numSongsUpdated > 0);
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

    private void loadAggregateData(boolean songsUpdated) {
        SQLiteDatabase db = mOpener.getDb();
        Cursor cursor = db.rawQuery("SELECT LocalTimeNsec FROM LastUpdateTime", null);
        cursor.moveToFirst();
        Date lastSyncDate = (cursor.getInt(0) > 0) ? new Date(cursor.getLong(0)/(1000*1000)) : null;
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

        List<StringIntPair> artistsSortedAlphabetically = new ArrayList<StringIntPair>();
        cursor = db.rawQuery("SELECT Artist, SUM(NumSongs) FROM ArtistAlbumStats GROUP BY 1 ORDER BY ArtistSortKey ASC", null);
        cursor.moveToFirst();
        while (!cursor.isAfterLast()) {
            artistsSortedAlphabetically.add(new StringIntPair(cursor.getString(0), cursor.getInt(1)));
            cursor.moveToNext();
        }
        cursor.close();

        List<StringIntPair> albumsSortedAlphabetically = new ArrayList<StringIntPair>();
        cursor = db.rawQuery("SELECT Album, SUM(NumSongs) FROM ArtistAlbumStats GROUP BY 1 ORDER BY AlbumSortKey ASC", null);
        cursor.moveToFirst();
        while (!cursor.isAfterLast()) {
            albumsSortedAlphabetically.add(new StringIntPair(cursor.getString(0), cursor.getInt(1)));
            cursor.moveToNext();
        }
        cursor.close();

        HashMap<String,List<StringIntPair>> artistAlbums = new HashMap<String,List<StringIntPair>>();
        cursor = db.rawQuery("SELECT Artist, Album, NumSongs FROM ArtistAlbumStats ORDER BY AlbumSortKey ASC", null);
        cursor.moveToFirst();
        while (!cursor.isAfterLast()) {
            String artist = cursor.getString(0);
            String album = cursor.getString(1);
            int numSongsForArtistAlbum = cursor.getInt(2);

            String lowerArtist = artist.toLowerCase();
            List<StringIntPair> albums = artistAlbums.get(lowerArtist);
            if (albums == null) {
                albums = new ArrayList<StringIntPair>();
                artistAlbums.put(lowerArtist, albums);
            }
            albums.add(new StringIntPair(album, numSongsForArtistAlbum));

            cursor.moveToNext();
        }
        cursor.close();

        List<StringIntPair> artistsSortedByNumSongs = new ArrayList<StringIntPair>();
        artistsSortedByNumSongs.addAll(artistsSortedAlphabetically);
        Collections.sort(artistsSortedByNumSongs, new Comparator<StringIntPair>() {
            @Override
            public int compare(StringIntPair a, StringIntPair b) {
                return b.getInt() - a.getInt();
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

    // Given a query that returns strings in its first column and ints in its second column, returns its results in
    // sorted order (on the first column only).
    private List<StringIntPair> getSortedItems(String query, String[] selectionArgs) {
        SQLiteDatabase db = mOpener.getDb();
        Cursor cursor = db.rawQuery(query, selectionArgs);

        List<StringIntPair> items = new ArrayList<StringIntPair>();
        cursor.moveToFirst();
        while (!cursor.isAfterLast()) {
            String string = cursor.getString(0);
            if (string.isEmpty())
                string = UNSET_STRING;
            items.add(new StringIntPair(string, cursor.getInt(1)));
            cursor.moveToNext();
        }
        cursor.close();

        Util.sortStringIntPairList(items);
        return items;
    }
}
