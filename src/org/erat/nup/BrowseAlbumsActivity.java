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

public class BrowseAlbumsActivity extends ListActivity
                                  implements NupService.SongDatabaseUpdateListener {
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

        mAdapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, mAlbums);
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
        Intent intent = new Intent();
        intent.putExtra(BrowseActivity.BUNDLE_ALBUM, mAlbums.get(position));
        if (mArtist != null)
            intent.putExtra(BrowseActivity.BUNDLE_ARTIST, mArtist);
        setResult(RESULT_OK, intent);
        finish();
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
}
