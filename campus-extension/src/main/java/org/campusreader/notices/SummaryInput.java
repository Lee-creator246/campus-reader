package org.campusreader.notices;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Plain cached source only: no resource URL, cookie, or reading flags. */
public final class SummaryInput {
    public final String title, bodyText, contentHash;
    public final long cacheGeneration;
    public SummaryInput(String title,String bodyText){this(title,bodyText,0);}
    public SummaryInput(String title,String bodyText,long cacheGeneration){
        if(title==null||bodyText==null)throw new IllegalArgumentException("Missing source text");
        this.title=title;this.bodyText=bodyText;this.cacheGeneration=cacheGeneration;this.contentHash=hash(title+"\u0000"+bodyText);
    }
    public static String hash(String value){
        try{byte[] digest=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));StringBuilder hex=new StringBuilder();for(byte b:digest)hex.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return hex.toString();}
        catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
    }
}
