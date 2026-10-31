package org.campusreader.portal;

import org.campusreader.config.CampusSite;
import org.campusreader.notices.CampusNotice;
import org.campusreader.notices.NoticeKey;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.*;
import org.jsoup.Jsoup;
import org.jsoup.nodes.*;
import org.jsoup.parser.Parser;
import org.jsoup.safety.Safelist;

/** Parses the configured mobile portal structure. No network or auth side effects. */
public final class CampusPortalParser {
    public static final String SOURCE = "campus:portal:notices";
    public enum Failure { REAUTH_REQUIRED, ACCESS_DENIED, HTTP_ERROR, UNKNOWN_STRUCTURE, INVALID_IDENTITY }
    public static final class ParseException extends Exception {
        public final Failure failure;
        ParseException(Failure failure, String message) { super(message); this.failure = failure; }
    }
    public static final class Summary {
        public final NoticeKey key;
        public final String title, originalUrl, treeId;
        public final long publishedAtMillis;
        Summary(Identity id, String title, long date) {
            this.key=id.key; this.originalUrl=id.url; this.treeId=id.treeId;
            this.title=title; this.publishedAtMillis=date;
        }
    }
    public static final class Detail {
        public final CampusNotice notice;
        public final String treeId;
        Detail(CampusNotice notice, String treeId) { this.notice=notice; this.treeId=treeId; }
    }
    public static final class Page {
        public final List<Summary> notices;
        public final MoreRequest next;
        Page(List<Summary> notices, MoreRequest next) {
            this.notices=Collections.unmodifiableList(notices); this.next=next;
        }
    }
    public static final class MoreRequest {
        public final String origin;
        public final Map<String,String> parameters;
        MoreRequest(String origin, Map<String,String> parameters) {
            this.origin=origin; this.parameters=Collections.unmodifiableMap(new LinkedHashMap<>(parameters));
        }
        public String url() {
            StringBuilder out=new StringBuilder(origin+"/system/resource/getmore.jsp?");
            for (Map.Entry<String,String> e:parameters.entrySet()) {
                if (out.charAt(out.length()-1)!='?') out.append('&');
                out.append(encode(e.getKey())).append('=').append(encode(e.getValue()));
            }
            return out.toString();
        }
        MoreRequest advance(int returnedCount) throws ParseException {
            int count=Integer.parseInt(parameters.get("count"));
            if (returnedCount < count) return null;
            if (returnedCount > count) throw fail(Failure.UNKNOWN_STRUCTURE,"Pagination returned too many rows");
            long start=Long.parseLong(parameters.get("start"))+count;
            if (start>Integer.MAX_VALUE) throw fail(Failure.UNKNOWN_STRUCTURE,"Pagination offset overflow");
            Map<String,String> p=new LinkedHashMap<>(parameters); p.put("start",Long.toString(start));
            return new MoreRequest(origin,p);
        }
    }
    private static final class Identity {
        final NoticeKey key; final String treeId,url;
        Identity(String newsId,String treeId,String url) { this.key=NoticeKey.portalId(SOURCE,newsId); this.treeId=treeId;this.url=url; }
    }
    private static ParseException fail(Failure type,String message) { return new ParseException(type,message); }
    private static final Pattern DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
    private static final Comparator<Summary> NEWEST = (a,b)-> {
        int date=Long.compare(b.publishedAtMillis,a.publishedAtMillis);
        return date!=0 ? date : a.key.stableId.compareTo(b.key.stableId);
    };

    public Page parseList(String finalUrl,int status,String html) throws ParseException {
        Document doc=checkedDocument(finalUrl,status,html);
        URI page=portalUri(finalUrl);
        if (!"/list.jsp".equals(page.getPath())) throw fail(Failure.UNKNOWN_STRUCTURE,"Expected mobile notice listing");
        Map<String,String> pageQuery=query(page);
        String category=digits(pageQuery.get("wbtreeid"));
        Element list=only(doc.select(".main ul.am-list"),"notice list");
        List<Summary> rows=new ArrayList<>();
        for (Element row:list.select("li.am-list-item-dated")) {
            if (row.id().startsWith("more_template_")) continue;
            Element a=only(row.select("a.am-list-item-hd[href]"),"notice link");
            Identity id=identity(resolve(page,a.attr("href")));
            if (!digits(row.id()).equals(id.key.stableId.substring(3))) throw fail(Failure.INVALID_IDENTITY,"List row and URL IDs disagree");
            rows.add(new Summary(id,text(a,"notice title"),date(only(row.select("span.am-list-date"),"notice date").text())));
        }
        if (rows.isEmpty()) throw fail(Failure.UNKNOWN_STRUCTURE,"Empty listing not verified for this portal template");
        MoreRequest next=discoverMore(doc,page,category);
        return new Page(mergeNewest(rows),next);
    }

