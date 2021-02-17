/*
 * Copyright 2014 Daniel Erat <dan@erat.org>
 * All rights reserved.
 */

package org.erat.nup

import android.content.Context
import android.os.Bundle
import android.preference.PreferenceManager
import android.util.Log
import android.widget.Toast
import com.google.android.gms.auth.GoogleAuthException
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.UserRecoverableNotifiedException
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async

/** Does OAuth2 authentication on behalf of the configured account. */
class Authenticator(private val context: Context) {
    class AuthException(reason: String) : Exception(reason)

    /** Synchronously get an auth token for Google Cloud Storage. */
    // TODO: Mark this as 'suspend' after callers have been updated.
    @get:Throws(AuthException::class)
    val authToken: String
        get() {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val accountName = prefs.getString(NupPreferences.ACCOUNT, "")!!
            if (accountName.isEmpty()) throw AuthException("Account isn't set")
            var token = try {
                Log.d(TAG, "Attempting to get token for $accountName")
                val bundle = Bundle()
                GoogleAuthUtil.getTokenWithNotification(context, accountName, SCOPE, bundle)
            } catch (e: UserRecoverableNotifiedException) {
                throw AuthException("User action required")
            } catch (e: GoogleAuthException) {
                throw AuthException("Authentication failed: $e")
            } catch (e: IOException) {
                throw AuthException("IO error: $e")
            }
            if (token == null) throw AuthException("Didn't receive auth token")
            Log.d(TAG, "Got token")
            return token
        }

    /** Asynchronously get an auth token and display a toast. */
    fun authenticateInBackground() = GlobalScope.async(Dispatchers.Main) {
        val msg = async(Dispatchers.IO) {
            try {
                authToken
                "Authenticated successfully."
            } catch (e: AuthException) {
                "Authentication failed: $e"
            }
        }.await()
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val TAG = "Auth"
        private const val SCOPE = "oauth2:https://www.googleapis.com/auth/devstorage.read_only"
    }
}
