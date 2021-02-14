// Copyright 2016 Daniel Erat <dan@erat.org>
// All rights reserved.
package org.erat.nup

import android.app.ListActivity
import android.content.Intent
import android.view.Menu
import android.view.MenuItem
import org.erat.nup.NupActivity.Companion.service

open class BrowseActivityBase : ListActivity() {
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.browse_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.browse_pause_menu_item -> {
                service!!.pause()
                true
            }
            R.id.browse_return_menu_item -> {
                setResult(RESULT_OK)
                finish()
                true
            }
            else -> false
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (resultCode == RESULT_OK) {
            setResult(RESULT_OK)
            finish()
        }
    }

    companion object {
        const val BUNDLE_ARTIST = "artist"
        const val BUNDLE_ALBUM = "album"
        const val BUNDLE_ALBUM_ID = "album_id"
        const val BUNDLE_MIN_RATING = "min_rating"
        const val BUNDLE_CACHED = "cached"
    }
}
