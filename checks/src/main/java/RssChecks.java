package org.campusreader.reader;

public final class RssChecks {
    private static int checks;
    private static void check(boolean condition,String name){checks++;if(!condition)throw new AssertionError(name);}
    private static final String URL="https://example.org/feed.xml";
    public static void main(String[] args)throws Exception {
        String item="<item><guid>stable-one</guid><title>Example</title><link>https://example.org/posts/1</link><pubDate>Fri, 30 Oct 2026 08:00:00 GMT</pubDate><description><![CDATA[<p>Example body</p><img src='/image.png' onerror='bad()'><script>bad()</script>]]></description></item>";
        RssParser.Feed rss=RssParser.parse(URL,URL,"<rss version='2.0'><channel><title>Example feed</title>"+item+item+"</channel></rss>");
        check(rss.title.equals("Example feed"),"Feed title");
        check(rss.items.size()==1,"Duplicate GUID collapses");
        check(rss.items.get(0).key.stableId.equals("id:stable-one"),"GUID identity");
        check(rss.items.get(0).imageUrls.get(0).equals("https://example.org/image.png"),"Resolve body image");
        check(!rss.items.get(0).bodyHtml.contains("onerror")&&!rss.items.get(0).bodyHtml.contains("script"),"Sanitize active content");
        check(rss.items.get(0).publishedAtMillis>0,"RSS publication date");
        RssParser.Feed atom=RssParser.parse(URL,URL,"<feed xmlns='http://www.w3.org/2005/Atom'><title>Atom</title><entry><id>tag:example.org,2026:one</id><title>Atom example</title><link rel='alternate' href='/posts/2'/><published>2026-10-30T10:00:00+08:00</published><content type='html'>&lt;p&gt;Atom body&lt;/p&gt;</content></entry></feed>");
        check(atom.items.size()==1,"Parse Atom");
        check(atom.items.get(0).originalUrl.equals("https://example.org/posts/2"),"Resolve Atom link");
        check(atom.items.get(0).bodyHtml.contains("<p>Atom body</p>"),"Preserve Atom HTML");
        check(atom.items.get(0).publishedAtMillis>0,"Atom offset date");
        RssParser.Feed fallback=RssParser.parse(URL,URL,"<rss><channel><item><title>No ID</title><link>https://example.org/stable</link></item><item><title>No link</title></item></channel></rss>");
        check(fallback.items.size()==1,"Missing stable identity skipped");
        check(fallback.items.get(0).key.stableId.equals("id:https://example.org/stable"),"URL identity fallback");
        check(fallback.items.get(0).publishedAtMillis==0,"Missing date uses stable unknown date");
        check(!RssParser.source(URL).equals(RssParser.source("https://other.example/feed")),"Feed namespace isolation");
        boolean rejected=false;try{RssParser.parse(URL,URL,"<html><h1>Login</h1></html>");}catch(IllegalArgumentException e){rejected=true;}check(rejected,"HTML is not feed success");
        rejected=false;try{RssParser.parse(URL,URL,"<!DOCTYPE rss [<!ENTITY secret SYSTEM 'file:///private'>]><rss><channel/></rss>");}catch(IllegalArgumentException e){rejected=true;}check(rejected,"Reject XML entity declarations");
        System.out.println("RSS/Atom checks passed: "+checks);
    }
}
