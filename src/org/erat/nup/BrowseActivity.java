// Copyright 2011 Daniel Erat <dan@erat.org>
// All rights reserved.

package org.erat.nup;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ListView;
import android.widget.SimpleAdapter;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;

public class BrowseActivity extends Activity {
    private static final String TAG = "BrowseActivity";

    public static final String BUNDLE_ARTIST = "artist";
    public static final String BUNDLE_ALBUM = "album";

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

        final String artistKey = "artist", albumKey = "album";
        List<HashMap<String, String>> data = new ArrayList<HashMap<String, String>>();
        for (String artist : artists) {
            List<String> albums = NupActivity.getService().getAlbumsByArtist(artist);
            if (albums == null)
                continue;

            for (String album : albums) {
                HashMap<String, String> map = new HashMap<String, String>();
                map.put(artistKey, artist);
                map.put(albumKey, album);
                data.add(map);
            }
        }

        // Sort by (artist, album).
        Collections.sort(data, new Comparator<HashMap<String, String>>() {
            @Override
            public int compare(HashMap<String, String> a, HashMap<String, String> b) {
                int comparison = a.get(artistKey).compareTo(b.get(artistKey));
                if (comparison != 0)
                    return comparison;
                return a.get(albumKey).compareTo(b.get(albumKey));
            }
        });

        SimpleAdapter adapter = new SimpleAdapter(
            this,
            data,
            R.layout.browse_row,
            new String[]{artistKey, albumKey},
            new int[]{R.id.artist, R.id.album});
        ListView view = (ListView) findViewById(R.id.list);
        view.setAdapter(adapter);

        view.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView parent, View view, int position, long id) {
                TextView artistView = (TextView) view.findViewById(R.id.artist);
                TextView albumView = (TextView) view.findViewById(R.id.album);

                Bundle bundle = new Bundle();
                bundle.putString(BUNDLE_ARTIST, artistView.getText().toString());
                bundle.putString(BUNDLE_ALBUM, albumView.getText().toString());

                Intent intent = new Intent();
                intent.putExtras(bundle);
                setResult(RESULT_OK, intent);
                finish();
                return true;
            }
        });
    }

    @Override
    protected void onDestroy() {
        Log.d(TAG, "activity destroyed");
        super.onDestroy();
    }
}
