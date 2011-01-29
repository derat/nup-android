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

public class BrowseAlbumsActivity extends ListActivity
                                  implements NupService.SongDatabaseUpdateListener {
    private static final int MENU_ITEM_SEARCH_WITH_RATING = 1;
    private static final int MENU_ITEM_SEARCH = 2;

    // Artist that was passed to us, or null if we were started directly from BrowseActivity.
    private String mArtist = null;

    // Albums that we display.  Just the albums featuring |mArtist| if it's non-null, or all
    // albums on the server otherwise.
    private List<String> mAlbums = new ArrayList<String>();

    private ArrayAdapter<String> mAdapter;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mArtist = getIntent().getStringExtra(BrowseActivity.BUNDLE_ARTIST);
        setTitle((mArtist != null) ? getString(R.string.browse_albums_fmt, mArtist) : getString(R.string.browse_albums));

        mAdapter = new ArrayAdapter<String>(this, R.layout.browse_row, mAlbums);
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
        returnResult(mAlbums.get(position), null);
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View view, ContextMenu.ContextMenuInfo menuInfo) {
        menu.add(0, MENU_ITEM_SEARCH_WITH_RATING, 0, R.string.search_with_75_rating);
        menu.add(0, MENU_ITEM_SEARCH, 0, R.string.search);
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        AdapterView.AdapterContextMenuInfo info = (AdapterView.AdapterContextMenuInfo) item.getMenuInfo();
        String album = mAlbums.get(info.position);
        switch (item.getItemId()) {
            case MENU_ITEM_SEARCH_WITH_RATING:
                returnResult(album, "0.75");
                return true;
            case MENU_ITEM_SEARCH:
                returnResult(album, null);
                return true;
            default:
                return false;
        }
    }

    // Implements NupService.SongDatabaseUpdateListener.
    @Override
    public void onSongDatabaseUpdate() {
        mAlbums.clear();
        mAlbums.addAll(
            (mArtist != null) ?
            NupActivity.getService().getSongDb().getAlbumsByArtist(mArtist) :
            NupActivity.getService().getSongDb().getAlbumsSortedAlphabetically());
        mAdapter.notifyDataSetChanged();
    }

    private void returnResult(String album, String minRating) {
        Intent intent = new Intent();
        intent.putExtra(BrowseActivity.BUNDLE_ALBUM, album);
        if (mArtist != null)
            intent.putExtra(BrowseActivity.BUNDLE_ARTIST, mArtist);
        if (minRating != null && !minRating.isEmpty())
            intent.putExtra(BrowseActivity.BUNDLE_MIN_RATING, minRating);
        setResult(RESULT_OK, intent);
        finish();
    }
}
