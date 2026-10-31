package org.campusreader.reader;

import org.campusreader.notices.*;
import org.campusreader.config.CampusSite;
import org.campusreader.portal.CampusPortalParser;
import java.io.IOException;
import java.util.*;

/** Fault-injection checks for the actual coordinator used by ReaderActivity. No network or account data. */
public final class RefreshLifecycleChecks {
    private static int checks;
    private static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    private static final CampusPortalParser PARSER=new CampusPortalParser();
    private static List<CampusPortalParser.Summary> summaries()throws Exception {
        StringBuilder html=new StringBuilder("<div class='main'><ul class='am-list'>");
        for(int id=101;id<=103;id++)html.append("<li class='am-list-item-dated' id='").append(id).append("'><a class='am-list-item-hd' href='xntz_content.jsp?urltype=news.NewsContentUrl&wbtreeid=54&wbnewsid=").append(id).append("'>Synthetic notice</a><span class='am-list-date'>2026-10-30</span></li>");
        html.append("</ul><script>var count_1=3;var start_1=3;$.get('/system/resource/getmore.jsp',{count:count_1,start:start_1,wbtreeid:1000,wbviewid:12,owner:99,dbname:'vsb',tf:'yyyy-MM-dd&zh',titlelen:0,summarylen:0,newsshowtype:'0',hastitlepic:'false',mode:10,ellipsismode:1,pageurl:'/mobile/list.jsp'},function(info){},'xml');</script></div>");
        return PARSER.parseList("http://portal.campus.example.edu/list.jsp?urltype=tree.TreeTempUrl&wbtreeid=1000",200,html.toString()).notices;
    }
    private static CampusNotice notice(CampusPortalParser.Summary item){return new CampusNotice(item.key,item.title,item.publishedAtMillis,"<p>Synthetic body</p>",item.originalUrl,Collections.emptyList(),Collections.emptyList());}
    private static final class Session implements PortalBatchDownloader.Session {
        PortalBatchDownloader.State value=PortalBatchDownloader.State.READY;
        public PortalBatchDownloader.State state(){return value;}
    }
    private static final PortalBatchDownloader.Progress PROGRESS=(position,total)->{};
    public static void main(String[] args)throws Exception {
        CampusSite.install(CampusSite.create("campus.example.edu","https://vpn.campus.example.edu/",
                "http://portal.campus.example.edu/list.jsp?urltype=tree.TreeTempUrl&wbtreeid=1000","https://auth.campus.example.edu/login"));
        DeferredPortalRefresh queue=new DeferredPortalRefresh();
        queue.request("account-A");queue.request("account-A");
        check(!queue.takeIfReady("account-A",true,false,true)&&queue.hasRequest(),"Login completion retained while request busy");
        check(!queue.takeIfReady("account-A",false,true,true)&&queue.hasRequest(),"Wait for cookie isolation");
        check(!queue.takeIfReady("account-A",false,false,false)&&queue.hasRequest(),"Wait for SDK recovery without consuming request");
        check(queue.takeIfReady("account-A",false,false,true),"Run once after all prerequisites ready");
        check(!queue.takeIfReady("account-A",false,false,true)&&!queue.hasRequest(),"Duplicate completion requests coalesce");
        queue.request("account-A");check(!queue.takeIfReady("account-B",false,false,true)&&!queue.hasRequest(),"Discard old account's deferred request");
        queue.request("account-B");check(queue.takeIfReady("account-B",false,false,true),"New account can request its own refresh");

        List<CampusPortalParser.Summary> items=summaries();
        List<CampusNotice> saved=new ArrayList<>();
        PortalBatchDownloader.Result healthy=PortalBatchDownloader.download(items,new Session(),RefreshLifecycleChecks::notice,saved::add,PROGRESS);
        check(healthy.saved==3&&saved.size()==3&&healthy.mayAdvance(),"Healthy page committed and advances");

        saved.clear();Session lostBetween=new Session();
        PortalBatchDownloader.Result interrupted=PortalBatchDownloader.download(items,lostBetween,RefreshLifecycleChecks::notice,n->{saved.add(n);lostBetween.value=PortalBatchDownloader.State.TUNNEL_DISCONNECTED;},PROGRESS);
        check(interrupted.saved==1&&saved.size()==1,"Prior detail remains saved when next preflight detects disconnect");
        check(interrupted.stop==PortalBatchDownloader.Stop.TUNNEL_DISCONNECTED&&!interrupted.mayAdvance(),"Tunnel interruption is distinct from portal expiry and retains page");

        saved.clear();Session lostDuring=new Session();int[] calls={0};
        PortalBatchDownloader.Result during=PortalBatchDownloader.download(items,lostDuring,item->{if(++calls[0]==2)lostDuring.value=PortalBatchDownloader.State.TUNNEL_DISCONNECTED;return notice(item);},saved::add,PROGRESS);
        check(saved.size()==1&&during.saved==1,"Discard uncertain response received after tunnel loss");
        check(calls[0]==2&&during.stop==PortalBatchDownloader.Stop.TUNNEL_DISCONNECTED,"No more requests after tunnel loss");

        saved.clear();Session changed=new Session();calls[0]=0;List<String> destinations=new ArrayList<>();
        PortalBatchDownloader.Result switched=PortalBatchDownloader.download(items,changed,item->{if(++calls[0]==2)changed.value=PortalBatchDownloader.State.ACCOUNT_CHANGED;return notice(item);},n->{saved.add(n);destinations.add("captured-account-A");},PROGRESS);
        check(switched.stop==PortalBatchDownloader.Stop.ACCOUNT_CHANGED&&!switched.mayAdvance(),"Account switch cancels batch");
        check(saved.size()==1&&destinations.equals(Collections.singletonList("captured-account-A")),"Only confirmed original-account response reaches captured store");
        check(saved.get(0).key.equals(items.get(0).key),"Switched-account response never reaches sink");

        saved.clear();calls[0]=0;
        PortalBatchDownloader.Result expired=PortalBatchDownloader.download(items,new Session(),item->{if(++calls[0]==2)PARSER.parseDetail(item.originalUrl,401,"");return notice(item);},saved::add,PROGRESS);
        check(expired.saved==1&&saved.size()==1,"Portal expiry retains previously committed body");
        check(expired.stop==PortalBatchDownloader.Stop.PORTAL_LOGIN_REQUIRED&&calls[0]==2&&!expired.mayAdvance(),"Stop on real portal expiry without advancing");

        saved.clear();calls[0]=0;
        PortalBatchDownloader.Result transientError=PortalBatchDownloader.download(items,new Session(),item->{if(++calls[0]==2)throw new IOException("Synthetic timeout");return notice(item);},saved::add,PROGRESS);
        check(transientError.saved==2&&transientError.failed==1&&saved.size()==2,"Independent network failure still commits other details");
        check(transientError.stop==PortalBatchDownloader.Stop.COMPLETE&&!transientError.mayAdvance(),"Partially failed page is retried");

        saved.clear();calls[0]=0;Session lostWithError=new Session();
        PortalBatchDownloader.Result transportLoss=PortalBatchDownloader.download(items,lostWithError,item->{if(++calls[0]==2){lostWithError.value=PortalBatchDownloader.State.TUNNEL_DISCONNECTED;throw new IOException("Synthetic reset");}return notice(item);},saved::add,PROGRESS);
        check(transportLoss.saved==1&&saved.size()==1&&calls[0]==2,"Transport exception plus tunnel loss retains prior commit and stops");
        check(transportLoss.stop==PortalBatchDownloader.Stop.TUNNEL_DISCONNECTED,"Transport loss does not demand portal password");

        saved.clear();calls[0]=0;
        PortalBatchDownloader.Result wrongId=PortalBatchDownloader.download(items,new Session(),item->{if(++calls[0]==2){CampusNotice n=notice(item);return new CampusNotice(NoticeKey.portalId(CampusPortalParser.SOURCE,"999"),n.title,n.publishedAtMillis,n.bodyHtml,n.originalUrl,n.imageUrls,n.attachments);}return notice(item);},saved::add,PROGRESS);
        check(wrongId.saved==2&&wrongId.failed==1&&!wrongId.mayAdvance(),"Mismatching response ID rejected without losing other successes");
        check(saved.stream().noneMatch(n->n.key.stableId.equals("id:999")),"Wrong-identity body never reaches cache sink");
        System.out.println("Refresh lifecycle checks passed: "+checks);
    }
}
