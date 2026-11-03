package org.campusreader.reader;

import android.app.Application;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.*;
import okio.Buffer;
import okio.Timeout;
import static org.junit.Assert.*;

/** Every test injects a fake HTTPS Call.Factory. No listening socket or real HTTP request exists. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class,manifest=Config.NONE)
public class AiTransportTest {
    private static final String SOURCE="标题：测试通知\n\n正文：\n11月5日截止报名。";
    private AiSettings.Config config(){return new AiSettings.Config(true,"https://api.example.org/v1/chat/completions","test-model","test-api-key",1);}
    private String envelope()throws Exception{return new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("finish_reason","stop").put("message",new JSONObject().put("content",AiSummaryModelTest.empty())))).toString();}
    private static class FakeCall implements Call {
        final Request request;final int status;final String json;boolean executed;volatile boolean cancelled;
        final CountDownLatch entered=new CountDownLatch(1),released=new CountDownLatch(1);boolean block;IOException failure;
        FakeCall(Request request,int status,String json){this.request=request;this.status=status;this.json=json;}
        @Override public Request request(){return request;}
        @Override public Response execute()throws IOException{
            executed=true;entered.countDown();if(block)try{released.await(3,TimeUnit.SECONDS);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IOException("CANCELLED");}
            if(cancelled)throw new IOException("CANCELLED");if(failure!=null)throw failure;
            return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("synthetic").body(ResponseBody.create(MediaType.parse("application/json"),json)).build();
        }
        @Override public void enqueue(Callback callback){throw new UnsupportedOperationException("Synchronous fake only");}
        @Override public void cancel(){cancelled=true;released.countDown();}
        @Override public boolean isExecuted(){return executed;}
        @Override public boolean isCanceled(){return cancelled;}
        @Override public Timeout timeout(){return new Timeout();}
        @Override public Call clone(){return new FakeCall(request,status,json);}
    }
    @Test public void isolatedClientCannotReadJvmCampusCookiesOrDefaultAuth()throws Exception{
        CookieHandler previous=CookieHandler.getDefault();AtomicInteger cookieReads=new AtomicInteger();CookieHandler.setDefault(new CookieHandler(){
            @Override public Map<String,List<String>> get(URI uri,Map<String,List<String>> headers){cookieReads.incrementAndGet();return Collections.singletonMap("Cookie",Collections.singletonList("campus-secret"));}
            @Override public void put(URI uri,Map<String,List<String>> headers){}
        });
        try{
            assertSame(CookieJar.NO_COOKIES,AiTransport.client().cookieJar());assertSame(okhttp3.Authenticator.NONE,AiTransport.client().authenticator());assertSame(okhttp3.Authenticator.NONE,AiTransport.client().proxyAuthenticator());
            assertFalse(AiTransport.client().followRedirects());assertFalse(AiTransport.client().followSslRedirects());assertFalse(AiTransport.client().retryOnConnectionFailure());assertEquals(40000,AiTransport.client().callTimeoutMillis());
            final Request[] captured=new Request[1];String response=envelope();
            AiTransport.generate(config(),SOURCE,new AiTransport.Cancellation(),()->{},request->{captured[0]=request;return new FakeCall(request,200,response);});
            assertNull(captured[0].header("Cookie"));assertNull(captured[0].header("Referer"));assertEquals("Bearer test-api-key",captured[0].header("Authorization"));assertEquals(0,cookieReads.get());
            Buffer buffer=new Buffer();captured[0].body().writeTo(buffer);JSONObject body=new JSONObject(buffer.readUtf8());assertEquals(SOURCE,body.getJSONArray("messages").getJSONObject(1).getString("content"));assertFalse(body.toString().contains("test-api-key"));assertFalse(body.toString().contains("campus-secret"));assertEquals(1000,body.getInt("max_tokens"));
        }finally{CookieHandler.setDefault(previous);}
    }
    @Test public void redirectAndRateLimitProduceExactlyOneAttemptWithoutFollowup()throws Exception{
        for(int status:new int[]{307,429,503}){AtomicInteger calls=new AtomicInteger();try{AiTransport.generate(config(),SOURCE,new AiTransport.Cancellation(),()->{},request->{calls.incrementAndGet();return new FakeCall(request,status,"{}");});fail();}catch(IOException expected){}assertEquals(1,calls.get());}
    }
    @Test public void cancelBeforeRequestAndStaleGuardNeverCreateNetworkCall()throws Exception{
        AtomicInteger calls=new AtomicInteger();Call.Factory factory=request->{calls.incrementAndGet();return new FakeCall(request,200,"{}");};AiTransport.Cancellation cancel=new AiTransport.Cancellation();cancel.cancel();
        try{AiTransport.generate(config(),SOURCE,cancel,()->{},factory);fail();}catch(IOException expected){assertEquals("CANCELLED",expected.getMessage());}
        try{AiTransport.generate(config(),SOURCE,new AiTransport.Cancellation(),()->{throw new IOException("STALE");},factory);fail();}catch(IOException expected){assertEquals("STALE",expected.getMessage());}assertEquals(0,calls.get());
    }
    @Test public void cancellingInFlightRequestCancelsUnderlyingCall()throws Exception{
        AiTransport.Cancellation cancellation=new AiTransport.Cancellation();FakeCall fake=new FakeCall(new Request.Builder().url(config().endpoint).build(),200,envelope());fake.block=true;
        ExecutorService worker=Executors.newSingleThreadExecutor();try{Future<?> result=worker.submit(()->{try{AiTransport.generate(config(),SOURCE,cancellation,()->{},request->fake);throw new AssertionError("Cancelled call returned summary");}catch(IOException expected){assertEquals("CANCELLED",expected.getMessage());}catch(Exception unexpected){throw new AssertionError(unexpected);}});
            assertTrue(fake.entered.await(2,TimeUnit.SECONDS));cancellation.cancel();result.get(2,TimeUnit.SECONDS);assertTrue(fake.cancelled);
        }finally{worker.shutdownNow();}
    }
    @Test public void timeoutAndResponseLimitsFailWithoutRetry()throws Exception{
        AtomicInteger calls=new AtomicInteger();try{AiTransport.generate(config(),SOURCE,new AiTransport.Cancellation(),()->{},request->{calls.incrementAndGet();FakeCall fake=new FakeCall(request,200,"{}");fake.failure=new SocketTimeoutException("synthetic timeout");return fake;});fail();}catch(SocketTimeoutException expected){assertTrue(AiTransport.failureMessage(expected).contains("超时"));}assertEquals(1,calls.get());
        char[] huge=new char[AiPolicy.MAX_RESPONSE_BYTES+1];Arrays.fill(huge,'x');try{AiTransport.generate(config(),SOURCE,new AiTransport.Cancellation(),()->{},request->new FakeCall(request,200,new String(huge)));fail();}catch(IOException expected){assertEquals("RESPONSE_LIMIT",expected.getMessage());}
    }
}
