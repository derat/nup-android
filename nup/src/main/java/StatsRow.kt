// Copyright 2021 Daniel Erat <dan@erat.org>
// All rights reserved.
package org.erat.nup

// Holds song count for a specific key.
class StatsRow(
    val key: StatsKey,
    var count: Int
) {
    constructor(artist: String, album: String, albumId: String, count: Int) :
        this(StatsKey(artist, album, albumId), count) {}
}
