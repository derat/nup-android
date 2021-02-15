// Copyright 2016 Daniel Erat <dan@erat.org>
// All rights reserved.
package org.erat.nup

import android.app.Notification
import android.app.Notification.MediaStyle
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.media.session.MediaSession
import android.os.Build

class NotificationCreator(
    private val context: Context,
    manager: NotificationManager,
    private val mediaSessionToken: MediaSession.Token,
    private val launchActivityIntent: PendingIntent,
    private val togglePauseIntent: PendingIntent,
    private val prevTrackIntent: PendingIntent,
    private val nextTrackIntent: PendingIntent
) {
    private var songId: Long = 0
    private var paused = false
    private var showingCoverBitmap = false
    private var showingPlayPause = false
    private var showingPrev = false
    private var showingNext = false

    /**
     * Creates a new notification.
     *
     * @param song currently-playing song
     * @param paused current paused state
     * @param playbackComplete `true` if the song has been played to completion
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
        val showPlayPause = song != null && !playbackComplete
        val showPrev = songIndex > 0
        val showNext = numSongs > 0 && songIndex < numSongs - 1
        if (onlyIfChanged &&
            song != null && song.id == songId && paused == this.paused &&
            (song.coverBitmap != null) == showingCoverBitmap &&
            showPlayPause == showingPlayPause && showPrev == showingPrev && showNext == showingNext
        ) {
            return null
        }
        songId = song?.id ?: 0
        this.paused = paused
        showingCoverBitmap = if (song != null) song.coverBitmap != null else false
        showingPlayPause = showPlayPause
        showingPrev = showPrev
        showingNext = showNext
        val builder = Notification.Builder(context)
            .setContentTitle(song?.artist ?: context.getString(R.string.startup_message_title))
            .setContentText(song?.title ?: context.getString(R.string.startup_message_text))
            .setSmallIcon(R.drawable.status)
            .setColor(context.resources.getColor(R.color.primary))
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(launchActivityIntent)
            .setOngoing(true)
            .setWhen(System.currentTimeMillis())
            .setShowWhen(false)
        if (Build.VERSION.SDK_INT >= 26) builder.setChannelId(CHANNEL_ID)
        if (song != null) {
            builder.setLargeIcon(song.coverBitmap)
            val style = MediaStyle()
            style.setMediaSession(mediaSessionToken)
            builder.style = style
            val numActions = (if (showPlayPause) 1 else 0) + (if (showPrev) 1 else 0) +
                if (showNext) 1 else 0
            val showLabels = numActions < 3
            if (showPlayPause) {
                val icon = if (paused) {
                    R.drawable.ic_play_arrow_black_36dp
                } else {
                    R.drawable.ic_pause_black_36dp
                }
                val label = if (paused) R.string.play else R.string.pause
                builder.addAction(
                    icon,
                    if (showLabels) context.getString(label) else "",
                    togglePauseIntent
                )
            }
            if (showPrev) {
                builder.addAction(
                    R.drawable.ic_skip_previous_black_36dp,
                    if (showLabels) context.getString(R.string.prev) else "",
                    prevTrackIntent
                )
            }
            if (showNext) {
                builder.addAction(
                    R.drawable.ic_skip_next_black_36dp,
                    if (showLabels) context.getString(R.string.next) else "",
                    nextTrackIntent
                )
            }
            if (numActions > 0) {
                // This is silly.
                style.setShowActionsInCompactView(
                    *when {
                        numActions == 3 -> intArrayOf(0, 1, 2)
                        numActions == 2 -> intArrayOf(0, 1)
                        else -> intArrayOf(0)
                    }

                )
            }
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
                NotificationManager.IMPORTANCE_DEFAULT
            )
            channel.description = context.getString(R.string.channel_description)
            manager.createNotificationChannel(channel)
        }
    }
}
