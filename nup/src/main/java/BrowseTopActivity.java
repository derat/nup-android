// Copyright 2011 Daniel Erat <dan@erat.org>
// All rights reserved.

package org.erat.nup;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.ListView;

public class BrowseTopActivity extends BrowseActivityBase {
    private static final int BROWSE_ARTISTS_REQUEST_CODE = 1;
    private static final int BROWSE_ALBUMS_REQUEST_CODE = 2;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(R.string.browse);

        ArrayAdapter<String> adapter =
                new ArrayAdapter<String>(this, R.layout.browse_row, R.id.main);
        adapter.add(getString(R.string.artists));
        adapter.add(getString(R.string.albums));
        adapter.add(getString(R.string.artists_cached));
        adapter.add(getString(R.string.albums_cached));
        setListAdapter(adapter);
    }

    @Override
    protected void onListItemClick(ListView listView, View view, int position, long id) {
        if (position == 0) {
            startActivityForResult(
                    new Intent(this, BrowseArtistsActivity.class), BROWSE_ARTISTS_REQUEST_CODE);
        } else if (position == 1) {
            startActivityForResult(
                    new Intent(this, BrowseAlbumsActivity.class), BROWSE_ALBUMS_REQUEST_CODE);
        } else if (position == 2) {
            Intent intent = new Intent(this, BrowseArtistsActivity.class);
            intent.putExtra(BUNDLE_CACHED, true);
            startActivityForResult(intent, BROWSE_ARTISTS_REQUEST_CODE);
        } else if (position == 3) {
            Intent intent = new Intent(this, BrowseAlbumsActivity.class);
            intent.putExtra(BUNDLE_CACHED, true);
            startActivityForResult(intent, BROWSE_ALBUMS_REQUEST_CODE);
        }
    }
}
