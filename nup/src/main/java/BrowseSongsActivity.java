// Copyright 2011 Daniel Erat <dan@erat.org>
// All rights reserved.

package org.erat.nup;

import android.app.Activity;
import android.app.Dialog;
import android.os.AsyncTask;
import android.os.Bundle;
import android.view.ContextMenu;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.SimpleAdapter;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;

// This class doesn't extend BrowseActivityBase since it displays a different menu.
public class BrowseSongsActivity extends Activity implements AdapterView.OnItemClickListener {
    private static final String TAG = "BrowseSongsActivity";

    // IDs for items in our context menus.
    private static final int MENU_ITEM_PLAY = 1;
    private static final int MENU_ITEM_INSERT = 2;
    private static final int MENU_ITEM_APPEND = 3;
    private static final int MENU_ITEM_SONG_DETAILS = 4;

    private static final int DIALOG_SONG_DETAILS = 1;

    // Passed-in criteria specifying which songs to display.
    private String artist = null;
    private String album = null;
    private String albumId = null;
    private boolean onlyCached = false;
    private double minRating = -1.0;

    // Songs that we're displaying.
    private List<Song> songs = new ArrayList<Song>();

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.browse_songs);

        artist = getIntent().getStringExtra(BrowseActivityBase.BUNDLE_ARTIST);
        album = getIntent().getStringExtra(BrowseActivityBase.BUNDLE_ALBUM);
        albumId = getIntent().getStringExtra(BrowseActivityBase.BUNDLE_ALBUM_ID);
        onlyCached = getIntent().getBooleanExtra(BrowseActivityBase.BUNDLE_CACHED, false);
        minRating = getIntent().getDoubleExtra(BrowseActivityBase.BUNDLE_MIN_RATING, -1.0);

        if (album != null) {
            setTitle(
                    getString(
                            onlyCached
                                    ? R.string.browse_cached_songs_from_album_fmt
                                    : R.string.browse_songs_from_album_fmt,
                            album));
        } else if (artist != null) {
            setTitle(
                    getString(
                            onlyCached
                                    ? R.string.browse_cached_songs_by_artist_fmt
                                    : R.string.browse_songs_by_artist_fmt,
                            artist));
        } else {
            setTitle(getString(onlyCached ? R.string.browse_cached_songs : R.string.browse_songs));
        }

        // Do the query for the songs in the background.
        new AsyncTask<Void, Void, List<Song>>() {
            @Override
            protected void onPreExecute() {
                // Create a temporary ArrayAdapter that just says "Loading...".
                List<String> items = new ArrayList<String>();
                items.add(getString(R.string.loading));
                ArrayAdapter<String> adapter =
                        new ArrayAdapter<String>(
                                BrowseSongsActivity.this, R.layout.browse_row, R.id.main, items) {
                            @Override
                            public boolean areAllItemsEnabled() {
                                return false;
                            }

                            @Override
                            public boolean isEnabled(int position) {
                                return false;
                            }
                        };
                ListView view = (ListView) findViewById(R.id.songs);
                view.setAdapter(adapter);
            }

            @Override
            protected List<Song> doInBackground(Void... args) {
                return NupActivity.getService()
                        .getSongDb()
                        .query(artist, null, album, albumId, minRating, false, false, onlyCached);
            }

            @Override
            protected void onPostExecute(List<Song> newSongs) {
                // The results come back in album order. If we're viewing all songs by
                // an artist, sort them alphabetically instead.
                if ((album == null || album.isEmpty()) && (albumId == null || albumId.isEmpty())) {
                    Collections.sort(
                            newSongs,
                            new Comparator<Song>() {
                                @Override
                                public int compare(Song a, Song b) {
                                    return Util.getSortingKey(a.title, Util.SORT_TITLE)
                                            .compareTo(
                                                    Util.getSortingKey(b.title, Util.SORT_TITLE));
                                }
                            });
                }
                songs = newSongs;

                final String titleKey = "title";
                List<HashMap<String, String>> data = new ArrayList<HashMap<String, String>>();
                for (Song song : songs) {
                    HashMap<String, String> map = new HashMap<String, String>();
                    map.put(titleKey, song.title);
                    data.add(map);
                }

                SimpleAdapter adapter =
                        new SimpleAdapter(
                                BrowseSongsActivity.this,
                                data,
                                R.layout.browse_row,
                                new String[] {titleKey},
                                new int[] {R.id.main});
                ListView view = (ListView) findViewById(R.id.songs);
                view.setAdapter(adapter);
                view.setOnItemClickListener(BrowseSongsActivity.this);
                registerForContextMenu(view);
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.browse_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.browse_pause_menu_item:
                NupActivity.getService().pause();
                return true;
            case R.id.browse_return_menu_item:
                setResult(RESULT_OK);
                finish();
                return true;
            default:
                return false;
        }
    }

    @Override
    public void onCreateContextMenu(
            ContextMenu menu, View view, ContextMenu.ContextMenuInfo menuInfo) {
        if (view.getId() == R.id.songs) {
            AdapterView.AdapterContextMenuInfo info = (AdapterView.AdapterContextMenuInfo) menuInfo;
            Song song = songs.get(info.position);
            if (song == null) return;
            menu.setHeaderTitle(song.title);
            menu.add(0, MENU_ITEM_PLAY, 0, R.string.play);
            menu.add(0, MENU_ITEM_INSERT, 0, R.string.insert);
            menu.add(0, MENU_ITEM_APPEND, 0, R.string.append);
            menu.add(0, MENU_ITEM_SONG_DETAILS, 0, R.string.song_details_ellipsis);
        }
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        AdapterView.AdapterContextMenuInfo info =
                (AdapterView.AdapterContextMenuInfo) item.getMenuInfo();
        Song song = songs.get(info.position);
        if (song == null) return false;
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
            case MENU_ITEM_SONG_DETAILS:
                showDialog(DIALOG_SONG_DETAILS, SongDetailsDialog.createBundle(song));
                return true;
            default:
                return false;
        }
    }

    // Implements AdapterView.OnItemClickListener.
    @Override
    public void onItemClick(AdapterView parent, View view, int position, long id) {
        Song song = songs.get(position);
        if (song == null) return;
        NupActivity.getService().appendSongToPlaylist(song);
        Toast.makeText(this, getString(R.string.appended_song_fmt, song.title), Toast.LENGTH_SHORT)
                .show();
    }

    @Override
    protected Dialog onCreateDialog(int id, Bundle args) {
        if (id == DIALOG_SONG_DETAILS) return SongDetailsDialog.createDialog(this);
        return null;
    }

    @Override
    protected void onPrepareDialog(int id, Dialog dialog, Bundle args) {
        super.onPrepareDialog(id, dialog, args);
        if (id == DIALOG_SONG_DETAILS) SongDetailsDialog.prepareDialog(dialog, args);
    }

    public void onAppendButtonClicked(View view) {
        if (songs.isEmpty()) return;
        NupActivity.getService().appendSongsToPlaylist(songs);
        setResult(RESULT_OK);
        finish();
    }

    public void onInsertButtonClicked(View view) {
        if (songs.isEmpty()) return;
        NupActivity.getService().addSongsToPlaylist(songs, false);
        setResult(RESULT_OK);
        finish();
    }

    public void onReplaceButtonClicked(View view) {
        if (songs.isEmpty()) return;
        NupActivity.getService().clearPlaylist();
        NupActivity.getService().appendSongsToPlaylist(songs);
        setResult(RESULT_OK);
        finish();
    }
}
