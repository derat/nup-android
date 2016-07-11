// Copyright 2016 Daniel Erat <dan@erat.org>
// All rights reserved.

package org.erat.nup;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.media.session.MediaSession;

class NotificationManager {
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

    public NotificationManager(Context context, MediaSession.Token mediaSessionToken,
                               PendingIntent launchActivityIntent, PendingIntent togglePauseIntent,
                               PendingIntent prevTrackIntent, PendingIntent nextTrackIntent) {
        mContext = context;
        mMediaSessionToken = mediaSessionToken;
        mLaunchActivityIntent = launchActivityIntent;
        mTogglePauseIntent = togglePauseIntent;
        mPrevTrackIntent = prevTrackIntent;
        mNextTrackIntent = nextTrackIntent;
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
    public Notification createNotificationIfChanged(Song song, boolean paused,
                                                    boolean playbackComplete,
                                                    int songIndex, int numSongs) {
        final boolean showPlayPause = song != null && !playbackComplete;
        final boolean showPrev = songIndex > 0;
        final boolean showNext = numSongs > 0 && songIndex < numSongs - 1;

        if (song != null &&
            song.getSongId() == mSongId &&
            paused == mPaused &&
            (song.getCoverBitmap() != null) == mShowingCoverBitmap &&
            showPlayPause == mShowingPlayPause &&
            showPrev == mShowingPrev &&
            showNext == mShowingNext) {
            return null;
        }

        mSongId = song != null ? song.getSongId() : 0;
        mPaused = paused;
        mShowingCoverBitmap = song != null ? song.getCoverBitmap() != null : false;
        mShowingPlayPause = showPlayPause;
        mShowingPrev = showPrev;
        mShowingNext = showNext;

        Notification.Builder builder = new Notification.Builder(mContext)
            .setContentTitle(song != null ? song.getArtist() : mContext.getString(R.string.startup_message_title))
            .setContentText(song != null ? song.getTitle() : mContext.getString(R.string.startup_message_text))
            .setSmallIcon(R.drawable.status)
            .setColor(mContext.getResources().getColor(R.color.primary))
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(mLaunchActivityIntent)
            .setOngoing(true)
            .setWhen(System.currentTimeMillis())
            .setShowWhen(false);

        Notification.MediaStyle style = new Notification.MediaStyle();
        style.setMediaSession(mMediaSessionToken);

        if (song != null) {
            builder.setLargeIcon(song.getCoverBitmap());

            int numActions = (showPlayPause ? 1 : 0) + (showPrev ? 1 : 0) + (showNext ? 1 : 0);
            boolean showLabels = numActions < 3;

            if (showPlayPause) {
                builder.addAction(paused ? R.drawable.ic_play_arrow_black_36dp : R.drawable.ic_pause_black_36dp,
                                  showLabels ? mContext.getString(mPaused ? R.string.play : R.string.pause) : "",
                                  mTogglePauseIntent);
            }
            if (showPrev) {
                builder.addAction(R.drawable.ic_skip_previous_black_36dp,
                                  showLabels ? mContext.getString(R.string.prev) : "",
                                  mPrevTrackIntent);
            }
            if (showNext) {
                builder.addAction(R.drawable.ic_skip_next_black_36dp,
                                  showLabels ? mContext.getString(R.string.next) : "",
                                  mNextTrackIntent);
            }

            if (numActions > 0) {
                // This is silly.
                style.setShowActionsInCompactView(numActions == 3 ? new int[]{0, 1, 2} : (numActions == 2 ? new int[]{0, 1} : new int[]{0}));
            }
        }

        builder.setStyle(style);
        return builder.build();
    }
}
