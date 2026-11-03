package org.campusreader.notices;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.text.Html;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Account-scoped, worker-thread cache. Migration and pruning never delete notice identities or flags. */
public final class CampusNoticeStore extends SQLiteOpenHelper {
    private static final String KEY_WHERE="source=? AND stable_id=?";
    private static final String[] LIGHT_COLUMNS={"source","stable_id","title","published_ms","original_url","is_read","is_later","refreshed_ms","body_cached"};
    private static final String PROTECTED="(is_later=1 OR EXISTS(SELECT 1 FROM notice_tags nt WHERE nt.source=campus_notices.source AND nt.stable_id=campus_notices.stable_id))";
    public enum ConnectionState { UNKNOWN, AVAILABLE, REAUTH_REQUIRED }
    public static final class UpsertResult {
        public final List<NoticeKey> insertedKeys;
        public final int insertedUnreadCount;
        UpsertResult(List<NoticeKey> keys){insertedKeys=Collections.unmodifiableList(new ArrayList<>(keys));insertedUnreadCount=keys.size();}
    }
    public CampusNoticeStore(Context context,String localProfileId){super(context.getApplicationContext(),databaseName(localProfileId),null,3);}
    @Override public void onConfigure(SQLiteDatabase db){db.setForeignKeyConstraintsEnabled(true);}
    @Override public void onCreate(SQLiteDatabase db){
        db.execSQL("CREATE TABLE campus_notices (source TEXT NOT NULL,stable_id TEXT NOT NULL,title TEXT NOT NULL,published_ms INTEGER NOT NULL,body_html TEXT NOT NULL,original_url TEXT NOT NULL,images_json TEXT NOT NULL,attachments_json TEXT NOT NULL,is_read INTEGER NOT NULL DEFAULT 0 CHECK(is_read IN (0,1)),is_later INTEGER NOT NULL DEFAULT 0 CHECK(is_later IN (0,1)),refreshed_ms INTEGER NOT NULL,body_cached INTEGER NOT NULL DEFAULT 0 CHECK(body_cached IN (0,1)),body_text TEXT NOT NULL DEFAULT '',body_bytes INTEGER NOT NULL DEFAULT 0,read_at_ms INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(source,stable_id))");
        db.execSQL("CREATE INDEX campus_publication ON campus_notices(source,published_ms DESC,stable_id ASC)");
        db.execSQL("CREATE TABLE campus_connections (source TEXT PRIMARY KEY NOT NULL,state TEXT NOT NULL CHECK(state IN ('UNKNOWN','AVAILABLE','REAUTH_REQUIRED')))");
        createV2Tables(db);
        createSummaryTable(db);
    }
    private static void createV2Tables(SQLiteDatabase db){
        db.execSQL("CREATE TABLE tags (id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT NOT NULL COLLATE NOCASE UNIQUE)");
        db.execSQL("CREATE TABLE notice_tags (source TEXT NOT NULL,stable_id TEXT NOT NULL,tag_id INTEGER NOT NULL,PRIMARY KEY(source,stable_id,tag_id),FOREIGN KEY(source,stable_id) REFERENCES campus_notices(source,stable_id) ON DELETE CASCADE,FOREIGN KEY(tag_id) REFERENCES tags(id) ON DELETE CASCADE)");
        db.execSQL("CREATE INDEX notice_tags_by_tag ON notice_tags(tag_id,source,stable_id)");
        db.execSQL("CREATE TABLE cache_policy (singleton INTEGER PRIMARY KEY CHECK(singleton=1),max_entries INTEGER NOT NULL,max_body_bytes INTEGER NOT NULL,read_retention_ms INTEGER NOT NULL)");
        db.execSQL("INSERT INTO cache_policy VALUES(1,300,20971520,86400000)");
    }
    @Override public void onUpgrade(SQLiteDatabase db,int oldVersion,int newVersion){
        if(oldVersion<1||oldVersion>2||newVersion!=3)throw new IllegalStateException("Unsupported cache migration "+oldVersion+" to "+newVersion);
        if(oldVersion==1){
        db.execSQL("ALTER TABLE campus_notices ADD COLUMN body_cached INTEGER NOT NULL DEFAULT 0 CHECK(body_cached IN (0,1))");
        db.execSQL("ALTER TABLE campus_notices ADD COLUMN body_text TEXT NOT NULL DEFAULT ''");
        db.execSQL("ALTER TABLE campus_notices ADD COLUMN body_bytes INTEGER NOT NULL DEFAULT 0");
        db.execSQL("ALTER TABLE campus_notices ADD COLUMN read_at_ms INTEGER NOT NULL DEFAULT 0");
        createV2Tables(db);
        List<NoticeKey> keys=new ArrayList<>();
        try(Cursor c=db.query("campus_notices",new String[]{"source","stable_id"},null,null,null,null,null)){while(c.moveToNext())keys.add(NoticeKey.stored(c.getString(0),c.getString(1)));}
        long now=System.currentTimeMillis();
        for(NoticeKey key:keys){String body=readText(db,key,"body_html");ContentValues v=bodyValues(body);db.update("campus_notices",v,KEY_WHERE,keyArgs(key));}
        // Version 1 had no read time: start its age window now rather than guessing an old read date.
        db.execSQL("UPDATE campus_notices SET read_at_ms=? WHERE is_read=1",new Object[]{now});
        enforce(db,now);
        }
        createSummaryTable(db);
    }
    private static void createSummaryTable(SQLiteDatabase db){
        db.execSQL("CREATE TABLE summary_control (singleton INTEGER PRIMARY KEY CHECK(singleton=1),generation INTEGER NOT NULL)");
        db.execSQL("INSERT INTO summary_control VALUES(1,0)");
        db.execSQL("CREATE TABLE notice_summaries (source TEXT NOT NULL,stable_id TEXT NOT NULL,content_hash TEXT NOT NULL,config_hash TEXT NOT NULL,result_json TEXT NOT NULL CHECK(length(CAST(result_json AS BLOB))<=16384),generated_ms INTEGER NOT NULL,PRIMARY KEY(source,stable_id),FOREIGN KEY(source,stable_id) REFERENCES campus_notices(source,stable_id) ON DELETE CASCADE)");
        db.execSQL("CREATE TRIGGER invalidate_notice_summary AFTER UPDATE OF title,body_html,body_text,body_cached ON campus_notices WHEN NEW.title<>OLD.title OR NEW.body_html<>OLD.body_html OR NEW.body_text<>OLD.body_text OR NEW.body_cached=0 BEGIN DELETE FROM notice_summaries WHERE source=NEW.source AND stable_id=NEW.stable_id; END");
    }
    public static NoticeKey summaryKey(String source,String stableId){if(source==null||source.length()>512||stableId==null||stableId.length()>8192)throw new IllegalArgumentException("Invalid notice identity");return NoticeKey.stored(source,stableId);}
    public SummaryInput summaryInput(NoticeKey key){SQLiteDatabase db=getReadableDatabase();db.beginTransactionNonExclusive();try{SummaryInput input=summaryInput(db,key);db.setTransactionSuccessful();return input;}finally{db.endTransaction();}}
    private static SummaryInput summaryInput(SQLiteDatabase db,NoticeKey key){
        String title;try(Cursor c=db.query("campus_notices",new String[]{"title","body_cached"},KEY_WHERE,keyArgs(key),null,null,null)){if(!c.moveToFirst()||c.getInt(1)!=1)return null;title=c.getString(0);}
        String text=readText(db,key,"body_text");return text.trim().isEmpty()?null:new SummaryInput(title,text,summaryGeneration(db));
    }
    public SummaryRecord findSummary(NoticeKey key,String contentHash,String configHash){
        try(Cursor c=getReadableDatabase().query("notice_summaries",new String[]{"result_json","generated_ms"},KEY_WHERE+" AND content_hash=? AND config_hash=?",new String[]{key.source,key.stableId,contentHash,configHash},null,null,null)){
            return c.moveToFirst()?new SummaryRecord(contentHash,configHash,c.getString(0),c.getLong(1)):null;
        }
    }
    /** Rechecks content inside the write transaction; a response cannot revive an evicted/changed body. */
    public boolean saveSummaryIfCurrent(NoticeKey key,String expectedHash,long expectedGeneration,String configHash,String resultJson,long now){
        if(expectedHash==null||!expectedHash.matches("[a-f0-9]{64}")||configHash==null||!configHash.matches("[a-f0-9]{64}")||resultJson==null||resultJson.trim().isEmpty()||resultJson.getBytes(StandardCharsets.UTF_8).length>16384||now<0)throw new IllegalArgumentException("Invalid summary");
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();try{
            SummaryInput input=summaryInput(db,key);if(input==null||input.cacheGeneration!=expectedGeneration||!input.contentHash.equals(expectedHash))return false;
            ContentValues row=new ContentValues();row.put("source",key.source);row.put("stable_id",key.stableId);row.put("content_hash",expectedHash);row.put("config_hash",configHash);row.put("result_json",resultJson);row.put("generated_ms",now);
            if(db.insertWithOnConflict("notice_summaries",null,row,SQLiteDatabase.CONFLICT_REPLACE)==-1)throw new IllegalStateException("Summary persistence failed");db.setTransactionSuccessful();return true;
        }finally{db.endTransaction();}
    }
    private static long summaryGeneration(SQLiteDatabase db){try(Cursor c=db.rawQuery("SELECT generation FROM summary_control WHERE singleton=1",null)){if(!c.moveToFirst())throw new IllegalStateException("Missing summary generation");return c.getLong(0);}}
    public int clearSummaries(){SQLiteDatabase db=getWritableDatabase();db.beginTransaction();try{int count=db.delete("notice_summaries",null,null);db.execSQL("UPDATE summary_control SET generation=generation+1 WHERE singleton=1");db.setTransactionSuccessful();return count;}finally{db.endTransaction();}}
    public long summaryCount(){try(Cursor c=getReadableDatabase().rawQuery("SELECT COUNT(*) FROM notice_summaries",null)){c.moveToFirst();return c.getLong(0);}}
    public UpsertResult upsert(List<CampusNotice> notices,long refreshedAtMillis){return upsertInternal(notices,refreshedAtMillis,false);}
    /** Lightweight list refresh must not overwrite an existing cached body with an empty preview. */
    public UpsertResult upsertMetadata(List<CampusNotice> notices,long refreshedAtMillis){return upsertInternal(notices,refreshedAtMillis,true);}
    private UpsertResult upsertInternal(List<CampusNotice> notices,long refreshedAtMillis,boolean metadataOnly){
        if(notices==null||refreshedAtMillis<0)throw new IllegalArgumentException("Invalid refresh");
        SQLiteDatabase db=getWritableDatabase();List<NoticeKey> inserted=new ArrayList<>();db.beginTransaction();
        try{for(CampusNotice notice:notices){if(notice==null)throw new IllegalArgumentException("Null notice");
            // Empty bodies are metadata, never proof that an article was successfully opened.
            boolean full=!metadataOnly&&!notice.bodyHtml.trim().isEmpty();
            ContentValues v=contentValues(notice,refreshedAtMillis,full);
            if(db.update("campus_notices",v,KEY_WHERE,keyArgs(notice.key))==0){
                v.put("source",notice.key.source);v.put("stable_id",notice.key.stableId);
                if(!full){v.put("body_html","");v.put("images_json",imagesJson(notice));v.put("attachments_json",attachmentsJson(notice));}
                db.insertOrThrow("campus_notices",null,v);inserted.add(notice.key);
            }
        }enforce(db,System.currentTimeMillis());db.setTransactionSuccessful();}finally{db.endTransaction();}
        return new UpsertResult(inserted);
    }
    public CachedNotice find(NoticeKey key){SQLiteDatabase db=getReadableDatabase();db.beginTransactionNonExclusive();try{CachedNotice found;try(Cursor c=db.query("campus_notices",LIGHT_COLUMNS,KEY_WHERE,keyArgs(key),null,null,null)){found=c.moveToFirst()?decode(db,c):null;}db.setTransactionSuccessful();return found;}finally{db.endTransaction();}}
    public List<CachedNotice> list(String source,boolean unreadOnly,boolean laterOnly,int limit,int offset){return query(NoticeQuery.builder(source).unreadOnly(unreadOnly).laterOnly(laterOnly).page(limit,offset).build());}
    public List<CachedNotice> query(NoticeQuery query){
        Selection selection=selection(query);SQLiteDatabase db=getReadableDatabase();List<CachedNotice> result=new ArrayList<>();
        db.beginTransactionNonExclusive();try{try(Cursor c=db.query("campus_notices",LIGHT_COLUMNS,selection.sql.toString(),selection.args(),null,null,"published_ms DESC,stable_id ASC,source ASC",query.offset+","+query.limit)){while(c.moveToNext())result.add(decode(db,c));}db.setTransactionSuccessful();return result;}finally{db.endTransaction();}
    }
    public long count(NoticeQuery query){Selection s=selection(query);try(Cursor c=getReadableDatabase().rawQuery("SELECT COUNT(*) FROM campus_notices WHERE "+s.sql,s.args())){c.moveToFirst();return c.getLong(0);}}
    /** Shares list/count rules without loading a body; page limit and offset do not affect membership. */
    public boolean matches(NoticeKey key,NoticeQuery query){Selection s=selection(query);s.sql.append(" AND source=? AND stable_id=?");Collections.addAll(s.values,keyArgs(key));try(Cursor c=getReadableDatabase().rawQuery("SELECT 1 FROM campus_notices WHERE "+s.sql+" LIMIT 1",s.args())){return c.moveToFirst();}}
    private static final class Selection{final StringBuilder sql=new StringBuilder("1=1");final List<String> values=new ArrayList<>();String[] args(){return values.toArray(new String[0]);}}
    private static Selection selection(NoticeQuery q){
        if(q==null)throw new IllegalArgumentException("Missing query");Selection s=new Selection();
        if(q.source!=null){s.sql.append(" AND source=?");s.values.add(q.source);}if(q.unreadOnly)s.sql.append(" AND is_read=0");if(q.laterOnly)s.sql.append(" AND is_later=1");
        if(!q.search.isEmpty())term(s,q.search,false);for(String value:q.includeKeywords)term(s,value,false);for(String value:q.excludeKeywords)term(s,value,true);
        for(String value:q.blockedKeywords)term(s,value,true);
        if(q.priorityOnly){
            if(q.priorityKeywords.isEmpty())s.sql.append(" AND 0=1");
            else{ s.sql.append(" AND (");boolean first=true;for(String value:q.priorityKeywords){if(!first)s.sql.append(" OR ");literalTerm(s,value);first=false;}s.sql.append(')'); }
        }
        if(!q.tagIds.isEmpty()){
            if(q.matchAllTags){for(Long id:q.tagIds){s.sql.append(" AND EXISTS(SELECT 1 FROM notice_tags nt WHERE nt.source=campus_notices.source AND nt.stable_id=campus_notices.stable_id AND nt.tag_id=?)");s.values.add(Long.toString(id));}}
            else{s.sql.append(" AND EXISTS(SELECT 1 FROM notice_tags nt WHERE nt.source=campus_notices.source AND nt.stable_id=campus_notices.stable_id AND nt.tag_id IN (");boolean first=true;for(Long id:q.tagIds){if(!first)s.sql.append(',');s.sql.append('?');s.values.add(Long.toString(id));first=false;}s.sql.append("))");}
        }return s;
    }
    private static void term(Selection s,String value,boolean exclude){s.sql.append(exclude?" AND NOT ":" AND ");literalTerm(s,value);}
    private static void literalTerm(Selection s,String value){s.sql.append("(title LIKE ? ESCAPE '\\' OR body_text LIKE ? ESCAPE '\\')");String literal="%"+value.replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%";s.values.add(literal);s.values.add(literal);}
    /** Auto-read must only happen after a real body was displayed. Metadata alone cannot trigger it. */
    public boolean markOpened(NoticeKey key){return setReadInternal(key,true,true);}
    public boolean markUnread(NoticeKey key){return setRead(key,false);}
    public boolean setRead(NoticeKey key,boolean read){return setReadInternal(key,read,false);}
    private boolean setReadInternal(NoticeKey key,boolean read,boolean requireBody){
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();try{
            String where=KEY_WHERE+(requireBody?" AND body_cached=1 AND length(trim(body_html))>0":"");
            List<String> args=new ArrayList<>();args.add(read?"1":"0");args.add(Long.toString(System.currentTimeMillis()));Collections.addAll(args,keyArgs(key));
            // Only the transition to read starts its age; refreshing or reopening does not extend it.
            android.database.sqlite.SQLiteStatement statement=db.compileStatement("UPDATE campus_notices SET is_read=?,read_at_ms=CASE WHEN ? < 0 THEN 0 WHEN is_read=1 THEN read_at_ms ELSE ? END WHERE "+where);
            int changed;
            try{statement.bindLong(1,read?1:0);statement.bindLong(2,read?0:-1);statement.bindLong(3,System.currentTimeMillis());statement.bindString(4,key.source);statement.bindString(5,key.stableId);changed=statement.executeUpdateDelete();}finally{statement.close();}
            enforce(db,System.currentTimeMillis());db.setTransactionSuccessful();return changed==1;
        }finally{db.endTransaction();}
    }
    public boolean setLater(NoticeKey key,boolean later){SQLiteDatabase db=getWritableDatabase();db.beginTransaction();try{ContentValues v=new ContentValues();v.put("is_later",later?1:0);boolean changed=db.update("campus_notices",v,KEY_WHERE,keyArgs(key))==1;enforce(db,System.currentTimeMillis());db.setTransactionSuccessful();return changed;}finally{db.endTransaction();}}
    public NoticeTag createTag(String name){String clean=tagName(name);SQLiteDatabase db=getWritableDatabase();ContentValues v=new ContentValues();v.put("name",clean);db.insertWithOnConflict("tags",null,v,SQLiteDatabase.CONFLICT_IGNORE);try(Cursor c=db.query("tags",new String[]{"id","name"},"name=? COLLATE NOCASE",new String[]{clean},null,null,null)){if(!c.moveToFirst())throw new IllegalStateException("Tag creation failed");return new NoticeTag(c.getLong(0),c.getString(1));}}
    public boolean renameTag(long tagId,String name){tagId(tagId);ContentValues v=new ContentValues();v.put("name",tagName(name));return getWritableDatabase().update("tags",v,"id=?",new String[]{Long.toString(tagId)})==1;}
    public boolean deleteTag(long tagId){tagId(tagId);SQLiteDatabase db=getWritableDatabase();db.beginTransaction();try{boolean deleted=db.delete("tags","id=?",new String[]{Long.toString(tagId)})==1;enforce(db,System.currentTimeMillis());db.setTransactionSuccessful();return deleted;}finally{db.endTransaction();}}
    public List<NoticeTag> listTags(){List<NoticeTag> result=new ArrayList<>();try(Cursor c=getReadableDatabase().query("tags",new String[]{"id","name"},null,null,null,null,"name COLLATE NOCASE,id")){while(c.moveToNext())result.add(new NoticeTag(c.getLong(0),c.getString(1)));}return result;}
    public List<NoticeTag> tags(NoticeKey key){return tags(getReadableDatabase(),key);}
    public void setTags(NoticeKey key,Collection<Long> tagIds){
        if(tagIds==null)throw new IllegalArgumentException("Missing tag IDs");LinkedHashSet<Long> unique=new LinkedHashSet<>();for(Long id:tagIds){if(id==null)throw new IllegalArgumentException("Null tag ID");tagId(id);unique.add(id);}
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();try{requireNotice(db,key);for(Long id:unique)requireTag(db,id);db.delete("notice_tags",KEY_WHERE,keyArgs(key));for(Long id:unique)attach(db,key,id);enforce(db,System.currentTimeMillis());db.setTransactionSuccessful();}finally{db.endTransaction();}
    }
    public boolean addTag(NoticeKey key,long tagId){tagId(tagId);SQLiteDatabase db=getWritableDatabase();db.beginTransaction();try{requireNotice(db,key);requireTag(db,tagId);boolean added=attach(db,key,tagId)!=-1;db.setTransactionSuccessful();return added;}finally{db.endTransaction();}}
    public boolean removeTag(NoticeKey key,long tagId){tagId(tagId);SQLiteDatabase db=getWritableDatabase();db.beginTransaction();try{boolean removed=db.delete("notice_tags",KEY_WHERE+" AND tag_id=?",new String[]{key.source,key.stableId,Long.toString(tagId)})==1;enforce(db,System.currentTimeMillis());db.setTransactionSuccessful();return removed;}finally{db.endTransaction();}}
    private static long attach(SQLiteDatabase db,NoticeKey key,long tagId){ContentValues v=new ContentValues();v.put("source",key.source);v.put("stable_id",key.stableId);v.put("tag_id",tagId);return db.insertWithOnConflict("notice_tags",null,v,SQLiteDatabase.CONFLICT_IGNORE);}
    private static void requireNotice(SQLiteDatabase db,NoticeKey key){try(Cursor c=db.query("campus_notices",new String[]{"stable_id"},KEY_WHERE,keyArgs(key),null,null,null)){if(!c.moveToFirst())throw new IllegalArgumentException("Unknown notice");}}
    private static void requireTag(SQLiteDatabase db,long id){try(Cursor c=db.query("tags",new String[]{"id"},"id=?",new String[]{Long.toString(id)},null,null,null)){if(!c.moveToFirst())throw new IllegalArgumentException("Unknown tag");}}
    private static void tagId(long id){if(id<=0)throw new IllegalArgumentException("Invalid tag ID");}
    private static String tagName(String name){String value=NoticeKey.requireText(name,"tag name").trim();if(value.length()>128)throw new IllegalArgumentException("Tag name too long");return value;}
    private static List<NoticeTag> tags(SQLiteDatabase db,NoticeKey key){List<NoticeTag> result=new ArrayList<>();try(Cursor c=db.rawQuery("SELECT t.id,t.name FROM tags t JOIN notice_tags nt ON nt.tag_id=t.id WHERE nt.source=? AND nt.stable_id=? ORDER BY t.name COLLATE NOCASE,t.id",keyArgs(key))){while(c.moveToNext())result.add(new NoticeTag(c.getLong(0),c.getString(1)));}return result;}
    public RetentionPolicy getRetentionPolicy(){return policy(getReadableDatabase());}
    public CacheStats setRetentionPolicy(RetentionPolicy policy){if(policy==null)throw new IllegalArgumentException("Missing policy");SQLiteDatabase db=getWritableDatabase();db.beginTransaction();try{ContentValues v=new ContentValues();v.put("max_entries",policy.maxCachedEntries);v.put("max_body_bytes",policy.maxBodyBytes);v.put("read_retention_ms",policy.readRetentionMillis);db.update("cache_policy",v,"singleton=1",null);enforce(db,System.currentTimeMillis());CacheStats result=stats(db);db.setTransactionSuccessful();return result;}finally{db.endTransaction();}}
    public CacheStats stats(){return stats(getReadableDatabase());}
    public int enforceRetention(){return enforceRetention(System.currentTimeMillis());}
    public int enforceRetention(long nowMillis){if(nowMillis<0)throw new IllegalArgumentException("Invalid cleanup time");SQLiteDatabase db=getWritableDatabase();db.beginTransaction();try{int cleared=enforce(db,nowMillis);db.setTransactionSuccessful();return cleared;}finally{db.endTransaction();}}
    public int clearReadBodies(){return clearBodiesWhere("body_cached=1 AND is_read=1 AND NOT "+PROTECTED);}
    public int clearBodies(){return clearBodiesWhere("body_cached=1");}
    private int clearBodiesWhere(String where){SQLiteDatabase db=getWritableDatabase();db.beginTransaction();try{int cleared=db.update("campus_notices",emptyBody(),where,null);db.setTransactionSuccessful();return cleared;}finally{db.endTransaction();}}
    public Set<String> cachedImageUrls(){SQLiteDatabase db=getReadableDatabase();db.beginTransactionNonExclusive();try{Set<String> urls=new LinkedHashSet<>();List<NoticeKey> keys=new ArrayList<>();try(Cursor c=db.query("campus_notices",new String[]{"source","stable_id"},"body_cached=1",null,null,null,null)){while(c.moveToNext())keys.add(NoticeKey.stored(c.getString(0),c.getString(1)));}for(NoticeKey key:keys){try{JSONArray a=new JSONArray(readText(db,key,"images_json"));for(int i=0;i<a.length();i++)urls.add(a.getString(i));}catch(JSONException e){throw new IllegalStateException("Corrupt image metadata",e);}}db.setTransactionSuccessful();return Collections.unmodifiableSet(urls);}finally{db.endTransaction();}}
    private static RetentionPolicy policy(SQLiteDatabase db){try(Cursor c=db.rawQuery("SELECT max_entries,max_body_bytes,read_retention_ms FROM cache_policy WHERE singleton=1",null)){if(!c.moveToFirst())throw new IllegalStateException("Cache policy missing");return new RetentionPolicy(c.getInt(0),c.getLong(1),c.getLong(2));}}
    private static CacheStats stats(SQLiteDatabase db){try(Cursor c=db.rawQuery("SELECT COUNT(*),COALESCE(SUM(body_cached),0),COALESCE(SUM(body_bytes),0) FROM campus_notices",null)){c.moveToFirst();return new CacheStats(c.getLong(0),c.getLong(1),c.getLong(2));}}
    private static int enforce(SQLiteDatabase db,long now){
        RetentionPolicy p=policy(db);int cleared=0;
        if(p.readRetentionMillis>=0&&p.readRetentionMillis<=now){String where="body_cached=1 AND is_read=1 AND read_at_ms<=? AND NOT "+PROTECTED;cleared+=db.update("campus_notices",emptyBody(),where,new String[]{Long.toString(now-p.readRetentionMillis)});}
        CacheStats initial=stats(db);long count=initial.cachedBodies,bytes=initial.bodyBytes;if(count<=p.maxCachedEntries&&bytes<=p.maxBodyBytes)return cleared;
        List<NoticeKey> keys=new ArrayList<>();List<Long> sizes=new ArrayList<>();
        try(Cursor c=db.query("campus_notices",new String[]{"source","stable_id","body_bytes"},"body_cached=1",null,null,null,"CASE WHEN "+PROTECTED+" THEN 1 ELSE 0 END ASC,is_read DESC,published_ms ASC,refreshed_ms ASC,source ASC,stable_id ASC")){while(c.moveToNext()){keys.add(NoticeKey.stored(c.getString(0),c.getString(1)));sizes.add(c.getLong(2));}}
        for(int i=0;i<keys.size()&&(count>p.maxCachedEntries||bytes>p.maxBodyBytes);i++){cleared+=db.update("campus_notices",emptyBody(),KEY_WHERE,keyArgs(keys.get(i)));count--;bytes-=sizes.get(i);}
        return cleared;
    }
    private static ContentValues emptyBody(){ContentValues v=new ContentValues();v.put("body_html","");v.put("body_text","");v.put("body_cached",0);v.put("body_bytes",0);return v;}
    public void setConnectionState(String source,ConnectionState state){NoticeKey.requireText(source,"source");if(state==null)throw new IllegalArgumentException("Missing state");ContentValues v=new ContentValues();v.put("source",source);v.put("state",state.name());getWritableDatabase().insertWithOnConflict("campus_connections",null,v,SQLiteDatabase.CONFLICT_REPLACE);}
    public ConnectionState connectionState(String source){NoticeKey.requireText(source,"source");try(Cursor c=getReadableDatabase().query("campus_connections",new String[]{"state"},"source=?",new String[]{source},null,null,null)){return c.moveToFirst()?ConnectionState.valueOf(c.getString(0)):ConnectionState.UNKNOWN;}}
    private static ContentValues contentValues(CampusNotice notice,long refreshed,boolean full){ContentValues v=new ContentValues();v.put("title",notice.title);v.put("published_ms",notice.publishedAtMillis);v.put("original_url",notice.originalUrl);v.put("refreshed_ms",refreshed);if(full){v.putAll(bodyValues(notice.bodyHtml));v.put("images_json",imagesJson(notice));v.put("attachments_json",attachmentsJson(notice));}return v;}
    @SuppressWarnings("deprecation") private static ContentValues bodyValues(String html){if(html.trim().isEmpty())return emptyBody();String withoutScripts=html.replaceAll("(?is)<(script|style)\\b[^>]*>.*?</\\1\\s*>","");String plain=Html.fromHtml(withoutScripts).toString().replace('\u00a0',' ').trim();ContentValues v=new ContentValues();v.put("body_html",html);v.put("body_text",plain);v.put("body_cached",1);v.put("body_bytes",(long)html.getBytes(StandardCharsets.UTF_8).length+plain.getBytes(StandardCharsets.UTF_8).length);return v;}
    private static String imagesJson(CampusNotice n){JSONArray a=new JSONArray();for(String u:n.imageUrls)a.put(u);return a.toString();}
    private static String attachmentsJson(CampusNotice n){JSONArray a=new JSONArray();try{for(CampusNotice.Attachment attachment:n.attachments){JSONObject o=new JSONObject();o.put("name",attachment.name);o.put("url",attachment.url);o.put("mimeType",attachment.mimeType);a.put(o);}}catch(JSONException e){throw new IllegalArgumentException("Invalid attachment",e);}return a.toString();}
    private static CachedNotice decode(SQLiteDatabase db,Cursor c){
        NoticeKey key=NoticeKey.stored(string(c,"source"),string(c,"stable_id"));boolean bodyCached=number(c,"body_cached")==1;
        try{List<String> images=new ArrayList<>();JSONArray rawImages=new JSONArray(readText(db,key,"images_json"));for(int i=0;i<rawImages.length();i++)images.add(rawImages.getString(i));List<CampusNotice.Attachment> attachments=new ArrayList<>();JSONArray rawAttachments=new JSONArray(readText(db,key,"attachments_json"));for(int i=0;i<rawAttachments.length();i++){JSONObject o=rawAttachments.getJSONObject(i);attachments.add(new CampusNotice.Attachment(o.getString("name"),o.getString("url"),o.getString("mimeType")));}
            CampusNotice notice=new CampusNotice(key,string(c,"title"),number(c,"published_ms"),bodyCached?readText(db,key,"body_html"):"",string(c,"original_url"),images,attachments);
            return new CachedNotice(notice,number(c,"is_read")==1,number(c,"is_later")==1,number(c,"refreshed_ms"),bodyCached,tags(db,key));
        }catch(JSONException e){throw new IllegalStateException("Corrupt resource metadata",e);}
    }
    /** Keep individual CursorWindow rows small even for a large (but within policy) body. */
    private static String readText(SQLiteDatabase db,NoticeKey key,String column){if(!Arrays.asList("body_html","body_text","images_json","attachments_json").contains(column))throw new IllegalArgumentException("Unexpected content column");StringBuilder out=new StringBuilder();final int chunk=131072;for(int offset=1;;offset+=chunk){String part;try(Cursor c=db.rawQuery("SELECT substr("+column+",?,?) FROM campus_notices WHERE "+KEY_WHERE,new String[]{Integer.toString(offset),Integer.toString(chunk),key.source,key.stableId})){if(!c.moveToFirst())return "";part=c.getString(0);}out.append(part);if(part.codePointCount(0,part.length())<chunk)return out.toString();}}
    private static String string(Cursor c,String col){return c.getString(c.getColumnIndexOrThrow(col));}
    private static long number(Cursor c,String col){return c.getLong(c.getColumnIndexOrThrow(col));}
    private static String[] keyArgs(NoticeKey key){if(key==null)throw new IllegalArgumentException("Missing key");return new String[]{key.source,key.stableId};}
    private static String databaseName(String profile){NoticeKey.requireText(profile,"localProfileId");try{byte[] digest=MessageDigest.getInstance("SHA-256").digest(profile.getBytes(StandardCharsets.UTF_8));StringBuilder hex=new StringBuilder();for(byte b:digest)hex.append(String.format(Locale.ROOT,"%02x",b&255));return "campus-notices-"+hex+".db";}catch(NoSuchAlgorithmException e){throw new AssertionError(e);}}
}
