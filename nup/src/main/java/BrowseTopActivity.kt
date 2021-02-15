/*
 * Copyright 2011 Daniel Erat <dan@erat.org>
 * All rights reserved.
 */

package org.erat.nup

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.ListView

class BrowseTopActivity : BrowseActivityBase() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTitle(R.string.browse)
        val adapter = ArrayAdapter<String>(this, R.layout.browse_row, R.id.main)
        adapter.add(getString(R.string.artists))
        adapter.add(getString(R.string.albums))
        adapter.add(getString(R.string.artists_cached))
        adapter.add(getString(R.string.albums_cached))
        listAdapter = adapter
    }

    override fun onListItemClick(listView: ListView?, view: View?, position: Int, id: Long) {
        if (position == 0) {
            startActivityForResult(
                Intent(this, BrowseArtistsActivity::class.java), BROWSE_ARTISTS_REQUEST_CODE
            )
        } else if (position == 1) {
            startActivityForResult(
                Intent(this, BrowseAlbumsActivity::class.java), BROWSE_ALBUMS_REQUEST_CODE
            )
        } else if (position == 2) {
            val intent = Intent(this, BrowseArtistsActivity::class.java)
            intent.putExtra(BUNDLE_CACHED, true)
            startActivityForResult(intent, BROWSE_ARTISTS_REQUEST_CODE)
        } else if (position == 3) {
            val intent = Intent(this, BrowseAlbumsActivity::class.java)
            intent.putExtra(BUNDLE_CACHED, true)
            startActivityForResult(intent, BROWSE_ALBUMS_REQUEST_CODE)
        }
    }

    companion object {
        private const val BROWSE_ARTISTS_REQUEST_CODE = 1
        private const val BROWSE_ALBUMS_REQUEST_CODE = 2
    }
}
