package org.campusreader.reader;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;
import static org.junit.Assert.*;

/** No network is opened: validates the guard reused at queued Cookie write time. */
public class ReaderHttpCancellationTest {
    @Test public void cancellationBetweenRequestAndCookieCallbackStopsTheWrite()throws Exception{
        AtomicBoolean cancelled=new AtomicBoolean();
        ReaderHttp.RequestGuard guard=new ReaderHttp.RequestGuard("",false,Thread.currentThread(),cancelled::get);
        guard.check();cancelled.set(true);
        try{guard.check();fail("late cookie must not be accepted");}catch(IOException expected){assertEquals("Request cancelled",expected.getMessage());}
    }
    @Test public void mainCallbackChecksOriginatingWorkerRatherThanItsOwnThread()throws Exception{
        Thread worker=new Thread();worker.interrupt();
        ReaderHttp.RequestGuard guard=new ReaderHttp.RequestGuard("",false,worker,()->false);
        assertFalse(Thread.currentThread().isInterrupted());
        try{guard.check();fail("interrupted worker must not write through another thread");}catch(IOException expected){assertEquals("Request cancelled",expected.getMessage());}
    }
    @Test public void cancelledRequestDoesNotEvenParseOrOpenTheUrl()throws Exception{
        try{ReaderHttp.get("not-a-url",1,false,()->true);fail();}catch(IOException expected){assertEquals("Request cancelled",expected.getMessage());}
    }
}
