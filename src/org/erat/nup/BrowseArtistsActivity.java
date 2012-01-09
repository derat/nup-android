// Copyright 2011 Daniel Erat <dan@erat.org>
// All rights reserved.

package org.erat.nup;

import android.app.ListActivity;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Bundle;
import android.util.Log;
import android.view.ContextMenu;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListAdapter;
import android.widget.ListView;

import java.util.ArrayList;
import java.util.List;

public class BrowseArtistsActivity extends ListActivity
                                   implements NupService.SongDatabaseUpdateListener {
    private static final String TAG = "BrowseArtistsActivity";

    // Identifiers for activities that we start.
    private static final int BROWSE_ALBUMS_REQUEST_CODE = 1;
    private static final int BROWSE_SONGS_REQUEST_CODE = 2;

    private static final int MENU_ITEM_BROWSE_SONGS_WITH_RATING = 1;
    private static final int MENU_ITEM_BROWSE_SONGS = 2;
    private static final int MENU_ITEM_BROWSE_ALBUMS = 3;

    // Are we displaying only cached songs?
    private boolean mOnlyCached = false;

    // Artists that we're displaying.
    private List<String> mArtists = new ArrayList<String>(); 

    private SortedStringArrayAdapter mAdapter;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mOnlyCached = getIntent().getBooleanExtra(BrowseActivity.BUNDLE_CACHED, false);
        setTitle(mOnlyCached ? R.string.browse_cached_artists : R.string.browse_artists);

        mAdapter = new SortedStringArrayAdapter(this, R.layout.browse_row, mArtists);
        setListAdapter(mAdapter);
        registerForContextMenu(getListView());

        NupActivity.getService().addSongDatabaseUpdateListener(this);
        onSongDatabaseUpdate();
    }

    @Override
    protected void onDestroy() {
        NupActivity.getService().removeSongDatabaseUpdateListener(this);
        super.onDestroy();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.browse_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
        case R.id.pause_menu_item:
            NupActivity.getService().togglePause();
            return true;
        case R.id.return_menu_item:
            setResult(RESULT_OK);
            finish();
            return true;
        default:
            return false;
        }
    }

    @Override
    protected void onListItemClick(ListView listView, View view, int position, long id) {
        String artist = mArtists.get(position);
        if (artist == null)
            return;
        startBrowseAlbumsActivity(artist);
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View view, ContextMenu.ContextMenuInfo menuInfo) {
        String artist = mArtists.get(((AdapterView.AdapterContextMenuInfo) menuInfo).position);
        if (artist != null)
            menu.setHeaderTitle(artist);
        menu.add(0, MENU_ITEM_BROWSE_SONGS_WITH_RATING, 0, R.string.browse_songs_with_75_rating);
        menu.add(0, MENU_ITEM_BROWSE_SONGS, 0, R.string.browse_songs);
        menu.add(0, MENU_ITEM_BROWSE_ALBUMS, 0, R.string.browse_albums);
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        AdapterView.AdapterContextMenuInfo info = (AdapterView.AdapterContextMenuInfo) item.getMenuInfo();
        String artist = mArtists.get(info.position);
        if (artist == null)
            return false;
        switch (item.getItemId()) {
            case MENU_ITEM_BROWSE_SONGS_WITH_RATING:
                startBrowseSongsActivity(artist, "0.75");
                return true;
            case MENU_ITEM_BROWSE_SONGS:
                startBrowseSongsActivity(artist, null);
                return true;
            case MENU_ITEM_BROWSE_ALBUMS:
                startBrowseAlbumsActivity(artist);
                return true;
            default:
                return false;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (resultCode == RESULT_OK) {
            setResult(RESULT_OK);
            finish();
        }
    }

    // Implements NupService.SongDatabaseUpdateListener.
    @Override
    public void onSongDatabaseUpdate() {
        if (!NupActivity.getService().getSongDb().getAggregateDataLoaded()) {
            mArtists.add(getString(R.string.loading));
            mAdapter.setEnabled(false);
            mAdapter.notifyDataSetChanged();
            return;
        }

        if (!mOnlyCached) {
            updateArtists(NupActivity.getService().getSongDb().getArtistsSortedAlphabetically());
        } else {
            new AsyncTask<Void, Void, List<String>>() {
                @Override
                protected List<String> doInBackground(Void... args) {
                    return NupActivity.getService().getSongDb().getCachedArtistsSortedAlphabetically();
                }
                @Override
                protected void onPostExecute(List<String> artists) {
                    updateArtists(artists);
                }
            }.execute();
        }
    }

    // Show a new list of artists.
    private void updateArtists(List<String> artists) {
        final ListView listView = getListView();
        mArtists.clear();
        mArtists.addAll(artists);
        listView.setFastScrollEnabled(false);
        mAdapter.setEnabled(true);
        mAdapter.notifyDataSetChanged();
        listView.setFastScrollEnabled(true);
        Util.resizeListViewToFixFastScroll(listView);
    }

    // Launch BrowseAlbumsActivity for a given artist.
    private void startBrowseAlbumsActivity(String artist) {
        Intent intent = new Intent(this, BrowseAlbumsActivity.class);
        intent.putExtra(BrowseActivity.BUNDLE_ARTIST, artist);
        intent.putExtra(BrowseActivity.BUNDLE_CACHED, mOnlyCached);
        startActivityForResult(intent, BROWSE_ALBUMS_REQUEST_CODE);
    }

    // Launch BrowseSongsActivity for a given artist.
    private void startBrowseSongsActivity(String artist, String minRating) {
        Intent intent = new Intent(this, BrowseSongsActivity.class);
        intent.putExtra(BrowseActivity.BUNDLE_ARTIST, artist);
        intent.putExtra(BrowseActivity.BUNDLE_CACHED, mOnlyCached);
        if (minRating != null && !minRating.isEmpty())
            intent.putExtra(BrowseActivity.BUNDLE_MIN_RATING, minRating);
        startActivityForResult(intent, BROWSE_SONGS_REQUEST_CODE);
    }
}
