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
import android.support.v4.media.MediaMetadataCompat
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
    private var lastMediaId = ""
    private var lastState = PlaybackStateCompat.STATE_NONE
    private var showingCover = false
    private var showingPlayPause = false
    private var showingPrev = false
    private var showingNext = false

    /**
     * Create a new notification.
     *
     * @param onlyIfChanged return null if the notification's content hasn't changed
     * @return new notification, or null if notification is unchanged
     */
    fun createNotification(onlyIfChanged: Boolean): Notification? {
        // The Kotlin defs for many of the controller's getters seem to incorrectly say that their
        // return values are non-nullable.
        val controller = mediaSession.controller
        val songIndex = controller.playbackState?.activeQueueItemId ?: -1
        val state = controller.playbackState?.state ?: PlaybackStateCompat.STATE_NONE
        val metadata: MediaMetadataCompat? = controller.metadata

        val mediaId = metadata?.getString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID) ?: ""
        val cover = metadata?.getBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART)
        val showPlayPause = state == PlaybackStateCompat.STATE_PLAYING ||
            state == PlaybackStateCompat.STATE_PAUSED
        val showPrev = songIndex >= 1
        val showNext = songIndex in 0 until ((controller.queue?.size ?: 0) - 1)

        if (onlyIfChanged &&
            mediaId == lastMediaId &&
            state == lastState &&
            (cover != null) == showingCover &&
            showPlayPause == showingPlayPause &&
            showPrev == showingPrev &&
            showNext == showingNext
        ) {
            return null
        }

        lastMediaId = mediaId
        lastState = state
        showingCover = cover != null
        showingPlayPause = showPlayPause
        showingPrev = showPrev
        showingNext = showNext

        val artist = metadata?.getString(MediaMetadataCompat.METADATA_KEY_ARTIST)
        val title = metadata?.getString(MediaMetadataCompat.METADATA_KEY_TITLE)

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(artist ?: context.getString(R.string.startup_message_title))
            .setContentText(title ?: context.getString(R.string.startup_message_text))
            .setSmallIcon(R.drawable.status)
            .setColor(ContextCompat.getColor(context, R.color.primary))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(mediaSession.controller.sessionActivity)
            .setOngoing(true)
            .setWhen(System.currentTimeMillis())
            .setShowWhen(false)

        if (!mediaId.isEmpty()) builder.setLargeIcon(cover)

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
            val paused = state == PlaybackStateCompat.STATE_PAUSED
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
