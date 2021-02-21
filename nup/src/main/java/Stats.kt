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
import java.util.Collections

/** Key for song counts. Fields may be empty. */
data class StatsKey(val artist: String, val album: String, val albumId: String)

/** Song count for a specific [StatsKey]. */
class StatsRow(val key: StatsKey, var count: Int) {
    constructor(artist: String, album: String, albumId: String, count: Int) :
        this(StatsKey(artist, album, albumId), count) {}
}

/** Sort [stats] according to [order]. */
fun sortStatsRows(stats: List<StatsRow>, order: SongOrder) {
    if (order == SongOrder.UNSORTED) return

    // Getting sorting keys is expensive, so just do it once.
    val keys = HashMap<StatsKey, String>()
    when (order) {
        SongOrder.ALBUM -> for (s in stats) {
            keys[s.key] = "${getSongOrderKey(s.key.album, order)} ${s.key.albumId}"
        }
        SongOrder.ARTIST -> for (s in stats) {
            keys[s.key] = getSongOrderKey(s.key.artist, order)
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

    private val order = display.songOrder()

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
        // Create a list of all possible sections in the order in which they'd appear.
        val allSections = ArrayList<String>()
        allSections.add(NUMBER_SECTION)
        for (ch in 'A'..'Z') allSections.add(Character.toString(ch))
        allSections.add(OTHER_SECTION)

        // Update the main list to have the sections that are needed.
        sections.clear()
        sectionPositions.clear()
        var sectionIndex = -1
        for (rowIndex in rows.indices) {
            val key = rows[rowIndex].key
            val sectionName = getSectionNameForString(
                if (order == SongOrder.ARTIST) key.artist else key.album
            )
            val prevSectionIndex = sectionIndex
            while (sectionIndex == -1 || sectionName != allSections[sectionIndex]) sectionIndex++

            // If we advanced to a new section, register it.
            if (sectionIndex != prevSectionIndex) {
                sections.add(allSections[sectionIndex])
                sectionPositions.add(rowIndex)
            }
        }
    }

    /** Get the section for [str]. */
    private fun getSectionNameForString(str: String): String {
        if (str.isEmpty()) return NUMBER_SECTION
        val sortStr = getSongOrderKey(str, order)
        val ch = sortStr[0]
        return when {
            ch < 'a' -> NUMBER_SECTION
            ch >= 'a' && ch <= 'z' -> Character.toString(Character.toUpperCase(ch))
            else -> OTHER_SECTION
        }
    }

    /** Type of information to display. */
    enum class Display {
        ARTIST { override fun songOrder() = SongOrder.ARTIST },
        ARTIST_UNSORTED { override fun songOrder() = SongOrder.UNSORTED },
        ALBUM { override fun songOrder() = SongOrder.ALBUM },
        ALBUM_ARTIST { override fun songOrder() = SongOrder.ALBUM };

        abstract fun songOrder(): SongOrder
    }

    companion object {
        private const val NUMBER_SECTION = "#"
        private const val OTHER_SECTION = "\u2668" // HOT SPRINGS (Android isn't snowman-compatible)
    }

    init {
        initSections()
    }
}
