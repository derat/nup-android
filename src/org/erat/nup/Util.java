// Copyright 2011 Daniel Erat <dan@erat.org>
// All rights reserved.

package org.erat.nup;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.widget.ListView;
import android.widget.FrameLayout;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;

class Util {
    private static final String TRUNCATION_STRING = "...";

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
        if (str.length() <= maxLength)
            return str;
        if (maxLength <= TRUNCATION_STRING.length())
            throw new RuntimeException("unable to truncate string to just " + maxLength + " char(s)");
        return str.substring(0, maxLength - TRUNCATION_STRING.length()) + TRUNCATION_STRING;
    }

    // Work around bad design decisions made 35 years ago.
    //
    // Unicode support in filenames is a mess on Android.  Filenames are silently converted to UTF-8
    // bytes before being written.  On FAT filesystems, hilarity ensues.  Just URL-escape everything instead.
    public static String escapeStringForFilename(String str) {
        // Uri: "Leaves letters (A-Z, a-z), numbers (0-9), and unreserved characters (_-!.~'()*) intact."
        // FAT: permits letters, numbers, spaces, and these characters: ! # $ % & ' ( ) - @ ^ _ ` { } ~
        str = Uri.encode(str, " #$&@^`{}");
        str = str.replace("*", "%2A");
        return str;
    }

    // Find the position of a string in an array.  Returns -1 if it's not there.
    public static int getStringArrayIndex(String[] array, String str) {
        for (int i = 0; i < array.length; ++i)
            if (array[i].equals(str))
                return i;
        return -1;
    }

    // Get a key that can be used for sorting a string.
    // The string is converted to lowercase and common leading articles are removed.
    public static String getSortingKey(String str) {
        str = str.toLowerCase();

        // Strip off some leading punctuation.
        int startingIndex = 0;
        for (; startingIndex < str.length(); ++startingIndex) {
            char ch = str.charAt(startingIndex);
            if (ch != ' ' && ch != '"' && ch != '\'')
                break;
        }
        if (startingIndex > 0)
            str = str.substring(startingIndex);

        // Remove common articles and other junk.
        String[] prefixes = { "...", "a ", "an ", "the " };
        for (int i = 0; i < prefixes.length; ++i) {
            String prefix = prefixes[i];
            if (str.startsWith(prefix))
                str = str.substring(prefix.length());
        }
        return str;
    }

    // Sort a list of StringIntPair objects.
    public static void sortStringIntPairList(List<StringIntPair> items) {
        final HashMap<String,String> sortKeys = new HashMap<String,String>();
        for (StringIntPair item : items)
            sortKeys.put(item.getString(), getSortingKey(item.getString()));
        Collections.sort(items, new Comparator<StringIntPair>() {
            @Override
            public int compare(StringIntPair a, StringIntPair b) {
                return sortKeys.get(a.getString()).compareTo(sortKeys.get(b.getString()));
            }
        });
    }

    // Is a network connection currently available?
    public static boolean isNetworkAvailable(Context context) {
        ConnectivityManager manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo info = manager.getActiveNetworkInfo();
        return (info != null && info.isAvailable());
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
    // TODO: Switch to something that doesn't shrink the size of the ListView every time it's called.
    public static void resizeListViewToFixFastScroll(ListView view) {
        FrameLayout.LayoutParams layoutParams =
            new FrameLayout.LayoutParams(
                view.getWidth() - 1, FrameLayout.LayoutParams.FILL_PARENT);
        view.setLayoutParams(layoutParams);
    }
}
