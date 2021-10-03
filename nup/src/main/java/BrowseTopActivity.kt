/*
 * Copyright 2011 Daniel Erat <dan@erat.org>
 * All rights reserved.
 */

package org.erat.nup

import android.content.Intent
import android.os.Bundle
import android.view.ContextMenu
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import org.erat.nup.NupActivity.Companion.service

/** Displays top-level browsing actions. */
class BrowseTopActivity : BrowseActivityBase() {
    override val display = StatsRowArrayAdapter.Display.ARTIST_UNSORTED

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTitle(R.string.browse)
    }

    override fun onRowClick(row: StatsRow, pos: Int) {
        when (pos) {
            0 -> startActivityForResult(
                Intent(this, BrowseArtistsActivity::class.java), BROWSE_ARTISTS_REQUEST_CODE
            )
            1 -> startActivityForResult(
                Intent(this, BrowseAlbumsActivity::class.java), BROWSE_ALBUMS_REQUEST_CODE
            )
            2 -> {
                val intent = Intent(this, BrowseArtistsActivity::class.java)
                intent.putExtra(BUNDLE_CACHED, true)
                startActivityForResult(intent, BROWSE_ARTISTS_REQUEST_CODE)
            }
            3 -> {
                val intent = Intent(this, BrowseAlbumsActivity::class.java)
                intent.putExtra(BUNDLE_CACHED, true)
                startActivityForResult(intent, BROWSE_ALBUMS_REQUEST_CODE)
            }
            4 -> {
                // TODO: If/when NupActivity and friends are using MediaBrowser, MediaController,
                // and friends to talk to NupService, this can just send an empty search.
                service.scope.async(Dispatchers.Main) {
                    val songs = async(Dispatchers.IO) {
                        searchForSongs(
                            service.songDb,
                            "" /* query */,
                            online = service.networkHelper.isNetworkAvailable
                        )
                    }.await()
                    if (songs.size > 0) {
                        service.clearPlaylist()
                        service.appendSongsToPlaylist(songs)
                        setResult(RESULT_OK)
                        finish()
                    }
                }
            }
        }
    }
    override fun fillMenu(menu: ContextMenu, row: StatsRow) = Unit
    override fun onMenuClick(itemId: Int, row: StatsRow) = false
    override fun getRows(
        db: SongDatabase,
        scope: CoroutineScope,
        update: (rows: List<StatsRow>?) -> Unit
    ) {
        update(
            arrayListOf(
                StatsRow(getString(R.string.artists), "", "", -1),
                StatsRow(getString(R.string.albums), "", "", -1),
                StatsRow(getString(R.string.artists_cached), "", "", -1),
                StatsRow(getString(R.string.albums_cached), "", "", -1),
                StatsRow(getString(R.string.shuffle), "", "", -1),
            )
        )
    }
}
