package org.campusreader.reader;

import java.util.concurrent.atomic.AtomicBoolean;

/** One in-process portal request chain at a time, shared by foreground and JobService. */
public final class SyncGate {
    private static final AtomicBoolean BUSY=new AtomicBoolean();
    private SyncGate(){}
    public static boolean isBusy(){return BUSY.get();}
    public static int intervalMinutes(int minutes){return Math.max(15,Math.min(7*24*60,minutes));}
    public static Lease tryAcquire(String profile){
        if(profile==null||profile.trim().isEmpty())throw new IllegalArgumentException("Missing profile");
        return BUSY.compareAndSet(false,true)?new Lease():null;
    }
    public static final class Lease implements AutoCloseable {
        private final AtomicBoolean held=new AtomicBoolean(true);
        private Lease(){}
        @Override public void close(){if(held.compareAndSet(true,false))BUSY.set(false);}
    }
}
