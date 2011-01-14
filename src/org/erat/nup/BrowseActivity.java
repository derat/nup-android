// Copyright 2011 Daniel Erat <dan@erat.org>
// All rights reserved.

package org.erat.nup;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.widget.ListView;
import android.widget.SimpleAdapter;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class BrowseActivity extends Activity {
    private static final String TAG = "BrowseActivity";

    @Override
    public void onCreate(Bundle savedInstanceState) {
        Log.d(TAG, "activity created");
        super.onCreate(savedInstanceState);
        setContentView(R.layout.browse);

        List<String> artists = NupActivity.getService().getArtists();
        if (artists == null) {
            Toast.makeText(this, "Server contents not loaded.", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        List<HashMap<String, String>> data = new ArrayList<HashMap<String, String>>();
        for (String artist : artists) {
            List<String> albums = NupActivity.getService().getAlbumsByArtist(artist);
            if (albums == null)
                continue;

            for (String album : albums) {
                HashMap<String, String> map = new HashMap<String, String>();
                map.put("artist", artist);
                map.put("album", album);
                data.add(map);
            }
        }

        // Sort by (artist, album).
        Collections.sort(data, new Comparator<HashMap<String, String>>() {
            @Override
            public int compare(HashMap<String, String> a, HashMap<String, String> b) {
                int comparison = a.get("artist").compareTo(b.get("artist"));
                if (comparison != 0)
                    return comparison;
                return a.get("album").compareTo(b.get("album"));
            }
        });

        SimpleAdapter adapter = new SimpleAdapter(
            this,
            data,
            R.layout.browse_row,
            new String[]{"artist", "album"},
            new int[]{R.id.artist, R.id.album});
        ListView view = (ListView) findViewById(R.id.list);
        view.setAdapter(adapter);
    }

    @Override
    protected void onDestroy() {
        Log.d(TAG, "activity destroyed");
        super.onDestroy();
    }
}
