// Copyright 2011 Daniel Erat <dan@erat.org>
// All rights reserved.

package org.erat.nup;

import android.content.Context;
import android.preference.DialogPreference;
import android.util.AttributeSet;

class YesNoPreference extends DialogPreference {
    Context mContext;

    public YesNoPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        mContext = context;
    }
    public YesNoPreference(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        mContext = context;
    }

    @Override
    protected void onDialogClosed(boolean positiveResult) {
        super.onDialogClosed(positiveResult);
        if (positiveResult) {
            if (NupPreferences.CLEAR_CACHE.equals(getKey())) {
                // FIXME: shouldn't do this on the UI thread
                NupActivity.getService().clearCache();
                setSummary(mContext.getString(R.string.cache_is_empty));
            }
        }
    }
}