    public Detail parseDetail(String finalUrl,int status,String html) throws ParseException {
        Document doc=checkedDocument(finalUrl,status,html);
        Identity id=identity(finalUrl);
        Element form=only(doc.select("form[name=_newscontent_fromname]"),"notice form");
        Element title=only(doc.select("form[name=_newscontent_fromname] > div > h1"),"notice heading");
        Element meta=title.nextElementSibling();
        if (meta==null || !meta.tagName().equals("div")) throw fail(Failure.UNKNOWN_STRUCTURE,"Missing publication metadata");
        String metadata=meta.text().replace('\u00a0',' ').trim();
        if (metadata.length()<10) throw fail(Failure.UNKNOWN_STRUCTURE,"Missing publication date");
        long published=date(metadata.substring(0,10));
        Element body=only(form.select("#vsb_content_100 > .v_news_content"),"notice body").clone();
        if (body.text().trim().isEmpty() && body.select("img[src]").isEmpty()) throw fail(Failure.UNKNOWN_STRUCTURE,"Empty notice body");
        body.select("script,style,iframe,object,embed,form,input,button").remove();
        URI base=portalUri(finalUrl);
        LinkedHashSet<String> images=new LinkedHashSet<>();
        for (Element img:body.select("img")) {
            String url=safeResource(base,img.attr("src"));
            if (url==null) { img.remove();continue; }
            img.attr("src",url); images.add(url);
        }
        for (Element a:body.select("a[href]")) {
            String url=safeResource(base,a.attr("href"));
            if (url==null) a.removeAttr("href"); else a.attr("href",url);
        }
        List<CampusNotice.Attachment> attachments=new ArrayList<>();
        Set<String> attachmentUrls=new HashSet<>();
        for (Element a:form.select("a[href]")) {
            String url=safeResource(base,a.attr("href"));
            if (url==null) continue;
            URI uri=URI.create(url);
            if (!CampusSite.PORTAL_HOST.equalsIgnoreCase(uri.getHost()) || !"/system/_content/download.jsp".equals(uri.getPath())) continue;
            Map<String,String> params=query(uri);
            if (!"news.DownloadAttachUrl".equals(params.get("urltype"))) throw fail(Failure.UNKNOWN_STRUCTURE,"Unexpected attachment link format");
            digits(params.get("wbfileid")); digits(params.get("owner"));
            if (attachmentUrls.add(url)) attachments.add(new CampusNotice.Attachment(text(a,"attachment name"),url,""));
        }
        String clean=Jsoup.clean(body.html(),finalUrl,Safelist.relaxed(),new Document.OutputSettings().prettyPrint(false));
        CampusNotice notice=new CampusNotice(id.key,text(title,"notice title"),published,clean,id.url,new ArrayList<>(images),attachments);
        return new Detail(notice,id.treeId);
    }

    public Page parseMore(String finalUrl,int status,String xml,MoreRequest request) throws ParseException {
        checkResponse(finalUrl,status,xml);
        URI url=portalUri(finalUrl);
        if (!"/system/resource/getmore.jsp".equals(url.getPath()) || !query(url).equals(request.parameters))
            throw fail(Failure.UNKNOWN_STRUCTURE,"Pagination response URL differs from request");
        if (xml.toUpperCase(Locale.ROOT).contains("<!DOCTYPE") || xml.toUpperCase(Locale.ROOT).contains("<!ENTITY"))
            throw fail(Failure.UNKNOWN_STRUCTURE,"Unexpected XML declarations");
        Document doc=Jsoup.parse(xml,finalUrl,Parser.xmlParser());
        if (doc.childrenSize()!=1 || !"data".equals(doc.child(0).tagName())) throw fail(Failure.UNKNOWN_STRUCTURE,"Expected pagination XML data root");
        Element data=doc.child(0);
        List<Summary> rows=new ArrayList<>();
        URI contentBase=URI.create(request.origin+request.parameters.get("pageurl"));
        for (Element item:data.children()) {
            if (!item.tagName().equals("news")) throw fail(Failure.UNKNOWN_STRUCTURE,"Unexpected XML record");
            Identity id=identity(resolve(contentBase,text(only(item.select("url"),"page item URL"),"page item URL")));
            if (!digits(item.id()).equals(id.key.stableId.substring(3))) throw fail(Failure.INVALID_IDENTITY,"XML and URL IDs disagree");
            rows.add(new Summary(id,text(only(item.select("showTitle"),"page item title"),"title"),date(only(item.select("showDate"),"page item date").text())));
        }
        return new Page(mergeNewest(rows),request.advance(rows.size()));
    }

