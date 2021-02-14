package org.erat.nup.test

import org.mockito.Mockito

// Use this instead of Matchers to avoid NPEs under Kotlin.
// See http://derekwilson.net/blog/2018/08/23/mokito-kotlin.
object MockitoHelper {
    fun <T> anyObject(): T {
        Mockito.any<T>()
        return uninitialized()
    }
    @Suppress("UNCHECKED_CAST")
    fun <T> uninitialized(): T = null as T
}
