// Copyright 2011 Daniel Erat <dan@erat.org>
// All rights reserved.

package org.erat.nup;

import java.io.File;

class FileCacheEntry {
    private final String mMusicDir;
    private final long mSongId;
    private long mCachedBytes, mTotalBytes;
    private int mLastAccessTime;

    public FileCacheEntry(String musicDir, long songId, long totalBytes, int lastAccessTime) {
        mMusicDir = musicDir;
        mSongId = songId;
        mCachedBytes = 0;
        mTotalBytes = totalBytes;
        mLastAccessTime = lastAccessTime;
    }

    public long getSongId() {
        return mSongId;
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
        return new File(mMusicDir, mSongId + ".mp3");
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
