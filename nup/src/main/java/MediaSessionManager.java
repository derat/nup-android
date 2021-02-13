// Copyright 2016 Daniel Erat <dan@erat.org>
// All rights reserved.

package org.erat.nup;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaDescription;
import android.media.MediaMetadata;
import android.media.Rating;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;

import java.util.ArrayList;
import java.util.List;

public class MediaSessionManager {
    private static final String TAG = "MediaSessionManager";

    private MediaSession session;

    MediaSessionManager(Context context, MediaSession.Callback callback) {
        session = new MediaSession(context, "nup");
        session.setFlags(
                MediaSession.FLAG_HANDLES_MEDIA_BUTTONS
                        | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        session.setRatingType(Rating.RATING_5_STARS);
        session.setCallback(callback);
        session.setActive(true);
        updateSong(null);
    }

    void cleanUp() {
        session.release();
    }

    public MediaSession.Token getToken() {
        return session.getSessionToken();
    }

    public void updateSong(Song song) {
        MediaMetadata.Builder builder = new MediaMetadata.Builder();
        if (song != null) {
            setString(builder, MediaMetadata.METADATA_KEY_ARTIST, song.artist);
            setString(builder, MediaMetadata.METADATA_KEY_TITLE, song.title);
            setString(builder, MediaMetadata.METADATA_KEY_ALBUM, song.album);

            if (song.rating >= 0.0) {
                builder.putRating(
                        MediaMetadata.METADATA_KEY_RATING,
                        Rating.newStarRating(
                                Rating.RATING_5_STARS, (float) (1.0 + song.rating * 4.0)));
            }
            if (song.track > 0) {
                builder.putLong(MediaMetadata.METADATA_KEY_TRACK_NUMBER, (long) song.track);
            }
            if (song.disc > 0) {
                builder.putLong(MediaMetadata.METADATA_KEY_DISC_NUMBER, (long) song.disc);
            }
            builder.putLong(MediaMetadata.METADATA_KEY_DURATION, song.lengthSec * 1000L);

            Bitmap bitmap = song.getCoverBitmap();
            if (bitmap != null) {
                // Pass a copy of the original bitmap. Apparently the later apply() call recycles
                // the bitmap, which then causes a crash when we try to use it later:
                // https://code.google.com/p/android/issues/detail?id=74967
                builder.putBitmap(
                        MediaMetadata.METADATA_KEY_ALBUM_ART,
                        bitmap.copy(bitmap.getConfig(), true));
            }
        }

        session.setMetadata(builder.build());
    }

    public void updatePlaybackState(
            Song song,
            boolean paused,
            boolean playbackComplete,
            boolean buffering,
            long positionMs,
            int songIndex,
            int numSongs) {
        PlaybackState.Builder builder = new PlaybackState.Builder();

        if (song != null) builder.setActiveQueueItemId(song.id);

        int state = PlaybackState.STATE_NONE;
        if (numSongs > 0) {
            if (buffering) {
                state = PlaybackState.STATE_BUFFERING;
            } else if (playbackComplete) {
                state =
                        (songIndex == numSongs - 1)
                                ? PlaybackState.STATE_STOPPED
                                : PlaybackState.STATE_SKIPPING_TO_NEXT;
            } else {
                state = paused ? PlaybackState.STATE_PAUSED : PlaybackState.STATE_PLAYING;
            }
        }
        builder.setState(state, positionMs, 1.0f);

        long actions = 0;
        if (!playbackComplete) {
            actions |= PlaybackState.ACTION_PLAY_PAUSE;
            actions |= (paused ? PlaybackState.ACTION_PLAY : PlaybackState.ACTION_PAUSE);
        }
        if (songIndex > 0) actions |= PlaybackState.ACTION_SKIP_TO_PREVIOUS;
        if (songIndex < numSongs - 1) actions |= PlaybackState.ACTION_SKIP_TO_NEXT;
        builder.setActions(actions);

        session.setPlaybackState(builder.build());
    }

    public void updatePlaylist(List<Song> songs) {
        List<MediaSession.QueueItem> queue = new ArrayList<MediaSession.QueueItem>();
        for (Song song : songs) {
            MediaDescription desc =
                    new MediaDescription.Builder()
                            .setMediaId(Long.toString(song.id))
                            .setTitle(song.title)
                            .setSubtitle(song.artist)
                            // TODO: Set icon too, maybe.
                            .build();
            queue.add(new MediaSession.QueueItem(desc, song.id));
        }
        session.setQueue(queue);
    }

    private void setString(MediaMetadata.Builder builder, String key, String value) {
        if (value != null && value.length() > 0) {
            builder.putString(key, value);
        }
    }
}
