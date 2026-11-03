package org.campusreader.auth;

import android.content.Context;
import android.util.AtomicFile;
import org.campusreader.config.SchoolDirectory;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Offline directory cache only; importing never changes the active site or SDK consent. */
public final class SchoolDirectoryStore {
    private final AtomicFile cache;
    public SchoolDirectoryStore(Context context){cache=new AtomicFile(new File(context.getNoBackupFilesDir(),"school-directory-v1.json"));}
    public SchoolDirectory load(){try(InputStream input=cache.openRead()){return SchoolDirectory.parse(SchoolDirectory.readText(input));}catch(Exception unavailable){return null;}}
    public void save(String document)throws java.io.IOException{
        SchoolDirectory.parse(document);FileOutputStream output=null;
        try{output=cache.startWrite();output.write(document.getBytes(StandardCharsets.UTF_8));cache.finishWrite(output);}
        catch(java.io.IOException failure){if(output!=null)cache.failWrite(output);throw failure;}
    }
}
