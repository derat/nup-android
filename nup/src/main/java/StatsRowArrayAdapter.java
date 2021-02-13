package org.erat.nup;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.SectionIndexer;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

class StatsRowArrayAdapter extends ArrayAdapter<StatsRow> implements SectionIndexer {
    private static final String TAG = "StatsRowArrayAdapter";

    // Different information to display.
    public static int DISPLAY_ARTIST = 1;
    public static int DISPLAY_ALBUM = 2;
    public static int DISPLAY_ALBUM_ARTIST = 3;

    private static final String NUMBER_SECTION = "#";
    private static final String OTHER_SECTION =
            "\u2668"; // HOT SPRINGS (Android isn't snowman-compatible)

    // Rows to display.
    private List<StatsRow> rows;

    // Are all rows in the list enabled?  If false, all are disabled.
    private boolean enabled = true;

    // Information to display from |mRows|.
    private int displayType = DISPLAY_ARTIST;

    // Manner in which |mRows| are sorted, as a Util.SORT_* value.
    private int sortType = Util.SORT_ARTIST;

    private ArrayList<String> sections = new ArrayList<String>();

    // Position of the first row in each section.
    private ArrayList<Integer> sectionStartingPositions = new ArrayList<Integer>();

    StatsRowArrayAdapter(
            Context context,
            int textViewResourceId,
            List<StatsRow> rows,
            int displayType,
            int sortType) {
        super(context, textViewResourceId, rows);
        this.rows = rows;
        this.displayType = displayType;
        this.sortType = sortType;
        initSections();
    }

    // Should all of the rows in the list be enabled, or all disabled?
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public int getPositionForSection(int section) {
        return sectionStartingPositions.get(section);
    }

    @Override
    public int getSectionForPosition(int position) {
        // No upper_bound()/lower_bound()? :-(
        for (int i = 0; i < sectionStartingPositions.size() - 1; ++i) {
            if (position < sectionStartingPositions.get(i + 1)) return i;
        }
        return sectionStartingPositions.size() - 1;
    }

    @Override
    public Object[] getSections() {
        return sections.toArray();
    }

    @Override
    public void notifyDataSetChanged() {
        super.notifyDataSetChanged();
        initSections();
    }

    @Override
    public boolean areAllItemsEnabled() {
        return enabled;
    }

    @Override
    public boolean isEnabled(int position) {
        return enabled;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        View view;
        if (convertView != null) {
            view = convertView;
        } else {
            LayoutInflater inflater =
                    (LayoutInflater) getContext().getSystemService(Context.LAYOUT_INFLATER_SERVICE);
            view = inflater.inflate(R.layout.browse_row, null);
        }

        StatsRow row = rows.get(position);
        ((TextView) view.findViewById(R.id.main)).setText(getDisplayString(row.key));
        ((TextView) view.findViewById(R.id.extra)).setText(row.count >= 0 ? "" + row.count : "");
        return view;
    }

    // Returns the string to display for the supplied key.
    private String getDisplayString(StatsKey key) {
        if (displayType == DISPLAY_ARTIST) return key.artist;
        else if (displayType == DISPLAY_ALBUM) return key.album;
        else if (displayType == DISPLAY_ALBUM_ARTIST) return key.album + " (" + key.artist + ")";
        throw new IllegalArgumentException("invalid sort type");
    }

    // Updates |mSections| and |mSectionStartingPositions| for |mRows|.
    private void initSections() {
        // Create a list of all possible sections in the order in which they'd appear.
        ArrayList<String> sections = new ArrayList<String>();
        sections.add(NUMBER_SECTION);
        for (char ch = 'A'; ch <= 'Z'; ++ch) sections.add(Character.toString(ch));
        sections.add(OTHER_SECTION);

        sections.clear();
        sectionStartingPositions.clear();

        int sectionIndex = -1;
        for (int rowIndex = 0; rowIndex < rows.size(); ++rowIndex) {
            StatsKey key = rows.get(rowIndex).key;
            String sectionName =
                    getSectionNameForString(sortType == Util.SORT_ARTIST ? key.artist : key.album);

            int prevSectionIndex = sectionIndex;
            while (sectionIndex == -1 || !sectionName.equals(sections.get(sectionIndex)))
                sectionIndex++;

            // If we advanced to a new section, register it.
            if (sectionIndex != prevSectionIndex) {
                sections.add(sections.get(sectionIndex));
                sectionStartingPositions.add(rowIndex);
            }
        }
    }

    private String getSectionNameForString(String str) {
        if (str.isEmpty()) return NUMBER_SECTION;

        String sortStr = Util.getSortingKey(str, sortType);
        char ch = sortStr.charAt(0);
        if (ch < 'a') return NUMBER_SECTION;
        if (ch >= 'a' && ch <= 'z') return Character.toString(Character.toUpperCase(ch));
        return OTHER_SECTION;
    }
}
