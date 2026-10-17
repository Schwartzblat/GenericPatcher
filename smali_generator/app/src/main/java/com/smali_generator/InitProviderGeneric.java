package com.smali_generator;

import android.content.ContentProvider;
import android.content.Context;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;

import com.arthooks.ArtHooks;
import com.smali_generator.patches.AdActivityHook;
import com.smali_generator.patches.BindServiceHook;
import com.smali_generator.patches.GmaHook;
import com.smali_generator.patches.GmaPlayServicesHook;
import com.smali_generator.patches.NetworkAdBlockHook;
import com.smali_generator.patches.UnityAdsHook;
import com.smali_generator.patches.WebViewAdBlockHook;
import com.smali_generator.utils.AdRules;
import com.smali_generator.utils.Artifacts;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Entry point of the patch.
 *
 * stitch adds this class to the target app's AndroidManifest as
 *   <provider android:name="com.smali_generator.InitProviderGeneric"
 *             android:authorities="<target package>.com.smali_generator.InitProviderGeneric"
 *             android:exported="false"
 *             android:initOrder="2147483647"/>
 * so onCreate() runs before the host Application's onCreate, and before every
 * ad SDK's own init provider (AppLovinInitProvider, MobileAdsInitProvider, ...).
 */
@SuppressWarnings("unused")
public class InitProviderGeneric extends ContentProvider {

    private static final String TAG = "PATCH";

    // Register Hook implementations from patches/ here.
    static Hook[] hooks = {
            new BindServiceHook(),     // owns bindService; ad hooks register handlers
            new NetworkAdBlockHook(),  // DNS + URL: ad hosts, and anything ad SDK code requests
            new WebViewAdBlockHook(),  // the same inside WebViews, which resolve names natively
            new AdActivityHook(),      // backstop: closes an ad SDK's full-screen activity
            new GmaHook(),             // Google Mobile Ads loads fail at once with "no fill"
            new GmaPlayServicesHook(), // ...and may not have Play services fetch ads out of our process
            new UnityAdsHook(),        // Unity Ads never initialises (it would switch to Cronet)
    };

    static final AtomicBoolean is_loaded = new AtomicBoolean(false);

    @Override
    public boolean onCreate() {
        Log.i(TAG, "InitProviderGeneric: onCreate called");
        on_load(getContext());
        return true;
    }

    public static void on_load(Context context) {
        if (is_loaded.getAndSet(true)) {
            return;
        }
        Artifacts.init(context);
        if (!AdRules.load()) {
            // Still install the hooks: with no rules they block nothing, and the
            // app runs as if unpatched rather than crashing before its Application.
            Log.e(TAG, "No ad rules in assets/patcher/artifactory.json: nothing will be blocked");
        }
        Log.i(TAG, "Patch loaded! Ad SDKs at patch time: "
                + (AdRules.sdksPresent().isEmpty() ? "none" : AdRules.sdksPresent())
                + "; " + AdRules.curatedCount() + " curated ad domains, " + AdRules.harvestedCount()
                + " harvested hosts, caller rule " + (AdRules.callerBlockEnabled() ? "on" : "off"));
        try {
            if (!ArtHooks.is_available()) {
                // libarthooks.so did not load (wrong --arch?): every hook would silently no-op.
                Log.e(TAG, "ArtHooks unavailable, no hooks installed");
                return;
            }
        } catch (Throwable t) {
            Log.e(TAG, "ArtHooks failed to load, no hooks installed", t);
            return;
        }
        for (Hook hook : hooks) {
            try {
                hook.load();
            } catch (Throwable t) {
                // Catch Throwable, not Exception: we run before the host Application,
                // where a NoClassDefFoundError would take the app down. One bad hook
                // must not stop the rest either.
                Log.e(TAG, "Hook " + hook.getClass().getSimpleName() + " failed", t);
            }
        }
    }

    @Override public Cursor query(@NonNull Uri u, String[] p, String s, String[] a, String o) { return null; }
    @Override public String getType(@NonNull Uri u) { return null; }
    @Override public Uri insert(@NonNull Uri u, ContentValues v) { return null; }
    @Override public int delete(@NonNull Uri u, String s, String[] a) { return 0; }
    @Override public int update(@NonNull Uri u, ContentValues v, String s, String[] a) { return 0; }
}
