// Copyright 2011 Daniel Erat <dan@erat.org>
// All rights reserved.

package org.erat.nup;

class FileCacheEntry {
    private final int mId;
    private final String mRemotePath, mLocalPath;
    private long mCachedBytes, mTotalBytes;

    public FileCacheEntry(int id, String remotePath, String localPath, long totalBytes) {
        mId = id;
        mRemotePath = remotePath;
        mLocalPath = localPath;
        mCachedBytes = 0;
        mTotalBytes = totalBytes;
    }

    public int getId() { return mId; }
    public String getRemotePath() { return mRemotePath; }
    public String getLocalPath() { return mLocalPath; }
    public long getCachedBytes() { return mCachedBytes; }
    public long getTotalBytes() { return mTotalBytes; }

    public void incrementCachedBytes(long bytes) { mCachedBytes += bytes; }
    public void setCachedBytes(long bytes) { mCachedBytes = bytes; }
    public void setTotalBytes(long bytes) { mTotalBytes = bytes; }

    public boolean isFullyCached() {
        return mTotalBytes > 0 && mCachedBytes == mTotalBytes;
    }
}
