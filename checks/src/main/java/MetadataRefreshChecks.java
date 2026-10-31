package org.campusreader.notices;

import org.campusreader.reader.NoticeRefreshPlan;
import java.util.*;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

/** Exercises the actual fetch-routing branch with a network spy; no Android or account data. */
public final class MetadataRefreshChecks {
    private static int assertions;
    private static void check(boolean value,String label){assertions++;if(!value)throw new AssertionError(label);}
    public static void main(String[] args)throws Exception{
        NoticeKey key=NoticeKey.portalId("test:portal","101");
        CampusNotice old=new CampusNotice(key,"Old title",1,"","https://example.org/old",Collections.singletonList("https://example.org/p.png"),Collections.singletonList(new CampusNotice.Attachment("file","https://example.org/a.pdf","application/pdf")));
        CampusNotice summary=new CampusNotice(key,"Updated title",2,"","https://example.org/current",Collections.emptyList(),Collections.emptyList());
        CampusNotice fetched=new CampusNotice(key,summary.title,2,"<p>full body</p>",summary.originalUrl,old.imageUrls,old.attachments);
        AtomicInteger requests=new AtomicInteger();NoticeRefreshPlan.Fetcher network=()->{requests.incrementAndGet();return fetched;};
        CachedNotice evicted=new CachedNotice(old,true,false,1,false,Collections.emptyList());
        NoticeRefreshPlan.Content result=NoticeRefreshPlan.resolve(evicted,summary,network);
        check(result.metadataOnly,"Evicted unprotected read notice remains metadata-only");check(requests.get()==0,"No detail HTTP request for evicted read body");
        check(result.notice.bodyHtml.isEmpty(),"No body resurrection");check(result.notice.title.equals(summary.title)&&result.notice.publishedAtMillis==2,"List metadata updates title and date");
        check(result.notice.originalUrl.equals(summary.originalUrl),"Original URL updates from validated list");
        check(result.notice.imageUrls.equals(old.imageUrls)&&result.notice.attachments.equals(old.attachments),"Keep known image and attachment entries");
        check(evicted.read&&!evicted.later&&!evicted.bodyCached&&evicted.tags.isEmpty(),"Read/later/tag flags unchanged by plan");
        check(!NoticeRefreshPlan.resolve(new CachedNotice(old,false,false,1,false,Collections.emptyList()),summary,network).metadataOnly,"Unread evicted body may download");
        check(!NoticeRefreshPlan.resolve(new CachedNotice(fetched,true,false,1,true,Collections.emptyList()),summary,network).metadataOnly,"Still-cached read content may refresh");
        check(!NoticeRefreshPlan.resolve(new CachedNotice(old,true,true,1,false,Collections.emptyList()),summary,network).metadataOnly,"Later read content may download");
        check(!NoticeRefreshPlan.resolve(new CachedNotice(old,true,false,1,false,Collections.singletonList(new NoticeTag(1,"keep"))),summary,network).metadataOnly,"Tagged read content may download");
        check(!NoticeRefreshPlan.resolve(null,summary,network).metadataOnly,"First-seen notice downloads full body");check(requests.get()==5,"Each eligible full refresh invokes network exactly once");
        NoticeRefreshPlan.resolve(evicted,summary,()->{throw new IOException("must not fetch");});check(true,"Metadata refresh remains available when detail network would fail");
        boolean rejected=false;try{CampusNotice mismatch=new CampusNotice(NoticeKey.portalId("test:portal","102"),"other",2,"",summary.originalUrl,Collections.emptyList(),Collections.emptyList());NoticeRefreshPlan.resolve(evicted,mismatch,network);}catch(IllegalArgumentException expected){rejected=true;}check(rejected,"Cannot reuse another notice's resource metadata");
        System.out.println("Metadata refresh checks passed: "+assertions);
    }
}
