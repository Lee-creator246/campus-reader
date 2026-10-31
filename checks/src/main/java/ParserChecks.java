import org.campusreader.portal.CampusPortalParser;
import org.campusreader.config.CampusSite;
import org.campusreader.portal.CampusPortalParser.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class ParserChecks {
    static final String BASE="http://portal.campus.example.edu";
    static final String LIST=BASE+"/list.jsp?urltype=tree.TreeTempUrl&wbtreeid=1000";
    static int checks;
    static final CampusPortalParser parser=new CampusPortalParser();
    static void check(boolean condition,String name) {checks++;if(!condition) throw new AssertionError(name);}
    interface Work { void run() throws Exception; }
    static void rejects(Failure failure,Work work) throws Exception {
        try {work.run();throw new AssertionError("Expected "+failure);} catch (ParseException e) {check(e.failure==failure,"Correct failure category");}
    }
    static String url(String id,String tree) {return BASE+"/xntz_content.jsp?urltype=news.NewsContentUrl&wbtreeid="+tree+"&wbnewsid="+id;}
    static String row(String id,String tree,String date) {
        return "<li class='am-list-item-dated' id='"+id+"'><a class='am-list-item-hd' href='"+url(id,tree)+"'>Synthetic notice "+id+"</a><span class='am-list-date'>"+date+"</span></li>";
    }
    static String script() {return "<script>var count_12=2;var start_12=2;function more(){ $.get('/system/resource/getmore.jsp',{count:count_12,start:start_12,wbtreeid:1000,wbviewid:12,owner:99,dbname:'vsb',tf:'yyyy-MM-dd&zh',titlelen:0,summarylen:0,newsshowtype:'0',hastitlepic:'false',mode:10,ellipsismode:1,pageurl:'/mobile/list.jsp'},function(info){},'xml');}</script>";}
    static String listing(String rows) {return "<div class='main'><ul class='am-list'>"+rows+"<li class='am-list-item-dated' id='more_template_12' style='display:none'><a href='#{url}'>{title}</a></li></ul>"+script()+"</div>";}
    static String detail(String contents,String extra) {return "<form name='_newscontent_fromname'><div><h1>Synthetic title</h1><div>2026-10-30&nbsp;Office&nbsp;clicks[<script>counter()</script>]</div><hr><div id='vsb_content_100'><div class='v_news_content'>"+contents+"</div></div>"+extra+"<p>Next: <a href='"+url("104","54")+"'>Another notice</a></p></div></form>";}
    static String item(String id,String tree) {return "<news id='"+id+"'><showTitle><![CDATA[Synthetic page title]]></showTitle><showDate>2026-10-29</showDate><picUrl/><url><![CDATA[../xntz_content.jsp?urltype=news.NewsContentUrl&wbtreeid="+tree+"&wbnewsid="+id+"]]></url><showAbstract>Unused summary</showAbstract></news>";}
    public static void main(String[] args) throws Exception {
        CampusSite.Profile fixture=CampusSite.create("campus.example.edu","https://vpn.campus.example.edu/",LIST,"https://auth.campus.example.edu/login");
        CampusSite.install(fixture);
        String list=listing(row("100","51","2026-01-01")+row("101","52","2026-10-30"));
        Page page=parser.parseList(LIST,200,list);
        check(page.notices.size()==2,"Skip template");
        check(page.notices.get(0).key.stableId.equals("id:101"),"Sort pinned old item after new items");
        check(page.notices.get(0).publishedAtMillis==1793289600000L,"Publication date interpreted as Shanghai midnight");
        check(page.next.url().contains("tf=yyyy-MM-dd%26zh"),"Pagination format encoded correctly");
        Page duplicate=parser.parseList(LIST,200,listing(row("101","53","2026-10-30")));
        List<Summary> all=new ArrayList<>(page.notices);all.addAll(duplicate.notices);
        check(CampusPortalParser.mergeNewest(all).size()==2,"One native ID across categories");
        Detail image=parser.parseDetail(url("101","52"),200,detail("<p>Body</p><img src='/__local/image.png' onerror='bad()'><script>bad()</script><a href='javascript:bad()'>unsafe</a>","<ul><li><a href='/system/_content/download.jsp?urltype=news.DownloadAttachUrl&owner=99&wbfileid=123'>Sample.xlsx</a></li></ul>"));
        check(image.notice.hasImage() && image.notice.imageUrls.size()==1,"Body images only");
        check(image.notice.imageUrls.get(0).equals(BASE+"/__local/image.png"),"Absolute image URL");
        check(!image.notice.bodyHtml.contains("script") && !image.notice.bodyHtml.contains("onerror"),"Clean active content");
        check(image.notice.attachments.size()==1,"Retain separate attachment entry");
        check(image.notice.attachments.get(0).name.equals("Sample.xlsx"),"Attachment name");
        check(image.treeId.equals("52"),"Category retained separately from identity");
        check(image.notice.key.equals(duplicate.notices.get(0).key),"Detail and list share stable key");
        Detail plain=parser.parseDetail(url("102","52"),200,detail("<h1>Body section heading</h1><p>Plain body</p>",""));
        check(!plain.notice.hasImage(),"No placeholder image");
        Page more=parser.parseMore(page.next.url(),200,"<?xml version='1.0'?><data>"+item("102","52")+item("103","53")+"</data>",page.next);
        check(more.notices.size()==2,"XML page parsed");
        check(more.next.parameters.get("start").equals("4"),"Advance actual offset");
        check(more.notices.get(0).originalUrl.startsWith(BASE+"/xntz_content.jsp"),"XML relative URL uses observed pageurl base");
        check(parser.parseMore(more.next.url(),200,"<data/>",more.next).next==null,"Recognized empty XML terminates pagination");
        rejects(Failure.REAUTH_REQUIRED,()->parser.parseList(LIST,401,list));
        rejects(Failure.ACCESS_DENIED,()->parser.parseList(LIST,403,list));
        rejects(Failure.HTTP_ERROR,()->parser.parseList(LIST,503,list));
        rejects(Failure.REAUTH_REQUIRED,()->parser.parseList("https://auth.campus.example.edu/login",200,"<html/>"));
        rejects(Failure.REAUTH_REQUIRED,()->parser.parseList("https://auth.campus.example.edu/login",302,""));
        rejects(Failure.REAUTH_REQUIRED,()->parser.parseList(BASE+"/login",302,""));
        rejects(Failure.REAUTH_REQUIRED,()->parser.parseList(LIST,200,"<input type='password'>"));
        rejects(Failure.UNKNOWN_STRUCTURE,()->parser.parseList(LIST,200,"<html>Service error</html>"));
        rejects(Failure.UNKNOWN_STRUCTURE,()->parser.parseList(LIST,200,listing("")));
        rejects(Failure.UNKNOWN_STRUCTURE,()->parser.parseList(LIST,200,list.replace("2026-10-30","2026-02-30")));
        rejects(Failure.INVALID_IDENTITY,()->parser.parseList(LIST,200,list.replace("id='101'","id='999'")));
        rejects(Failure.INVALID_IDENTITY,()->parser.parseDetail(url("101","52")+"&wbnewsid=102",200,detail("<p>Body</p>","")));
        rejects(Failure.UNKNOWN_STRUCTURE,()->parser.parseDetail(url("101","52"),200,"<html>Login timed out</html>"));
        rejects(Failure.UNKNOWN_STRUCTURE,()->parser.parseMore(page.next.url(),200,"<html>Error</html>",page.next));
        rejects(Failure.UNKNOWN_STRUCTURE,()->parser.parseMore(page.next.url(),200,"<!DOCTYPE data [<!ENTITY a SYSTEM 'file:///etc/passwd'>]><data/>",page.next));
        rejects(Failure.INVALID_IDENTITY,()->parser.parseMore(page.next.url(),200,"<data>"+item("102","52").replace("id='102'","id='999'")+"</data>",page.next));
        rejects(Failure.UNKNOWN_STRUCTURE,()->parser.parseMore(page.next.url().replace("start=2","start=99"),200,"<data/>",page.next));
        rejects(Failure.INVALID_IDENTITY,()->parser.parseList(LIST.replace(BASE,BASE+":443"),200,list));
        rejects(Failure.INVALID_IDENTITY,()->parser.parseList(LIST.replace(BASE,"https://portal.campus.example.edu:80"),200,list));
        rejects(Failure.INVALID_IDENTITY,()->parser.parseList(LIST+"#fragment",200,list));
        rejects(Failure.INVALID_IDENTITY,()->parser.parseList(LIST.replace(BASE,"http://user@portal.campus.example.edu"),200,list));
        CampusSite.install(CampusSite.create("second.example.edu","https://vpn.second.example.edu/",
                LIST.replace("campus.example.edu","second.example.edu"),"https://auth.second.example.edu/login"));
        rejects(Failure.REAUTH_REQUIRED,()->parser.parseList(LIST,200,list));
        Page second=parser.parseList(LIST.replace("campus.example.edu","second.example.edu"),200,list.replace("campus.example.edu","second.example.edu"));
        check(second.notices.size()==2 && second.next.url().startsWith("http://portal.second.example.edu/"),"Parser follows installed site at runtime");
        CampusSite.install(fixture);
        check(parser.parseList(LIST,200,list).notices.size()==2,"Original site restores without rebuilding parser");
        System.out.println("Synthetic parser checks passed: "+checks);
    }
}
