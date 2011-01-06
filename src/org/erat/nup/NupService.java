// Copyright 2011 Daniel Erat <dan@erat.org>
// All rights reserved.

package org.erat.nup;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.AsyncTask;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.util.Log;
import android.widget.RemoteViews;
import android.widget.Toast;

import java.io.InputStream;
import java.io.IOException;
import java.lang.Runnable;
import java.lang.Thread;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;

public class NupService extends Service implements Player.SongCompleteListener {
    private static final String TAG = "NupService";

    // Identifier used for our "currently playing" notification.
    private static final int NOTIFICATION_ID = 0;

    // Listener for changes to a new song.
    interface SongChangeListener {
        void onSongChange(Song currentSong);
    }

    // Listener for the cover bitmap being successfully loaded for the current song.
    interface CoverLoadListener {
        void onCoverLoad(Song currentSong);
    }

    // Listener for changes to the current playlist.
    interface PlaylistChangeListener {
        void onPlaylistChange(ArrayList<Song> songs);
    }

    // Plays songs.
    private Player mPlayer;

    // Thread where mPlayerThread runs.
    private Thread mPlayerThread;

    // Listens on a local port and proxies HTTP requests to a remote web server.
    // Needed since MediaPlayer doesn't support HTTP authentication.
    private LocalProxy mProxy;

    // Thread where mProxy runs.
    private Thread mProxyThread;

    private FileCache mFileCache;
    private Thread mFileCacheThread;

    // Is the proxy currently configured and running?
    private boolean mProxyRunning = false;

    private NotificationManager mNotificationManager;

    // Current playlist.
    private ArrayList<Song> mSongs = new ArrayList<Song>();

    // Index of the song in mSongs that's being played.
    private int mCurrentSongIndex = -1;

    private final IBinder mBinder = new LocalBinder();

    // Points from cover filename Strings to previously-fetched Bitmaps.
    // TODO: Limit the growth of this or switch it to a real LRU cache or something.
    private HashMap coverCache = new HashMap();

    // Used to run tasks on our thread.
    private Handler mHandler = new Handler();

    private SongChangeListener mSongChangeListener;
    private CoverLoadListener mCoverLoadListener;
    private PlaylistChangeListener mPlaylistChangeListener;

