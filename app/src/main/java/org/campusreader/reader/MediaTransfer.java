package org.campusreader.reader;

import android.content.Context;
import android.graphics.BitmapFactory;
import android.webkit.CookieManager;
import org.campusreader.auth.CampusApplication;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Worker-thread streaming transfers; cookies are scoped to the exact school portal only. */
final class MediaTransfer {
    static final long MAX_ATTACHMENT = 20L * 1024 * 1024, MAX_IMAGE = 12L * 1024 * 1024;
    static final long MAX_CACHE = 100L * 1024 * 1024;
    // Per-resource serialization prevents a simultaneous image/attachment request from replacing
    // another caller's same-URL result. Fixed stripes avoid an unbounded URL/credential-key map.
    private static final Object[] FETCH_LOCKS=new Object[64];
    static {for(int i=0;i<FETCH_LOCKS.length;i++)FETCH_LOCKS[i]=new Object();}
    static final class Entry {
        final String profileHash, id, name, mime; final File file;
        Entry(String profileHash, String id, String name, String mime, File file) {
            this.profileHash=profileHash;this.id=id;this.name=name;this.mime=mime;this.file=file;
        }
    }
    static File root(Context context) { return new File(context.getCacheDir(), "campus-media"); }
    static String hash(String value) { return RssParser.digest(value); }
    static void requireProfile(String profile) throws IOException {
        if (!MediaActions.currentProfile(profile)) throw new MediaSafety.Rejected("ACCOUNT_CHANGED");
    }
    static Entry fetch(Context context, String profile, String address, String hint, boolean image) throws Exception {
        return fetch(context,profile,address,hint,image,null);
    }
    static Entry fetch(Context context, String profile, String address, String hint, boolean image,String referer) throws Exception {
        int stripe=((String.valueOf(profile)+"\u0000"+address).hashCode()&0x7fffffff)%FETCH_LOCKS.length;
        synchronized(FETCH_LOCKS[stripe]){return fetchLocked(context,profile,address,hint,image,referer);}
    }
    private static Entry fetchLocked(Context context,String profile,String address,String hint,boolean image,String referer) throws Exception {
        requireProfile(profile);
        boolean campus = ReaderHttp.isCampus(address);
        if(campus&&"local".equals(profile))throw new MediaSafety.Rejected("CAMPUS_ACCOUNT_REQUIRED");
        URL original = MediaSafety.checked(address, campus);
        String profileHash = hash(profile), id = hash(address);
        Entry cached = read(context, profileHash, id);
        if (cached != null && cached.file.length() <= (image ? MAX_IMAGE : MAX_ATTACHMENT)) {
            if (!image || imageMime(cached.file) != null) { cached.file.setLastModified(System.currentTimeMillis()); return cached; }
        }
        File directory = new File(root(context), profileHash);
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("无法创建下载缓存");
        File temporary = File.createTempFile("incoming-", ".part", directory);
        try {
            // Prefer existing per-account image cache used by the article renderer, including offline use.
            if (image) {
                File existing = new File(new File(context.getCacheDir(), "images-" + hash(profile)), hash(address));
                if (existing.isFile() && existing.length() > 0 && existing.length() <= MAX_IMAGE && imageMime(existing) != null) {
                    try (InputStream input = new FileInputStream(existing); OutputStream output = new FileOutputStream(temporary)) {
                        MediaSafety.copyBounded(input, output, MAX_IMAGE, existing.length());
                    }
                    return commit(context, profile, profileHash, id, temporary, hint, imageMime(temporary));
                }
            }
            if (campus && !CampusApplication.auth().isAccessReady()) throw new MediaSafety.Rejected("CAMPUS_OFFLINE");
            URL current = original;
            for (int redirects=0; redirects <= 5; redirects++) {
                requireProfile(profile);
                HttpURLConnection connection = (HttpURLConnection) current.openConnection();
                try {
                    connection.setConnectTimeout(15000); connection.setReadTimeout(20000); connection.setInstanceFollowRedirects(false);
                    connection.setRequestProperty("User-Agent", ReaderHttp.USER_AGENT);
                    connection.setRequestProperty("Accept-Encoding", "identity");
                    if(campus){
                        URL from=referer==null?new URL(original,"/"):MediaSafety.checked(referer,true);
                        if(!("https".equals(from.getProtocol())&&"http".equals(current.getProtocol())))connection.setRequestProperty("Referer",from.toString());
                    }
                    if (campus) {
                        String cookie = CookieManager.getInstance().getCookie(current.toString());
                        if (cookie != null && !cookie.isEmpty()) connection.setRequestProperty("Cookie", cookie);
                    }
                    int status = connection.getResponseCode();
                    if(!image)android.util.Log.i("CampusMedia","event=attachment_response redirect="+redirects+" http="+status);
                    requireProfile(profile);
                    if (campus) for (Map.Entry<String,List<String>> header : connection.getHeaderFields().entrySet()) {
                        if ("set-cookie".equalsIgnoreCase(header.getKey())) for (String cookie : header.getValue()) {
                            requireProfile(profile);CookieManager.getInstance().setCookie(current.toString(), cookie);
                        }
                    }
                    if (status==301||status==302||status==303||status==307||status==308) {
                        String location=connection.getHeaderField("Location");
                        if (location==null) throw new MediaSafety.Rejected("INVALID_REDIRECT");
                        current=MediaSafety.redirect(original,current,location,campus);continue;
                    }
                    if (status != 200) throw new MediaSafety.Rejected("HTTP_"+status);
                    String mime = MediaSafety.mime(connection.getContentType(), hint);
                    long declared = -1;
                    try { declared = Long.parseLong(connection.getHeaderField("Content-Length")); } catch (Exception ignored) {}
                    try (InputStream input=connection.getInputStream(); OutputStream output=new FileOutputStream(temporary)) {
                        MediaSafety.copyBounded(input,output,image?MAX_IMAGE:MAX_ATTACHMENT,declared);
                    }
                    byte[] prefix;
                    try (InputStream input=new FileInputStream(temporary)) {
                        byte[] buffer=new byte[8192];int count=input.read(buffer);prefix=Arrays.copyOf(buffer,Math.max(0,count));
                    }
                    if (temporary.length()==0)throw new MediaSafety.Rejected("EMPTY_FILE");
                    if (MediaSafety.looksHtml(mime,prefix)) throw new MediaSafety.Rejected("LOGIN_OR_HTML");
                    String name=MediaSafety.filename(connection.getHeaderField("Content-Disposition"),hint);
                    if("application/octet-stream".equals(mime)) {
                        String extension=android.webkit.MimeTypeMap.getFileExtensionFromUrl(name.replace(" ","_"));
                        String inferred=android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.toLowerCase(Locale.ROOT));
                        if(inferred!=null)mime=inferred;
                    }
                    if (image) { mime=imageMime(temporary); if (mime==null) throw new IOException("不是支持的图片格式"); }
                    return commit(context,profile,profileHash,id,temporary,name,mime);
                } finally { connection.disconnect(); }
            }
            throw new MediaSafety.Rejected("TOO_MANY_REDIRECTS");
        } finally { if (temporary.exists()) temporary.delete(); }
    }
    static String imageMime(File file) {
        BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;
        BitmapFactory.decodeFile(file.getAbsolutePath(),options);
        if(options.outWidth<1||options.outHeight<1||((long)options.outWidth)*options.outHeight>100_000_000L) return null;
        return options.outMimeType!=null&&options.outMimeType.startsWith("image/")?options.outMimeType:null;
    }
    private static synchronized Entry commit(Context context,String profile,String profileHash,String id,File temporary,String hint,String mime) throws Exception {
        requireProfile(profile);
        String name=MediaSafety.filename(null,hint);
        if(mime.startsWith("image/")&&!name.matches("(?i).*\\.(png|jpe?g|webp|gif|bmp)$")) name += extension(mime);
        File target=new File(temporary.getParentFile(),id+".data");
        trim(context,temporary.length(),target);
        if(target.exists()&&!target.delete()) throw new IOException("无法替换旧下载");
        if(!temporary.renameTo(target)) throw new IOException("无法保存下载");
        JSONObject metadata=new JSONObject();metadata.put("name",name);metadata.put("mime",mime);
        File meta=new File(target.getParentFile(),id+".json");
        try(OutputStream output=new FileOutputStream(meta)) { output.write(metadata.toString().getBytes(StandardCharsets.UTF_8)); }
        catch(IOException failure){target.delete();meta.delete();throw failure;}
        return new Entry(profileHash,id,name,mime,target);
    }
    static String extension(String mime) { if("image/jpeg".equals(mime))return ".jpg";if("image/webp".equals(mime))return ".webp";if("image/gif".equals(mime))return ".gif";if("image/bmp".equals(mime))return ".bmp";return ".png"; }
    static synchronized Entry read(Context context,String profileHash,String id) {
        if(profileHash==null||id==null||!profileHash.matches("[a-f0-9]{64}")||!id.matches("[a-f0-9]{64}"))return null;
        File directory=new File(root(context),profileHash), data=new File(directory,id+".data"), meta=new File(directory,id+".json");
        if(!data.isFile()||!meta.isFile()||meta.length()>4096)return null;
        try(InputStream input=new FileInputStream(meta);ByteArrayOutputStream output=new ByteArrayOutputStream()) {
            MediaSafety.copyBounded(input,output,4096,meta.length());JSONObject obj=new JSONObject(new String(output.toByteArray(),StandardCharsets.UTF_8));
            return new Entry(profileHash,id,MediaSafety.filename(null,obj.getString("name")),MediaSafety.mime(obj.getString("mime"),""),data);
        }catch(Exception e){return null;}
    }
    static synchronized List<Entry> list(Context context,String profile) {
        List<Entry> result=new ArrayList<>();File directory=new File(root(context),hash(profile));File[] files=directory.listFiles();
        if(files!=null)for(File file:files)if(file.getName().endsWith(".data")) {
            Entry entry=read(context,hash(profile),file.getName().replace(".data",""));if(entry!=null)result.add(entry);
        }
        result.sort((a,b)->Long.compare(b.file.lastModified(),a.file.lastModified()));return result;
    }
    static synchronized void delete(Entry entry) { entry.file.delete();new File(entry.file.getParentFile(),entry.id+".json").delete(); }
    static synchronized void clear(Context context,String profile) { for(Entry entry:list(context,profile))delete(entry); }
    private static void trim(Context context,long incoming,File excluded) throws IOException {
        List<File> data=new ArrayList<>();long size=0;File[] dirs=root(context).listFiles();
        if(dirs!=null)for(File dir:dirs){File[] files=dir.listFiles();if(files!=null)for(File file:files){
            if(file.getName().endsWith(".part")&&System.currentTimeMillis()-file.lastModified()>3600_000)file.delete();
            if(file.getName().endsWith(".data")&&!file.equals(excluded)){data.add(file);size+=file.length()+4096;}
        }}
        data.sort(Comparator.comparingLong(File::lastModified));
        int count=data.size();
        for(File file:data) { if(size+incoming+4096<=MAX_CACHE&&count<500)break;long length=file.length()+4096;if(file.delete()){count--;size-=length;new File(file.getParentFile(),file.getName().replace(".data",".json")).delete();} }
        if(size+incoming+4096>MAX_CACHE||count>=500)throw new IOException("下载缓存已满，请先清理下载");
    }
}
