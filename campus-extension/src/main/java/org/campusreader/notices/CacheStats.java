package org.campusreader.notices;

/** bodyBytes counts UTF-8 HTML plus its searchable plain text, excluding resource binaries. */
public final class CacheStats {
    public final long totalEntries,cachedBodies,bodyBytes,metadataOnlyEntries;
    CacheStats(long total,long cached,long bytes){totalEntries=total;cachedBodies=cached;bodyBytes=bytes;metadataOnlyEntries=total-cached;}
}
