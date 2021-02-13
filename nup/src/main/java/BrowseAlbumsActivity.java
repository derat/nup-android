// Copyright 2011 Daniel Erat <dan@erat.org>
// All rights reserved.

package org.erat.nup;

import android.content.Intent;
import android.os.AsyncTask;
import android.os.Bundle;
import android.view.ContextMenu;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ListView;

import java.util.ArrayList;
import java.util.List;

public class BrowseAlbumsActivity extends BrowseActivityBase
        implements NupService.SongDatabaseUpdateListener {
    private static final String TAG = "BrowseAlbumsActivity";

    // Identifier for the BrowseSongsActivity that we start.
    private static final int BROWSE_SONGS_REQUEST_CODE = 1;

    private static final int MENU_ITEM_BROWSE_SONGS_BY_ARTIST = 1;
    private static final int MENU_ITEM_BROWSE_SONGS_WITH_RATING = 2;
    private static final int MENU_ITEM_BROWSE_SONGS = 3;

    // Are we displaying only cached songs?
    private boolean onlyCached = false;

    // Artist that was passed to us, or null if we were started directly from BrowseTopActivity.
    private String artist = null;

    // Albums that we're displaying along with number of tracks.
    // Just the albums featuring |mArtist| if it's non-null, or all albums on the server otherwise.
    private List<StatsRow> rows = new ArrayList<StatsRow>();

    private StatsRowArrayAdapter adapter;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        onlyCached = getIntent().getBooleanExtra(BUNDLE_CACHED, false);
        artist = getIntent().getStringExtra(BUNDLE_ARTIST);
        setTitle(
                (artist != null)
                        ? getString(
                                onlyCached
                                        ? R.string.browse_cached_albums_fmt
                                        : R.string.browse_albums_fmt,
                                artist)
                        : getString(
                                onlyCached
                                        ? R.string.browse_cached_albums
                                        : R.string.browse_albums));

        adapter =
                new StatsRowArrayAdapter(
                        this,
                        R.layout.browse_row,
                        rows,
                        artist == null
                                ? StatsRowArrayAdapter.DISPLAY_ALBUM_ARTIST
                                : StatsRowArrayAdapter.DISPLAY_ALBUM,
                        Util.SORT_ALBUM);
        setListAdapter(adapter);
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
    protected void onListItemClick(ListView listView, View view, int position, long id) {
        StatsRow row = rows.get(position);
        if (row == null) return;

        // TODO: Right now, we show the full album by default instead of limiting it to songs by
        // |mArtist|. Decide if this makes sense.
        startBrowseSongsActivity(null, row.key.album, row.key.albumId, -1.0);
    }

    @Override
    public void onCreateContextMenu(
            ContextMenu menu, View view, ContextMenu.ContextMenuInfo menuInfo) {
        int pos = ((AdapterView.AdapterContextMenuInfo) menuInfo).position;
        StatsRow row = rows.get(pos);
        if (row != null) menu.setHeaderTitle(row.key.album);

        if (artist != null) {
            String msg = getString(R.string.browse_songs_by_artist_fmt, artist);
            menu.add(0, MENU_ITEM_BROWSE_SONGS_BY_ARTIST, 0, msg);
        }
        menu.add(0, MENU_ITEM_BROWSE_SONGS_WITH_RATING, 0, R.string.browse_songs_four_stars);
        menu.add(0, MENU_ITEM_BROWSE_SONGS, 0, R.string.browse_songs);
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        AdapterView.AdapterContextMenuInfo info =
                (AdapterView.AdapterContextMenuInfo) item.getMenuInfo();
        StatsRow row = rows.get(info.position);
        if (row == null) return false;

        switch (item.getItemId()) {
            case MENU_ITEM_BROWSE_SONGS_BY_ARTIST:
                startBrowseSongsActivity(artist, row.key.album, row.key.albumId, -1.0);
                return true;
            case MENU_ITEM_BROWSE_SONGS_WITH_RATING:
                startBrowseSongsActivity("", row.key.album, row.key.albumId, 0.75);
                return true;
            case MENU_ITEM_BROWSE_SONGS:
                startBrowseSongsActivity("", row.key.album, row.key.albumId, -1.0);
                return true;
            default:
                return false;
        }
    }

    // Implements NupService.SongDatabaseUpdateListener.
    @Override
    public void onSongDatabaseUpdate() {
        if (!NupActivity.getService().getSongDb().getAggregateDataLoaded()) {
            rows.add(new StatsRow("", getString(R.string.loading), "", -1));
            adapter.setEnabled(false);
            adapter.notifyDataSetChanged();
            return;
        }

        if (!onlyCached) {
            updateRows(
                    (artist != null)
                            ? NupActivity.getService().getSongDb().getAlbumsByArtist(artist)
                            : NupActivity.getService().getSongDb().getAlbumsSortedAlphabetically());
        } else {
            new AsyncTask<Void, Void, List<StatsRow>>() {
                @Override
                protected List<StatsRow> doInBackground(Void... args) {
                    return artist != null
                            ? NupActivity.getService().getSongDb().getCachedAlbumsByArtist(artist)
                            : NupActivity.getService()
                                    .getSongDb()
                                    .getCachedAlbumsSortedAlphabetically();
                }

                @Override
                protected void onPostExecute(List<StatsRow> rows) {
                    updateRows(rows);
                }
            }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
        }
    }

    // Show a new list of albums.
    private void updateRows(List<StatsRow> newRows) {
        rows.clear();
        rows.addAll(newRows);
        final ListView listView = getListView();
        listView.setFastScrollEnabled(false);
        adapter.setEnabled(true);
        adapter.notifyDataSetChanged();
        listView.setFastScrollEnabled(true);
        Util.resizeListViewToFixFastScroll(listView);
    }

    // Launch BrowseSongsActivity for a given album.
    private void startBrowseSongsActivity(
            String artist, String album, String albumId, double minRating) {
        Intent intent = new Intent(this, BrowseSongsActivity.class);
        if (artist != null) intent.putExtra(BUNDLE_ARTIST, artist);
        intent.putExtra(BUNDLE_ALBUM, album);
        intent.putExtra(BUNDLE_ALBUM_ID, albumId);
        intent.putExtra(BUNDLE_CACHED, onlyCached);
        if (minRating >= 0.0) intent.putExtra(BUNDLE_MIN_RATING, minRating);
        startActivityForResult(intent, BROWSE_SONGS_REQUEST_CODE);
    }
}
