// Copyright 2011 Daniel Erat <dan@erat.org>
// All rights reserved.

package org.erat.nup;

import android.app.ListActivity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.Toast;

public class BrowseActivity extends ListActivity {
    public static final String BUNDLE_ARTIST = "artist";
    public static final String BUNDLE_ALBUM = "album";
    public static final String BUNDLE_MIN_RATING = "min_rating";
    public static final String BUNDLE_CACHED = "cached";

    private static final int BROWSE_ARTISTS_REQUEST_CODE = 1;
    private static final int BROWSE_ALBUMS_REQUEST_CODE = 2;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(R.string.browse);

        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this, R.layout.browse_row);
        adapter.add(getString(R.string.artists));
        adapter.add(getString(R.string.albums));
        adapter.add(getString(R.string.artists_cached));
        adapter.add(getString(R.string.albums_cached));
        setListAdapter(adapter);
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
        if (position == 0) {
            startActivityForResult(new Intent(this, BrowseArtistsActivity.class), BROWSE_ARTISTS_REQUEST_CODE);
        } else if (position == 1) {
            startActivityForResult(new Intent(this, BrowseAlbumsActivity.class), BROWSE_ALBUMS_REQUEST_CODE);
        } else if (position == 2) {
            Intent intent = new Intent(this, BrowseArtistsActivity.class);
            intent.putExtra(BrowseActivity.BUNDLE_CACHED, true);
            startActivityForResult(intent, BROWSE_ARTISTS_REQUEST_CODE);
        } else if (position == 3) {
            Intent intent = new Intent(this, BrowseAlbumsActivity.class);
            intent.putExtra(BrowseActivity.BUNDLE_CACHED, true);
            startActivityForResult(intent, BROWSE_ALBUMS_REQUEST_CODE);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (resultCode == RESULT_OK) {
            setResult(RESULT_OK);
            finish();
        }
    }
}
