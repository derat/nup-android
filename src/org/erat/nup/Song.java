// Copyright 2011 Daniel Erat <dan@erat.org>
// All rights reserved.

package org.erat.nup;

import android.graphics.Bitmap;
import org.json.JSONObject;

class Song {
    private final String mArtist, mTitle, mAlbum, mFilename, mCoverFilename;
    private final int mLengthSec, mSongId;
    private final double mRating;
    private Bitmap mCoverBitmap;

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

    public String getUrlPath() { return "/music/" + mFilename; }

    public void setCoverBitmap(Bitmap bitmap) { mCoverBitmap = bitmap; }
}
