// Copyright 2011 Daniel Erat <dan@erat.org>
// All rights reserved.

package org.erat.nup;

import android.app.Activity;
import android.app.Dialog;
import android.app.ProgressDialog;
import android.app.SearchManager;
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
    private static final int MENU_ITEM_SONG_DETAILS = 4;

    private static final int DIALOG_SONG_DETAILS = 1;

    // Songs that we're displaying.
    private List<Song> mSongs = new ArrayList<Song>();

    @Override
    public void onCreate(Bundle savedInstanceState) {
        Log.d(TAG, "activity created");
        super.onCreate(savedInstanceState);
        setTitle(R.string.search_results);
        setContentView(R.layout.search_results);

        class Query {
            public String artist, title, album;
            public double minRating;
            public boolean shuffle, substring, onlyCached;

            // TODO: Include albumId somehow.
            Query(
                    String artist,
                    String title,
                    String album,
                    double minRating,
                    boolean shuffle,
                    boolean substring,
                    boolean onlyCached) {
                this.artist = artist;
                this.title = title;
                this.album = album;
                this.minRating = minRating;
                this.shuffle = shuffle;
                this.substring = substring;
                this.onlyCached = onlyCached;
            }
        }
        ;
        final List<Query> queries = new ArrayList<Query>();

        final Intent intent = getIntent();
        if (Intent.ACTION_SEARCH.equals(intent.getAction())) {
            String queryString = intent.getStringExtra(SearchManager.QUERY);
            queries.add(new Query(queryString, null, null, -1.0, false, true, false));
            queries.add(new Query(null, null, queryString, -1.0, false, true, false));
            queries.add(new Query(null, queryString, null, -1.0, false, true, false));
        } else {
            queries.add(
                    new Query(
                            intent.getStringExtra(BUNDLE_ARTIST),
                            intent.getStringExtra(BUNDLE_TITLE),
                            intent.getStringExtra(BUNDLE_ALBUM),
                            intent.getDoubleExtra(BUNDLE_MIN_RATING, -1.0),
                            intent.getBooleanExtra(BUNDLE_SHUFFLE, false),
                            intent.getBooleanExtra(BUNDLE_SUBSTRING, false),
                            intent.getBooleanExtra(BUNDLE_CACHED, false)));
        }

        new AsyncTask<Void, Void, List<Song>>() {
            private ProgressDialog mDialog;

            @Override
            protected void onPreExecute() {
                mDialog =
                        ProgressDialog.show(
                                SearchResultsActivity.this,
                                getString(R.string.searching),
                                getString(R.string.querying_database),
                                true, // indeterminate
                                true); // cancelable
                // FIXME: Support canceling.
            }

            @Override
            protected List<Song> doInBackground(Void... args) {
                List<Song> songs = new ArrayList<Song>();
                for (Query query : queries) {
                    songs.addAll(
                            NupActivity.getService()
                                    .getSongDb()
                                    .query(
                                            query.artist,
                                            query.title,
                                            query.album,
                                            null,
                                            query.minRating,
                                            query.shuffle,
                                            query.substring,
                                            query.onlyCached));
                }
                return songs;
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

                    SimpleAdapter adapter =
                            new SimpleAdapter(
                                    SearchResultsActivity.this,
                                    data,
                                    R.layout.search_results_row,
                                    new String[] {artistKey, titleKey},
                                    new int[] {R.id.artist, R.id.title});
                    ListView view = (ListView) findViewById(R.id.results);
                    view.setAdapter(adapter);
                    registerForContextMenu(view);
                }

                mDialog.dismiss();
                String message =
                        !mSongs.isEmpty()
                                ? getResources()
                                        .getQuantityString(
                                                R.plurals.search_found_songs_fmt,
                                                mSongs.size(),
                                                mSongs.size())
                                : getString(R.string.no_results);
                Toast.makeText(SearchResultsActivity.this, message, Toast.LENGTH_SHORT).show();

                if (mSongs.isEmpty()) finish();
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    @Override
    protected void onDestroy() {
        Log.d(TAG, "activity destroyed");
        super.onDestroy();
    }

    @Override
    public void onCreateContextMenu(
            ContextMenu menu, View view, ContextMenu.ContextMenuInfo menuInfo) {
        if (view.getId() == R.id.results) {
            AdapterView.AdapterContextMenuInfo info = (AdapterView.AdapterContextMenuInfo) menuInfo;
            Song song = mSongs.get(info.position);
            menu.setHeaderTitle(song.getTitle());
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
            case MENU_ITEM_SONG_DETAILS:
                showDialog(DIALOG_SONG_DETAILS, SongDetailsDialog.createBundle(song));
                return true;
            default:
                return false;
        }
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
