// Copyright 2011 Daniel Erat <dan@erat.org>
// All rights reserved.

package org.erat.nup;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.ContextMenu;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ListView;
import android.widget.SimpleAdapter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class SearchResultsActivity extends Activity {
    private static final String TAG = "SearchResultsActivity";

    // Key for the SongList object in the Bundle of the Intent that's used to create us.
    public static final String SONG_KEY = "songs";

    // IDs for items in our context menus.
    private static final int MENU_ITEM_PLAY = 1;
    private static final int MENU_ITEM_INSERT = 2;
    private static final int MENU_ITEM_APPEND = 3;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        Log.d(TAG, "activity created");
        super.onCreate(savedInstanceState);
        setTitle(R.string.search_results);
        setContentView(R.layout.search_results);

        List<Song> songs = SearchActivity.getSearchResults();
        final String artistKey = "artist", titleKey = "title";
        List<HashMap<String, String>> data = new ArrayList<HashMap<String, String>>();
        for (Song song : songs) {
            HashMap<String, String> map = new HashMap<String, String>();
            map.put(artistKey, song.getArtist());
            map.put(titleKey, song.getTitle());
            data.add(map);
        }

        SimpleAdapter adapter = new SimpleAdapter(
            this,
            data,
            R.layout.search_results_row,
            new String[]{ artistKey, titleKey },
            new int[]{ R.id.artist, R.id.title });
        ListView view = (ListView) findViewById(R.id.results);
        view.setAdapter(adapter);
        registerForContextMenu(view);
    }

    @Override
    protected void onDestroy() {
        Log.d(TAG, "activity destroyed");
        super.onDestroy();
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View view, ContextMenu.ContextMenuInfo menuInfo) {
        if (view.getId() == R.id.results) {
            AdapterView.AdapterContextMenuInfo info = (AdapterView.AdapterContextMenuInfo) menuInfo;
            Song song = SearchActivity.getSearchResults().get(info.position);
            menu.setHeaderTitle(song.getArtist() + " - " + song.getTitle());
            menu.add(0, MENU_ITEM_PLAY, 0, R.string.play);
            menu.add(0, MENU_ITEM_INSERT, 0, R.string.insert);
            menu.add(0, MENU_ITEM_APPEND, 0, R.string.append);
        }
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        AdapterView.AdapterContextMenuInfo info = (AdapterView.AdapterContextMenuInfo) item.getMenuInfo();
        Song song = SearchActivity.getSearchResults().get(info.position);
        switch (item.getItemId()) {
            case MENU_ITEM_PLAY:
                NupActivity.getService().addSongToPlaylist(song, true);
                return true;
            case MENU_ITEM_INSERT:
                NupActivity.getService().addSongToPlaylist(song, false);
                return true;
            case MENU_ITEM_APPEND:
                NupActivity.getService().appendSongToPlaylist(song);
                return true;
            default:
                return false;
        }
    }

    public void onAppendButtonClicked(View view) {
        NupActivity.getService().appendSongsToPlaylist(SearchActivity.getSearchResults());
        setResult(RESULT_OK);
        finish();
    }

    public void onInsertButtonClicked(View view) {
        NupActivity.getService().addSongsToPlaylist(SearchActivity.getSearchResults(), false);
        setResult(RESULT_OK);
        finish();
    }

    public void onReplaceButtonClicked(View view) {
        NupActivity.getService().clearPlaylist();
        NupActivity.getService().appendSongsToPlaylist(SearchActivity.getSearchResults());
        setResult(RESULT_OK);
        finish();
    }
}
