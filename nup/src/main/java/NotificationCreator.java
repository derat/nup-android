// Copyright 2016 Daniel Erat <dan@erat.org>
// All rights reserved.

package org.erat.nup;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.media.session.MediaSession;

class NotificationCreator {
    private static final String CHANNEL_ID = "NupService";

    private final Context context;

    private final MediaSession.Token mediaSessionToken;

    private final PendingIntent launchActivityIntent;
    private final PendingIntent togglePauseIntent;
    private final PendingIntent prevTrackIntent;
    private final PendingIntent nextTrackIntent;

    private long songId;
    private boolean paused;
    private boolean showingCoverBitmap;
    private boolean showingPlayPause;
    private boolean showingPrev;
    private boolean showingNext;

    public NotificationCreator(
            Context context,
            NotificationManager manager,
            MediaSession.Token mediaSessionToken,
            PendingIntent launchActivityIntent,
            PendingIntent togglePauseIntent,
            PendingIntent prevTrackIntent,
            PendingIntent nextTrackIntent) {
        this.context = context;
        this.mediaSessionToken = mediaSessionToken;
        this.launchActivityIntent = launchActivityIntent;
        this.togglePauseIntent = togglePauseIntent;
        this.prevTrackIntent = prevTrackIntent;
        this.nextTrackIntent = nextTrackIntent;

        if (android.os.Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel =
                    new NotificationChannel(
                            CHANNEL_ID,
                            this.context.getString(R.string.channel_name),
                            NotificationManager.IMPORTANCE_DEFAULT);
            channel.setDescription(context.getString(R.string.channel_description));
            manager.createNotificationChannel(channel);
        }
    }

    /**
     * Creates a new notification.
     *
     * @param song currently-playing song
     * @param paused current paused state
     * @param playbackComplete <code>true</code> if the song has been played to completion
     * @param songIndex index of currently-playing song
     * @param numSongs total number of songs in playlist
     * @return new notification, or null if notification is unchanged
     */
    public Notification createNotification(
            boolean onlyIfChanged,
            Song song,
            boolean paused,
            boolean playbackComplete,
            int songIndex,
            int numSongs) {
        final boolean showPlayPause = song != null && !playbackComplete;
        final boolean showPrev = songIndex > 0;
        final boolean showNext = numSongs > 0 && songIndex < numSongs - 1;

        if (onlyIfChanged
                && song != null
                && song.id == this.songId
                && paused == this.paused
                && (song.getCoverBitmap() != null) == this.showingCoverBitmap
                && showPlayPause == this.showingPlayPause
                && showPrev == this.showingPrev
                && showNext == this.showingNext) {
            return null;
        }

        this.songId = song != null ? song.id : 0;
        this.paused = paused;
        this.showingCoverBitmap = song != null ? song.getCoverBitmap() != null : false;
        this.showingPlayPause = showPlayPause;
        this.showingPrev = showPrev;
        this.showingNext = showNext;

        Notification.Builder builder =
                new Notification.Builder(context)
                        .setContentTitle(
                                song != null
                                        ? song.artist
                                        : context.getString(R.string.startup_message_title))
                        .setContentText(
                                song != null
                                        ? song.title
                                        : context.getString(R.string.startup_message_text))
                        .setSmallIcon(R.drawable.status)
                        .setColor(context.getResources().getColor(R.color.primary))
                        .setVisibility(Notification.VISIBILITY_PUBLIC)
                        .setContentIntent(launchActivityIntent)
                        .setOngoing(true)
                        .setWhen(System.currentTimeMillis())
                        .setShowWhen(false);

        if (android.os.Build.VERSION.SDK_INT >= 26) {
            builder.setChannelId(CHANNEL_ID);
        }

        if (song != null) {
            builder.setLargeIcon(song.getCoverBitmap());

            Notification.MediaStyle style = new Notification.MediaStyle();
            style.setMediaSession(mediaSessionToken);
            builder.setStyle(style);

            int numActions = (showPlayPause ? 1 : 0) + (showPrev ? 1 : 0) + (showNext ? 1 : 0);
            boolean showLabels = numActions < 3;

            if (showPlayPause) {
                builder.addAction(
                        paused
                                ? R.drawable.ic_play_arrow_black_36dp
                                : R.drawable.ic_pause_black_36dp,
                        showLabels
                                ? context.getString(paused ? R.string.play : R.string.pause)
                                : "",
                        togglePauseIntent);
            }
            if (showPrev) {
                builder.addAction(
                        R.drawable.ic_skip_previous_black_36dp,
                        showLabels ? context.getString(R.string.prev) : "",
                        prevTrackIntent);
            }
            if (showNext) {
                builder.addAction(
                        R.drawable.ic_skip_next_black_36dp,
                        showLabels ? context.getString(R.string.next) : "",
                        nextTrackIntent);
            }

            if (numActions > 0) {
                // This is silly.
                style.setShowActionsInCompactView(
                        numActions == 3
                                ? new int[] {0, 1, 2}
                                : (numActions == 2 ? new int[] {0, 1} : new int[] {0}));
            }
        }

        return builder.build();
    }
}
