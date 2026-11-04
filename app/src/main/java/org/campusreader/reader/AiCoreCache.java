package org.campusreader.reader;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.content.ContentValues;
import org.campusreader.notices.*;
import org.json.*;

/** Notice-only facts survive unrelated policy edits. No policy text or links are retained here. */
final class AiCoreCache {
    private static final java.util.Set<String> INITIALIZED=new java.util.HashSet<>();
    private AiCoreCache(){}
    private static SQLiteDatabase database(CampusNoticeStore store){
        SQLiteDatabase db=store.getWritableDatabase();synchronized(INITIALIZED){if(INITIALIZED.contains(db.getPath()))return db;
        db.execSQL("CREATE TABLE IF NOT EXISTS ai_core_summaries (source TEXT NOT NULL,stable_id TEXT NOT NULL,content_hash TEXT NOT NULL,config_hash TEXT NOT NULL,result_json TEXT NOT NULL CHECK(length(CAST(result_json AS BLOB))<=16384),generated_ms INTEGER NOT NULL,PRIMARY KEY(source,stable_id),FOREIGN KEY(source,stable_id) REFERENCES campus_notices(source,stable_id) ON DELETE CASCADE)");
        db.execSQL("CREATE TRIGGER IF NOT EXISTS invalidate_ai_core AFTER UPDATE OF title,body_html,body_text,body_cached ON campus_notices WHEN NEW.title<>OLD.title OR NEW.body_html<>OLD.body_html OR NEW.body_text<>OLD.body_text OR NEW.body_cached=0 BEGIN DELETE FROM ai_core_summaries WHERE source=NEW.source AND stable_id=NEW.stable_id; END");
        INITIALIZED.add(db.getPath());return db;}
    }
    private static String hash(AiSettings.Config config){return SummaryInput.hash(config.configHash+"\u0000"+AiBriefSummary.VERSION+"\u0000notice-core");}
    static boolean hasFull(CampusNoticeStore store,NoticeKey key){try(Cursor row=store.getReadableDatabase().query("notice_summaries",new String[]{"stable_id"},"source=? AND stable_id=?",new String[]{key.source,key.stableId},null,null,null)){return row.moveToFirst();}}
    static SummaryRecord find(CampusNoticeStore store,NoticeKey key,SummaryInput input,AiSettings.Config config){
        try(Cursor row=database(store).query("ai_core_summaries",new String[]{"result_json","generated_ms"},"source=? AND stable_id=? AND content_hash=? AND config_hash=?",new String[]{key.source,key.stableId,input.contentHash,hash(config)},null,null,null)){
            return row.moveToFirst()?new SummaryRecord(input.contentHash,hash(config),row.getString(0),row.getLong(1)):null;
        }
    }
    static boolean save(CampusNoticeStore store,NoticeKey key,SummaryInput input,AiSettings.Config config,AiBriefSummary summary,long at)throws JSONException{
        SQLiteDatabase db=database(store);db.beginTransaction();try{
            SummaryInput current=store.summaryInput(key);if(current==null||current.cacheGeneration!=input.cacheGeneration||!current.contentHash.equals(input.contentHash))return false;
            JSONObject raw=new JSONObject(summary.json);raw.put("policy_links",new JSONArray());
            AiEvidence.Snapshot context=AiAutoSummaries.context(current,new PolicyLibrary.Snapshot(0,java.util.Collections.emptyList()),false);
            // Revalidate against notice-only units before persisting; policy IDs cannot survive stripping.
            AiBriefSummary core=AiBriefSummary.parseCached(raw.toString(),context);
            ContentValues value=new ContentValues();value.put("source",key.source);value.put("stable_id",key.stableId);value.put("content_hash",input.contentHash);value.put("config_hash",hash(config));value.put("result_json",core.json);value.put("generated_ms",at);
            boolean saved=db.insertWithOnConflict("ai_core_summaries",null,value,SQLiteDatabase.CONFLICT_REPLACE)>=0;db.setTransactionSuccessful();return saved;
        }finally{db.endTransaction();}
    }
    static int clear(CampusNoticeStore store){return database(store).delete("ai_core_summaries",null,null);}
    /** Before policy invalidation, preserve any v9 result written before core caching was introduced. */
    static void preserve(CampusNoticeStore store,AiSettings.Config config,PolicyLibrary.Snapshot policies){
        java.util.List<NoticeKey> keys=new java.util.ArrayList<>();
        try(Cursor rows=store.getReadableDatabase().query("notice_summaries",new String[]{"source","stable_id"},null,null,null,null,"generated_ms DESC","300")){while(rows.moveToNext())keys.add(CampusNoticeStore.summaryKey(rows.getString(0),rows.getString(1)));}
        for(NoticeKey key:keys)try{SummaryInput input=store.summaryInput(key);if(input==null)continue;
            for(boolean include:new boolean[]{false,true}){AiEvidence.Snapshot context=AiAutoSummaries.context(input,policies,include);SummaryRecord record=store.findSummary(key,input.contentHash,AiAutoSummaries.cacheHash(context,config));if(record!=null){AiBriefSummary parsed=AiBriefSummary.parseCached(record.resultJson,context);save(store,key,input,config,parsed,record.generatedAtMillis);break;}}
        }catch(Exception unsupported){/* Legacy extracts or unverifiable records are not converted. */}
    }
}
