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

    private final Context mContext;

    private final MediaSession.Token mMediaSessionToken;

    private final PendingIntent mLaunchActivityIntent;
    private final PendingIntent mTogglePauseIntent;
    private final PendingIntent mPrevTrackIntent;
    private final PendingIntent mNextTrackIntent;

    private long mSongId;
    private boolean mPaused;
    private boolean mShowingCoverBitmap;
    private boolean mShowingPlayPause;
    private boolean mShowingPrev;
    private boolean mShowingNext;

    public NotificationCreator(
            Context context,
            NotificationManager manager,
            MediaSession.Token mediaSessionToken,
            PendingIntent launchActivityIntent,
            PendingIntent togglePauseIntent,
            PendingIntent prevTrackIntent,
            PendingIntent nextTrackIntent) {
        mContext = context;
        mMediaSessionToken = mediaSessionToken;
        mLaunchActivityIntent = launchActivityIntent;
        mTogglePauseIntent = togglePauseIntent;
        mPrevTrackIntent = prevTrackIntent;
        mNextTrackIntent = nextTrackIntent;

        if (android.os.Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel =
                    new NotificationChannel(
                            CHANNEL_ID,
                            mContext.getString(R.string.channel_name),
                            NotificationManager.IMPORTANCE_DEFAULT);
            channel.setDescription(mContext.getString(R.string.channel_description));
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
                && song.id == mSongId
                && paused == mPaused
                && (song.getCoverBitmap() != null) == mShowingCoverBitmap
                && showPlayPause == mShowingPlayPause
                && showPrev == mShowingPrev
                && showNext == mShowingNext) {
            return null;
        }

        mSongId = song != null ? song.id : 0;
        mPaused = paused;
        mShowingCoverBitmap = song != null ? song.getCoverBitmap() != null : false;
        mShowingPlayPause = showPlayPause;
        mShowingPrev = showPrev;
        mShowingNext = showNext;

        Notification.Builder builder =
                new Notification.Builder(mContext)
                        .setContentTitle(
                                song != null
                                        ? song.artist
                                        : mContext.getString(R.string.startup_message_title))
                        .setContentText(
                                song != null
                                        ? song.title
                                        : mContext.getString(R.string.startup_message_text))
                        .setSmallIcon(R.drawable.status)
                        .setColor(mContext.getResources().getColor(R.color.primary))
                        .setVisibility(Notification.VISIBILITY_PUBLIC)
                        .setContentIntent(mLaunchActivityIntent)
                        .setOngoing(true)
                        .setWhen(System.currentTimeMillis())
                        .setShowWhen(false);

        if (android.os.Build.VERSION.SDK_INT >= 26) {
            builder.setChannelId(CHANNEL_ID);
        }

        if (song != null) {
            builder.setLargeIcon(song.getCoverBitmap());

            Notification.MediaStyle style = new Notification.MediaStyle();
            style.setMediaSession(mMediaSessionToken);
            builder.setStyle(style);

            int numActions = (showPlayPause ? 1 : 0) + (showPrev ? 1 : 0) + (showNext ? 1 : 0);
            boolean showLabels = numActions < 3;

            if (showPlayPause) {
                builder.addAction(
                        paused
                                ? R.drawable.ic_play_arrow_black_36dp
                                : R.drawable.ic_pause_black_36dp,
                        showLabels
                                ? mContext.getString(mPaused ? R.string.play : R.string.pause)
                                : "",
                        mTogglePauseIntent);
            }
            if (showPrev) {
                builder.addAction(
                        R.drawable.ic_skip_previous_black_36dp,
                        showLabels ? mContext.getString(R.string.prev) : "",
                        mPrevTrackIntent);
            }
            if (showNext) {
                builder.addAction(
                        R.drawable.ic_skip_next_black_36dp,
                        showLabels ? mContext.getString(R.string.next) : "",
                        mNextTrackIntent);
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
