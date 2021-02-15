// Copyright 2014 Daniel Erat <dan@erat.org>
// All rights reserved.
package org.erat.nup

import android.content.Context
import android.os.AsyncTask
import android.os.Bundle
import android.preference.PreferenceManager
import android.util.Log
import com.google.android.gms.auth.GoogleAuthException
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.UserRecoverableNotifiedException
import java.io.IOException

// Utility class for doing Oauth2 authenticate to read from Google Cloud Storage on behalf of the
// configure account.
class Authenticator(private val context: Context) {
    class AuthException(reason: String?) : Exception(reason)

    @get:Throws(AuthException::class)
    val authToken: String
        get() {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val accountName = prefs.getString(NupPreferences.ACCOUNT, "")
            if (accountName!!.isEmpty()) throw AuthException("Account isn't set")
            var token: String?
            token = try {
                Log.d(TAG, "attempting to get token for $accountName")
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
            Log.d(TAG, "got token")
            return token
        }

    fun authenticateInBackground() {
        object : AsyncTask<Void?, Void?, String>() {
            protected override fun doInBackground(vararg args: Void?): String {
                return try {
                    authToken
                    "Authenticated successfully."
                } catch (e: AuthException) {
                    "Authentication failed: $e"
                }
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR)
    }

    companion object {
        private const val TAG = "Auth"
        private const val SCOPE = "oauth2:https://www.googleapis.com/auth/devstorage.read_only"
    }
}
