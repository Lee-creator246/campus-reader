package org.campusreader.reader;

import android.content.Context;
import org.campusreader.notices.CampusNoticeStore;
import java.io.*;
import java.util.*;

/** Bounded, account-scoped thumbnails. Exported gallery pictures are never removed here. */
public final class ImageCacheMaintenance {
    public static final Object LOCK=new Object();
    private static File directory(Context c,String p){return new File(c.getCacheDir(),"images-"+RssParser.digest(p));}
    public static void prune(Context c,String profile) {
        Set<String> keep=new HashSet<>();
        try(CampusNoticeStore store=new CampusNoticeStore(c,profile)) {
            for(String url:store.cachedImageUrls())keep.add(RssParser.digest(url));
        }
        synchronized(LOCK) {
            File[] files=directory(c,profile).listFiles();if(files==null)return;
            long limit=new ReaderPreferences(c).getImageCacheLimitBytes(),total=0;
            List<File> remaining=new ArrayList<>();
            for(File f:files){if(!f.isFile())continue;if(!keep.contains(f.getName())||limit==0){f.delete();continue;}total+=f.length();remaining.add(f);}
            remaining.sort(Comparator.comparingLong(File::lastModified));
            for(File f:remaining){if(total<=limit)break;long n=f.length();if(f.delete())total-=n;}
        }
    }
    public static byte[] read(Context c,String profile,String url) throws IOException {
        synchronized(LOCK){File f=new File(directory(c,profile),RssParser.digest(url));if(!f.isFile()||f.length()>12L*1024*1024)return null;
            try(InputStream in=new FileInputStream(f);ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);f.setLastModified(System.currentTimeMillis());return out.toByteArray();}}
    }
    public static void put(Context c,String profile,String url,byte[] bytes) throws IOException {
        long limit=new ReaderPreferences(c).getImageCacheLimitBytes();if(limit<=0||bytes.length>limit)return;
        synchronized(LOCK){File dir=directory(c,profile);if(!dir.isDirectory()&&!dir.mkdirs())return;
            File[] files=dir.listFiles();long size=0;if(files!=null){Arrays.sort(files,Comparator.comparingLong(File::lastModified));for(File f:files)size+=f.length();for(File f:files){if(size+bytes.length<=limit)break;long n=f.length();if(f.delete())size-=n;}}
            File target=new File(dir,RssParser.digest(url));File temp=File.createTempFile("pending-",".part",dir);
            try{try(OutputStream out=new FileOutputStream(temp)){out.write(bytes);}if(!temp.renameTo(target))throw new IOException("Cannot commit image cache");}finally{temp.delete();}}
    }
    public static long size(Context c,String profile){synchronized(LOCK){long n=0;File[] files=directory(c,profile).listFiles();if(files!=null)for(File f:files)if(f.isFile())n+=f.length();return n;}}
}
