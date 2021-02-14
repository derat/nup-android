// Copyright 2011 Daniel Erat <dan@erat.org>
// All rights reserved.
package org.erat.nup

import android.net.Uri
import android.os.Looper
import android.widget.FrameLayout
import android.widget.ListView
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.util.*

internal object Util {
    private const val TRUNCATION_STRING = "..."

    // Different sort types that can be passed to getSortingKey().
    @JvmField var SORT_ARTIST = 1
    @JvmField var SORT_TITLE = 2
    @JvmField var SORT_ALBUM = 3

    /** Crash if not running on the given Looper.  */
    @JvmStatic fun assertOnLooper(looper: Looper) {
        check(!(looper.thread !== Thread.currentThread())) { "Running on " + Thread.currentThread() + " instead of " + looper.thread }
    }

    /** Crash if called from a thread besides the main/UI one.  */
    @JvmStatic fun assertOnMainThread() {
        assertOnLooper(Looper.getMainLooper())
    }

    /** Crash if called from the main thread.  */
    @JvmStatic fun assertNotOnMainThread() {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Running on main thread; shouldn't be" }
    }

    // Yay.
    @JvmStatic
    @Throws(IOException::class)
    fun getStringFromInputStream(stream: InputStream?): String {
        val sb = StringBuilder()
        val reader = BufferedReader(InputStreamReader(stream))
        var line: String?
        while (reader.readLine().also { line = it } != null) {
            sb.append(line)
        }
        return sb.toString()
    }

    // Formats a duration as "0:00".
    @JvmStatic
    fun formatDurationString(sec: Int): String {
        return String.format("%d:%02d", sec / 60, sec % 60)
    }

    // Formats a current duration and total duration as "0:00 / 0:00".
    @JvmStatic
    fun formatDurationProgressString(curSec: Int, totalSec: Int): String {
        return formatDurationString(curSec) + " / " + formatDurationString(totalSec)
    }

    // Truncate a string.
    @JvmStatic
    fun truncateString(str: String, maxLength: Int): String {
        if (str.length <= maxLength) return str
        if (maxLength <= TRUNCATION_STRING.length) throw RuntimeException(
                "unable to truncate string to just $maxLength char(s)")
        return str.substring(0, maxLength - TRUNCATION_STRING.length) + TRUNCATION_STRING
    }

    // Work around bad design decisions made 35 years ago.
    //
    // Unicode support in filenames is a mess on Android.  Filenames are silently converted to UTF-8
    // bytes before being written.  On FAT filesystems, hilarity ensues.  Just URL-escape everything
    // instead.
    @JvmStatic
    fun escapeStringForFilename(str: String): String {
        // Uri: "Leaves letters (A-Z, a-z), numbers (0-9), and unreserved characters (_-!.~'()*)
        // intact."
        // FAT: permits letters, numbers, spaces, and these characters: ! # $ % & ' ( ) - @ ^ _ ` {
        // } ~
        var esc = Uri.encode(str, " #$&@^`{}")
        esc = esc.replace("*", "%2A")
        return esc
    }

    // Find the position of a string in an array.  Returns -1 if it's not there.
    @JvmStatic
    fun getStringArrayIndex(array: Array<String>, str: String): Int {
        for (i in array.indices) if (array[i] == str) return i
        return -1
    }

    // Get a key that can be used for sorting a string.
    // The string is converted to lowercase and common leading articles are removed.
    // |sortType| is a SORT_* value.
    //
    // If this method is changed, SongDatabase.updateStatsTables() must be called on the next run,
    // as sorting keys are cached in the database.
    @JvmStatic
    fun getSortingKey(str: String, sortType: Int): String {
        var key = str.toLowerCase()
        if (sortType == SORT_ARTIST) {
            if (key == "[dialogue]" || key == "[no artist]" || key == "[unknown]") {
                // Strings used by MusicBrainz and/or Picard.
                return "!$key"
            }
        } else if (sortType == SORT_ALBUM) {
            if (key == "[non-album tracks]" || key == "[unset]") {
                // Strings used by MusicBrainz and/or Picard.
                return "!$str"
            } else if (key.startsWith("( )")) {
                // Weird album title.
                return key
            }
        }

        // Strip off leading punctuation, common articles, and other junk.
        val prefixes = arrayOf(" ", "\"", "'", "’", "(", "[", "<", "...", "a ", "an ", "the ")
        var start = 0
        while (start < key.length) {
            var found = false
            for (i in prefixes.indices) {
                val prefix = prefixes[i]
                if (key.startsWith(prefix, start)) {
                    start += prefix.length
                    found = true
                    break
                }
            }
            if (!found) {
                break
            }
        }
        if (start > 0) {
            key = key.substring(start)
        }
        return key
    }

    // Sort the supplied list by its keys.
    @JvmStatic
    fun sortStatsRowList(stats: List<StatsRow>, sortType: Int) {
        // Getting sorting keys is expensive, so just do it once.
        val keys = HashMap<StatsKey, String>()
        if (sortType == SORT_ALBUM) {
            for (s in stats) {
                var key = getSortingKey(s.key.album, sortType)
                key += " " + s.key.albumId
                keys[s.key] = key
            }
        } else if (sortType == SORT_ARTIST) {
            for (s in stats) {
                keys[s.key] = getSortingKey(s.key.artist, sortType)
            }
        } else {
            throw IllegalArgumentException("invalid sort type")
        }
        Collections.sort(
                stats
        ) { a, b -> keys[a.key]!!.compareTo(keys[b.key]!!) }
    }

    // ListView is stupid and can't handle a SectionIndexer's sections getting updated:
    // http://code.google.com/p/android/issues/detail?id=9054
    // http://stackoverflow.com/questions/2912082/section-indexer-overlay-is-not-updating-as-the-adapters-data-changes
    //
    // One workaround is calling setFastScrollEnabled(false), calling notifyDataSetChanged(),
    // calling setFastScrollEnabled(true), and then calling this method to force a resize of the
    // ListView (otherwise, the section indicator is drawn in the top-left corner of the view
    // instead of being centered over it).
    //
    // TODO: Switch to something that doesn't shrink the size of the ListView every time it's
    // called.
    @JvmStatic
    fun resizeListViewToFixFastScroll(view: ListView) {
        val layoutParams = FrameLayout.LayoutParams(
                view.width - 1, FrameLayout.LayoutParams.FILL_PARENT)
        view.layoutParams = layoutParams
    }
}
