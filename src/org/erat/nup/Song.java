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
}
