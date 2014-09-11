// Copyright 2011 Daniel Erat <dan@erat.org>
// All rights reserved.

package org.erat.nup;

import android.graphics.Bitmap;

import org.json.JSONObject;

import java.io.Serializable;
import java.net.URI;
import java.net.URISyntaxException;

class Song implements Serializable {
    private final long mSongId;
    private final String mArtist, mTitle, mAlbum;
    private final int mLengthSec, mTrackNum, mDiscNum;
    private final double mRating;
    private URI mUri = null, mCoverUri = null;
    private Bitmap mCoverBitmap = null;

    // Number of bytes available to us (i.e. what we have on disk).
    private long mAvailableBytes = 0;

    // Total size of the song, as supplied by the server.
    // 0 if the song doesn't have a cache entry.
    private long mTotalBytes = 0;

    public Song(long songId, String artist, String title, String album, String url, String coverUrl,
                int lengthSec, int trackNum, int discNum, double rating) throws URISyntaxException {
        mSongId = songId;
        mArtist = artist;
        mTitle = title;
        mAlbum = album;
        mUri = constructURI(url);
        mCoverUri = constructURI(coverUrl);
        mLengthSec = lengthSec;
        mTrackNum = trackNum;
        mDiscNum = discNum;
        mRating = rating;

        // TODO: Tags.
    }

    // Yay.
    public long getSongId() { return mSongId; }
    public String getArtist() { return mArtist; }
    public String getTitle() { return mTitle; }
    public String getAlbum() { return mAlbum; }
    public URI getUri() { return mUri; }
    public URI getCoverUri() { return mCoverUri; }
    public Bitmap getCoverBitmap() { return mCoverBitmap; }
    public int getLengthSec() { return mLengthSec; }
    public int getTrackNum() { return mTrackNum; }
    public int getDiscNum() { return mDiscNum; }
    public double getRating() { return mRating; }
    public long getAvailableBytes() { return mAvailableBytes; }
    public long getTotalBytes() { return mTotalBytes; }

    public void setCoverBitmap(Bitmap bitmap) { mCoverBitmap = bitmap; }
    public void setAvailableBytes(long bytes) { mAvailableBytes = bytes; }
    public void setTotalBytes(long bytes) { mTotalBytes = bytes; }

    // Update our available and total bytes from a cache entry.
    public void updateBytes(FileCacheEntry entry) {
        mAvailableBytes = entry.getCachedBytes();
        mTotalBytes = entry.getTotalBytes();
    }

    private static URI constructURI(String url) throws URISyntaxException {
        if (url.isEmpty())
            return null;

        // TODO: Lols.
        String[] parts = url.split(":", 2);
        if (parts.length != 2)
            throw new URISyntaxException(url, "Didn't match scheme:[...] format");

        URI uri = new URI(parts[0], parts[1], null);
        if (uri.getPort() == -1) {
            int port = uri.getScheme() == "https" ? 443 : 80;
            uri = new URI(uri.getScheme(), uri.getUserInfo(), uri.getHost(), port, uri.getPath(), uri.getQuery(), uri.getFragment());
        }
        return uri;
    }
}
