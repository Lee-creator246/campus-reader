package org.campusreader.reader;

import android.content.Context;
import android.content.ContextWrapper;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/** Short isolated paths avoid native SQLite's Windows path limit on long test method names. */
final class DatabaseTestContext extends ContextWrapper {
    private final File directory;
    DatabaseTestContext(Context base)throws IOException{super(base);directory=Files.createTempDirectory("cr-db-").toFile();}
    @Override public Context getApplicationContext(){return this;}
    @Override public File getDatabasePath(String name){return new File(directory,name);}
    void dispose(){File[] files=directory.listFiles();if(files!=null)for(File file:files)file.delete();directory.delete();}
}
