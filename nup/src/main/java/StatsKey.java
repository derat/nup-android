// Copyright 2021 Daniel Erat <dan@erat.org>
// All rights reserved.

package org.erat.nup;

// Key for song counts. Fields may be empty.
class StatsKey {
    public final String artist;
    public final String album;
    public final String albumId;

    public StatsKey(String artist, String album, String albumId) {
        this.artist = artist;
        this.album = album;
        this.albumId = albumId;
    }

    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null) return false;
        if (getClass() != o.getClass()) return false;

        StatsKey ok = (StatsKey) o;
        return ok.artist == artist && ok.album == album && ok.albumId == albumId;
    }

    public int hashCode() {
        // https://stackoverflow.com/a/113600/6882947
        int result = 5;
        result = 37 * result + artist.hashCode();
        result = 37 * result + album.hashCode();
        result = 37 * result + albumId.hashCode();
        return result;
    }
}
