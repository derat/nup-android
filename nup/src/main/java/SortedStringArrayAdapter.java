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

class SortedStringArrayAdapter extends ArrayAdapter<StringIntPair>
                               implements SectionIndexer {
    private static final String TAG = "SortedStringArrayAdapter";

    private List<StringIntPair> mItems;

    // Are all items in the list enabled?  If false, all are disabled.
    private boolean mEnabled = true;

    // Manner in which to sort strings, as a Util.SORT_* value.
    private int mSortType = Util.SORT_ARTIST;

    private static final String NUMBER_SECTION = "#";
    private static final String OTHER_SECTION = "\u2668";  // HOT SPRINGS (Android isn't snowman-compatible)

    private ArrayList<String> mSections = new ArrayList<String>();

    // Position of the first item in each section.
    private ArrayList<Integer> mSectionStartingPositions = new ArrayList<Integer>();

    SortedStringArrayAdapter(Context context,
                             int textViewResourceId,
                             List<StringIntPair> items,
                             int sortType) {
        super(context, textViewResourceId, items);
        mItems = items;
        mSortType = sortType;
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

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        View view;
        if (convertView != null) {
            view = convertView;
        } else {
            LayoutInflater inflater = (LayoutInflater) getContext().getSystemService(Context.LAYOUT_INFLATER_SERVICE);
            view = inflater.inflate(R.layout.browse_row, null);
        }

        StringIntPair item = mItems.get(position);
        ((TextView) view.findViewById(R.id.main)).setText(item.getString());
        ((TextView) view.findViewById(R.id.extra)).setText(item.getInt() >= 0 ? "" + item.getInt() : "");
        return view;
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

        String sortStr = Util.getSortingKey(str, mSortType);
        char ch = sortStr.charAt(0);
        if (ch < 'a')
            return NUMBER_SECTION;
        if (ch >= 'a' && ch <= 'z')
            return Character.toString(Character.toUpperCase(ch));
        return OTHER_SECTION;
    }
}
