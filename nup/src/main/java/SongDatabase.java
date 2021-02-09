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

import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

public class SongDatabase {
    private static final String TAG = "SongDatabase";

    // Special user-visible string that we use to represent a blank field.
    // It should be something that doesn't legitimately appear in any fields,
    // so "[unknown]" is out (MusicBrainz uses it for unknown artists).
    public static final String UNSET_STRING = "[unset]";

    private static final String MAX_QUERY_RESULTS = "250";

    private static final String DATABASE_NAME = "NupSongs";
    private static final int DATABASE_VERSION = 15;

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
        "  AlbumId VARCHAR(256) NOT NULL, " +
        "  TrackNumber INTEGER NOT NULL, " +
        "  DiscNumber INTEGER NOT NULL, " +
        "  Length INTEGER NOT NULL, " +
        "  TrackGain FLOAT NOT NULL, " +
        "  AlbumGain FLOAT NOT NULL, " +
        "  PeakAmp FLOAT NOT NULL, " +
        "  Rating FLOAT NOT NULL)";
    private static final String CREATE_SONGS_ARTIST_INDEX_SQL =
        "CREATE INDEX Artist ON Songs (Artist)";
    private static final String CREATE_SONGS_ALBUM_INDEX_SQL =
        "CREATE INDEX Album ON Songs (Album)";
    private static final String CREATE_SONGS_ALBUM_ID_INDEX_SQL =
        "CREATE INDEX AlbumId ON Songs (AlbumId)";

    private static final String CREATE_ARTIST_ALBUM_STATS_SQL =
        "CREATE TABLE ArtistAlbumStats (" +
        "  Artist VARCHAR(256) NOT NULL, " +
        "  Album VARCHAR(256) NOT NULL, " +
        "  AlbumId VARCHAR(256) NOT NULL, " +
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
    private final Listener mListener;
    private final FileCache mCache;
    private final Downloader mDownloader;
    private final NetworkHelper mNetworkHelper;

    private final DatabaseOpener mOpener;

    // Update the database in a background thread.
    private final DatabaseUpdater mUpdater;
    private final Thread mUpdaterThread;

    private boolean mAggregateDataLoaded = false;
    private int mNumSongs = 0;
    private Date mLastSyncDate = null;

    // Int values in these maps are numbers of songs.
    private List<StatsRow> mArtistsSortedAlphabetically = new ArrayList<StatsRow>();
    private List<StatsRow> mArtistsSortedByNumSongs = new ArrayList<StatsRow>();
    private List<StatsRow> mAlbumsSortedAlphabetically = new ArrayList<StatsRow>();
    private HashMap<String,List<StatsRow>> mArtistAlbums = new HashMap<String,List<StatsRow>>();

    public interface Listener {
        void onAggregateDataUpdate();
    }

    public enum SyncState {
        UPDATING_SONGS,
        DELETING_SONGS,
        UPDATING_STATS
    }
    public interface SyncProgressListener {
        void onSyncProgress(SyncState state, int numSongs);
    }

