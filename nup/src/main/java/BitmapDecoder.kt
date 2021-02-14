// Copyright 2016 Daniel Erat <dan@erat.org>
// All rights reserved.
package org.erat.nup

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

/** Wrapper around BitmapFactory to permit use in unit tests.  */
class BitmapDecoder {
    /**
     * Decodes a file to a bitmap.
     *
     * @param file file to decode
     * @return decoded bitmap
     */
    fun decodeFile(file: File): Bitmap {
        return BitmapFactory.decodeFile(file.path)
    }
}