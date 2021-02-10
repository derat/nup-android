// Copyright 2016 Daniel Erat <dan@erat.org>
// All rights reserved.

package org.erat.nup;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import java.io.File;

/** Wrapper around BitmapFactory to permit use in unit tests. */
public class BitmapDecoder {
  public BitmapDecoder() {}

  /**
   * Decodes a file to a bitmap.
   *
   * @param file file to decode
   * @return decoded bitmap
   */
  public Bitmap decodeFile(File file) {
    return BitmapFactory.decodeFile(file.getPath());
  }
}