    public SongDatabase(Context context, Listener listener, FileCache cache, Downloader downloader,
                        NetworkHelper networkHelper) {
        mContext = context;
        mListener = listener;
        mCache = cache;
        mDownloader = downloader;
        mNetworkHelper = networkHelper;

        SQLiteOpenHelper helper = new SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {
            @Override
            public void onCreate(SQLiteDatabase db) {
                db.execSQL(CREATE_SONGS_SQL);
                db.execSQL(CREATE_SONGS_ARTIST_INDEX_SQL);
                db.execSQL(CREATE_SONGS_ALBUM_INDEX_SQL);
                db.execSQL(CREATE_SONGS_ALBUM_ID_INDEX_SQL);
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
            // The only reason I'm keeping the old upgrade steps is for reference when writing new ones.
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
                } else if (newVersion == 12 || newVersion == 13) {
                    // Versions 12 and 13: Update sort ordering.
                    // It isn't actually safe to call updateStatsTables() like this, since it could
                    // be now be assuming a schema different than the one used in these old
                    // versions.
                    updateStatsTables(db);
                } else if (newVersion == 14) {
                    // Version 14: Add AlbumId, TrackGain, AlbumGain, and PeakAmp to Songs.
                    db.execSQL("ALTER TABLE Songs RENAME TO SongsTmp");
                    db.execSQL("CREATE TABLE Songs (" +
                               "  SongId INTEGER PRIMARY KEY NOT NULL, " +
                               "  Url VARCHAR(256) NOT NULL, " +
                               "  CoverUrl VARCHAR(256) NOT NULL, " +
                               "  Artist VARCHAR(256) NOT NULL, " +
                               "  Title VARCHAR(256) NOT NULL, " +
                               "  Album VARCHAR(256) NOT NULL, " +
                               "  AlbumId VARCHAR(256) NOT NULL, " +
                               "  TrackNumber INTEGER NOT NULL, " +
                               "  DiscNumber INTEGER NOT NULL, " +
                               "  Length INTEGER NOT NULL, " +
                               "  TrackGain FLOAT NOT NULL, " +
                               "  AlbumGain FLOAT NOT NULL, " +
                               "  PeakAmp FLOAT NOT NULL, " +
                               "  Rating FLOAT NOT NULL)");
                    db.execSQL("INSERT INTO Songs " +
                               "SELECT SongId, Url, CoverUrl, Artist, Title, Album, '', " +
                               "    TrackNumber, DiscNumber, Length, 0, 0, 0, Rating " +
                               "FROM SongsTmp");
                    db.execSQL("DROP TABLE SongsTmp");
                    // Sigh, I think I should've been recreating indexes after previous upgrades.
                    // From testing with the sqlite3 command, it looks like the old indexes will
                    // probably be updated to point at SongsTmp and then dropped.
                    db.execSQL(CREATE_SONGS_ARTIST_INDEX_SQL);
                    db.execSQL(CREATE_SONGS_ALBUM_INDEX_SQL);
                    db.execSQL(CREATE_SONGS_ALBUM_ID_INDEX_SQL);
                } else if (newVersion == 15) {
                    // Version 15: Add AlbumId to ArtistAlbumStats.
                    db.execSQL("DROP TABLE ArtistAlbumStats");
                    db.execSQL("CREATE TABLE ArtistAlbumStats (" +
                               "  Artist VARCHAR(256) NOT NULL, " +
                               "  Album VARCHAR(256) NOT NULL, " +
                               "  AlbumId VARCHAR(256) NOT NULL, " +
                               "  NumSongs INTEGER NOT NULL, " +
                               "  ArtistSortKey VARCHAR(256) NOT NULL, " +
                               "  AlbumSortKey VARCHAR(256) NOT NULL)");
                    db.execSQL(CREATE_ARTIST_ALBUM_STATS_ARTIST_SORT_KEY_INDEX_SQL);
                    db.execSQL(CREATE_ARTIST_ALBUM_STATS_ALBUM_SORT_KEY_INDEX_SQL);
                    updateStatsTables(db);
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
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);

        mUpdater = new DatabaseUpdater(mOpener);
        mUpdaterThread = new Thread(mUpdater, "SongDatabase.DatabaseUpdater");
        mUpdaterThread.start();
    }

    public boolean getAggregateDataLoaded() { return mAggregateDataLoaded; }
    public int getNumSongs() { return mNumSongs; }
    public Date getLastSyncDate() { return mLastSyncDate; }

    public List<StatsRow> getAlbumsSortedAlphabetically() { return mAlbumsSortedAlphabetically; }
    public List<StatsRow> getArtistsSortedAlphabetically() { return mArtistsSortedAlphabetically; }
    public List<StatsRow> getArtistsSortedByNumSongs() { return mArtistsSortedByNumSongs; }
    public List<StatsRow> getAlbumsByArtist(String artist) {
        String lowerArtist = artist.toLowerCase();
        return mArtistAlbums.containsKey(lowerArtist)
            ? mArtistAlbums.get(lowerArtist)
            : new ArrayList<StatsRow>();
    }

    public List<StatsRow> getCachedArtistsSortedAlphabetically() {
        return getSortedRows(
            "SELECT s.Artist, '' AS Album, '' AS AlbumId, COUNT(*) " +
            "FROM Songs s " +
            "JOIN CachedSongs cs ON(s.SongId = cs.SongId) " +
            "GROUP BY LOWER(TRIM(s.Artist))",
            null, Util.SORT_ARTIST);
    }

    public List<StatsRow> getCachedAlbumsSortedAlphabetically() {
        // TODO: It'd be better to use most-frequent-artist logic here similar
        // to what loadAggregateData() does for |mAlbumsSortedAlphabetically|.
        // I haven't figured out how to do that solely with SQL, though, and
        // I'd rather not write one-off code here.
        return getSortedRows(
            "SELECT MIN(s.Artist), s.Album, s.AlbumId, COUNT(*) " +
            "FROM Songs s " +
            "JOIN CachedSongs cs ON(s.SongId = cs.SongId) " +
            "GROUP BY LOWER(TRIM(s.Album)), s.AlbumId",
            null, Util.SORT_ALBUM);
    }

    public List<StatsRow> getCachedAlbumsByArtist(String artist) {
        return getSortedRows(
            "SELECT '' AS Artist, s.Album, s.AlbumId, COUNT(*) " +
            "FROM Songs s " +
            "JOIN CachedSongs cs ON(s.SongId = cs.SongId) " +
            "WHERE LOWER(s.Artist) = LOWER(?) " +
            "GROUP BY LOWER(TRIM(s.Album)), s.AlbumId",
            new String[]{ artist }, Util.SORT_ALBUM);
    }

    public synchronized void quit() {
        mUpdater.quit();
        try { mUpdaterThread.join(); } catch (InterruptedException e) {}
        mOpener.close();
    }

    public List<Song> query(String artist, String title, String album, String albumId,
                            double minRating, boolean shuffle, boolean substring, boolean onlyCached) {
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
                    selectionArgs.add(substring ? ("%" + selectionArg + "%") : selectionArg);
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
        builder.add("AlbumId = ?", albumId, false);
        builder.add("Rating >= ?", minRating >= 0.0 ? Double.toString(minRating) : null, false);

        String query =
            "SELECT s.SongId, Artist, Title, Album, AlbumId, Url, CoverUrl, Length, " +
            "TrackNumber, DiscNumber, TrackGain, AlbumGain, PeakAmp, Rating " +
            "FROM Songs s " +
            (onlyCached ? "JOIN CachedSongs cs ON(s.SongId = cs.SongId) " : "") +
            builder.getWhereClause() +
            "ORDER BY " + (shuffle ? "RANDOM()" : "Album ASC, AlbumId ASC, DiscNumber ASC, TrackNumber ASC") + " " +
            "LIMIT " + MAX_QUERY_RESULTS;
        Log.d(TAG, "running query \"" + query + "\" with args " + TextUtils.join(", ", builder.selectionArgs));
        SQLiteDatabase db = mOpener.getDb();
        Cursor cursor = db.rawQuery(query, builder.selectionArgs.toArray(new String[]{}));

        List<Song> songs = new ArrayList<Song>();
        cursor.moveToFirst();
        while (!cursor.isAfterLast()) {
            try {
                Song song = new Song(
                    cursor.getLong(0),    // songId
                    cursor.getString(1),  // artist
                    cursor.getString(2),  // title
                    cursor.getString(3),  // album
                    cursor.getString(4),  // albumId
                    cursor.getString(5),  // url
                    cursor.getString(6),  // coverUrl
                    cursor.getInt(7),     // length
                    cursor.getInt(8),     // trackNumber
                    cursor.getInt(9),     // discNumber
                    cursor.getFloat(10),  // trackGain
                    cursor.getFloat(11),  // albumGain
                    cursor.getFloat(12),  // peakAmp
                    cursor.getFloat(13)); // rating
                songs.add(song);
            } catch (MalformedURLException e) {
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

        public PendingPlaybackReport(long songId, Date startDate) {
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
        if (!mNetworkHelper.isNetworkAvailable()) {
            message[0] = mContext.getString(R.string.network_is_unavailable);
            return false;
        }

        int numSongsUpdated = 0;
        SQLiteDatabase db = mOpener.getDb();
        db.beginTransaction();
        try {
            // Ask the server for the current time before we fetch anything.  We'll use this as the starting point for
            // the next sync, to handle the case where some songs in the server are updated while we're doing this sync.
            String startTimeStr = mDownloader.downloadString("/now_nsec", message);
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

            try {
                numSongsUpdated += queryServer(db, prevStartTimeNsec, false, listener, message);
                numSongsUpdated += queryServer(db, prevStartTimeNsec, true, listener, message);
            } catch (ServerException e) {
                return false;
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

    public static class ServerException extends Exception {
        public ServerException(String reason) {
            super(reason);
        }
    }

    // Helper method for SyncWithServer. Returns number of updated songs.
    private int queryServer(SQLiteDatabase db, long prevStartTimeNsec, boolean deleted,
                            SyncProgressListener listener, String message[]) throws ServerException {
        int numUpdates = 0;

        // The server breaks its results up into batches instead of sending us a bunch of songs
        // at once, so use the cursor that it returns to start in the correct place in the next request.
        String serverCursor = "";

        while (numUpdates == 0 || !serverCursor.isEmpty()) {
            String path = String.format("/songs?minLastModifiedNsec=%d&deleted=%d&max=%d&cursor=%s",
                                        prevStartTimeNsec, deleted ? 1 : 0, SERVER_SONG_BATCH_SIZE,
                                        serverCursor);
            String response = mDownloader.downloadString(path, message);
            if (response == null) {
                throw new ServerException("download failed");
            }

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
                            throw new ServerException("list item not object");
                        }
                    }

                    long songId = jsonSong.getLong("songId");

                    if (deleted) {
                        Log.d(TAG, "deleting song " + songId);
                        db.delete("Songs", "SongId = ?", new String[]{ Long.toString(songId) });
                    } else {
                        ContentValues values = new ContentValues(14);
                        values.put("SongId", songId);
                        values.put("Url", jsonSong.getString("url"));
                        values.put("CoverUrl", jsonSong.has("coverUrl") ? jsonSong.getString("coverUrl") : "");
                        values.put("Artist", jsonSong.getString("artist"));
                        values.put("Title", jsonSong.getString("title"));
                        values.put("Album", jsonSong.getString("album"));
                        values.put("AlbumId", jsonSong.has("albumId") ? jsonSong.getString("albumId") : "");
                        values.put("TrackNumber", jsonSong.getInt("track"));
                        values.put("DiscNumber", jsonSong.getInt("disc"));
                        values.put("Length", jsonSong.getDouble("length"));
                        values.put("TrackGain", jsonSong.has("trackGain") ? jsonSong.getDouble("trackGain") : 0.0);
                        values.put("AlbumGain", jsonSong.has("albumGain") ? jsonSong.getDouble("albumGain") : 0.0);
                        values.put("PeakAmp", jsonSong.has("peakAmp") ? jsonSong.getDouble("peakAmp") : 0.0);
                        values.put("Rating", jsonSong.getDouble("rating"));
                        db.replace("Songs", "", values);
                    }
                    numUpdates++;
                }
            } catch (org.json.JSONException e) {
                message[0] = "Couldn't parse response: " + e;
                throw new ServerException("bad data");
            }

            listener.onSyncProgress(deleted ? SyncState.DELETING_SONGS : SyncState.UPDATING_SONGS, numUpdates);
        }

        return numUpdates;
    }

    private void updateStatsTables(SQLiteDatabase db) {
        // Map from lowercased artist name to the first row that we saw in its original case.
        HashMap<String,String> artistCaseMap = new HashMap<String,String>();

        // Map from artist name to map from album to number of songs.
        HashMap<String,HashMap<StatsKey,Integer>> artistAlbums =
            new HashMap<String,HashMap<StatsKey,Integer>>();

        Cursor cursor = db.rawQuery("SELECT Artist, Album, AlbumId, COUNT(*) " +
                                    "  FROM Songs " +
                                    "  GROUP BY Artist, Album, AlbumId", null);
        cursor.moveToFirst();
        while (!cursor.isAfterLast()) {
            String origArtist = cursor.getString(0);
            if (origArtist.isEmpty())
                origArtist = UNSET_STRING;

            // Normalize the artist case so that we'll still group all their songs together
            // even if some use different capitalization.
            String lowerArtist = origArtist.toLowerCase();
            if (!artistCaseMap.containsKey(lowerArtist))
                artistCaseMap.put(lowerArtist, origArtist);
            String artist = artistCaseMap.get(lowerArtist);

            String album = cursor.getString(1);
            if (album.isEmpty())
                album = UNSET_STRING;
            String albumId = cursor.getString(2);
            int numSongs = cursor.getInt(3);

            HashMap<StatsKey,Integer> albumMap;
            if (artistAlbums.containsKey(artist)) {
                albumMap = artistAlbums.get(artist);
            } else {
                albumMap = new HashMap<StatsKey,Integer>();
                artistAlbums.put(artist, albumMap);
            }
            albumMap.put(new StatsKey(origArtist, album, albumId), numSongs);

            cursor.moveToNext();
        }
        cursor.close();

        db.delete("ArtistAlbumStats", null, null);
        for (String artist : artistAlbums.keySet()) {
            String artistSortKey = Util.getSortingKey(artist, Util.SORT_ARTIST);
            HashMap<StatsKey,Integer> albumMap = artistAlbums.get(artist);
            for (StatsKey key : albumMap.keySet()) {
                ContentValues values = new ContentValues(6);
                values.put("Artist", artist);
                values.put("Album", key.album);
                values.put("AlbumId", key.albumId);
                values.put("NumSongs", albumMap.get(key));
                values.put("ArtistSortKey", artistSortKey);
                values.put("AlbumSortKey", Util.getSortingKey(key.album, Util.SORT_ALBUM));
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
        mLastSyncDate = (cursor.getLong(0) > 0) ? new Date(cursor.getLong(0)/(1000*1000)) : null;
        cursor.close();

        // If the song data didn't change and we've already loaded it, bail out early.
        if (!songsUpdated && mAggregateDataLoaded) {
            mListener.onAggregateDataUpdate();
            return;
        }

        cursor = db.rawQuery("SELECT COUNT(*) FROM Songs", null);
        cursor.moveToFirst();
        int numSongs = cursor.getInt(0);
        cursor.close();

        List<StatsRow> artistsSortedAlphabetically = new ArrayList<StatsRow>();
        cursor = db.rawQuery("SELECT Artist, SUM(NumSongs) " +
                             "FROM ArtistAlbumStats " +
                             "GROUP BY LOWER(TRIM(Artist)) " +
                             "ORDER BY ArtistSortKey ASC", null);
        cursor.moveToFirst();
        while (!cursor.isAfterLast()) {
            artistsSortedAlphabetically.add(new StatsRow(cursor.getString(0), "", "", cursor.getInt(1)));
            cursor.moveToNext();
        }
        cursor.close();

        // Aggregate by (album, albumId) while storing the most-frequent artist for each album.
        List<StatsRow> albumsSortedAlphabetically = new ArrayList<StatsRow>();
        HashMap<String,List<StatsRow>> artistAlbums = new HashMap<String,List<StatsRow>>();
        StatsRow lastRow = null; // last row added to |albumsSortedAlphabetically|
        cursor = db.rawQuery("SELECT Artist, Album, AlbumId, SUM(NumSongs) " +
                             "FROM ArtistAlbumStats " +
                             "GROUP BY LOWER(TRIM(Artist)), LOWER(TRIM(Album)), AlbumId " +
                             "ORDER BY AlbumSortKey ASC, AlbumId ASC, 4 DESC", null);
        cursor.moveToFirst();
        while (!cursor.isAfterLast()) {
            String artist = cursor.getString(0);
            String album = cursor.getString(1);
            String albumId = cursor.getString(2);
            int count = cursor.getInt(3);
            cursor.moveToNext();

            if (lastRow != null
                    && lastRow.key.albumId.equals(albumId)
                    && lastRow.key.album.trim().toLowerCase().equals(album.trim().toLowerCase())) {
                lastRow.count += count;
            } else {
                StatsRow row = new StatsRow(artist, album, albumId, count);
                albumsSortedAlphabetically.add(row);
                lastRow = row;
            }

            // TODO: Consider aggregating by album ID so that we have the full count from
            // each album rather than just songs exactly matching the artist. This will
            // affect the count displayed when navigating from BrowseArtistsActivity to
            // BrowseAlbumsActivity.
            String lowerArtist = artist.toLowerCase();
            List<StatsRow> albums = artistAlbums.get(lowerArtist);
            if (albums == null) {
                albums = new ArrayList<StatsRow>();
                artistAlbums.put(lowerArtist, albums);
            }
            albums.add(new StatsRow(artist, album, albumId, count));
        }
        cursor.close();

        List<StatsRow> artistsSortedByNumSongs = new ArrayList<StatsRow>();
        artistsSortedByNumSongs.addAll(artistsSortedAlphabetically);
        Collections.sort(artistsSortedByNumSongs, new Comparator<StatsRow>() {
            @Override public int compare(StatsRow a, StatsRow b) {
                return b.count - a.count;
            }
        });

        mNumSongs = numSongs;
        mArtistsSortedAlphabetically = artistsSortedAlphabetically;
        mAlbumsSortedAlphabetically = albumsSortedAlphabetically;
        mArtistsSortedByNumSongs = artistsSortedByNumSongs;
        mArtistAlbums = artistAlbums;
        mAggregateDataLoaded = true;

        mListener.onAggregateDataUpdate();
    }

    // Given a query that returns artist, album, album ID, and num songs,
    // returns its results in sorted order.
    private List<StatsRow> getSortedRows(String query, String[] selectionArgs, int sortType) {
        SQLiteDatabase db = mOpener.getDb();
        Cursor cursor = db.rawQuery(query, selectionArgs);

        List<StatsRow> rows = new ArrayList<StatsRow>();
        cursor.moveToFirst();
        while (!cursor.isAfterLast()) {
            String artist = cursor.getString(0);
            if (artist.isEmpty()) artist = UNSET_STRING;
            String album = cursor.getString(1);
            if (album.isEmpty()) album = UNSET_STRING;
            String albumId = cursor.getString(2);
            int count = cursor.getInt(3);

            rows.add(new StatsRow(artist, album, albumId, count));
            cursor.moveToNext();
        }
        cursor.close();

        Util.sortStatsRowList(rows, sortType);
        return rows;
    }
}
