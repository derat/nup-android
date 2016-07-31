// Copyright 2011 Daniel Erat <dan@erat.org>
// All rights reserved.

package org.erat.nup;

import android.app.ListActivity;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Bundle;
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

public class BrowseAlbumsActivity extends ListActivity
                                  implements NupService.SongDatabaseUpdateListener {
    private static final String TAG = "BrowseAlbumsActivity";

    // Identifier for the BrowseSongsActivity that we start.
    private static final int BROWSE_SONGS_REQUEST_CODE = 1;

    private static final int MENU_ITEM_BROWSE_SONGS_WITH_RATING = 1;
    private static final int MENU_ITEM_BROWSE_SONGS = 2;

    // Are we displaying only cached songs?
    private boolean mOnlyCached = false;

    // Artist that was passed to us, or null if we were started directly from BrowseActivity.
    private String mArtist = null;

    // Albums that we're displaying along with number of tracks.  Just the albums featuring |mArtist| if it's non-null,
    // or all albums on the server otherwise.
    private List<StringIntPair> mAlbums = new ArrayList<StringIntPair>();

    private SortedStringArrayAdapter mAdapter;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mOnlyCached = getIntent().getBooleanExtra(BrowseActivity.BUNDLE_CACHED, false);
        mArtist = getIntent().getStringExtra(BrowseActivity.BUNDLE_ARTIST);
        setTitle(
            (mArtist != null) ?
            getString(mOnlyCached ? R.string.browse_cached_albums_fmt : R.string.browse_albums_fmt, mArtist) :
            getString(mOnlyCached ? R.string.browse_cached_albums: R.string.browse_albums));

        mAdapter = new SortedStringArrayAdapter(this, R.layout.browse_row, mAlbums, Util.SORT_ALBUM);
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

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (resultCode == RESULT_OK) {
            setResult(RESULT_OK);
            finish();
        }
    }

    @Override
    protected void onListItemClick(ListView listView, View view, int position, long id) {
        StringIntPair album = mAlbums.get(position);
        if (album == null)
            return;
        startBrowseSongsActivity(album.getString(), -1.0);
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View view, ContextMenu.ContextMenuInfo menuInfo) {
        StringIntPair album = mAlbums.get(((AdapterView.AdapterContextMenuInfo) menuInfo).position);
        if (album != null)
            menu.setHeaderTitle(album.getString());
        menu.add(0, MENU_ITEM_BROWSE_SONGS_WITH_RATING, 0, R.string.browse_songs_four_stars);
        menu.add(0, MENU_ITEM_BROWSE_SONGS, 0, R.string.browse_songs);
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        AdapterView.AdapterContextMenuInfo info = (AdapterView.AdapterContextMenuInfo) item.getMenuInfo();
        StringIntPair album = mAlbums.get(info.position);
        if (album == null)
            return false;
        switch (item.getItemId()) {
            case MENU_ITEM_BROWSE_SONGS_WITH_RATING:
                startBrowseSongsActivity(album.getString(), 0.75);
                return true;
            case MENU_ITEM_BROWSE_SONGS:
                startBrowseSongsActivity(album.getString(), -1.0);
                return true;
            default:
                return false;
        }
    }

    // Implements NupService.SongDatabaseUpdateListener.
    @Override
    public void onSongDatabaseUpdate() {
        if (!NupActivity.getService().getSongDb().getAggregateDataLoaded()) {
            mAlbums.add(new StringIntPair(getString(R.string.loading), -1));
            mAdapter.setEnabled(false);
            mAdapter.notifyDataSetChanged();
            return;
        }

        if (!mOnlyCached) {
            updateAlbums(
                (mArtist != null) ?
                NupActivity.getService().getSongDb().getAlbumsByArtist(mArtist) :
                NupActivity.getService().getSongDb().getAlbumsSortedAlphabetically());
        } else {
            new AsyncTask<Void, Void, List<StringIntPair>>() {
                @Override
                protected List<StringIntPair> doInBackground(Void... args) {
                    return (mArtist != null) ?
                        NupActivity.getService().getSongDb().getCachedAlbumsByArtist(mArtist) :
                        NupActivity.getService().getSongDb().getCachedAlbumsSortedAlphabetically();
                }
                @Override
                protected void onPostExecute(List<StringIntPair> albums) {
                    updateAlbums(albums);
                }
            }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
        }
    }

    // Show a new list of albums.
    private void updateAlbums(List<StringIntPair> albums) {
        mAlbums.clear();
        mAlbums.addAll(albums);
        final ListView listView = getListView();
        listView.setFastScrollEnabled(false);
        mAdapter.setEnabled(true);
        mAdapter.notifyDataSetChanged();
        listView.setFastScrollEnabled(true);
        Util.resizeListViewToFixFastScroll(listView);
    }

    // Launch BrowseSongsActivity for a given album.
    private void startBrowseSongsActivity(String album, double minRating) {
        Intent intent = new Intent(this, BrowseSongsActivity.class);
        if (mArtist != null)
            intent.putExtra(BrowseActivity.BUNDLE_ARTIST, mArtist);
        intent.putExtra(BrowseActivity.BUNDLE_ALBUM, album);
        intent.putExtra(BrowseActivity.BUNDLE_CACHED, mOnlyCached);
        if (minRating >= 0.0)
            intent.putExtra(BrowseActivity.BUNDLE_MIN_RATING, minRating);
        startActivityForResult(intent, BROWSE_SONGS_REQUEST_CODE);
    }
}
