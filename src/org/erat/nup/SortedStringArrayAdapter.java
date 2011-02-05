package org.erat.nup;

import android.content.Context;
import android.util.Log;
import android.widget.ArrayAdapter;
import android.widget.SectionIndexer;

import java.util.ArrayList;
import java.util.List;

class SortedStringArrayAdapter extends ArrayAdapter<String>
                               implements SectionIndexer {
    private List<String> mItems;

    private static final String NUMBER_SECTION = "#";
    private static final String OTHER_SECTION = "\u2668";  // HOT SPRINGS (Android isn't snowman-compatible)

    private ArrayList<String> mSections = new ArrayList<String>();

    // Position of the first item in each section.
    private ArrayList<Integer> mSectionStartingPositions = new ArrayList<Integer>();

    SortedStringArrayAdapter(Context context,
                             int textViewResourceId,
                             List<String> items) {
        super(context, textViewResourceId, items);

        mItems = items;

        mSections.add(NUMBER_SECTION);
        for (char ch = 'A'; ch <= 'Z'; ++ch)
            mSections.add(Character.toString(ch));
        mSections.add(OTHER_SECTION);

        initSections();
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

    private void initSections() {
        mSectionStartingPositions.clear();
        mSectionStartingPositions.add(0);

        int currentSection = 0;
        for (int i = 0; i < mItems.size(); ++i) {
            String item = mItems.get(i);
            String sectionName = getSectionNameForString(item);

            if (sectionName.equals(mSections.get(currentSection)))
                continue;

            for (currentSection = currentSection + 1;
                 currentSection < mSections.size();
                 currentSection++) {
                mSectionStartingPositions.add(i);
                if (sectionName.equals(mSections.get(currentSection)))
                    break;
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
