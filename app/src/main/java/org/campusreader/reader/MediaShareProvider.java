package org.campusreader.reader;

import android.content.*;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.*;
import java.util.List;

/** Private, read-only provider. Only an explicit temporary Android URI grant permits another app. */
public final class MediaShareProvider extends ContentProvider {
    static Uri uri(Context context,MediaTransfer.Entry entry) {
        return new Uri.Builder().scheme("content").authority(context.getPackageName()+".media")
                .appendPath(entry.profileHash).appendPath(entry.id).appendPath(entry.revision).build();
    }
    private MediaTransfer.Entry entry(Uri uri) throws FileNotFoundException {
        List<String> parts=uri.getPathSegments();
        if(!getContext().getPackageName().concat(".media").equals(uri.getAuthority())||parts.size()!=3)throw new FileNotFoundException();
        MediaTransfer.Entry entry=MediaTransfer.read(getContext(),parts.get(0),parts.get(1));
        try {
            if(entry==null||!entry.revision.equals(parts.get(2))||!entry.file.getCanonicalPath().startsWith(MediaTransfer.root(getContext()).getCanonicalPath()+File.separator))throw new FileNotFoundException();
        }catch(IOException e){throw new FileNotFoundException();}
        return entry;
    }
    @Override public boolean onCreate(){return true;}
    @Override public String getType(Uri uri){synchronized(MediaTransfer.class){try{return entry(uri).mime;}catch(FileNotFoundException e){return "application/octet-stream";}}}
    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String order) {
        synchronized(MediaTransfer.class){
        MatrixCursor result=new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE});
        try{MediaTransfer.Entry entry=entry(uri);result.addRow(new Object[]{entry.name,entry.file.length()});}catch(FileNotFoundException ignored){}
        return result;
        }
    }
    @Override public ParcelFileDescriptor openFile(Uri uri,String mode) throws FileNotFoundException {
        if(!"r".equals(mode))throw new FileNotFoundException("Read only");
        // Keep revision validation and file opening atomic with cache replacement/eviction.
        synchronized(MediaTransfer.class){return ParcelFileDescriptor.open(entry(uri).file,ParcelFileDescriptor.MODE_READ_ONLY);}
    }
    @Override public Uri insert(Uri uri,ContentValues values){throw new UnsupportedOperationException();}
    @Override public int delete(Uri uri,String selection,String[] args){throw new UnsupportedOperationException();}
    @Override public int update(Uri uri,ContentValues values,String selection,String[] args){throw new UnsupportedOperationException();}
}
