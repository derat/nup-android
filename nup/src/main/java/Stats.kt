/*
 * Copyright 2021 Daniel Erat <dan@erat.org>
 * All rights reserved.
 */

package org.erat.nup

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.SectionIndexer
import android.widget.TextView
import java.time.Instant
import java.util.Collections

/** Key for song counts. Fields may be empty. */
data class StatsKey(var artist: String, val album: String, val albumId: String)

/** Song count and related information for a specific [StatsKey]. */
data class StatsRow(
    val key: StatsKey,
    var count: Int,
    var coverFilename: String = "",
    var date: Instant? = null,
) {
    constructor(
        artist: String,
        album: String,
        albumId: String,
        count: Int,
        coverFilename: String = "",
        date: Instant? = null,
    ) :
        this(StatsKey(artist, album, albumId), count, coverFilename, date) {}
}

/** Ways to order [StatsRow]s. */
enum class StatsOrder { ARTIST, TITLE, ALBUM, DATE, UNSORTED }

/** Sort [stats] according to [order]. */
fun sortStatsRows(stats: List<StatsRow>, order: StatsOrder) {
    if (order == StatsOrder.UNSORTED) return

    // Getting sorting keys is expensive, so just do it once.
    val keys = HashMap<StatsKey, String>()
    when (order) {
        StatsOrder.ALBUM -> for (s in stats) {
            keys[s.key] = "${getSongSortKey(s.key.album)} ${s.key.albumId}"
        }
        StatsOrder.ARTIST -> for (s in stats) {
            keys[s.key] = getSongSortKey(s.key.artist)
        }
        StatsOrder.DATE -> for (s in stats) {
            keys[s.key] = "${s.date?.toString() ?: "9999"} ${getSongSortKey(s.key.album)}"
        }
        else -> throw IllegalArgumentException("Invalid ordering $order")
    }
    Collections.sort(stats) { a, b -> keys[a.key]!!.compareTo(keys[b.key]!!) }
}

/** Adapts an array of [StatsRow]s for display. */
class StatsRowArrayAdapter(
    context: Context,
    textViewResourceId: Int,
    val rows: List<StatsRow>,
    private val display: Display,
) : ArrayAdapter<StatsRow>(context, textViewResourceId, rows), SectionIndexer {
    /** Whether rows are enabled or not. */
    var enabled = true

    private val sections = ArrayList<String>()
    private val sectionPositions = ArrayList<Int>()

    private val order = display.statsOrder()

    override fun getPositionForSection(section: Int): Int {
        return sectionPositions[section]
    }

    override fun getSectionForPosition(position: Int): Int {
        // No upper_bound()/lower_bound()? :-(
        for (i in 0 until sectionPositions.size - 1) {
            if (position < sectionPositions[i + 1]) return i
        }
        return sectionPositions.size - 1
    }

    override fun getSections(): Array<Any> {
        return sections.toTypedArray()
    }

    override fun notifyDataSetChanged() {
        super.notifyDataSetChanged()
        initSections()
    }

    override fun areAllItemsEnabled(): Boolean { return enabled }
    override fun isEnabled(position: Int): Boolean { return enabled }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = if (convertView != null) {
            convertView
        } else {
            val inflater =
                context.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
            inflater.inflate(R.layout.browse_row, null)
        }
        val row = rows[position]
        (view.findViewById<View>(R.id.main) as TextView).text = getDisplayString(row.key)
        (view.findViewById<View>(R.id.extra) as TextView).text =
            if (row.count >= 0) row.count.toString() else ""
        return view
    }

    /** Get the string to display for [key]. */
    private fun getDisplayString(key: StatsKey): String {
        return when (display) {
            Display.ARTIST -> key.artist
            Display.ARTIST_UNSORTED -> key.artist
            Display.ALBUM -> key.album
            Display.ALBUM_ARTIST -> "${key.album} (${key.artist})"
        }
    }

    /** Update [sections] and [sectionPositions] for [rows]. */
    private fun initSections() {
        // Update the main list to have the sections that are needed.
        sections.clear()
        sectionPositions.clear()

        // Don't initialize sections if the rows won't be alphabetically ordered.
        if (order != StatsOrder.ARTIST && order != StatsOrder.ALBUM) return

        var sectionIndex = -1
        for (rowIndex in rows.indices) {
            val key = rows[rowIndex].key
            val sectionName = getSongSection(
                if (order == StatsOrder.ARTIST) key.artist else key.album
            )
            val prevSectionIndex = sectionIndex
            while (sectionIndex == -1 || sectionName != allSongSections[sectionIndex]) {
                sectionIndex++
            }

            // If we advanced to a new section, register it.
            if (sectionIndex != prevSectionIndex) {
                sections.add(allSongSections[sectionIndex])
                sectionPositions.add(rowIndex)
            }
        }
    }

    /** Type of information to display. */
    enum class Display {
        ARTIST { override fun statsOrder() = StatsOrder.ARTIST },
        ARTIST_UNSORTED { override fun statsOrder() = StatsOrder.UNSORTED },
        ALBUM { override fun statsOrder() = StatsOrder.DATE }, // albums for single artist
        ALBUM_ARTIST { override fun statsOrder() = StatsOrder.ALBUM }; // all albums

        abstract fun statsOrder(): StatsOrder
    }

    init {
        initSections()
    }
}
