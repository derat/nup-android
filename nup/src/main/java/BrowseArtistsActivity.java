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

public class BrowseArtistsActivity extends BrowseActivityBase
    implements NupService.SongDatabaseUpdateListener {
  private static final String TAG = "BrowseArtistsActivity";

  // Identifiers for activities that we start.
  private static final int BROWSE_ALBUMS_REQUEST_CODE = 1;
  private static final int BROWSE_SONGS_REQUEST_CODE = 2;

  private static final int MENU_ITEM_BROWSE_SONGS_WITH_RATING = 1;
  private static final int MENU_ITEM_BROWSE_SONGS = 2;
  private static final int MENU_ITEM_BROWSE_ALBUMS = 3;

  // Are we displaying only cached songs?
  private boolean mOnlyCached = false;

  // Artists that we're displaying.
  private List<StatsRow> mRows = new ArrayList<StatsRow>();

  private StatsRowArrayAdapter mAdapter;

  @Override
  public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    mOnlyCached = getIntent().getBooleanExtra(BUNDLE_CACHED, false);
    setTitle(mOnlyCached ? R.string.browse_cached_artists : R.string.browse_artists);

    mAdapter =
        new StatsRowArrayAdapter(
            this,
            R.layout.browse_row,
            mRows,
            StatsRowArrayAdapter.DISPLAY_ARTIST,
            Util.SORT_ARTIST);
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
  protected void onListItemClick(ListView listView, View view, int position, long id) {
    StatsRow row = mRows.get(position);
    if (row == null) return;
    startBrowseAlbumsActivity(row.key.artist);
  }

  @Override
  public void onCreateContextMenu(
      ContextMenu menu, View view, ContextMenu.ContextMenuInfo menuInfo) {
    int pos = ((AdapterView.AdapterContextMenuInfo) menuInfo).position;
    StatsRow row = mRows.get(pos);
    if (row != null) menu.setHeaderTitle(row.key.artist);

    menu.add(0, MENU_ITEM_BROWSE_SONGS_WITH_RATING, 0, R.string.browse_songs_four_stars);
    menu.add(0, MENU_ITEM_BROWSE_SONGS, 0, R.string.browse_songs);
    menu.add(0, MENU_ITEM_BROWSE_ALBUMS, 0, R.string.browse_albums);
  }

  @Override
  public boolean onContextItemSelected(MenuItem item) {
    AdapterView.AdapterContextMenuInfo info =
        (AdapterView.AdapterContextMenuInfo) item.getMenuInfo();
    StatsRow row = mRows.get(info.position);
    if (row == null) return false;

    switch (item.getItemId()) {
      case MENU_ITEM_BROWSE_SONGS_WITH_RATING:
        startBrowseSongsActivity(row.key.artist, 0.75);
        return true;
      case MENU_ITEM_BROWSE_SONGS:
        startBrowseSongsActivity(row.key.artist, -1.0);
        return true;
      case MENU_ITEM_BROWSE_ALBUMS:
        startBrowseAlbumsActivity(row.key.artist);
        return true;
      default:
        return false;
    }
  }

  // Implements NupService.SongDatabaseUpdateListener.
  @Override
  public void onSongDatabaseUpdate() {
    if (!NupActivity.getService().getSongDb().getAggregateDataLoaded()) {
      mRows.add(new StatsRow(getString(R.string.loading), "", "", -1));
      mAdapter.setEnabled(false);
      mAdapter.notifyDataSetChanged();
      return;
    }

    if (!mOnlyCached) {
      updateRows(NupActivity.getService().getSongDb().getArtistsSortedAlphabetically());
    } else {
      new AsyncTask<Void, Void, List<StatsRow>>() {
        @Override
        protected List<StatsRow> doInBackground(Void... args) {
          return NupActivity.getService().getSongDb().getCachedArtistsSortedAlphabetically();
        }

        @Override
        protected void onPostExecute(List<StatsRow> rows) {
          updateRows(rows);
        }
      }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }
  }

  // Show a new list of artists.
  private void updateRows(List<StatsRow> rows) {
    final ListView listView = getListView();
    mRows.clear();
    mRows.addAll(rows);
    listView.setFastScrollEnabled(false);
    mAdapter.setEnabled(true);
    mAdapter.notifyDataSetChanged();
    listView.setFastScrollEnabled(true);
    Util.resizeListViewToFixFastScroll(listView);
  }

  // Launch BrowseAlbumsActivity for a given artist.
  private void startBrowseAlbumsActivity(String artist) {
    Intent intent = new Intent(this, BrowseAlbumsActivity.class);
    intent.putExtra(BUNDLE_ARTIST, artist);
    intent.putExtra(BUNDLE_CACHED, mOnlyCached);
    startActivityForResult(intent, BROWSE_ALBUMS_REQUEST_CODE);
  }

  // Launch BrowseSongsActivity for a given artist.
  private void startBrowseSongsActivity(String artist, double minRating) {
    Intent intent = new Intent(this, BrowseSongsActivity.class);
    intent.putExtra(BUNDLE_ARTIST, artist);
    intent.putExtra(BUNDLE_CACHED, mOnlyCached);
    if (minRating >= 0) intent.putExtra(BUNDLE_MIN_RATING, minRating);
    startActivityForResult(intent, BROWSE_SONGS_REQUEST_CODE);
  }
}
