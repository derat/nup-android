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

import java.util.List;

public class BrowseAlbumsActivity extends ListActivity
                                  implements NupService.SongDatabaseUpdateListener {
    // Artist that was passed to us, or null if we were started directly from BrowseActivity.
    private String mArtist = null;

    // Albums that we display.  Just the albums featuring |mArtist| if it's non-null, or all
    // albums on the server otherwise.
    private List<String> mAlbums;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        NupActivity.getService().addSongDatabaseUpdateListener(this);

        mArtist = getIntent().getStringExtra(BrowseActivity.BUNDLE_ARTIST);

        if (mArtist != null) {
            setTitle(getString(R.string.browse_albums_fmt, mArtist));
            mAlbums = NupActivity.getService().getSongDb().getAlbumsByArtist(mArtist);
        } else {
            setTitle(R.string.browse_albums);
            mAlbums = NupActivity.getService().getSongDb().getAlbumsSortedAlphabetically();
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1);
        for (String album : mAlbums)
            adapter.add(album);
        setListAdapter(adapter);

        getListView().setFastScrollEnabled(true);
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
        // FIXME
    }
}
