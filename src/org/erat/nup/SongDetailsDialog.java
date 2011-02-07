// Copyright 2011 Daniel Erat <dan@erat.org>
// All rights reserved.

package org.erat.nup;

import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.view.Window;
import android.widget.TextView;

// Static methods for activities that want to display dialogs showing
// the details of Song objects.
class SongDetailsDialog {
    private static final String BUNDLE_ARTIST = "artist";
    private static final String BUNDLE_TITLE = "title";
    private static final String BUNDLE_ALBUM = "album";
    private static final String BUNDLE_LENGTH_SEC = "length_sec";

    public static Bundle createBundle(Song song) {
        Bundle bundle = new Bundle();
        bundle.putString(BUNDLE_ARTIST, song.getArtist());
        bundle.putString(BUNDLE_TITLE, song.getTitle());
        bundle.putString(BUNDLE_ALBUM, song.getAlbum());
        bundle.putInt(BUNDLE_LENGTH_SEC, song.getLengthSec());
        return bundle;
    }

    public static Dialog createDialog(Context context) {
        Dialog dialog = new Dialog(context);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.song_details_dialog);
        return dialog;
    }

    public static void prepareDialog(Dialog dialog, Bundle bundle) {
        ((TextView) dialog.findViewById(R.id.artist)).setText(
            bundle.getString(BUNDLE_ARTIST));
        ((TextView) dialog.findViewById(R.id.title)).setText(
            bundle.getString(BUNDLE_TITLE));
        ((TextView) dialog.findViewById(R.id.album)).setText(
            bundle.getString(BUNDLE_ALBUM));
        ((TextView) dialog.findViewById(R.id.length)).setText(
            Util.formatDurationString(bundle.getInt(BUNDLE_LENGTH_SEC)));
    }
}
