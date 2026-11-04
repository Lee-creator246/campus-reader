package org.campusreader.auth;

import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import com.sangfor.sdk.storageipc.provider.HSProvider;

/** Gates the vendor's eagerly created storage IPC provider behind explicit SDK consent. */
public final class ConsentStorageProvider extends HSProvider {
    private boolean vendorCreated;
    @Override public boolean onCreate() { return true; }

    @Override public synchronized Bundle call(String method, String argument, Bundle extras) {
        // This provider and the VPN service run in the main application process. Before the
        // SDK has been initialized, the original provider also returns an empty result.
        if (!CampusConfiguration.isConfigured() || !CampusConfiguration.hasConsent() || !CampusSdk.isInitialized()) return Bundle.EMPTY;
        if (!vendorCreated) {
            // Keep the same Android Transport and caller attribution for vendor caller checks.
            // This AAR's HSProvider constructor only invokes ContentProvider's constructor;
            // its device metadata access is entirely inside onCreate().
            vendorCreated = super.onCreate();
            if (!vendorCreated) return Bundle.EMPTY;
        }
        return super.call(method, argument, extras);
    }
    // HSProvider's CRUD surface is intentionally empty; only call() implements SDK IPC.
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) { return null; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] args) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { return 0; }
}
