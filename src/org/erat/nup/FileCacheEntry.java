// Copyright 2011 Daniel Erat <dan@erat.org>
// All rights reserved.

package org.erat.nup;

class FileCacheEntry {
    private final String mRemotePath, mLocalFilename, mETag;
    private final long mContentLength;

    public FileCacheEntry(String remotePath, String localFilename, long contentLength, String eTag) {
        mRemotePath = remotePath;
        mLocalFilename = localFilename;
        mETag = eTag;
        mContentLength = contentLength;
    }

    public String getRemotePath() { return mRemotePath; }
    public String getLocalFilename() { return mLocalFilename; }
    public long getContentLength() { return mContentLength; }
    public String getETag() { return mETag; }
}
