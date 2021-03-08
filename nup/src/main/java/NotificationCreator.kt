/*
 * Copyright 2016 Daniel Erat <dan@erat.org>
 * All rights reserved.
 */

package org.erat.nup

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.media.app.NotificationCompat.MediaStyle
import androidx.media.session.MediaButtonReceiver

/** Creates system notifications. */
class NotificationCreator(
    private val context: Context,
    private val manager: NotificationManager,
    private val mediaSession: MediaSessionCompat,
) {
    private var songId: Long = 0
    private var paused = false
    private var showingCoverBitmap = false
    private var showingPlayPause = false
    private var showingPrev = false
    private var showingNext = false

    /**
     * Create a new notification.
     *
     * @param song currently-playing song
     * @param paused current paused state
     * @param playbackComplete true if the song has been played to completion
     * @param songIndex index of currently-playing song
     * @param numSongs total number of songs in playlist
     * @return new notification, or null if notification is unchanged
     */
    fun createNotification(
        onlyIfChanged: Boolean,
        song: Song?,
        paused: Boolean,
        playbackComplete: Boolean,
        songIndex: Int,
        numSongs: Int
    ): Notification? {
        // TODO: Update this to get all of its information from MediaControllerCompat someday.
        val showPlayPause = song != null && !playbackComplete
        val showPrev = songIndex in 1 until numSongs
        val showNext = songIndex in 0 until (numSongs - 1)
        if (onlyIfChanged &&
            song != null &&
            song.id == songId &&
            paused == this.paused &&
            (song.coverBitmap != null) == showingCoverBitmap &&
            showPlayPause == showingPlayPause &&
            showPrev == showingPrev &&
            showNext == showingNext
        ) {
            return null
        }

        songId = song?.id ?: 0
        this.paused = paused
        showingCoverBitmap = song != null && song.coverBitmap != null
        showingPlayPause = showPlayPause
        showingPrev = showPrev
        showingNext = showNext

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(song?.artist ?: context.getString(R.string.startup_message_title))
            .setContentText(song?.title ?: context.getString(R.string.startup_message_text))
            .setSmallIcon(R.drawable.status)
            .setColor(ContextCompat.getColor(context, R.color.primary))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(mediaSession.controller.sessionActivity)
            .setOngoing(true)
            .setWhen(System.currentTimeMillis())
            .setShowWhen(false)

        if (song != null) builder.setLargeIcon(song.coverBitmap)

        val style = MediaStyle()
        style.setMediaSession(mediaSession.sessionToken)
        builder.setStyle(style)

        if (showPrev) {
            builder.addAction(
                NotificationCompat.Action.Builder(
                    R.drawable.skip_previous,
                    context.getString(R.string.prev),
                    MediaButtonReceiver.buildMediaButtonPendingIntent(
                        context, PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                    ),
                ).build()
            )
        }
        if (showPlayPause) {
            builder.addAction(
                NotificationCompat.Action.Builder(
                    if (paused) R.drawable.play else R.drawable.pause,
                    context.getString(if (paused) R.string.play else R.string.pause),
                    MediaButtonReceiver.buildMediaButtonPendingIntent(
                        context, PlaybackStateCompat.ACTION_PLAY_PAUSE
                    ),
                ).build()
            )
        }
        if (showNext) {
            builder.addAction(
                NotificationCompat.Action.Builder(
                    R.drawable.skip_next,
                    context.getString(R.string.next),
                    MediaButtonReceiver.buildMediaButtonPendingIntent(
                        context, PlaybackStateCompat.ACTION_SKIP_TO_NEXT,
                    ),
                ).build()
            )
        }

        // This is silly.
        val numActions = (if (showPlayPause) 1 else 0) +
            (if (showPrev) 1 else 0) +
            (if (showNext) 1 else 0)
        if (numActions > 0) {
            style.setShowActionsInCompactView(
                *when {
                    numActions == 3 -> intArrayOf(0, 1, 2)
                    numActions == 2 -> intArrayOf(0, 1)
                    else -> intArrayOf(0)
                }
            )
        }

        return builder.build()
    }

    companion object {
        private const val CHANNEL_ID = "NupService"
    }

    init {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.channel_name),
                NotificationManagerCompat.IMPORTANCE_DEFAULT
            )
            channel.description = context.getString(R.string.channel_description)
            manager.createNotificationChannel(channel)
        }
    }
}
