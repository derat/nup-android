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
            else -> {
                val idx = pos - 4
                val presets = service.songDb.searchPresets
                if (idx >= presets.size) return
                val preset = presets[idx]
                service.scope.async(Dispatchers.Main) {
                    val songs = async(Dispatchers.IO) {
                        presetSearchUsingNetwork(service.songDb, service.downloader, preset)
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
        val rows = mutableListOf(
            StatsRow(getString(R.string.artists), "", "", -1),
            StatsRow(getString(R.string.albums), "", "", -1),
            StatsRow(getString(R.string.artists_cached), "", "", -1),
            StatsRow(getString(R.string.albums_cached), "", "", -1),
        )
        rows.addAll(db.searchPresets.filter { it.play }.map { StatsRow(it.name, "", "", -1) })
        update(rows)
    }
}
