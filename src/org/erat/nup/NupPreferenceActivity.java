package org.erat.nup;

import android.preference.PreferenceActivity;
import android.os.Bundle;

public class NupPreferenceActivity extends PreferenceActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.preferences);
    }
}
