/*
 * Copyright 2021 Daniel Erat <dan@erat.org>
 * All rights reserved.
 */

package org.erat.nup

import org.json.JSONArray

// Make JSONArray iterable: https://stackoverflow.com/a/36188796/6882947
@Suppress("UNCHECKED_CAST")
operator fun <T> JSONArray.iterator(): Iterator<T> =
    (0 until length()).asSequence().map { get(it) as T }.iterator()
