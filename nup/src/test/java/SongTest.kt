/*
 * Copyright 2021 Daniel Erat <dan@erat.org>
 * All rights reserved.
 */

package org.erat.nup.test

import com.google.common.truth.Truth.assertThat
import java.util.Collections
import org.erat.nup.SongOrder
import org.erat.nup.getSongOrderKey
import org.junit.Test

class SongTest {
    @Test fun getSongOrderKeyWorks() {
        val artists = arrayListOf<String>(
            "def leppard",
            "Daft Punk",
            " The Black Dog",
            "The Alan Parsons Project",
            "[no artist]",
            "[unset]",
        )
        Collections.sort(artists) { a, b ->
            getSongOrderKey(a, SongOrder.ARTIST)
                .compareTo(getSongOrderKey(b, SongOrder.ARTIST))
        }
        assertThat(artists).containsExactly(
            "[no artist]",
            "[unset]",
            "The Alan Parsons Project",
            " The Black Dog",
            "Daft Punk",
            "def leppard",
        ).inOrder()
    }
}
