// Copyright 2011 Daniel Erat <dan@erat.org>
// All rights reserved.

package org.erat.nup;

import android.util.Log;

import java.io.File;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;

public class CrashLogger implements Thread.UncaughtExceptionHandler {
    private static final String TAG = "CrashLogger";

    private static CrashLogger singleton;

    private final File dir;
    private final Thread.UncaughtExceptionHandler defaultHandler;

    private CrashLogger(File dir) {
        this.dir = dir;
        this.defaultHandler = Thread.getDefaultUncaughtExceptionHandler();
    }

    @Override
    public void uncaughtException(Thread thread, Throwable error) {
        try {
            dir.mkdirs();
            SimpleDateFormat format = new SimpleDateFormat("yyyyMMdd-HHmmss");
            File file = new File(dir, format.format(new Date()) + ".txt");
            Log.d(TAG, "creating crash file " + file.getAbsolutePath());
            file.createNewFile();

            PrintWriter writer = new PrintWriter(file);
            error.printStackTrace(writer);

            Throwable cause = error.getCause();
            if (cause != null) {
                writer.print("\n\nCaused by:\n");
                cause.printStackTrace(writer);
            }
            writer.close();
        } catch (java.io.FileNotFoundException e) {
            Log.e(TAG, "file not found: " + e);
        } catch (java.io.IOException e) {
            Log.e(TAG, "IO error: " + e);
        }

        defaultHandler.uncaughtException(thread, error);
    }

    public static void register(File dir) {
        if (singleton != null) return;

        singleton = new CrashLogger(dir);
        Thread.setDefaultUncaughtExceptionHandler(singleton);
    }

    public static void unregister() {
        if (singleton == null) return;

        Thread.setDefaultUncaughtExceptionHandler(null);
        singleton = null;
    }
}
