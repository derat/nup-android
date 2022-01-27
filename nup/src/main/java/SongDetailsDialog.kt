/*
 * Copyright 2011 Daniel Erat <dan@erat.org>
 * All rights reserved.
 */

package org.erat.nup

import android.app.AlertDialog
import android.content.Context
import android.view.LayoutInflater
import android.widget.TextView

/** Show a dialog containing details about [song]. */
fun showSongDetailsDialog(context: Context, song: Song) {
    // https://stackoverflow.com/a/22655641/6882947
    val view = LayoutInflater.from(context).inflate(R.layout.song_details_dialog, null)
    view.findViewById<TextView>(R.id.artist)!!.text = song.artist
    view.findViewById<TextView>(R.id.title)!!.text = song.title
    view.findViewById<TextView>(R.id.album)!!.text = song.album
    view.findViewById<TextView>(R.id.track)!!.text = song.track.toString()
    view.findViewById<TextView>(R.id.length)!!.text = formatDuration(song.lengthSec.toInt())

    var ratingStr = ""
    if (song.rating >= 0.0) {
        val numStars = 1 + Math.round(song.rating * 4.0).toInt()
        for (i in 1..5) ratingStr += if (i <= numStars) "\u2605" else "\u2606"
    }
    view.findViewById<TextView>(R.id.rating)!!.text = ratingStr

    AlertDialog.Builder(context)
        .setTitle(null)
        .setView(view)
        .show()
}
