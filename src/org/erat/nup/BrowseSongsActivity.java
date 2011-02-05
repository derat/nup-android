// Copyright 2011 Daniel Erat <dan@erat.org>
// All rights reserved.

package org.erat.nup;

import android.app.Activity;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Bundle;
import android.view.ContextMenu;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.SimpleAdapter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;

public class BrowseSongsActivity extends Activity {
    private static final String TAG = "BrowseSongsActivity";

    // IDs for items in our context menus.
    private static final int MENU_ITEM_PLAY = 1;
    private static final int MENU_ITEM_INSERT = 2;
    private static final int MENU_ITEM_APPEND = 3;

    // Are we displaying only cached songs?
    private boolean mOnlyCached = false;

    // Artist that was passed to us.
    private String mArtist = null;

    // Album that was passed to us.
    private String mAlbum = null;

    // Minimum rating that was passed to us.
    private String mMinRating = null;

    // Songs that we're displaying.
    private List<Song> mSongs = new ArrayList<Song>();

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.browse_songs);

        mArtist = getIntent().getStringExtra(BrowseActivity.BUNDLE_ARTIST);
        mAlbum = getIntent().getStringExtra(BrowseActivity.BUNDLE_ALBUM);
        mOnlyCached = getIntent().getBooleanExtra(BrowseActivity.BUNDLE_CACHED, false);
        mMinRating = getIntent().getStringExtra(BrowseActivity.BUNDLE_MIN_RATING);

        if (mAlbum != null) {
            setTitle(getString(mOnlyCached ?
                                   R.string.browse_cached_songs_from_album_fmt :
                                   R.string.browse_songs_from_album_fmt,
                               mAlbum));
        } else if (mArtist != null) {
            setTitle(getString(mOnlyCached ?
                                   R.string.browse_cached_songs_by_artist_fmt :
                                   R.string.browse_songs_by_artist_fmt,
                               mArtist));
        } else {
            setTitle(getString(mOnlyCached ?
                               R.string.browse_cached_songs :
                               R.string.browse_songs));
        }

        // Do the query for the songs in the background.
        new AsyncTask<Void, Void, List<Song>>() {
            @Override
            protected void onPreExecute() {
                // Create a temporary ArrayAdapter that just says "Loading...".
                List<String> items = new ArrayList<String>();
                items.add(getString(R.string.loading));
                ArrayAdapter<String> adapter =
                    new ArrayAdapter<String>(BrowseSongsActivity.this, R.layout.browse_row, items);
                ListView view = (ListView) findViewById(R.id.songs);
                view.setAdapter(adapter);
            }
            @Override
            protected List<Song> doInBackground(Void... args) {
                return NupActivity.getService().getSongDb().query(
                    mArtist, null, mAlbum, mMinRating, false, false, mOnlyCached);
            }
            @Override
            protected void onPostExecute(List<Song> songs) {
                // The results come back in album order.  If we're viewing all songs by
                // an artist, sort them alphabetically instead.
                if (mAlbum == null || mAlbum.isEmpty()) {
                    Collections.sort(songs, new Comparator<Song>() {
                        @Override
                        public int compare(Song a, Song b) {
                            return Util.getSortingKey(a.getTitle()).compareTo(
                                Util.getSortingKey(b.getTitle()));
                        }
                    });
                }
                mSongs = songs;

                final String titleKey = "title";
                List<HashMap<String, String>> data = new ArrayList<HashMap<String, String>>();
                for (Song song : mSongs) {
                    HashMap<String, String> map = new HashMap<String, String>();
                    map.put(titleKey, song.getTitle());
                    data.add(map);
                }

                SimpleAdapter adapter = new SimpleAdapter(
                    BrowseSongsActivity.this,
                    data,
                    R.layout.browse_row,
                    new String[]{ titleKey },
                    new int[]{ R.id.text });
                ListView view = (ListView) findViewById(R.id.songs);
                view.setAdapter(adapter);
                registerForContextMenu(view);
            }
        }.execute();
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View view, ContextMenu.ContextMenuInfo menuInfo) {
        if (view.getId() == R.id.songs) {
            AdapterView.AdapterContextMenuInfo info = (AdapterView.AdapterContextMenuInfo) menuInfo;
            Song song = mSongs.get(info.position);
            if (song == null)
                return;
            menu.setHeaderTitle(song.getTitle());
            menu.add(0, MENU_ITEM_PLAY, 0, R.string.play);
            menu.add(0, MENU_ITEM_INSERT, 0, R.string.insert);
            menu.add(0, MENU_ITEM_APPEND, 0, R.string.append);
        }
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        AdapterView.AdapterContextMenuInfo info = (AdapterView.AdapterContextMenuInfo) item.getMenuInfo();
        Song song = mSongs.get(info.position);
        if (song == null)
            return false;
        switch (item.getItemId()) {
            case MENU_ITEM_PLAY:
                NupActivity.getService().addSongToPlaylist(song, true);
                return true;
            case MENU_ITEM_INSERT:
                NupActivity.getService().addSongToPlaylist(song, false);
                return true;
            case MENU_ITEM_APPEND:
                NupActivity.getService().appendSongToPlaylist(song);
                return true;
            default:
                return false;
        }
    }

    public void onAppendButtonClicked(View view) {
        NupActivity.getService().appendSongsToPlaylist(mSongs);
        setResult(RESULT_OK);
        finish();
    }

    public void onInsertButtonClicked(View view) {
        NupActivity.getService().addSongsToPlaylist(mSongs, false);
        setResult(RESULT_OK);
        finish();
    }

    public void onReplaceButtonClicked(View view) {
        NupActivity.getService().clearPlaylist();
        NupActivity.getService().appendSongsToPlaylist(mSongs);
        setResult(RESULT_OK);
        finish();
    }
}
