// Copyright 2011 Daniel Erat <dan@erat.org>
// All rights reserved.

package org.erat.nup;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;

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

    // Formats a current time and total time as "0:00 / 0:00".
    public static String formatTimeString(int curSec, int totalSec) {
        return String.format("%d:%02d / %d:%02d", curSec / 60, curSec % 60, totalSec / 60, totalSec % 60);
    }

    // Work around bad design decisions made 35 years ago.
    public static String escapeFilename(String str) {
        // Uri: "Leaves letters (A-Z, a-z), numbers (0-9), and unreserved characters (_-!.~'()*) intact."
        // FAT: permits letters, numbers, spaces, and these characters: ! # $ % & ' ( ) - @ ^ _ ` { } ~
        str = str.trim();
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

    // Sort a list of strings.
    public static void sortStringList(List<String> items) {
        final HashMap<String,String> sortKeys = new HashMap<String,String>();
        for (String item : items)
            sortKeys.put(item, getSortingKey(item));
        Collections.sort(items, new Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                return sortKeys.get(a).compareTo(sortKeys.get(b));
            }
        });
    }

    // Is a network connection currently available?
    public static boolean isNetworkAvailable(Context context) {
        ConnectivityManager manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo info = manager.getActiveNetworkInfo();
        return (info != null && info.isAvailable());
    }
}
