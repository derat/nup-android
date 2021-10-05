/*
 * Copyright 2021 Daniel Erat <dan@erat.org>
 * All rights reserved.
 */

package org.erat.nup

import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import org.erat.nup.NupActivity.Companion.service

/** Create the "common" options menu for onCreateOptionsMenu. */
fun AppCompatActivity.createCommonOptionsMenu(menu: Menu): Boolean {
    menuInflater.inflate(R.menu.common_menu, menu)
    return true
}

/** Handle a click in the "common" options menu for onOptionsItemSelected. */
fun AppCompatActivity.handleCommonOptionsItemSelected(item: MenuItem): Boolean {
    return when (item.itemId) {
        R.id.browse_pause_menu_item -> {
            service.pause()
            true
        }
        R.id.browse_return_menu_item -> {
            setResult(AppCompatActivity.RESULT_OK)
            finish()
            true
        }
        else -> false
    }
}
