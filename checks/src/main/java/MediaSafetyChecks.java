package org.campusreader.reader;

import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

/** Standalone host-JVM adversarial streaming tests. No Android classes or production accounts. */
public final class MediaSafetyChecks {
    private static int checks;
    private interface Checked { void run() throws Exception; }
    private static void rejects(Checked action) throws Exception {
        try { action.run(); } catch(IOException expected) { checks++;return; }
        throw new AssertionError("Invalid transfer was accepted");
    }
    private static void check(boolean value){if(!value)throw new AssertionError();checks++;}
    public static void main(String[] args) throws Exception {
        String campus="campus:a665a45920422f9d417e4867efdc4fb8a04a1f3fff1fa07e998e86f7f7a27ae3";
        check(MediaSafety.readerProfile("123").equals(campus));
        check(MediaSafety.readerProfile(" 123 ").equals(campus));
        check(MediaSafety.readerProfile(null).equals("local"));
        check(MediaSafety.readerProfile("").equals("local"));
        check(MediaSafety.readerProfile(" \t ").equals("local"));
        check(!MediaSafety.readerProfile("123").equals("123"));
        check(!MediaSafety.readerProfile("123").equals(MediaSafety.readerProfile("456")));
        check(MediaSafety.failureCode(new java.net.SocketTimeoutException("https://private.invalid/?secret=never-log")).equals("TIMEOUT"));
        check(MediaSafety.failureCode(new MediaSafety.Rejected("SIZE_LIMIT")).equals("SIZE_LIMIT"));
        check(MediaSafety.failureCode(new MediaSafety.Rejected("LOGIN_OR_HTML")).equals("LOGIN_OR_HTML"));
        check(MediaSafety.failureCode(new MediaSafety.Rejected("HTTP_403")).equals("HTTP_403"));
        check(MediaSafety.failureCode(new java.net.UnknownHostException("never-log-host")).equals("DNS"));
        check(MediaSafety.failureCode(new IOException("never-log-credential")).equals("IO"));
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        AtomicInteger requests=new AtomicInteger();
        server.createContext("/chunked",exchange->{requests.incrementAndGet();exchange.sendResponseHeaders(200,0);try(OutputStream output=exchange.getResponseBody()){output.write(new byte[8192]);}});
        server.createContext("/short",exchange->{requests.incrementAndGet();exchange.sendResponseHeaders(200,9);try(OutputStream output=exchange.getResponseBody()){output.write(new byte[3]);}catch(IOException ignored){}});
        server.createContext("/okay",exchange->{requests.incrementAndGet();exchange.sendResponseHeaders(200,4);try(OutputStream output=exchange.getResponseBody()){output.write(new byte[4]);}});
        server.start();
        String root="http://127.0.0.1:"+server.getAddress().getPort();
        try {
            URLConnection chunked=new URL(root+"/chunked").openConnection();chunked.setReadTimeout(1500);
            try(InputStream input=chunked.getInputStream()) { rejects(()->MediaSafety.copyBounded(input,new ByteArrayOutputStream(),4096,-1)); }
            URLConnection shortFile=new URL(root+"/short").openConnection();shortFile.setReadTimeout(1500);
            try(InputStream input=shortFile.getInputStream()) { rejects(()->MediaSafety.copyBounded(input,new ByteArrayOutputStream(),4096,9)); }
            URLConnection okay=new URL(root+"/okay").openConnection();
            try(InputStream input=okay.getInputStream()) { check(MediaSafety.copyBounded(input,new ByteArrayOutputStream(),4,4)==4); }
            rejects(()->MediaSafety.copyBounded(new ByteArrayInputStream(new byte[0]),new ByteArrayOutputStream(),4,5));
            check(requests.get()==3);
            URL campusUrl=new URL("https://portal.campus.example.edu/file");
            rejects(()->MediaSafety.redirect(campusUrl,campusUrl,"https://evil.example/file",true));
            rejects(()->MediaSafety.redirect(campusUrl,campusUrl,"http://portal.campus.example.edu/file",true));
            rejects(()->MediaSafety.checked("https://portal.campus.example.edu.evil.example/file",true));
            rejects(()->MediaSafety.checked("https://user:secret@portal.campus.example.edu/file",true));
            rejects(()->MediaSafety.checked("https://example.com:8080/file",false));
            URL publicOrigin=new URL("https://images.example.com/a");
            rejects(()->MediaSafety.redirect(publicOrigin,publicOrigin,"https://other.example.com/b",false));
            check(MediaSafety.redirect(campusUrl,campusUrl,"/attachments/a.pdf",true).getHost().equals("portal.campus.example.edu"));
            check(MediaSafety.looksHtml("application/octet-stream","<!doctype html><html>login</html>".getBytes(StandardCharsets.UTF_8)));
            check(MediaSafety.looksHtml("text/html",new byte[]{1,2}));
            check(!MediaSafety.looksHtml("application/pdf","%PDF-1.7".getBytes(StandardCharsets.UTF_8)));
            check(MediaSafety.filename("attachment; filename*=UTF-8''%E9%99%84%E4%BB%B6.pdf","bad").equals("附件.pdf"));
            check(!MediaSafety.filename("attachment; filename=\"../../escape.pdf\"","bad").contains("/"));
            check(!MediaSafety.filename(null,"\r\n../../evil").contains("\n"));
            System.out.println("PASS: "+checks+" media profile, streaming, redirect, filename and HTML rejection checks");
        } finally { server.stop(0); }
    }
}
