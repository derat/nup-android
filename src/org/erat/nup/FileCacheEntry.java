// Copyright 2011 Daniel Erat <dan@erat.org>
// All rights reserved.

package org.erat.nup;

class FileCacheEntry {
    private final int mId;
    private final String mRemotePath, mLocalPath;
    private long mContentLength;
    private final String mETag;

    public FileCacheEntry(int id, String remotePath, String localPath, long contentLength, String eTag) {
        mId = id;
        mRemotePath = remotePath;
        mLocalPath = localPath;
        mContentLength = contentLength;
        mETag = eTag;
    }

    public int getId() { return mId; }
    public String getRemotePath() { return mRemotePath; }
    public String getLocalPath() { return mLocalPath; }
    public long getContentLength() { return mContentLength; }
    public String getETag() { return mETag; }

    public void setContentLength(long length) { mContentLength = length; }
}
