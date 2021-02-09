// Copyright 2021 Daniel Erat <dan@erat.org>
// All rights reserved.

package org.erat.nup;

// Holds song count for a specific key.
class StatsRow {
    public final StatsKey key;
    public final int count;

    public StatsRow(StatsKey key, int count) {
        this.key = key;
        this.count = count;
    }

    public StatsRow(String artist, String album, String albumId, int count) {
        this(new StatsKey(artist, album, albumId), count);
    }
}
