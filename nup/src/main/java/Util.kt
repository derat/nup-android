/*
 * Copyright 2011 Daniel Erat <dan@erat.org>
 * All rights reserved.
 */

package org.erat.nup

import android.widget.FrameLayout
import android.widget.ListView

object Util {
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
    // TODO: Is this still needed?
    fun resizeListViewToFixFastScroll(view: ListView) {
        view.layoutParams = FrameLayout.LayoutParams(
            view.width - 1, FrameLayout.LayoutParams.FILL_PARENT
        )
    }
}
