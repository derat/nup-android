/*
 * Copyright 2011 Daniel Erat <dan@erat.org>
 * All rights reserved.
 */

package org.erat.nup

object NupPreferences {
    const val SERVER_URL = "server_url"
    const val USERNAME = "username"
    const val PASSWORD = "password"
    const val SYNC_SONG_LIST = "sync_song_list"
    const val PRE_AMP_GAIN = "pre_amp_gain" // positive or negative decibels
    const val CACHE_SIZE = "cache_size" // megabytes
    const val CLEAR_CACHE = "clear_cache"
    const val SONGS_TO_PRELOAD = "songs_to_preload"
    const val DOWNLOAD_RATE = "download_rate" // kilobytes per second
    const val PRE_AMP_GAIN_DEFAULT = "0"
    const val CACHE_SIZE_DEFAULT = "512"
    const val CACHE_SIZE_MINIMUM = 20
    const val SONGS_TO_PRELOAD_DEFAULT = "1"
    const val DOWNLOAD_RATE_DEFAULT = "0"
}
