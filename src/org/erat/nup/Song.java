// Copyright 2011 Daniel Erat <dan@erat.org>
// All rights reserved.

package org.erat.nup;

import android.graphics.Bitmap;

import org.json.JSONObject;

import java.io.Serializable;

class Song implements Serializable {
    private final String mArtist, mTitle, mAlbum, mFilename, mCoverFilename;
    private final int mLengthSec, mSongId;
    private final double mRating;
    private Bitmap mCoverBitmap = null;

    // Number of bytes available to us (i.e. what we have on disk).
    private long mAvailableBytes = 0;

    // Total size of the song, as supplied by the server.
    // 0 if the song doesn't have a cache entry.
    private long mTotalBytes = 0;

    public Song(String artist, String title, String album, String filename,
                String coverFilename, int lengthSec, int songId, double rating) {
        mArtist = artist;
        mTitle = title;
        mAlbum = album;
        mFilename = filename;
        mCoverFilename = coverFilename;
        mLengthSec = lengthSec;
        mSongId = songId;
        mRating = rating;
    }

    public Song(JSONObject json) throws org.json.JSONException {
        mArtist = !json.isNull("artist") ? json.getString("artist") : "";
        mTitle = !json.isNull("title") ? json.getString("title") : "";
        mAlbum = !json.isNull("album") ? json.getString("album") : "";
        mFilename = json.getString("filename");
        mCoverFilename = !json.isNull("cover") ? json.getString("cover") : "";
        mLengthSec = json.getInt("length");
        mSongId = json.getInt("songId");
        mRating = json.getDouble("rating");
        // TODO: Tags.
    }

    // Yay.
    public String getArtist() { return mArtist; }
    public String getTitle() { return mTitle; }
    public String getAlbum() { return mAlbum; }
    public String getFilename() { return mFilename; }
    public String getCoverFilename() { return mCoverFilename; }
    public Bitmap getCoverBitmap() { return mCoverBitmap; }
    public int getLengthSec() { return mLengthSec; }
    public int getSongId() { return mSongId; }
    public double getRating() { return mRating; }
    public long getAvailableBytes() { return mAvailableBytes; }
    public long getTotalBytes() { return mTotalBytes; }

    public void setCoverBitmap(Bitmap bitmap) { mCoverBitmap = bitmap; }
    public void setAvailableBytes(long bytes) { mAvailableBytes = bytes; }
    public void setTotalBytes(long bytes) { mTotalBytes = bytes; }

    public String getRemotePath() { return "/music/" + mFilename; }
}
