// Copyright 2012 Daniel Erat <dan@erat.org>
// All rights reserved.

package org.erat.nup;

// Exactly what you'd think.
class StringIntPair {
    private final String mString;
    private final int mInt;

    public StringIntPair(String first, int second) {
        mString = first;
        mInt = second;
    }

    public String getString() { return mString; }
    public int getInt() { return mInt; }

    public String toString() {
        String out = mString;
        if (mInt >= 0)
            out += " (" + mInt + ")";
        return out;
    }
}
