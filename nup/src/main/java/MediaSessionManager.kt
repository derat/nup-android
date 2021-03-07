/*
 * Copyright 2016 Daniel Erat <dan@erat.org>
 * All rights reserved.
 */

package org.erat.nup

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.RatingCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.MediaSessionCompat.QueueItem
import android.support.v4.media.session.PlaybackStateCompat

/** Updates [MediaSession] data for the current song. */
class MediaSessionManager constructor(context: Context, callback: MediaSessionCompat.Callback) {
    val session: MediaSessionCompat

    val token: MediaSessionCompat.Token
        get() = session.sessionToken

    /** Release the session. */
    fun release() {
        session.release()
    }

    /** Update the session for [song]. */
    fun updateSong(song: Song?, entry: FileCacheEntry?) {
        if (song == null) {
            session.setMetadata(null)
            return
        }

        val builder = MediaMetadataCompat.Builder()
        setString(builder, MediaMetadataCompat.METADATA_KEY_ARTIST, song.artist)
        setString(builder, MediaMetadataCompat.METADATA_KEY_TITLE, song.title)
        setString(builder, MediaMetadataCompat.METADATA_KEY_ALBUM, song.album)

        if (song.rating >= 0.0) {
            builder.putRating(
                MediaMetadataCompat.METADATA_KEY_RATING,
                RatingCompat.newStarRating(
                    RatingCompat.RATING_5_STARS, (1.0 + song.rating * 4.0).toFloat()
                )
            )
        }
        if (song.track > 0) {
            builder.putLong(MediaMetadataCompat.METADATA_KEY_TRACK_NUMBER, song.track.toLong())
        }
        if (song.disc > 0) {
            builder.putLong(MediaMetadataCompat.METADATA_KEY_DISC_NUMBER, song.disc.toLong())
        }
        builder.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, song.lengthSec * 1000L)

        val bitmap = song.coverBitmap
        if (bitmap != null) {
            // Pass a copy of the original bitmap. Apparently the later apply() call recycles
            // the bitmap, which then causes a crash when we try to use it later:
            // https://code.google.com/p/android/issues/detail?id=74967
            builder.putBitmap(
                MediaMetadataCompat.METADATA_KEY_ALBUM_ART,
                bitmap.copy(bitmap.config, true)
            )
        }

        // It seems completely bonkers that you can shove a field from MediaDescriptionCompat into
        // MediaMetadataCompat, but see
        // https://developer.android.com/training/cars/media#metadata-indicators.
        if (entry != null) {
            builder.putLong(
                MediaDescriptionCompat.EXTRA_DOWNLOAD_STATUS,
                when {
                    entry.isFullyCached -> MediaDescriptionCompat.STATUS_DOWNLOADED
                    entry.cachedBytes > 0 -> MediaDescriptionCompat.STATUS_DOWNLOADING
                    else -> MediaDescriptionCompat.STATUS_NOT_DOWNLOADED
                }
            )
        }

        session.setMetadata(builder.build())
    }

    /** Update the session for the supplied playback state. */
    fun updatePlaybackState(
        song: Song?,
        paused: Boolean,
        playbackComplete: Boolean,
        buffering: Boolean,
        positionMs: Long,
        songIndex: Int,
        numSongs: Int
    ) {
        val builder = PlaybackStateCompat.Builder()
        if (song != null) builder.setActiveQueueItemId(song.id)

        builder.setState(
            when {
                numSongs == 0 -> PlaybackStateCompat.STATE_NONE
                buffering -> PlaybackStateCompat.STATE_BUFFERING
                playbackComplete && songIndex == numSongs - 1 -> PlaybackStateCompat.STATE_STOPPED
                playbackComplete -> PlaybackStateCompat.STATE_SKIPPING_TO_NEXT
                paused -> PlaybackStateCompat.STATE_PAUSED
                else -> PlaybackStateCompat.STATE_PLAYING
            },
            positionMs, 1.0f
        )

        var actions: Long = PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID or
            PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH
        if (!playbackComplete) {
            actions = actions or PlaybackStateCompat.ACTION_PLAY_PAUSE
            if (paused) actions = actions or PlaybackStateCompat.ACTION_PLAY
            else actions = actions or PlaybackStateCompat.ACTION_PAUSE
        }
        if (songIndex > 0) actions = actions or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
        if (songIndex < numSongs - 1) actions = actions or PlaybackStateCompat.ACTION_SKIP_TO_NEXT
        if (numSongs > 1) actions = actions or PlaybackStateCompat.ACTION_SKIP_TO_QUEUE_ITEM
        builder.setActions(actions)

        session.setPlaybackState(builder.build())
    }

    /** Update the session for the supplied playlist. */
    fun updatePlaylist(songs: List<Song>) {
        val queue: MutableList<QueueItem> = ArrayList()
        for (song in songs) {
            val desc = MediaDescriptionCompat.Builder()
                .setMediaId("${MediaBrowserHelper.SONG_ID_PREFIX}${song.id}")
                .setTitle(song.title)
                .setSubtitle(song.artist)
                .setIconBitmap(song.coverBitmap)
                .build()
            queue.add(QueueItem(desc, song.id))
        }
        session.setQueue(queue)
    }

    /** Set [key] to [value] if the latter is a non-null, non-empty string. */
    private fun setString(builder: MediaMetadataCompat.Builder, key: String, value: String?) {
        if (!value.isNullOrEmpty()) builder.putString(key, value)
    }

    companion object {
        private const val TAG = "MediaSessionManager"
    }

    init {
        session = MediaSessionCompat(context, "nup").apply {
            setRatingType(RatingCompat.RATING_5_STARS)
            setCallback(callback)
            setFlags(MediaSessionCompat.FLAG_HANDLES_QUEUE_COMMANDS)
            setSessionActivity(
                PendingIntent.getActivity(context, 0, Intent(context, NupActivity::class.java), 0)
            )
            setActive(true)
        }
        updateSong(null, null)
    }
}
