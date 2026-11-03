package org.campusreader.reader;

import android.content.Context;
import android.util.AtomicFile;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.time.LocalDate;
import java.util.*;
import org.json.*;
import org.campusreader.notices.CampusNoticeStore;
import org.campusreader.notices.SummaryInput;

/** Account-local, extracted policy text. Metadata is a user's assertion, not verification. */
public final class PolicyLibrary {
    public static final int MAX_DOCUMENTS=20,MAX_PAGES=40,MAX_DOCUMENT_CHARS=100000,MAX_TOTAL_CHARS=1000000;
    private static final int MAX_LIBRARY_BYTES=8*1024*1024;
    public static final String[] STATUSES={"待核验","有效（自行确认）","已过期","已废止"};
    private static final Map<String,Snapshot> CACHE=new LinkedHashMap<String,Snapshot>(4,.75f,true){
        @Override protected boolean removeEldestEntry(Map.Entry<String,Snapshot> eldest){return size()>3;}
    };
    private final Context context;
    private final String profile;
    private final AtomicFile file;
    public static final class Metadata {
        public final String title,issuer,version,validFrom,validUntil,scope,status,sourceLabel;
        public Metadata(String title,String issuer,String version,String validFrom,String validUntil,String scope,String status,String sourceLabel){
            this.title=text(title,200,true);this.issuer=text(issuer,200,false);this.version=text(version,100,false);
            this.validFrom=date(validFrom);this.validUntil=date(validUntil);this.scope=text(scope,500,false);
            this.status=text(status,30,true);this.sourceLabel=text(sourceLabel,500,false);
            if(!Arrays.asList(STATUSES).contains(this.status))throw new IllegalArgumentException("请选择资料状态");
            if(!this.validFrom.isEmpty()&&!this.validUntil.isEmpty()&&this.validFrom.compareTo(this.validUntil)>0)throw new IllegalArgumentException("失效日期不可早于生效日期");
        }
    }
    public static final class Document {
        public final String id,title,issuer,version,validFrom,validUntil,scope,status,sourceLabel,contentHash;
        public final List<String> pages;
        Document(String id,Metadata metadata,List<String> pages){
            if(id==null||!id.matches("[a-f0-9-]{36}"))throw new IllegalArgumentException("Invalid document ID");
            this.id=id;title=metadata.title;issuer=metadata.issuer;version=metadata.version;validFrom=metadata.validFrom;validUntil=metadata.validUntil;scope=metadata.scope;status=metadata.status;sourceLabel=metadata.sourceLabel;
            if(pages==null||pages.isEmpty()||pages.size()>MAX_PAGES)throw new IllegalArgumentException("资料最多 40 页");
            ArrayList<String> copy=new ArrayList<>();int length=0;boolean hasText=false;
            for(String page:pages){if(page==null||page.indexOf('\0')>=0)throw new IllegalArgumentException("资料文字格式不受支持");length+=page.length();if(length>MAX_DOCUMENT_CHARS)throw new IllegalArgumentException("单份资料最多 100000 字符");hasText|=!page.trim().isEmpty();copy.add(page);}
            if(!hasText)throw new IllegalArgumentException("没有可提取文字；扫描件请先转换为文字版");
            this.pages=Collections.unmodifiableList(copy);
            try{contentHash=SummaryInput.hash(toJson(false).toString());}catch(JSONException impossible){throw new IllegalArgumentException("Invalid document",impossible);}
        }
        public Metadata metadata(){return new Metadata(title,issuer,version,validFrom,validUntil,scope,status,sourceLabel);}
        JSONObject toJson(boolean includeId)throws JSONException{
            JSONObject result=new JSONObject().put("title",title).put("issuer",issuer).put("version",version).put("validFrom",validFrom).put("validUntil",validUntil).put("scope",scope).put("status",status).put("sourceLabel",sourceLabel).put("pages",new JSONArray(pages));
            if(includeId)result.put("id",id);return result;
        }
    }
    public static final class Snapshot {
        public final long revision;
        public final List<Document> documents;
        Snapshot(long revision,List<Document> documents){this.revision=revision;this.documents=Collections.unmodifiableList(new ArrayList<>(documents));}
    }
    public PolicyLibrary(Context context,String profile){
        if(profile==null||!(profile.equals("local")||profile.matches("campus:[a-f0-9]{64}")))throw new IllegalArgumentException("Invalid profile");
        this.context=context.getApplicationContext();this.profile=profile;
        file=new AtomicFile(new File(this.context.getNoBackupFilesDir(),"policies-"+SummaryInput.hash(profile)+".json"));
    }
    public Snapshot snapshot(){synchronized(AiSettings.LOCK){return read();}}
    public long revision(){return snapshot().revision;}
    public boolean isCurrent(long revision){return revision()==revision;}
    public Document add(Metadata metadata,List<String> pages){synchronized(AiSettings.LOCK){
        Snapshot prior=read();if(prior.documents.size()>=MAX_DOCUMENTS)throw new IllegalArgumentException("资料库最多 20 份，请先删除不再需要的资料");
        Document next=new Document(UUID.randomUUID().toString(),metadata,pages);
        for(Document document:prior.documents)if(document.contentHash.equals(next.contentHash))throw new IllegalArgumentException("相同资料已在资料库中，无需重复导入");
        ArrayList<Document> all=new ArrayList<>(prior.documents);all.add(next);write(prior.revision,all);return next;
    }}
    public void update(String id,Metadata metadata){synchronized(AiSettings.LOCK){
        Snapshot prior=read();ArrayList<Document> all=new ArrayList<>(prior.documents);int index=index(all,id);if(index<0)throw new IllegalArgumentException("资料已删除，请重新打开资料库");
        all.set(index,new Document(id,metadata,all.get(index).pages));write(prior.revision,all);
    }}
    public boolean delete(String id){synchronized(AiSettings.LOCK){Snapshot prior=read();ArrayList<Document> all=new ArrayList<>(prior.documents);int index=index(all,id);if(index<0)return false;all.remove(index);write(prior.revision,all);return true;}}
    public void clear(){synchronized(AiSettings.LOCK){Snapshot prior=read();write(prior.revision,Collections.emptyList());}}
    private static int index(List<Document> docs,String id){for(int i=0;i<docs.size();i++)if(docs.get(i).id.equals(id))return i;return -1;}
    private Snapshot read(){
        String cacheKey=file.getBaseFile().getAbsolutePath();Snapshot cached=CACHE.get(cacheKey);if(cached!=null)return cached;
        if(!file.getBaseFile().exists()&&!new File(file.getBaseFile().getPath()+".bak").exists()){Snapshot empty=new Snapshot(0,Collections.emptyList());CACHE.put(cacheKey,empty);return empty;}
        try(InputStream input=file.openRead()){
            byte[] data=PolicyImport.readBounded(input,MAX_LIBRARY_BYTES);
            String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data)).toString();
            JSONObject json=new JSONObject(text);if(json.getInt("schema")!=1)throw new IOException("Unsupported schema");
            long revision=json.getLong("revision");if(revision<0)throw new IOException("Invalid revision");
            JSONArray array=json.getJSONArray("documents");if(array.length()>MAX_DOCUMENTS)throw new IOException("Document count");
            ArrayList<Document> docs=new ArrayList<>();HashSet<String> ids=new HashSet<>();
            for(int i=0;i<array.length();i++){JSONObject d=array.getJSONObject(i);JSONArray p=d.getJSONArray("pages");ArrayList<String> pages=new ArrayList<>();for(int j=0;j<p.length();j++)pages.add(p.getString(j));
                Document doc=new Document(d.getString("id"),new Metadata(d.getString("title"),d.getString("issuer"),d.getString("version"),d.getString("validFrom"),d.getString("validUntil"),d.getString("scope"),d.getString("status"),d.getString("sourceLabel")),pages);
                if(!ids.add(doc.id))throw new IOException("Duplicate document ID");docs.add(doc);
            }
            validateTotal(docs);Snapshot loaded=new Snapshot(revision,docs);CACHE.put(cacheKey,loaded);return loaded;
        }catch(Exception failure){throw new IllegalStateException("本机政策资料库无法读取，未使用其中内容；请保留数据并检查存储空间",failure);}
    }
    private void write(long oldRevision,List<Document> docs){
        validateTotal(docs);if(oldRevision==Long.MAX_VALUE)throw new IllegalStateException("资料库版本已达到上限");
        try{
            JSONArray values=new JSONArray();for(Document doc:docs)values.put(doc.toJson(true));
            byte[] data=new JSONObject().put("schema",1).put("revision",oldRevision+1).put("documents",values).toString().getBytes(StandardCharsets.UTF_8);
            if(data.length>MAX_LIBRARY_BYTES)throw new IllegalArgumentException("资料库已达到本机容量上限");
            // Fail closed: old outputs and in-flight generations are invalid before any policy change.
            new AiSettings(context,profile).invalidateRequests();
            try(CampusNoticeStore store=new CampusNoticeStore(context,profile)){store.clearSummaries();}
            // A cancelled verification may follow a successful disk commit. Never leave the
            // pre-commit snapshot in memory; the next reader must discover actual disk state.
            CACHE.remove(file.getBaseFile().getAbsolutePath());
            FileOutputStream output=null;try{output=file.startWrite();output.write(data);file.finishWrite(output);}catch(IOException error){if(output!=null)file.failWrite(output);throw error;}
            try(InputStream verify=file.openRead()){if(!Arrays.equals(data,PolicyImport.readBounded(verify,MAX_LIBRARY_BYTES)))throw new IOException("Policy persistence verification failed");}
            CACHE.put(file.getBaseFile().getAbsolutePath(),new Snapshot(oldRevision+1,docs));
        }catch(IOException|JSONException failure){throw new IllegalStateException("政策资料未能保存，请检查本机存储空间后重试",failure);}
    }
    private static void validateTotal(List<Document> docs){int size=0;for(Document doc:docs)for(String page:doc.pages){size+=page.length();if(size>MAX_TOTAL_CHARS)throw new IllegalArgumentException("资料库文字总量最多 1000000 字符，请先清理旧资料");}}
    private static String text(String value,int max,boolean required){String clean=value==null?"":value.trim();if(clean.length()>max||clean.indexOf('\0')>=0||(required&&clean.isEmpty()))throw new IllegalArgumentException(required?"请填写标题及资料状态，且不要超过字段长度限制":"资料字段超过长度限制");return clean;}
    private static String date(String value){String clean=text(value,10,false);if(clean.isEmpty())return clean;try{if(!clean.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))throw new IllegalArgumentException();LocalDate.parse(clean);return clean;}catch(RuntimeException invalid){throw new IllegalArgumentException("日期须为有效的 YYYY-MM-DD；未知请留空");}}
}
