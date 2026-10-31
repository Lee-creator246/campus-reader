package org.campusreader.notices;

/** Persisted per account. Zero count/bytes disables body caching; -1 age means capacity only. */
public final class RetentionPolicy {
    public static final RetentionPolicy DEFAULT=new RetentionPolicy(300,20L*1024*1024,24L*60*60*1000);
    public final int maxCachedEntries;
    public final long maxBodyBytes,readRetentionMillis;
    public RetentionPolicy(int maxCachedEntries,long maxBodyBytes){this(maxCachedEntries,maxBodyBytes,86400000L);}
    public RetentionPolicy(int maxCachedEntries,long maxBodyBytes,long readRetentionMillis){
        if(maxCachedEntries<0||maxBodyBytes<0||readRetentionMillis< -1)throw new IllegalArgumentException("Invalid retention policy");
        this.maxCachedEntries=maxCachedEntries;this.maxBodyBytes=maxBodyBytes;this.readRetentionMillis=readRetentionMillis;
    }
}
