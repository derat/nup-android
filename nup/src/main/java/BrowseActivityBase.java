// Copyright 2016 Daniel Erat <dan@erat.org>
// All rights reserved.

package org.erat.nup;

import android.app.ListActivity;
import android.content.Intent;
import android.view.Menu;
import android.view.MenuItem;

public class BrowseActivityBase extends ListActivity {
    public static final String BUNDLE_ARTIST = "artist";
    public static final String BUNDLE_ALBUM = "album";
    public static final String BUNDLE_MIN_RATING = "min_rating";
    public static final String BUNDLE_CACHED = "cached";

    @Override public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.browse_menu, menu);
        return true;
    }

    @Override public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
        case R.id.browse_pause_menu_item:
            NupActivity.getService().pause();
            return true;
        case R.id.browse_return_menu_item:
            setResult(RESULT_OK);
            finish();
            return true;
        default:
            return false;
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (resultCode == RESULT_OK) {
            setResult(RESULT_OK);
            finish();
        }
    }
}
