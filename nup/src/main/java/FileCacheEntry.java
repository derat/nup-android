// Copyright 2011 Daniel Erat <dan@erat.org>
// All rights reserved.

package org.erat.nup;

import java.io.File;

class FileCacheEntry {
    public final long songId;
    private final String musicDir;
    private long cachedBytes, totalBytes;
    private int lastAccessTime;

    public FileCacheEntry(String musicDir, long songId, long totalBytes, int lastAccessTime) {
        this.musicDir = musicDir;
        this.songId = songId;
        this.cachedBytes = 0;
        this.totalBytes = totalBytes;
        this.lastAccessTime = lastAccessTime;
    }

    public long getCachedBytes() {
        return cachedBytes;
    }

    public long getTotalBytes() {
        return totalBytes;
    }

    public int getLastAccessTime() {
        return lastAccessTime;
    }

    public File getLocalFile() {
        return new File(musicDir, songId + ".mp3");
    }

    public void incrementCachedBytes(long bytes) {
        cachedBytes += bytes;
    }

    public void setCachedBytes(long bytes) {
        cachedBytes = bytes;
    }

    public void setTotalBytes(long bytes) {
        totalBytes = bytes;
    }

    public void setLastAccessTime(int time) {
        lastAccessTime = time;
    }

    public boolean isFullyCached() {
        return totalBytes > 0 && cachedBytes == totalBytes;
    }
}
