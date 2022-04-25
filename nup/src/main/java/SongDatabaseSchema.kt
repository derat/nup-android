/*
 * Copyright 2022 Daniel Erat <dan@erat.org>
 * All rights reserved.
 */

package org.erat.nup

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase

/** Create the latest version of the [SongDatabase] schema in [db]. */
fun createSongDatabase(db: SQLiteDatabase) {
    runSQL(
        db,
        """
CREATE TABLE Songs (
  SongId INTEGER PRIMARY KEY NOT NULL,
  Filename VARCHAR(256) NOT NULL,
  CoverFilename VARCHAR(256) NOT NULL,
  Artist VARCHAR(256) NOT NULL,
  Title VARCHAR(256) NOT NULL,
  Album VARCHAR(256) NOT NULL,
  AlbumId VARCHAR(256) NOT NULL,
  ArtistNorm VARCHAR(256) NOT NULL,
  TitleNorm VARCHAR(256) NOT NULL,
  AlbumNorm VARCHAR(256) NOT NULL,
  Track INTEGER NOT NULL,
  Disc INTEGER NOT NULL,
  Length FLOAT NOT NULL,
  TrackGain FLOAT NOT NULL,
  AlbumGain FLOAT NOT NULL,
  PeakAmp FLOAT NOT NULL,
  Rating FLOAT NOT NULL);
CREATE INDEX Artist ON Songs (Artist);
CREATE INDEX Album ON Songs (Album);
CREATE INDEX AlbumId ON Songs (AlbumId);

CREATE TABLE ArtistAlbumStats (
  Artist VARCHAR(256) NOT NULL,
  Album VARCHAR(256) NOT NULL,
  AlbumId VARCHAR(256) NOT NULL,
  NumSongs INTEGER NOT NULL,
  ArtistSortKey VARCHAR(256) NOT NULL,
  AlbumSortKey VARCHAR(256) NOT NULL,
  CoverFilename VARCHAR(256) NOT NULL);
CREATE INDEX ArtistSortKey ON ArtistAlbumStats (ArtistSortKey);
CREATE INDEX AlbumSortKey ON ArtistAlbumStats (AlbumSortKey);

CREATE TABLE LastUpdateTime (
  LocalTimeNsec INTEGER NOT NULL,
  ServerTimeNsec INTEGER NOT NULL);
INSERT INTO LastUpdateTime (LocalTimeNsec, ServerTimeNsec) VALUES(0, 0);

CREATE TABLE CachedSongs (SongId INTEGER PRIMARY KEY NOT NULL);

CREATE TABLE PendingPlaybackReports (
  SongId INTEGER NOT NULL,
  StartTime INTEGER NOT NULL,
  PRIMARY KEY (SongId, StartTime));

CREATE TABLE SearchPresets (
  SortKey INTEGER NOT NULL, -- 0-based index in array from server
  Name VARCHAR(256) NOT NULL,
  Tags VARCHAR(256) NOT NULL,
  MinRating FLOAT NOT NULL, -- [0.0, 1.0], -1 for unset
  Unrated BOOLEAN NOT NULL,
  FirstPlayed INTEGER NOT NULL, -- seconds before now, 0 for unset
  LastPlayed INTEGER NOT NULL, -- seconds before now, 0 for unset
  OrderByLastPlayed BOOLEAN NOT NULL,
  MaxPlays INTEGER NOT NULL, -- -1 for unset
  FirstTrack BOOLEAN NOT NULL,
  Shuffle BOOLEAN NOT NULL,
  Play BOOLEAN NOT NULL);
"""
    )
}

/** Upgrade the [SongDatabase] schema from [newVersion]-1 to [newVersion]. */
fun upgradeSongDatabase(db: SQLiteDatabase, newVersion: Int) {
    val step = upgradeSteps.get(newVersion)
    if (step == null) throw RuntimeException("Invalid schema version $newVersion")
    step(db)
}

/** Get the maximum version of the [SongDatabase] schema. */
fun getMaxSongDatabaseVersion() = upgradeSteps.keys.maxOf { it }

