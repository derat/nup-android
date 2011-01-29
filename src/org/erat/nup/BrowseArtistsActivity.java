// Copyright 2011 Daniel Erat <dan@erat.org>
// All rights reserved.

package org.erat.nup;

import android.app.ListActivity;
import android.content.Intent;
import android.os.Bundle;
import android.view.ContextMenu;
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
    // Identifier for the BrowseAlbumsActivity that we start.
    private static final int BROWSE_ALBUMS_REQUEST_CODE = 1;

    private static final int MENU_ITEM_SEARCH_WITH_RATING = 1;
    private static final int MENU_ITEM_SEARCH = 2;
    private static final int MENU_ITEM_BROWSE_ALBUMS = 3;

    // Artists that we display.
    private List<String> mArtists = new ArrayList<String>(); 

    private ArrayAdapter<String> mAdapter;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(R.string.browse_artists);

        mAdapter = new ArrayAdapter<String>(this, R.layout.browse_row, mArtists);
        setListAdapter(mAdapter);
        getListView().setFastScrollEnabled(true);
        registerForContextMenu(getListView());

        NupActivity.getService().addSongDatabaseUpdateListener(this);
        onSongDatabaseUpdate();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        NupActivity.getService().removeSongDatabaseUpdateListener(this);
    }

    @Override
    protected void onListItemClick(ListView listView, View view, int position, long id) {
        startBrowseAlbumsActivity(mArtists.get(position));
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View view, ContextMenu.ContextMenuInfo menuInfo) {
        menu.add(0, MENU_ITEM_SEARCH_WITH_RATING, 0, R.string.search_with_75_rating);
        menu.add(0, MENU_ITEM_SEARCH, 0, R.string.search);
        menu.add(0, MENU_ITEM_BROWSE_ALBUMS, 0, R.string.browse_albums);
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        AdapterView.AdapterContextMenuInfo info = (AdapterView.AdapterContextMenuInfo) item.getMenuInfo();
        String artist = mArtists.get(info.position);
        switch (item.getItemId()) {
            case MENU_ITEM_SEARCH_WITH_RATING:
                returnArtistResult(artist, "0.75");
                return true;
            case MENU_ITEM_SEARCH:
                returnArtistResult(artist, null);
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
        // Pass the intent back through to BrowseActivity.
        if (requestCode == BROWSE_ALBUMS_REQUEST_CODE && resultCode == RESULT_OK) {
            setResult(RESULT_OK, data);
            finish();
        }
    }

    // Implements NupService.SongDatabaseUpdateListener.
    @Override
    public void onSongDatabaseUpdate() {
        mArtists.clear();
        mArtists.addAll(NupActivity.getService().getSongDb().getArtistsSortedAlphabetically());
        mAdapter.notifyDataSetChanged();
    }

    // Launch BrowseAlbumsActivity for a given artist.
    private void startBrowseAlbumsActivity(String artist) {
        Intent intent = new Intent(this, BrowseAlbumsActivity.class);
        intent.putExtra(BrowseActivity.BUNDLE_ARTIST, artist);
        startActivityForResult(intent, BROWSE_ALBUMS_REQUEST_CODE);
    }

    // Return a result to BrowseActivity containing just an artist and an optional minimum rating.
    // Invoked when the user searches for an artist (without browsing albums) via the context menu.
    private void returnArtistResult(String artist, String minRating) {
        Intent intent = new Intent();
        intent.putExtra(BrowseActivity.BUNDLE_ARTIST, artist);
        if (minRating != null && !minRating.isEmpty())
            intent.putExtra(BrowseActivity.BUNDLE_MIN_RATING, minRating);
        setResult(RESULT_OK, intent);
        finish();
    }
}
