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

public class BrowseArtistsActivity extends ListActivity {
    private static final int BROWSE_ALBUMS_REQUEST_CODE = 1;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(R.string.browse_artists);

        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1);
        for (String artist : NupActivity.getService().getArtists())
            adapter.add(artist);

        setListAdapter(adapter);
    }

    @Override
    protected void onListItemClick(ListView listView, View view, int position, long id) {
        String artist = NupActivity.getService().getArtists().get(position);
        Intent intent = new Intent(this, BrowseAlbumsActivity.class);
        intent.putExtra(BrowseActivity.BUNDLE_ARTIST, artist);
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
}
