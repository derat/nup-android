package org.erat.nup;

import android.content.Context;
import android.util.Log;
import android.widget.ArrayAdapter;
import android.widget.SectionIndexer;

import java.util.ArrayList;
import java.util.List;

class SortedStringArrayAdapter extends ArrayAdapter<StringIntPair>
                               implements SectionIndexer {
    private static final String TAG = "SortedStringArrayAdapter";

    private List<StringIntPair> mItems;

    // Are all items in the list enabled?  If false, all are disabled.
    private boolean mEnabled = true;

    private static final String NUMBER_SECTION = "#";
    private static final String OTHER_SECTION = "\u2668";  // HOT SPRINGS (Android isn't snowman-compatible)

    private ArrayList<String> mSections = new ArrayList<String>();

    // Position of the first item in each section.
    private ArrayList<Integer> mSectionStartingPositions = new ArrayList<Integer>();

    SortedStringArrayAdapter(Context context,
                             int textViewResourceId,
                             List<StringIntPair> items) {
        super(context, textViewResourceId, items);
        mItems = items;
        initSections();
    }

    // Should all of the items in the list be enabled, or all disabled?
    public void setEnabled(boolean enabled) {
        mEnabled = enabled;
    }

    @Override
    public int getPositionForSection(int section) {
        return mSectionStartingPositions.get(section);
    }

    @Override
    public int getSectionForPosition(int position) {
        // No upper_bound()/lower_bound()? :-(
        for (int i = 0; i < mSectionStartingPositions.size() - 1; ++i) {
            if (position < mSectionStartingPositions.get(i + 1))
                return i;
        }
        return mSectionStartingPositions.size() - 1;
    }

    @Override
    public Object[] getSections() {
        return mSections.toArray();
    }

    @Override
    public void notifyDataSetChanged() {
        super.notifyDataSetChanged();
        initSections();
    }

    @Override
    public boolean areAllItemsEnabled() {
        return mEnabled;
    }

    @Override
    public boolean isEnabled(int position) {
        return mEnabled;
    }

    private void initSections() {
        // Create a list of all possible sections in the order in which they'd appear.
        ArrayList<String> sections = new ArrayList<String>();
        sections.add(NUMBER_SECTION);
        for (char ch = 'A'; ch <= 'Z'; ++ch)
            sections.add(Character.toString(ch));
        sections.add(OTHER_SECTION);

        mSections.clear();
        mSectionStartingPositions.clear();

        int sectionIndex = -1;
        for (int itemIndex = 0; itemIndex < mItems.size(); ++itemIndex) {
            StringIntPair item = mItems.get(itemIndex);
            String sectionName = getSectionNameForString(item.getString());

            int prevSectionIndex = sectionIndex;
            while (sectionIndex == -1 || !sectionName.equals(sections.get(sectionIndex)))
                sectionIndex++;

            // If we advanced to a new section, register it.
            if (sectionIndex != prevSectionIndex) {
                mSections.add(sections.get(sectionIndex));
                mSectionStartingPositions.add(itemIndex);
            }
        }
    }

    private String getSectionNameForString(String str) {
        if (str.isEmpty())
            return NUMBER_SECTION;

        str = Util.getSortingKey(str);
        char ch = str.charAt(0);
        if (ch < 'a')
            return NUMBER_SECTION;
        if (ch >= 'a' && ch <= 'z')
            return Character.toString(Character.toUpperCase(ch));
        return OTHER_SECTION;
    }
}
