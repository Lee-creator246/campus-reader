package org.campusreader.reader;

import org.campusreader.notices.CachedNotice;
import org.campusreader.notices.CampusNotice;
import org.campusreader.portal.CampusPortalParser;
import java.io.IOException;

/** Keeps deliberately evicted read bodies evicted during periodic/list refresh. */
public final class NoticeRefreshPlan {
    public interface Fetcher {CampusNotice fetch()throws IOException,CampusPortalParser.ParseException;}
    public static final class Content {
        public final CampusNotice notice;
        public final boolean metadataOnly;
        Content(CampusNotice notice,boolean metadataOnly){this.notice=notice;this.metadataOnly=metadataOnly;}
    }
    private NoticeRefreshPlan(){}
    public static Content resolve(CachedNotice existing,CampusNotice summary,Fetcher fetcher)throws IOException,CampusPortalParser.ParseException{
        if(existing!=null&&!existing.content.key.equals(summary.key))throw new IllegalArgumentException("Notice identity changed");
        if(existing!=null&&existing.read&&!existing.bodyCached&&!existing.later&&existing.tags.isEmpty()){
            CampusNotice metadata=new CampusNotice(summary.key,summary.title,summary.publishedAtMillis,"",summary.originalUrl,
                    existing.content.imageUrls,existing.content.attachments);
            return new Content(metadata,true);
        }
        return new Content(fetcher.fetch(),false);
    }
}
