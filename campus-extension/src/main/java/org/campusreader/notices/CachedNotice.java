package org.campusreader.notices;

import java.util.*;

/** Local reading state is deliberately separate from fetched content. */
public final class CachedNotice {
    public final CampusNotice content;
    public final boolean read;
    public final boolean later;
    public final long refreshedAtMillis;
    public final boolean bodyCached;
    public final List<NoticeTag> tags;

    CachedNotice(CampusNotice content, boolean read, boolean later, long refreshedAtMillis,boolean bodyCached,List<NoticeTag> tags) {
        this.content = content;
        this.read = read;
        this.later = later;
        this.refreshedAtMillis = refreshedAtMillis;
        this.bodyCached=bodyCached;
        this.tags=Collections.unmodifiableList(new ArrayList<>(tags));
    }
}
