// Copyright 2011 Daniel Erat <dan@erat.org>
// All rights reserved.

package org.erat.nup;

import android.graphics.Bitmap;

import java.io.Serializable;
import java.net.MalformedURLException;
import java.net.URL;

class Song implements Serializable {
    public final long id;
    public final String artist, title, album, albumId;
    public final int lengthSec, track, disc;
    public final double trackGain, albumGain, peakAmp;
    public final double rating;
    public final URL url, coverUrl;

    private Bitmap coverBitmap = null;

    // Number of bytes available to us (i.e. what we have on disk).
    private long availableBytes = 0;

    // Total size of the song, as supplied by the server.
    // 0 if the song doesn't have a cache entry.
    private long totalBytes = 0;

    public Song(
            long id,
            String artist,
            String title,
            String album,
            String albumId,
            String url,
            String coverUrl,
            int lengthSec,
            int track,
            int disc,
            double trackGain,
            double albumGain,
            double peakAmp,
            double rating)
            throws MalformedURLException {
        this.id = id;
        this.artist = artist;
        this.title = title;
        this.album = album;
        this.albumId = albumId;
        this.url = url.isEmpty() ? null : new URL(url);
        this.coverUrl = coverUrl.isEmpty() ? null : new URL(coverUrl);
        this.lengthSec = lengthSec;
        this.track = track;
        this.disc = disc;
        this.trackGain = trackGain;
        this.albumGain = albumGain;
        this.peakAmp = peakAmp;
        this.rating = rating;

        // TODO: Tags.
    }

    public Bitmap getCoverBitmap() {
        return coverBitmap;
    }

    public long getAvailableBytes() {
        return availableBytes;
    }

    public long getTotalBytes() {
        return totalBytes;
    }

    public void setCoverBitmap(Bitmap bitmap) {
        coverBitmap = bitmap;
    }

    public void setAvailableBytes(long bytes) {
        availableBytes = bytes;
    }

    public void setTotalBytes(long bytes) {
        totalBytes = bytes;
    }

    // Update our available and total bytes from a cache entry.
    public void updateBytes(FileCacheEntry entry) {
        availableBytes = entry.getCachedBytes();
        totalBytes = entry.getTotalBytes();
    }
}
