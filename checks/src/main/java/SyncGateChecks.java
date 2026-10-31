package org.campusreader.reader;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/** Real foreground/background lock contention and duplicate-close regression, no Android stubs. */
public final class SyncGateChecks {
    private static int assertions;
    private static void check(boolean value,String name){assertions++;if(!value)throw new AssertionError(name);}
    public static void main(String[] args)throws Exception{
        check(SyncGate.intervalMinutes(0)==15,"No sub-15-minute jobs");
        check(SyncGate.intervalMinutes(60)==60,"One-hour preference preserved");
        check(SyncGate.intervalMinutes(Integer.MAX_VALUE)==10080,"Untrusted huge interval bounded");
        check(!SyncGate.isBusy(),"No lease initially");
        SyncGate.Lease foreground=SyncGate.tryAcquire("account-A");check(foreground!=null,"Foreground acquires");
        check(SyncGate.isBusy(),"Observation follows acquired lease");
        check(SyncGate.tryAcquire("account-A")==null,"Same-account background cannot duplicate");
        check(SyncGate.tryAcquire("account-B")==null,"Account switch shares cookie-sensitive gate");
        foreground.close();check(!SyncGate.isBusy(),"Observation clears when lease closes");SyncGate.Lease next=SyncGate.tryAcquire("account-B");check(next!=null,"Close releases");
        foreground.close();check(SyncGate.tryAcquire("account-C")==null,"Duplicate old close cannot release new owner");next.close();
        CountDownLatch start=new CountDownLatch(1),attempted=new CountDownLatch(16),release=new CountDownLatch(1);
        AtomicInteger winners=new AtomicInteger();Thread[] threads=new Thread[16];
        for(int i=0;i<threads.length;i++){threads[i]=new Thread(()->{try{start.await();SyncGate.Lease held=SyncGate.tryAcquire("account-A");if(held!=null)winners.incrementAndGet();attempted.countDown();release.await();if(held!=null)held.close();}catch(InterruptedException error){throw new AssertionError(error);}});threads[i].start();}
        start.countDown();attempted.await();check(winners.get()==1,"16 racing refreshes have exactly one winner");check(SyncGate.isBusy(),"Observed busy during concurrent winning lease");release.countDown();for(Thread thread:threads)thread.join();check(!SyncGate.isBusy(),"Observed idle after concurrent work");
        try(SyncGate.Lease held=SyncGate.tryAcquire("account-A")){check(held!=null,"Gate reusable after concurrent tasks finish");}
        boolean rejected=false;try{SyncGate.tryAcquire(" ");}catch(IllegalArgumentException expected){rejected=true;}check(rejected,"Missing profile rejected");
        System.out.println("Sync gate checks passed: "+assertions);
    }
}
