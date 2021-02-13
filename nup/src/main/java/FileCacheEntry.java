// Copyright 2011 Daniel Erat <dan@erat.org>
// All rights reserved.

package org.erat.nup;

import java.io.File;

class FileCacheEntry {
    public final long songId;
    private final String mMusicDir;
    private long mCachedBytes, mTotalBytes;
    private int mLastAccessTime;

    public FileCacheEntry(String musicDir, long songId, long totalBytes, int lastAccessTime) {
        this.mMusicDir = musicDir;
        this.songId = songId;
        this.mCachedBytes = 0;
        this.mTotalBytes = totalBytes;
        this.mLastAccessTime = lastAccessTime;
    }

    public long getCachedBytes() {
        return mCachedBytes;
    }

    public long getTotalBytes() {
        return mTotalBytes;
    }

    public int getLastAccessTime() {
        return mLastAccessTime;
    }

    public File getLocalFile() {
        return new File(mMusicDir, songId + ".mp3");
    }

    public void incrementCachedBytes(long bytes) {
        mCachedBytes += bytes;
    }

    public void setCachedBytes(long bytes) {
        mCachedBytes = bytes;
    }

    public void setTotalBytes(long bytes) {
        mTotalBytes = bytes;
    }

    public void setLastAccessTime(int time) {
        mLastAccessTime = time;
    }

    public boolean isFullyCached() {
        return mTotalBytes > 0 && mCachedBytes == mTotalBytes;
    }
}
