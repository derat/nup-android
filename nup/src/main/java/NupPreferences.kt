/*
 * Copyright 2011 Daniel Erat <dan@erat.org>
 * All rights reserved.
 */

package org.erat.nup

object NupPreferences {
    // These are all strings. I think that Android's settings-related UI classes make (or made?) it
    // hard to use numeric types for prefs.
    const val SERVER_URL = "server_url"
    const val USERNAME = "username"
    const val PASSWORD = "password"
    const val SYNC_SONG_LIST = "sync_song_list"
    const val PRE_AMP_GAIN = "pre_amp_gain" // positive or negative decibels
    const val CACHE_SIZE = "cache_size" // megabytes
    const val CLEAR_CACHE = "clear_cache"
    const val SONGS_TO_PRELOAD = "songs_to_preload"
    const val DOWNLOAD_RATE = "download_rate" // kilobytes per second

    const val PREV_PLAYLIST_SONG_IDS = "prev_playlist_song_ids" // comma-separated list of long IDs
    const val PREV_PLAYLIST_INDEX = "prev_playlist_index" // int index into playlist
    const val PREV_POSITION_MS = "prev_position_ms" // int ms
    const val PREV_EXIT_MS = "prev_exit_ms" // long ms since epoch
}
