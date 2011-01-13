// Copyright 2011 Daniel Erat <dan@erat.org>
// All rights reserved.

package org.erat.nup;

class FileCacheEntry {
    private final int mId;
    private final String mRemotePath, mLocalFilename, mETag;
    private final long mContentLength;

    public FileCacheEntry(int id, String remotePath, String localFilename, long contentLength, String eTag) {
        mId = id;
        mRemotePath = remotePath;
        mLocalFilename = localFilename;
        mETag = eTag;
        mContentLength = contentLength;
    }

    public int getId() { return mId; }
    public String getRemotePath() { return mRemotePath; }
    public String getLocalFilename() { return mLocalFilename; }
    public long getContentLength() { return mContentLength; }
    public String getETag() { return mETag; }
}
