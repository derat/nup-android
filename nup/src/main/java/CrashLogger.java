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

    private static CrashLogger mSingleton;

    private final File mDirectory;
    private final Thread.UncaughtExceptionHandler mDefaultHandler;

    private CrashLogger(File directory) {
        mDirectory = directory;
        mDefaultHandler = Thread.getDefaultUncaughtExceptionHandler();
    }

    @Override
    public void uncaughtException(Thread thread, Throwable error) {
        try {
            mDirectory.mkdirs();
            SimpleDateFormat format = new SimpleDateFormat("yyyyMMdd-HHmmss");
            File file = new File(mDirectory, format.format(new Date()) + ".txt");
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

        mDefaultHandler.uncaughtException(thread, error);
    }

    public static void register(File directory) {
        if (mSingleton != null)
            return;

        mSingleton = new CrashLogger(directory);
        Thread.setDefaultUncaughtExceptionHandler(mSingleton);
    }

    public static void unregister() {
        if (mSingleton == null)
            return;

        Thread.setDefaultUncaughtExceptionHandler(null);
        mSingleton = null;
    }
}
