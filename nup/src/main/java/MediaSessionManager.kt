// Copyright 2016 Daniel Erat <dan@erat.org>
// All rights reserved.
package org.erat.nup

import android.content.Context
import android.media.MediaDescription
import android.media.MediaMetadata
import android.media.Rating
import android.media.session.MediaSession
import android.media.session.MediaSession.QueueItem
import android.media.session.PlaybackState
import java.util.*

class MediaSessionManager internal constructor(context: Context?, callback: MediaSession.Callback?) {
    private val session: MediaSession
    fun cleanUp() {
        session.release()
    }

    val token: MediaSession.Token
        get() = session.sessionToken

    fun updateSong(song: Song?) {
        val builder = MediaMetadata.Builder()
        if (song != null) {
            setString(builder, MediaMetadata.METADATA_KEY_ARTIST, song.artist)
            setString(builder, MediaMetadata.METADATA_KEY_TITLE, song.title)
            setString(builder, MediaMetadata.METADATA_KEY_ALBUM, song.album)
            if (song.rating >= 0.0) {
                builder.putRating(
                        MediaMetadata.METADATA_KEY_RATING,
                        Rating.newStarRating(
                                Rating.RATING_5_STARS, (1.0 + song.rating * 4.0).toFloat()))
            }
            if (song.track > 0) {
                builder.putLong(MediaMetadata.METADATA_KEY_TRACK_NUMBER, song.track.toLong())
            }
            if (song.disc > 0) {
                builder.putLong(MediaMetadata.METADATA_KEY_DISC_NUMBER, song.disc.toLong())
            }
            builder.putLong(MediaMetadata.METADATA_KEY_DURATION, song.lengthSec * 1000L)
            val bitmap = song.coverBitmap
            if (bitmap != null) {
                // Pass a copy of the original bitmap. Apparently the later apply() call recycles
                // the bitmap, which then causes a crash when we try to use it later:
                // https://code.google.com/p/android/issues/detail?id=74967
                builder.putBitmap(
                        MediaMetadata.METADATA_KEY_ALBUM_ART,
                        bitmap.copy(bitmap.config, true))
            }
        }
        session.setMetadata(builder.build())
    }

    fun updatePlaybackState(
            song: Song?,
            paused: Boolean,
            playbackComplete: Boolean,
            buffering: Boolean,
            positionMs: Long,
            songIndex: Int,
            numSongs: Int) {
        val builder = PlaybackState.Builder()
        if (song != null) builder.setActiveQueueItemId(song.id)
        var state = PlaybackState.STATE_NONE
        if (numSongs > 0) {
            state = if (buffering) {
                PlaybackState.STATE_BUFFERING
            } else if (playbackComplete) {
                if (songIndex == numSongs - 1) PlaybackState.STATE_STOPPED else PlaybackState.STATE_SKIPPING_TO_NEXT
            } else {
                if (paused) PlaybackState.STATE_PAUSED else PlaybackState.STATE_PLAYING
            }
        }
        builder.setState(state, positionMs, 1.0f)
        var actions: Long = 0
        if (!playbackComplete) {
            actions = actions or PlaybackState.ACTION_PLAY_PAUSE
            actions = actions or if (paused) PlaybackState.ACTION_PLAY else PlaybackState.ACTION_PAUSE
        }
        if (songIndex > 0) actions = actions or PlaybackState.ACTION_SKIP_TO_PREVIOUS
        if (songIndex < numSongs - 1) actions = actions or PlaybackState.ACTION_SKIP_TO_NEXT
        builder.setActions(actions)
        session.setPlaybackState(builder.build())
    }

    fun updatePlaylist(songs: List<Song>) {
        val queue: MutableList<QueueItem> = ArrayList()
        for (song in songs) {
            val desc = MediaDescription.Builder()
                    .setMediaId(java.lang.Long.toString(song.id))
                    .setTitle(song.title)
                    .setSubtitle(song.artist) // TODO: Set icon too, maybe.
                    .build()
            queue.add(QueueItem(desc, song.id))
        }
        session.setQueue(queue)
    }

    private fun setString(builder: MediaMetadata.Builder, key: String, value: String?) {
        if (value != null && value.length > 0) {
            builder.putString(key, value)
        }
    }

    companion object {
        private const val TAG = "MediaSessionManager"
    }

    init {
        session = MediaSession(context!!, "nup")
        session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS
                or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
        session.setRatingType(Rating.RATING_5_STARS)
        session.setCallback(callback)
        session.isActive = true
        updateSong(null)
    }
}