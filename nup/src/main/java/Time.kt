/*
 * Copyright 2011 Daniel Erat <dan@erat.org>
 * All rights reserved.
 */

package org.erat.nup

/** Format [sec] as "0:00". */
fun formatDuration(sec: Int) = String.format("%d:%02d", sec / 60, sec % 60)
