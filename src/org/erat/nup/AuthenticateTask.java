// Copyright 2014 Daniel Erat <dan@erat.org>
// All rights reserved.

package org.erat.nup;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.accounts.AccountManagerFuture;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.AsyncTask;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.widget.Toast;

import java.io.IOException;
import java.net.URI;

// Authenticates in the background.
//
// This code is heavily based on
// http://devblog.consileon.pl/2014/01/14/Android-authentication-against-Google-App-Engine/.
class AuthenticateTask extends AsyncTask<Void, Void, String> {
    Context mContext;

    public AuthenticateTask(Context context) {
        mContext = context;
    }

    @Override
    protected String doInBackground(Void... args) {
        Account account = getAccount();
        if (account == null)
            return "Failed to get account for authentication.";

        String token = getAuthToken(account);
        if (token == null || token.isEmpty())
            return "Failed to get authentication token.";

        try {
            URI uri = DownloadRequest.getServerUri(mContext, "/_ah/login", "continue=http://localhost/&auth=" + token);
            DownloadRequest request = new DownloadRequest(uri, DownloadRequest.Method.GET);
            DownloadResult result = Download.startDownload(request);
            if (result.getStatusCode() != 200 && result.getStatusCode() != 302) {
                return "Server returned " + result.getStatusCode() + " while authenticating.";
            }
        } catch (DownloadRequest.PrefException e) {
            return "Pref error while authenticating: " + e.getMessage();
        } catch (IOException e) {
            return "IO error while authenticating: " + e.getMessage();
        } catch (org.apache.http.HttpException e) {
            return "HTTP error while authenticating: " + e.getMessage();
        }

        return "Authenticated successfully.";
    }

    @Override
    protected void onPostExecute(String message) {
        Toast.makeText(mContext, message, Toast.LENGTH_SHORT).show();
    }

    private Account getAccount() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(mContext);
        String accountName = prefs.getString(NupPreferences.ACCOUNT, "");
        if (accountName.isEmpty())
            return null;

        AccountManager manager = AccountManager.get(mContext);
        Account[] accounts = manager.getAccountsByType("com.google");
        if (accounts == null)
            return null;

        for (Account account : accounts) {
            if (account.name.equals(accountName)) {
                return account;
            }
        }
        return null;
    }

    private String getAuthToken(Account account) {
        AccountManager manager = AccountManager.get(mContext);
        AccountManagerFuture<Bundle> future = manager.getAuthToken(account, "ah", false, null, null);
        Bundle bundle;
        try {
            bundle = future.getResult();
        } catch (Exception e) {
            return "";
        }
        Intent intent = (Intent) bundle.get(AccountManager.KEY_INTENT);
        if (intent != null) {
            // FIXME: Need to wait for result here.
            mContext.startActivity(intent);
        }
        return bundle.getString(AccountManager.KEY_AUTHTOKEN);
    }
}
