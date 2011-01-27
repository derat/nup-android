// Copyright 2011 Daniel Erat <dan@erat.org>
// All rights reserved.

package org.erat.nup;

import android.net.Uri;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.IOException;

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
}