// Database schema upgrade step to each version from the previous version.
// Indexes must be recreated after altering a table.
private val upgradeSteps = mapOf<Int, (SQLiteDatabase) -> Unit>(
    14 to { db ->
        // Add AlbumId, TrackGain, AlbumGain, and PeakAmp to Songs.
        runSQL(
            db,
            """
            ALTER TABLE Songs RENAME TO SongsTmp;
            CREATE TABLE Songs (
              SongId INTEGER PRIMARY KEY NOT NULL,
              Url VARCHAR(256) NOT NULL,
              CoverUrl VARCHAR(256) NOT NULL,
              Artist VARCHAR(256) NOT NULL,
              Title VARCHAR(256) NOT NULL,
              Album VARCHAR(256) NOT NULL,
              AlbumId VARCHAR(256) NOT NULL,
              TrackNumber INTEGER NOT NULL,
              DiscNumber INTEGER NOT NULL,
              Length INTEGER NOT NULL,
              TrackGain FLOAT NOT NULL,
              AlbumGain FLOAT NOT NULL,
              PeakAmp FLOAT NOT NULL,
              Rating FLOAT NOT NULL);
            INSERT INTO Songs
              SELECT SongId, Url, CoverUrl, Artist, Title, Album, '',
                TrackNumber, DiscNumber, Length, 0, 0, 0, Rating
              FROM SongsTmp;
            DROP TABLE SongsTmp;
            CREATE INDEX Artist ON Songs (Artist);
            CREATE INDEX Album ON Songs (Album);
            CREATE INDEX AlbumId ON Songs (AlbumId);
            """
        )
    },
    15 to { db ->
        // Add AlbumId to ArtistAlbumStats.
        runSQL(
            db,
            """
            DROP TABLE ArtistAlbumStats;
            CREATE TABLE ArtistAlbumStats (
              Artist VARCHAR(256) NOT NULL,
              Album VARCHAR(256) NOT NULL,
              AlbumId VARCHAR(256) NOT NULL,
              NumSongs INTEGER NOT NULL,
              ArtistSortKey VARCHAR(256) NOT NULL,
              AlbumSortKey VARCHAR(256) NOT NULL);
            CREATE INDEX ArtistSortKey ON ArtistAlbumStats (ArtistSortKey);
            CREATE INDEX AlbumSortKey ON ArtistAlbumStats (AlbumSortKey);
            """
        )
    },
    16 to { db ->
        // Replace Url and CoverUrl with Filename and CoverFilename.
        runSQL(
            db,
            """
            ALTER TABLE Songs RENAME TO SongsTmp;
            CREATE TABLE Songs (
              SongId INTEGER PRIMARY KEY NOT NULL,
              Filename VARCHAR(256) NOT NULL,
              CoverFilename VARCHAR(256) NOT NULL,
              Artist VARCHAR(256) NOT NULL,
              Title VARCHAR(256) NOT NULL,
              Album VARCHAR(256) NOT NULL,
              AlbumId VARCHAR(256) NOT NULL,
              TrackNumber INTEGER NOT NULL,
              DiscNumber INTEGER NOT NULL,
              Length INTEGER NOT NULL,
              TrackGain FLOAT NOT NULL,
              AlbumGain FLOAT NOT NULL,
              PeakAmp FLOAT NOT NULL,
              Rating FLOAT NOT NULL);
            INSERT INTO Songs
              SELECT SongId, '', '', Artist, Title, Album, AlbumId,
              TrackNumber, DiscNumber, Length, TrackGain, AlbumGain,
              PeakAmp, Rating FROM SongsTmp;
            DROP TABLE SongsTmp;
            CREATE INDEX Artist ON Songs (Artist);
            CREATE INDEX Album ON Songs (Album);
            CREATE INDEX AlbumId ON Songs (AlbumId);
            -- I'm deeming it too hard to convert URLs to filenames, so force a full sync.
            UPDATE LastUpdateTime SET LocalTimeNsec = 0, ServerTimeNsec = 0;
            """
        )
    },
    17 to { db ->
        // Add SearchPresets table.
        runSQL(
            db,
            """
            CREATE TABLE SearchPresets (
              SortKey INTEGER NOT NULL,
              Name VARCHAR(256) NOT NULL,
              Tags VARCHAR(256) NOT NULL,
              MinRating FLOAT NOT NULL,
              Unrated BOOLEAN NOT NULL,
              FirstPlayed INTEGER NOT NULL,
              LastPlayed INTEGER NOT NULL,
              FirstTrack BOOLEAN NOT NULL,
              Shuffle BOOLEAN NOT NULL,
              Play BOOLEAN NOT NULL);
            """
        )
    },
    18 to { db ->
        // Add ArtistNorm, TitleNorm, and AlbumNorm to Songs.
        // Change Length from INT to FLOAT in Songs.
        // Rename TrackNumber to Track and DiscNumber to Disc in Songs.
        runSQL(
            db,
            """
            ALTER TABLE Songs RENAME TO SongsTmp;
            CREATE TABLE Songs (
              SongId INTEGER PRIMARY KEY NOT NULL,
              Filename VARCHAR(256) NOT NULL,
              CoverFilename VARCHAR(256) NOT NULL,
              Artist VARCHAR(256) NOT NULL,
              Title VARCHAR(256) NOT NULL,
              Album VARCHAR(256) NOT NULL,
              AlbumId VARCHAR(256) NOT NULL,
              ArtistNorm VARCHAR(256) NOT NULL,
              TitleNorm VARCHAR(256) NOT NULL,
              AlbumNorm VARCHAR(256) NOT NULL,
              Track INTEGER NOT NULL,
              Disc INTEGER NOT NULL,
              Length FLOAT NOT NULL,
              TrackGain FLOAT NOT NULL,
              AlbumGain FLOAT NOT NULL,
              PeakAmp FLOAT NOT NULL,
              Rating FLOAT NOT NULL);
            """
        )
        db.rawQuery("SELECT * FROM SongsTmp", null).use {
            with(it) {
                while (moveToNext()) {
                    val vals = ContentValues(17)
                    vals.put("SongId", getLong(0))
                    vals.put("Filename", getString(1))
                    vals.put("CoverFilename", getString(2))
                    vals.put("Artist", getString(3))
                    vals.put("Title", getString(4))
                    vals.put("Album", getString(5))
                    vals.put("AlbumId", getString(6))
                    vals.put("ArtistNorm", normalizeForSearch(getString(3)))
                    vals.put("TitleNorm", normalizeForSearch(getString(4)))
                    vals.put("AlbumNorm", normalizeForSearch(getString(5)))
                    vals.put("Track", getInt(7))
                    vals.put("Disc", getInt(8))
                    vals.put("Length", getInt(9).toDouble())
                    vals.put("TrackGain", getFloat(10))
                    vals.put("AlbumGain", getFloat(11))
                    vals.put("PeakAmp", getFloat(12))
                    vals.put("Rating", getFloat(13))
                    db.replace("Songs", "", vals)
                }
            }
        }
        runSQL(
            db,
            """
            DROP TABLE SongsTmp;
            CREATE INDEX Artist ON Songs (Artist);
            CREATE INDEX Album ON Songs (Album);
            CREATE INDEX AlbumId ON Songs (AlbumId);
            """
        )
    },
    19 to { db ->
        // Add CoverFilename to ArtistAlbumStats.
        runSQL(
            db,
            """
            DROP TABLE ArtistAlbumStats;
            CREATE TABLE ArtistAlbumStats (
              Artist VARCHAR(256) NOT NULL,
              Album VARCHAR(256) NOT NULL,
              AlbumId VARCHAR(256) NOT NULL,
              NumSongs INTEGER NOT NULL,
              ArtistSortKey VARCHAR(256) NOT NULL,
              AlbumSortKey VARCHAR(256) NOT NULL,
              CoverFilename VARCHAR(256) NOT NULL);
            CREATE INDEX ArtistSortKey ON ArtistAlbumStats (ArtistSortKey);
            CREATE INDEX AlbumSortKey ON ArtistAlbumStats (AlbumSortKey);
            """
        )
    },
    20 to { db ->
        // Add OrderByLastPlayed to SearchPresets.
        runSQL(
            db,
            """
            ALTER TABLE SearchPresets RENAME TO SearchPresetsTmp;
            CREATE TABLE SearchPresets (
              SortKey INTEGER NOT NULL, -- 0-based index in array from server
              Name VARCHAR(256) NOT NULL,
              Tags VARCHAR(256) NOT NULL,
              MinRating FLOAT NOT NULL, -- [0.0, 1.0], -1 for unset
              Unrated BOOLEAN NOT NULL,
              FirstPlayed INTEGER NOT NULL, -- seconds before now, 0 for unset
              LastPlayed INTEGER NOT NULL, -- seconds before now, 0 for unset
              OrderByLastPlayed BOOLEAN NOT NULL,
              FirstTrack BOOLEAN NOT NULL,
              Shuffle BOOLEAN NOT NULL,
              Play BOOLEAN NOT NULL);
            INSERT INTO SearchPresets
              SELECT SortKey, Name, Tags, MinRating, Unrated, FirstPlayed, LastPlayed,
                0, FirstTrack, Shuffle, Play FROM SearchPresetsTmp;
            DROP TABLE SearchPresetsTmp;
            """
        )
    },
    21 to { db ->
        // Add MaxPlays to SearchPresets.
        runSQL(
            db,
            """
            ALTER TABLE SearchPresets RENAME TO SearchPresetsTmp;
            CREATE TABLE SearchPresets (
              SortKey INTEGER NOT NULL, -- 0-based index in array from server
              Name VARCHAR(256) NOT NULL,
              Tags VARCHAR(256) NOT NULL,
              MinRating FLOAT NOT NULL, -- [0.0, 1.0], -1 for unset
              Unrated BOOLEAN NOT NULL,
              FirstPlayed INTEGER NOT NULL, -- seconds before now, 0 for unset
              LastPlayed INTEGER NOT NULL, -- seconds before now, 0 for unset
              OrderByLastPlayed BOOLEAN NOT NULL,
              MaxPlays INTEGER NOT NULL, -- -1 for unset
              FirstTrack BOOLEAN NOT NULL,
              Shuffle BOOLEAN NOT NULL,
              Play BOOLEAN NOT NULL);
            INSERT INTO SearchPresets
              SELECT SortKey, Name, Tags, MinRating, Unrated, FirstPlayed, LastPlayed,
                OrderByLastPlayed, -1, FirstTrack, Shuffle, Play FROM SearchPresetsTmp;
            DROP TABLE SearchPresetsTmp;
            """
        )
    },
)