    @Override
    public void onCreate() {
        Log.d(TAG, "service created");

        mNotificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        Notification notification = updateNotification("nup", getString(R.string.initial_notification), "", (Bitmap) null);
        startForeground(NOTIFICATION_ID, notification);

        initProxy();

        mPlayer = new Player();
        mPlayerThread = new Thread(mPlayer, "Player");
        mPlayerThread.start();
        mPlayer.setSongCompleteListener(this);

        mFileCache = new FileCache(this);
        mFileCacheThread = new Thread(mFileCache, "FileCache");
        mFileCacheThread.start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "received start id " + startId + ": " + intent);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "service destroyed");
        mNotificationManager.cancel(NOTIFICATION_ID);
        mPlayer.quit();
        mFileCache.quit();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    // Is the proxy available?  Callers must call this before trying to fetch a URL (or even calling
    // getProxyPort()).
    public boolean isProxyRunning() { return mProxyRunning; }

    // Get the port where the proxy is listening on localhost.
    public int getProxyPort() { return mProxy.getPort(); }

    public final ArrayList<Song> getSongs() { return mSongs; }
    public final int getCurrentSongIndex() { return mCurrentSongIndex; }

    public Player getPlayer() { return mPlayer; }

    public final Song getCurrentSong() {
        return (mCurrentSongIndex >= 0 && mCurrentSongIndex < mSongs.size()) ? mSongs.get(mCurrentSongIndex) : null;
    }

    public void setSongChangeListener(SongChangeListener listener) {
        mSongChangeListener = listener;
    }
    public void setCoverLoadListener(CoverLoadListener listener) {
        mCoverLoadListener = listener;
    }
    public void setPlaylistChangeListener(PlaylistChangeListener listener) {
        mPlaylistChangeListener = listener;
    }

    public class LocalBinder extends Binder {
        NupService getService() {
            return NupService.this;
        }
    }

    // Attempt to initialize and start the proxy based on the user's settings.
    public void initProxy() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        String urlString = prefs.getString("server_url", "");
        if (urlString.isEmpty()) {
            Toast.makeText(this, "You must enter a server URL in Preferences.", Toast.LENGTH_LONG).show();
            return;
        }

        URI uri;
        try {
            uri = new URI(urlString);
        } catch (java.net.URISyntaxException e) {
            Toast.makeText(this, "Unable to parse server URL: " + e.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }
        boolean useSsl = false;
        int port = uri.getPort();

        String scheme = uri.getScheme();

        if (scheme == null || scheme.equals("http")) {
            if (port < 0)
                port = 80;
        } else if (scheme.equals("https")) {
            useSsl = true;
            if (port < 0)
                port = 443;
        } else {
            Toast.makeText(this, "Unknown server URL scheme \"" + scheme + "\" (should be \"http\" or \"https\").", Toast.LENGTH_LONG).show();
            return;
        }

        try {
            mProxy = new LocalProxy(uri.getHost(), port, useSsl, prefs.getString("username", ""), prefs.getString("password", ""));
            mProxyThread = new Thread(mProxy, "LocalProxy");
            mProxyThread.setDaemon(true);
            mProxyThread.start();
            mProxyRunning = true;
        } catch (IOException e) {
            Log.wtf(TAG, "creating proxy failed: " + e);
        }
    }

    // Create a new persistent notification displaying information about the current song.
    private Notification updateNotification(String artist, String title, String album, Bitmap bitmap) {
        Notification notification = new Notification(R.drawable.icon, artist + " - " + title, System.currentTimeMillis());
        notification.flags |= (Notification.FLAG_ONGOING_EVENT | Notification.FLAG_NO_CLEAR);

        // TODO: Any way to update an existing remote view?  I couldn't find one. :-(
        RemoteViews view = new RemoteViews(getPackageName(), R.layout.notification);
        view.setTextViewText(R.id.notification_artist, artist);
        view.setTextViewText(R.id.notification_title, title);
        view.setTextViewText(R.id.notification_album, album);
        if (bitmap != null)
            view.setImageViewBitmap(R.id.notification_image, bitmap);
        notification.contentView = view;

        notification.contentIntent = PendingIntent.getActivity(this, 0, new Intent(this, NupActivity.class), 0);
        mNotificationManager.notify(NOTIFICATION_ID, notification);
        return notification;
    }

    // Toggle whether we're playing the current song or not.
    public void togglePause() {
        mPlayer.togglePause();
    }

    // Replace the current playlist with a new one.
    // Plays the first song in the new list.
    public void setPlaylist(ArrayList<Song> songs) {
        mSongs = songs;
        mCurrentSongIndex = -1;
        if (mPlaylistChangeListener != null)
            mPlaylistChangeListener.onPlaylistChange(mSongs);
        if (songs.size() > 0)
            playSongAtIndex(0, 0);
    }

    // Play the song at a particular position in the playlist.
    public void playSongAtIndex(int index, int delayMs) {
        if (index < 0 || index >= mSongs.size())
            return;

        mCurrentSongIndex = index;
        Song song = mSongs.get(index);
        String url = "http://localhost:" + mProxy.getPort() + "/music/" + song.getFilename();
        mPlayer.playSong(url, delayMs);
        mFileCache.downloadFile(url, song.getFilename());

        if (coverCache.containsKey(song.getCoverFilename())) {
            song.setCoverBitmap((Bitmap) coverCache.get(song.getCoverFilename()));
            updateNotification(song.getArtist(), song.getTitle(), song.getAlbum(), song.getCoverBitmap());
        } else {
            new CoverFetcherTask(this).execute(song);
        }

        if (mSongChangeListener != null)
            mSongChangeListener.onSongChange(song);
    }

    // Fetches the cover bitmap for a particular song.
    // onCoverFetchDone() is called on completion, even if the fetch failed.
    class CoverFetcherTask extends AsyncTask<Song, Void, Song> {
        private final NupService mService;
        CoverFetcherTask(NupService service) {
            mService = service;
        }

        @Override
        protected Song doInBackground(Song... songs) {
            Song song = songs[0];
            song.setCoverBitmap(null);
            try {
                URL coverUrl = new URL("http://localhost:" + mProxy.getPort() + "/cover/" + song.getCoverFilename());
                InputStream stream = (InputStream) coverUrl.getContent();
                Bitmap bitmap = BitmapFactory.decodeStream(stream);
                song.setCoverBitmap(bitmap);
            } catch (IOException e) {
                Log.e(TAG, "unable to load album cover " + song.getCoverFilename() + ": " + e);
            }
            return song;
        }

        @Override
        protected void onPostExecute(Song song) {
            mService.onCoverFetchDone(song);
        }
    }

    // Called by CoverFetcherTask on the UI thread.
    public void onCoverFetchDone(Song song) {
        if (song.getCoverBitmap() != null)
            coverCache.put(song.getCoverFilename(), song.getCoverBitmap());

        Song currentSong = getCurrentSong();
        if (song == getCurrentSong()) {
            // We need to update our notification even if the fetch failed.
            updateNotification(song.getArtist(), song.getTitle(), song.getAlbum(), song.getCoverBitmap());
            if (song.getCoverBitmap() != null && mCoverLoadListener != null)
                mCoverLoadListener.onCoverLoad(song);
        }
    }

    // Implements Player.SongCompleteListener.
    @Override
    public void onSongComplete() {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                playSongAtIndex(mCurrentSongIndex + 1, 0);
            }
        });
    }
}