    /** Deduplicates across pages/categories and removes original sticky-item ordering. */
    public static List<Summary> mergeNewest(Collection<Summary> records) {
        Map<NoticeKey,Summary> unique=new LinkedHashMap<>();
        for (Summary record:records) unique.put(record.key,record);
        List<Summary> sorted=new ArrayList<>(unique.values()); sorted.sort(NEWEST);return sorted;
    }
    private static Document checkedDocument(String url,int status,String html) throws ParseException {
        checkResponse(url,status,html); Document doc=Jsoup.parse(html,url);
        if (!doc.select("input[type=password]").isEmpty() || doc.title().matches(".*(统一身份认证|登录|登陆|Login|Sign in).*"))
            throw fail(Failure.REAUTH_REQUIRED,"Portal returned an authentication page");
        return doc;
    }
    private static void checkResponse(String url,int status,String body) throws ParseException {
        if (status==401) throw fail(Failure.REAUTH_REQUIRED,"Authentication expired");
        if (status==403) throw fail(Failure.ACCESS_DENIED,"Portal denied access");
        URI uri;
        try {uri=URI.create(url);} catch (RuntimeException e) {throw fail(Failure.UNKNOWN_STRUCTURE,"Invalid final response URL");}
        if (!CampusSite.PORTAL_HOST.equalsIgnoreCase(uri.getHost()) || uri.getPath().toLowerCase(Locale.ROOT).matches(".*(login|passport|/cas/).*"))
            throw fail(Failure.REAUTH_REQUIRED,"Portal redirected to authentication or another origin");
        if (status!=200) throw fail(Failure.HTTP_ERROR,"Unexpected HTTP status: "+status);
        portalUri(url);
        if (body==null || body.length()>5_000_000) throw fail(Failure.UNKNOWN_STRUCTURE,"Missing or oversized response body");
    }
    private static Identity identity(String url) throws ParseException {
        URI uri=portalUri(url);
        if (!"/xntz_content.jsp".equals(uri.getPath())) throw fail(Failure.INVALID_IDENTITY,"Not a known campus notice URL");
        Map<String,String> q=query(uri);
        if (q.size()!=3 || !"news.NewsContentUrl".equals(q.get("urltype"))) throw fail(Failure.INVALID_IDENTITY,"Unexpected notice URL parameters");
        String id=digits(q.get("wbnewsid")), tree=digits(q.get("wbtreeid"));
        String canonical=origin(uri)+"/xntz_content.jsp?urltype=news.NewsContentUrl&wbtreeid="+tree+"&wbnewsid="+id;
        return new Identity(id,tree,canonical);
    }
    private static URI portalUri(String url) throws ParseException {
        try {
            URI uri=URI.create(url);
            boolean http="http".equalsIgnoreCase(uri.getScheme()), https="https".equalsIgnoreCase(uri.getScheme());
            if ((!http&&!https) || !CampusSite.PORTAL_HOST.equalsIgnoreCase(uri.getHost()) || uri.getRawUserInfo()!=null || uri.getRawFragment()!=null || (uri.getPort()!=-1 && uri.getPort()!=(http?80:443)))
                throw new IllegalArgumentException();
            return uri;
        } catch (RuntimeException e) {throw fail(Failure.INVALID_IDENTITY,"URL is not the expected portal origin");}
    }
    private static String origin(URI uri) {return uri.getScheme().toLowerCase(Locale.ROOT)+"://"+CampusSite.PORTAL_HOST;}
    private static String resolve(URI base,String relative) throws ParseException {
        try {return base.resolve(relative.trim()).normalize().toString();}
        catch (RuntimeException e) {throw fail(Failure.UNKNOWN_STRUCTURE,"Invalid relative URL");}
    }
    private static String safeResource(URI base,String relative) {
        if (relative==null || relative.trim().isEmpty()) return null;
        try {
            URI uri=base.resolve(relative.trim()).normalize();
            if (uri.getHost()==null || uri.getRawUserInfo()!=null || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) return null;
            return uri.toString();
        } catch (RuntimeException e) {return null;}
    }
    private static Map<String,String> query(URI uri) throws ParseException {
        Map<String,String> result=new LinkedHashMap<>();
        if (uri.getRawQuery()==null) return result;
        try {
            for (String pair:uri.getRawQuery().split("&",-1)) {
                int at=pair.indexOf('='); if (at<1) throw new IllegalArgumentException();
                String key=URLDecoder.decode(pair.substring(0,at),"UTF-8"),value=URLDecoder.decode(pair.substring(at+1),"UTF-8");
                if (result.put(key,value)!=null) throw new IllegalArgumentException();
            }
        } catch (Exception e) {throw fail(Failure.INVALID_IDENTITY,"Malformed or duplicate URL parameter");}
        return result;
    }
    private static String digits(String value) throws ParseException {
        if (value==null || !value.matches("[1-9][0-9]{0,18}")) throw fail(Failure.INVALID_IDENTITY,"Missing or malformed portal numeric ID");
        return value;
    }
    private static long date(String value) throws ParseException {
        String clean=value.trim(); if (!DATE.matcher(clean).matches()) throw fail(Failure.UNKNOWN_STRUCTURE,"Missing or invalid publication date");
        SimpleDateFormat format=new SimpleDateFormat("yyyy-MM-dd",Locale.ROOT); format.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai")); format.setLenient(false);
        try {return format.parse(clean).getTime();} catch (java.text.ParseException e) {throw fail(Failure.UNKNOWN_STRUCTURE,"Invalid calendar date");}
    }
    private static Element only(List<Element> values,String field) throws ParseException {
        if (values.size()!=1) throw fail(Failure.UNKNOWN_STRUCTURE,"Missing or ambiguous "+field);return values.get(0);
    }
    private static String text(Element element,String field) throws ParseException {
        String text=element.text().trim(); if (text.isEmpty()) throw fail(Failure.UNKNOWN_STRUCTURE,"Missing "+field);return text;
    }
    private static String encode(String value) {
        try {return URLEncoder.encode(value,"UTF-8");} catch (java.io.UnsupportedEncodingException impossible) {throw new AssertionError(impossible);}
    }

