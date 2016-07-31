// Copyright 2014 Daniel Erat <dan@erat.org>
// All rights reserved.

package org.erat.nup;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.AsyncTask;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.util.Log;
import android.widget.Toast;

import com.google.android.gms.auth.GoogleAuthException;
import com.google.android.gms.auth.GoogleAuthUtil;
import com.google.android.gms.auth.UserRecoverableNotifiedException;

import java.io.IOException;

// Utility class for doing Oauth2 authenticate to read from Google Cloud Storage on behalf of the configure account.
class Authenticator {
    private static final String TAG = "Auth";

    private static final String SCOPE = "oauth2:https://www.googleapis.com/auth/devstorage.read_only";

    public static class AuthException extends Exception {
        public AuthException(String reason) {
            super(reason);
        }
    }

    private final Context mContext;

    public Authenticator(Context context) {
        mContext = context;
    }

    public String getAuthToken() throws AuthException {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(mContext);
        String accountName = prefs.getString(NupPreferences.ACCOUNT, "");
        if (accountName.isEmpty())
            throw new AuthException("Account isn't set");

        String token = null;
        try {
            Log.d(TAG, "attempting to get token for " + accountName);
            Bundle bundle = new Bundle();
            token = GoogleAuthUtil.getTokenWithNotification(mContext, accountName, SCOPE, bundle);
        } catch (UserRecoverableNotifiedException e) {
            throw new AuthException("User action required");
        } catch (GoogleAuthException e) {
            throw new AuthException("Authentication failed: " + e);
        } catch (IOException e) {
            throw new AuthException("IO error: " + e);
        }

        if (token == null)
            throw new AuthException("Didn't receive auth token");

        Log.d(TAG, "got token");
        return token;
    }

    public void authenticateInBackground() {
        new AsyncTask<Void, Void, String>() {
            @Override protected String doInBackground(Void... args) {
                try {
                    getAuthToken();
                    return "Authenticated successfully.";
                } catch (AuthException e) {
                    return "Authentication failed: " + e;
                }
            }

            @Override protected void onPostExecute(String message) {
                Toast.makeText(mContext, message, Toast.LENGTH_SHORT).show();
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }
}
