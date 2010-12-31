package org.erat.nup;

import org.json.JSONObject;

class Song {
    private final String mArtist, mTitle, mAlbum, mFilename, mCoverFilename;
    private final int mLengthSec, mSongId;
    private final double mRating;

    public Song(JSONObject json) throws org.json.JSONException {
        mArtist = json.getString("artist");
        mTitle = json.getString("title");
        mAlbum = json.getString("album");
        mFilename = json.getString("filename");
        mCoverFilename = json.getString("cover");
        mLengthSec = json.getInt("length");
        mSongId = json.getInt("songId");
        mRating = json.getDouble("rating");
        // TODO: Tags.
    }

    // Yay.
    public final String getArtist() { return mArtist; }
    public final String getTitle() { return mTitle; }
    public final String getAlbum() { return mAlbum; }
    public final String getFilename() { return mFilename; }
    public final String getCoverFilename() { return mCoverFilename; }
    public final int getLengthSec() { return mLengthSec; }
    public final int getSongId() { return mSongId; }
    public final double getRating() { return mRating; }
}
