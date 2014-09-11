// Copyright 2011 Daniel Erat <dan@erat.org>
// All rights reserved.

package org.erat.nup;

import android.content.Context;

import java.io.File;

class FileCacheEntry {
    private final long mSongId;
    private long mCachedBytes, mTotalBytes;
    private int mLastAccessTime;

    public FileCacheEntry(long songId, long totalBytes, int lastAccessTime) {
        mSongId = songId;
        mCachedBytes = 0;
        mTotalBytes = totalBytes;
        mLastAccessTime = lastAccessTime;
    }

    public long getSongId() { return mSongId; }
    public long getCachedBytes() { return mCachedBytes; }
    public long getTotalBytes() { return mTotalBytes; }
    public int getLastAccessTime() { return mLastAccessTime; }

    public File getLocalFile(Context context) {
        return new File(FileCache.getMusicDir(context), mSongId + ".mp3");
    }

    public void incrementCachedBytes(long bytes) { mCachedBytes += bytes; }
    public void setCachedBytes(long bytes) { mCachedBytes = bytes; }
    public void setTotalBytes(long bytes) { mTotalBytes = bytes; }
    public void setLastAccessTime(int time) { mLastAccessTime = time; }

    public boolean isFullyCached() {
        return mTotalBytes > 0 && mCachedBytes == mTotalBytes;
    }
}
