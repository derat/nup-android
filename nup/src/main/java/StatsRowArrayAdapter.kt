package org.erat.nup

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.SectionIndexer
import android.widget.TextView
import org.erat.nup.Util.getSortingKey
import java.util.*

internal class StatsRowArrayAdapter(
        context: Context?,
        textViewResourceId: Int,
        // Rows to display.
        private val rows: List<StatsRow>,
        // Information to display from |rows|.
        private val displayType: Int,
        // Manner in which |mRows| are sorted, as a Util.SORT_* value.
        private val sortType: Int) : ArrayAdapter<StatsRow?>(context!!, textViewResourceId, rows), SectionIndexer {
    // Are all rows in the list enabled?  If false, all are disabled.
    private var enabled = true

    private val sections = ArrayList<String>()

    // Position of the first row in each section.
    private val sectionStartingPositions = ArrayList<Int>()

    // Should all of the rows in the list be enabled, or all disabled?
    fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
    }

    override fun getPositionForSection(section: Int): Int {
        return sectionStartingPositions[section]
    }

    override fun getSectionForPosition(position: Int): Int {
        // No upper_bound()/lower_bound()? :-(
        for (i in 0 until sectionStartingPositions.size - 1) {
            if (position < sectionStartingPositions[i + 1]) return i
        }
        return sectionStartingPositions.size - 1
    }

    override fun getSections(): Array<Any> {
        return sections.toTypedArray()
    }

    override fun notifyDataSetChanged() {
        super.notifyDataSetChanged()
        initSections()
    }

    override fun areAllItemsEnabled(): Boolean {
        return enabled
    }

    override fun isEnabled(position: Int): Boolean {
        return enabled
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view: View
        view = if (convertView != null) {
            convertView
        } else {
            val inflater = context.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
            inflater.inflate(R.layout.browse_row, null)
        }
        val row = rows[position]
        (view.findViewById<View>(R.id.main) as TextView).text = getDisplayString(row.key)
        (view.findViewById<View>(R.id.extra) as TextView).text = if (row.count >= 0) "" + row.count else ""
        return view
    }

    // Returns the string to display for the supplied key.
    private fun getDisplayString(key: StatsKey): String {
        if (displayType == DISPLAY_ARTIST) return key.artist else if (displayType == DISPLAY_ALBUM) return key.album else if (displayType == DISPLAY_ALBUM_ARTIST) return key.album + " (" + key.artist + ")"
        throw IllegalArgumentException("invalid sort type")
    }

    // Updates |mSections| and |mSectionStartingPositions| for |mRows|.
    private fun initSections() {
        // Create a list of all possible sections in the order in which they'd appear.
        val allSections = ArrayList<String>()
        allSections.add(NUMBER_SECTION)
        var ch = 'A'
        while (ch <= 'Z') {
            allSections.add(Character.toString(ch))
            ++ch
        }
        allSections.add(OTHER_SECTION)

        // // Update the main list to have the sections that are needed.
        sections.clear()
        sectionStartingPositions.clear()

        var sectionIndex = -1
        for (rowIndex in rows.indices) {
            val key = rows[rowIndex].key
            val sectionName = getSectionNameForString(if (sortType == Util.SORT_ARTIST) key.artist else key.album)
            val prevSectionIndex = sectionIndex
            while (sectionIndex == -1 || sectionName != allSections[sectionIndex]) sectionIndex++

            // If we advanced to a new section, register it.
            if (sectionIndex != prevSectionIndex) {
                sections.add(allSections[sectionIndex])
                sectionStartingPositions.add(rowIndex)
            }
        }
    }

    private fun getSectionNameForString(str: String): String {
        if (str.isEmpty()) return NUMBER_SECTION
        val sortStr = getSortingKey(str, sortType)
        val ch = sortStr[0]
        if (ch < 'a') return NUMBER_SECTION
        return if (ch >= 'a' && ch <= 'z') Character.toString(Character.toUpperCase(ch)) else OTHER_SECTION
    }

    companion object {
        private const val TAG = "StatsRowArrayAdapter"

        // Different information to display.
        @JvmField var DISPLAY_ARTIST = 1
        @JvmField var DISPLAY_ALBUM = 2
        @JvmField var DISPLAY_ALBUM_ARTIST = 3
        private const val NUMBER_SECTION = "#"
        private const val OTHER_SECTION = "\u2668" // HOT SPRINGS (Android isn't snowman-compatible)
    }

    init {
        initSections()
    }
}
