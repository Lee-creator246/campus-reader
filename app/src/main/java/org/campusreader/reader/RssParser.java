package org.campusreader.reader;

import org.campusreader.notices.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.jsoup.Jsoup;
import org.jsoup.nodes.*;
import org.jsoup.parser.Parser;
import org.jsoup.safety.Safelist;

/** Real RSS/Atom import. Cloud accounts are not synchronized. */
final class RssParser {
    static final class Feed {
        final String title; final List<CampusNotice> items;
        Feed(String title,List<CampusNotice> items){this.title=title;this.items=items;}
    }
    static String source(String url){return "rss:"+digest(url);}
    static String digest(String value) {
        try {byte[] hash=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));StringBuilder b=new StringBuilder();for(byte v:hash)b.append(String.format(Locale.ROOT,"%02x",v&255));return b.toString();}catch(Exception e){throw new IllegalStateException(e);}
    }
    static Feed parse(String sourceUrl,String finalUrl,String xml) throws Exception {
        if(xml.toUpperCase(Locale.ROOT).contains("<!DOCTYPE")||xml.toUpperCase(Locale.ROOT).contains("<!ENTITY"))throw new IllegalArgumentException("Unsupported feed declarations");
        Document doc=Jsoup.parse(xml,finalUrl,Parser.xmlParser());
        Element root=doc.children().first();
        if(root==null)throw new IllegalArgumentException("Empty feed");
        boolean atom="feed".equals(root.normalName());
        Element channel=atom?root:root.selectFirst("channel");
        if(channel==null||(!atom&&!"rss".equals(root.normalName())))throw new IllegalArgumentException("Not an RSS or Atom feed");
        String title=childText(channel,"title");if(title.isEmpty())title=new URI(sourceUrl).getHost();
        LinkedHashMap<NoticeKey,CampusNotice> unique=new LinkedHashMap<>();
        for(Element entry:channel.children()) {
            if(!(atom?"entry":"item").equals(entry.normalName()))continue;
            if(unique.size()>=300)break;
            String link="";
            if(atom) {for(Element e:entry.children()) if("link".equals(e.normalName())&&(!e.hasAttr("rel")||"alternate".equals(e.attr("rel")))) {link=e.attr("href");break;}}
            else link=childText(entry,"link");
            link=absolute(finalUrl,link);
            String id=childText(entry,atom?"id":"guid");
            if(id.isEmpty())id=link; // Stable canonical URL fallback, never title/date/content hash.
            if(id.isEmpty()||link.isEmpty())continue;
            String heading=childText(entry,"title");if(heading.isEmpty())continue;
            String content=childContent(entry,atom?"content":"content:encoded");
            if(content.isEmpty())content=childContent(entry,atom?"summary":"description");
            Element body=Jsoup.parseBodyFragment(content,link).body();
            List<String> images=new ArrayList<>();
            for(Element img:body.select("img")) {String src=absolute(link,img.attr("src"));if(src.isEmpty())img.remove();else{img.attr("src",src);if(!images.contains(src))images.add(src);}}
            for(Element anchor:body.select("a[href]")){String target=absolute(link,anchor.attr("href"));if(target.isEmpty())anchor.removeAttr("href");else anchor.attr("href",target);}
            String clean=Jsoup.clean(body.html(),link,Safelist.relaxed(),new Document.OutputSettings().prettyPrint(false));
            if(clean.trim().isEmpty())clean="<p>订阅未提供正文，请打开原文。</p>";
            String time=childText(entry,atom?"published":"pubDate");if(time.isEmpty()&&atom)time=childText(entry,"updated");
            NoticeKey key=NoticeKey.portalId(source(sourceUrl),id);
            unique.put(key,new CampusNotice(key,heading,date(time),clean,link,images,Collections.emptyList()));
        }
        return new Feed(title,new ArrayList<>(unique.values()));
    }
    private static String childText(Element e,String name){for(Element c:e.children())if(name.equals(c.tagName()))return c.text().trim();return "";}
    private static String childContent(Element e,String name){for(Element c:e.children())if(name.equals(c.tagName()))return "xhtml".equals(c.attr("type"))?c.html():c.text();return "";}
    private static String absolute(String base,String value){if(value==null||value.trim().isEmpty())return "";try{URI u=new URI(base).resolve(value.trim());return (u.getHost()!=null&&u.getUserInfo()==null&&("https".equalsIgnoreCase(u.getScheme())||"http".equalsIgnoreCase(u.getScheme())))?u.toString():"";}catch(Exception e){return "";}}
    private static long date(String value){try{return Instant.parse(value).toEpochMilli();}catch(Exception ignored){}try{return OffsetDateTime.parse(value).toInstant().toEpochMilli();}catch(Exception ignored){}try{return ZonedDateTime.parse(value,DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli();}catch(Exception ignored){}return 0;}
}