    // Only the observed VSB $.get object/var literal grammar is accepted; never executes page JS.
    private static MoreRequest discoverMore(Document doc,URI page,String category) throws ParseException {
        String source=null;
        for (Element script:doc.select("script:not([src])")) if (script.data().contains("/system/resource/getmore.jsp")) {
            if (source!=null) throw fail(Failure.UNKNOWN_STRUCTURE,"Ambiguous pagination scripts");source=script.data();
        }
        if (source==null) throw fail(Failure.UNKNOWN_STRUCTURE,"Pagination definition missing");
        Matcher object=Pattern.compile("\\$\\.get\\(\\s*['\"]/system/resource/getmore\\.jsp['\"]\\s*,\\s*\\{([^}]+)\\}",Pattern.DOTALL).matcher(source);
        if (!object.find()) throw fail(Failure.UNKNOWN_STRUCTURE,"Pagination definition changed");
        Map<String,String> params=new LinkedHashMap<>();
        for (String part:object.group(1).split(",")) {
            Matcher property=Pattern.compile("\\s*([A-Za-z][A-Za-z0-9]*)\\s*:\\s*(?:'([^']*)'|\"([^\"]*)\"|([A-Za-z_0-9]+))\\s*").matcher(part);
            if (!property.matches()) throw fail(Failure.UNKNOWN_STRUCTURE,"Unsupported pagination property");
            String key=property.group(1), value=property.group(2)!=null?property.group(2):property.group(3)!=null?property.group(3):property.group(4);
            if ((key.equals("count")||key.equals("start")) && !value.matches("[0-9]+")) {
                Matcher variable=Pattern.compile("\\bvar\\s+"+Pattern.quote(value)+"\\s*=\\s*([0-9]+)\\s*;").matcher(source);
                if (!variable.find()) throw fail(Failure.UNKNOWN_STRUCTURE,"Pagination offset definition missing");value=variable.group(1);
            }
            if (params.put(key,value)!=null) throw fail(Failure.UNKNOWN_STRUCTURE,"Duplicate pagination property");
        }
        Set<String> expected=new HashSet<>(Arrays.asList("count","start","wbtreeid","wbviewid","owner","dbname","tf","titlelen","summarylen","newsshowtype","hastitlepic","mode","ellipsismode","pageurl"));
        if (!params.keySet().equals(expected) || !category.equals(params.get("wbtreeid")) || !"vsb".equals(params.get("dbname")) || !params.get("pageurl").matches("/[A-Za-z0-9_./-]+\\.jsp"))
            throw fail(Failure.UNKNOWN_STRUCTURE,"Pagination source changed");
        digits(params.get("wbviewid"));digits(params.get("owner"));
        try {
            int count=Integer.parseInt(params.get("count")),start=Integer.parseInt(params.get("start"));
            if (count<1||count>100||start<0) throw new NumberFormatException();
        } catch (NumberFormatException e) {throw fail(Failure.UNKNOWN_STRUCTURE,"Invalid pagination bounds");}
        return new MoreRequest(origin(page),params);
    }
}
