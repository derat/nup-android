// Copyright 2011 Daniel Erat <dan@erat.org>
// All rights reserved.

package org.erat.nup;

import android.net.Uri;
import android.os.Looper;
import android.widget.FrameLayout;
import android.widget.ListView;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;

public class Util {
    private static final String TRUNCATION_STRING = "...";

    // Different sort types that can be passed to getSortingKey().
    public static int SORT_ARTIST = 1;
    public static int SORT_TITLE = 2;
    public static int SORT_ALBUM = 3;

    /** Crash if not running on the given Looper. */
    public static void assertOnLooper(Looper looper) {
        if (looper.getThread() != Thread.currentThread()) {
            throw new IllegalStateException(
                    "Running on " + Thread.currentThread() + " instead of " + looper.getThread());
        }
    }

    /** Crash if called from a thread besides the main/UI one. */
    public static void assertOnMainThread() {
        assertOnLooper(Looper.getMainLooper());
    }

    /** Crash if called from the main thread. */
    public static void assertNotOnMainThread() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw new IllegalStateException("Running on main thread; shouldn't be");
        }
    }

    // Yay.
    public static String getStringFromInputStream(InputStream stream) throws IOException {
        StringBuilder sb = new StringBuilder();
        BufferedReader reader = new BufferedReader(new InputStreamReader(stream));
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line);
        }
        return sb.toString();
    }

    // Formats a duration as "0:00".
    public static String formatDurationString(int sec) {
        return String.format("%d:%02d", sec / 60, sec % 60);
    }

    // Formats a current duration and total duration as "0:00 / 0:00".
    public static String formatDurationProgressString(int curSec, int totalSec) {
        return formatDurationString(curSec) + " / " + formatDurationString(totalSec);
    }

    // Truncate a string.
    public static String truncateString(String str, int maxLength) {
        if (str.length() <= maxLength) return str;
        if (maxLength <= TRUNCATION_STRING.length())
            throw new RuntimeException(
                    "unable to truncate string to just " + maxLength + " char(s)");
        return str.substring(0, maxLength - TRUNCATION_STRING.length()) + TRUNCATION_STRING;
    }

    // Work around bad design decisions made 35 years ago.
    //
    // Unicode support in filenames is a mess on Android.  Filenames are silently converted to UTF-8
    // bytes before being written.  On FAT filesystems, hilarity ensues.  Just URL-escape everything
    // instead.
    public static String escapeStringForFilename(String str) {
        // Uri: "Leaves letters (A-Z, a-z), numbers (0-9), and unreserved characters (_-!.~'()*)
        // intact."
        // FAT: permits letters, numbers, spaces, and these characters: ! # $ % & ' ( ) - @ ^ _ ` {
        // } ~
        str = Uri.encode(str, " #$&@^`{}");
        str = str.replace("*", "%2A");
        return str;
    }

    // Find the position of a string in an array.  Returns -1 if it's not there.
    public static int getStringArrayIndex(String[] array, String str) {
        for (int i = 0; i < array.length; ++i) if (array[i].equals(str)) return i;
        return -1;
    }

    // Get a key that can be used for sorting a string.
    // The string is converted to lowercase and common leading articles are removed.
    // |sortType| is a SORT_* value.
    //
    // If this method is changed, SongDatabase.updateStatsTables() must be called on the next run,
    // as sorting keys are cached in the database.
    public static String getSortingKey(String str, int sortType) {
        str = str.toLowerCase();

        if (sortType == SORT_ARTIST) {
            if (str.equals("[dialogue]") || str.equals("[no artist]") || str.equals("[unknown]")) {
                // Strings used by MusicBrainz and/or Picard.
                return "!" + str;
            }
        } else if (sortType == SORT_ALBUM) {
            if (str.equals("[non-album tracks]") || str.equals("[unset]")) {
                // Strings used by MusicBrainz and/or Picard.
                return "!" + str;
            } else if (str.startsWith("( )")) {
                // Weird album title.
                return str;
            }
        }

        // Strip off leading punctuation, common articles, and other junk.
        final String[] prefixes = {" ", "\"", "'", "’", "(", "[", "<", "...", "a ", "an ", "the "};
        int start = 0;
        while (start < str.length()) {
            boolean found = false;
            for (int i = 0; i < prefixes.length; ++i) {
                String prefix = prefixes[i];
                if (str.startsWith(prefix, start)) {
                    start += prefix.length();
                    found = true;
                    break;
                }
            }
            if (!found) {
                break;
            }
        }
        if (start > 0) {
            str = str.substring(start);
        }

        return str;
    }

    // Sort the supplied list by its keys.
    public static void sortStatsRowList(List<StatsRow> stats, int sortType) {
        // Getting sorting keys is expensive, so just do it once.
        final HashMap<StatsKey, String> keys = new HashMap<StatsKey, String>();
        if (sortType == SORT_ALBUM) {
            for (StatsRow s : stats) {
                String key = getSortingKey(s.key.album, sortType);
                key += " " + s.key.albumId;
                keys.put(s.key, key);
            }
        } else if (sortType == SORT_ARTIST) {
            for (StatsRow s : stats) {
                keys.put(s.key, getSortingKey(s.key.artist, sortType));
            }
        } else {
            throw new IllegalArgumentException("invalid sort type");
        }

        Collections.sort(
                stats,
                new Comparator<StatsRow>() {
                    @Override
                    public int compare(StatsRow a, StatsRow b) {
                        return keys.get(a.key).compareTo(keys.get(b.key));
                    }
                });
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
    public static void resizeListViewToFixFastScroll(ListView view) {
        FrameLayout.LayoutParams layoutParams =
                new FrameLayout.LayoutParams(
                        view.getWidth() - 1, FrameLayout.LayoutParams.FILL_PARENT);
        view.setLayoutParams(layoutParams);
    }
}
