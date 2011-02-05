// Copyright 2011 Daniel Erat <dan@erat.org>
// All rights reserved.

package org.erat.nup;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Bundle;
import android.util.Log;
import android.view.ContextMenu;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ListView;
import android.widget.SimpleAdapter;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class SearchResultsActivity extends Activity {
    private static final String TAG = "SearchResultsActivity";

    // Key for the objects in the bundle that's passed to us by SearchFormActivity.
    public static final String BUNDLE_ARTIST = "artist";
    public static final String BUNDLE_TITLE = "title";
    public static final String BUNDLE_ALBUM = "album";
    public static final String BUNDLE_MIN_RATING = "min_rating";
    public static final String BUNDLE_SHUFFLE = "shuffle";
    public static final String BUNDLE_SUBSTRING = "substring";
    public static final String BUNDLE_CACHED = "cached";

    public static final String SONG_KEY = "songs";

    // IDs for items in our context menus.
    private static final int MENU_ITEM_PLAY = 1;
    private static final int MENU_ITEM_INSERT = 2;
    private static final int MENU_ITEM_APPEND = 3;

    // Songs that we're displaying.
    private List<Song> mSongs = new ArrayList<Song>();

    @Override
    public void onCreate(Bundle savedInstanceState) {
        Log.d(TAG, "activity created");
        super.onCreate(savedInstanceState);
        setTitle(R.string.search_results);
        setContentView(R.layout.search_results);

        final String artist = getIntent().getStringExtra(BUNDLE_ARTIST);
        final String title = getIntent().getStringExtra(BUNDLE_TITLE);
        final String album = getIntent().getStringExtra(BUNDLE_ALBUM);
        final String minRating = getIntent().getStringExtra(BUNDLE_MIN_RATING);
        final boolean shuffle = getIntent().getBooleanExtra(BUNDLE_SHUFFLE, false);
        final boolean substring = getIntent().getBooleanExtra(BUNDLE_SUBSTRING, false);
        final boolean onlyCached = getIntent().getBooleanExtra(BUNDLE_CACHED, false);

        new AsyncTask<Void, Void, List<Song>>() {
            private ProgressDialog mDialog;

            @Override
            protected void onPreExecute() {
                mDialog = ProgressDialog.show(
                    SearchResultsActivity.this,
                    getString(R.string.searching),
                    getString(R.string.querying_database),
                    true,   // indeterminate
                    true);  // cancelable
                // FIXME: Support canceling.
            }
            @Override
            protected List<Song> doInBackground(Void... args) {
                return NupActivity.getService().getSongDb().query(
                    artist, title, album, minRating, shuffle, substring, onlyCached);
            }
            @Override
            protected void onPostExecute(List<Song> songs) {
                mSongs = songs;
                if (!mSongs.isEmpty()) {
                    final String artistKey = "artist", titleKey = "title";
                    List<HashMap<String, String>> data = new ArrayList<HashMap<String, String>>();
                    for (Song song : mSongs) {
                        HashMap<String, String> map = new HashMap<String, String>();
                        map.put(artistKey, song.getArtist());
                        map.put(titleKey, song.getTitle());
                        data.add(map);
                    }

                    SimpleAdapter adapter = new SimpleAdapter(
                        SearchResultsActivity.this,
                        data,
                        R.layout.search_results_row,
                        new String[]{ artistKey, titleKey },
                        new int[]{ R.id.artist, R.id.title });
                    ListView view = (ListView) findViewById(R.id.results);
                    view.setAdapter(adapter);
                    registerForContextMenu(view);
                }

                mDialog.dismiss();
                String message =!mSongs.isEmpty() ?
                    getResources().getQuantityString(R.plurals.search_found_songs_fmt, mSongs.size(), mSongs.size()) :
                    getString(R.string.no_results);
                Toast.makeText(SearchResultsActivity.this, message, Toast.LENGTH_SHORT).show();

                if (mSongs.isEmpty())
                    finish();
            }
        }.execute();
    }

    @Override
    protected void onDestroy() {
        Log.d(TAG, "activity destroyed");
        super.onDestroy();
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View view, ContextMenu.ContextMenuInfo menuInfo) {
        if (view.getId() == R.id.results) {
            AdapterView.AdapterContextMenuInfo info = (AdapterView.AdapterContextMenuInfo) menuInfo;
            Song song = mSongs.get(info.position);
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
