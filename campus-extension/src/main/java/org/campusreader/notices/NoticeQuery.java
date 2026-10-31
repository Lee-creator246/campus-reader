package org.campusreader.notices;

import java.util.*;

/**
 * Literal substring query over titles and cached body text. Null source means all sources.
 * Includes require all terms; excludes/blocks reject any term; priority-only accepts any priority term.
 */
public final class NoticeQuery {
    public final String source,search;
    public final boolean unreadOnly,laterOnly,matchAllTags,priorityOnly;
    public final List<String> includeKeywords,excludeKeywords,blockedKeywords,priorityKeywords;
    public final List<Long> tagIds;
    public final int limit,offset;
    private NoticeQuery(Builder b){source=b.source;search=b.search;unreadOnly=b.unread;laterOnly=b.later;matchAllTags=b.matchAll;priorityOnly=b.priorityOnly;includeKeywords=terms(b.include);excludeKeywords=terms(b.exclude);blockedKeywords=terms(b.blocked);priorityKeywords=terms(b.priority);tagIds=Collections.unmodifiableList(new ArrayList<>(b.tags));limit=b.limit;offset=b.offset;}
    public static Builder builder(String source){return new Builder(source);}
    private static List<String> terms(Collection<String> values){LinkedHashSet<String> clean=new LinkedHashSet<>();for(String value:values){if(value==null)throw new IllegalArgumentException("Null keyword");String v=value.trim();if(v.length()>256)throw new IllegalArgumentException("Keyword too long");if(!v.isEmpty())clean.add(v);}if(clean.size()>50)throw new IllegalArgumentException("Too many keywords");return Collections.unmodifiableList(new ArrayList<>(clean));}
    public static final class Builder {
        private final String source;private String search="";private boolean unread,later,priorityOnly,matchAll=true;private Collection<String> include=Collections.emptyList(),exclude=Collections.emptyList(),blocked=Collections.emptyList(),priority=Collections.emptyList();private final LinkedHashSet<Long> tags=new LinkedHashSet<>();private int limit=100,offset;
        private Builder(String source){this.source=source==null?null:NoticeKey.requireText(source,"source");}
        public Builder search(String value){if(value==null||value.length()>256)throw new IllegalArgumentException("Invalid search");search=value.trim();return this;}
        public Builder includeKeywords(Collection<String> values){if(values==null)throw new IllegalArgumentException("Missing include terms");include=values;return this;}
        public Builder excludeKeywords(Collection<String> values){if(values==null)throw new IllegalArgumentException("Missing exclude terms");exclude=values;return this;}
        /** Exclude any matching blocked term independently of the current search filters. */
        public Builder blockedKeywords(Collection<String> values){if(values==null)throw new IllegalArgumentException("Missing blocked terms");blocked=values;return this;}
        /** Select any matching priority term. An empty list deliberately selects no notices. */
        public Builder priorityKeywords(Collection<String> values){if(values==null)throw new IllegalArgumentException("Missing priority terms");priority=values;priorityOnly=true;return this;}
        public Builder priorityOnly(boolean value){priorityOnly=value;return this;}
        public Builder tagIds(Collection<Long> values,boolean matchAll){tags.clear();for(Long id:values){if(id==null||id<=0)throw new IllegalArgumentException("Invalid tag ID");tags.add(id);}if(tags.size()>100)throw new IllegalArgumentException("Too many tags");this.matchAll=matchAll;return this;}
        public Builder unreadOnly(boolean value){unread=value;return this;}
        public Builder laterOnly(boolean value){later=value;return this;}
        public Builder page(int limit,int offset){if(limit<1||limit>1000||offset<0)throw new IllegalArgumentException("Invalid page");this.limit=limit;this.offset=offset;return this;}
        public NoticeQuery build(){return new NoticeQuery(this);}
    }
}
