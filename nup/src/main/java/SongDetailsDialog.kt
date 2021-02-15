/*
 * Copyright 2011 Daniel Erat <dan@erat.org>
 * All rights reserved.
 */

package org.erat.nup

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.Window
import android.widget.TextView
import org.erat.nup.Util.formatDurationString

// Static methods for activities that want to display dialogs showing
// the details of Song objects.
object SongDetailsDialog {
    private const val BUNDLE_ARTIST = "artist"
    private const val BUNDLE_TITLE = "title"
    private const val BUNDLE_ALBUM = "album"
    private const val BUNDLE_TRACK_NUM = "track_num"
    private const val BUNDLE_LENGTH_SEC = "length_sec"
    private const val BUNDLE_RATING = "rating"

    fun createBundle(song: Song): Bundle {
        val bundle = Bundle()
        bundle.putString(BUNDLE_ARTIST, song.artist)
        bundle.putString(BUNDLE_TITLE, song.title)
        bundle.putString(BUNDLE_ALBUM, song.album)
        bundle.putInt(BUNDLE_TRACK_NUM, song.track)
        bundle.putInt(BUNDLE_LENGTH_SEC, song.lengthSec)
        bundle.putDouble(BUNDLE_RATING, song.rating)
        return bundle
    }

    fun createDialog(context: Context?): Dialog {
        val dialog = Dialog(context!!)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.song_details_dialog)
        return dialog
    }

    fun prepareDialog(dialog: Dialog, bundle: Bundle) {
        (dialog.findViewById<View>(R.id.artist) as TextView).text = bundle.getString(BUNDLE_ARTIST)
        (dialog.findViewById<View>(R.id.title) as TextView).text = bundle.getString(BUNDLE_TITLE)
        (dialog.findViewById<View>(R.id.album) as TextView).text = bundle.getString(BUNDLE_ALBUM)
        (dialog.findViewById<View>(R.id.track) as TextView).text =
            bundle.getInt(BUNDLE_TRACK_NUM).toString()
        (dialog.findViewById<View>(R.id.length) as TextView).text =
            formatDurationString(bundle.getInt(BUNDLE_LENGTH_SEC))

        var ratingStr = ""
        val rating = bundle.getDouble(BUNDLE_RATING)
        if (rating >= 0.0) {
            // Really, Java?  No way to repeat a string X times without using Apache Commons?
            val numStars = 1 + Math.round(rating * 4.0).toInt()
            for (i in 1..5) ratingStr += if (i <= numStars) "\u2605" else "\u2606"
        }
        (dialog.findViewById<View>(R.id.rating) as TextView).text = ratingStr
    }
}
