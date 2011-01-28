// Copyright 2011 Daniel Erat <dan@erat.org>
// All rights reserved.

package org.erat.nup;

import android.app.ListActivity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.ListAdapter;
import android.widget.ListView;

import java.util.ArrayList;
import java.util.List;

public class BrowseArtistsActivity extends ListActivity
                                   implements NupService.SongDatabaseUpdateListener {
    // Identifier for the BrowseAlbumsActivity that we start.
    private static final int BROWSE_ALBUMS_REQUEST_CODE = 1;

    // Artists that we display.
    private List<String> mArtists = new ArrayList<String>(); 

    private ArrayAdapter<String> mAdapter;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(R.string.browse_artists);

        mAdapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, mArtists);
        setListAdapter(mAdapter);
        getListView().setFastScrollEnabled(true);

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
        Intent intent = new Intent(this, BrowseAlbumsActivity.class);
        intent.putExtra(BrowseActivity.BUNDLE_ARTIST, mArtists.get(position));
        startActivityForResult(intent, BROWSE_ALBUMS_REQUEST_CODE);
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
}
