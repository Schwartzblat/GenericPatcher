package com.smali_generator.patches;

import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.util.Log;

import com.arthooks.ArtHooks;
import com.smali_generator.Hook;
import com.smali_generator.utils.AdRules;

import java.lang.reflect.Method;
import java.util.concurrent.Executor;

/**
 * Google Mobile Ads may not hand its work to Play services.
 *
 * Current GMA does not fetch ads itself. Measured on API 36 with GMA 23.6: the
 * socket to googleads.g.doubleclick.net belongs to com.google.android.gms, not
 * to the app, so no in-app network hook sees the request. GMA reaches Play
 * services two ways, and this hook closes both:
 *
 *  1. RemoteCreator: createPackageContext("com.google.android.gms").getClassLoader()
 *     .loadClass("com.google.android.gms.ads.<...>CreatorImpl") yields an ad engine
 *     that runs Play services' code. Refused with ClassNotFoundException, which is
 *     what RemoteCreator expects when Play services is missing or too old; GMA then
 *     falls back to the ad engine bundled in the app.
 *  2. bindService("com.google.android.gms.ads.service.START" / "...CACHE"): the
 *     bundled engine still asks Play services' ad request service to make the
 *     HTTP request. Refused by returning false, as for a service that does not
 *     exist; GMA then makes the request itself, from this process, where
 *     NetworkAdBlockHook fails it and GMA reports the failure to the app.
 *
 * The bindService hooks themselves live in BindServiceHook, which owns those
 * three methods; this hook contributes the
 * decision for GMA's service actions as a handler.
 *
 * Both class names and intent actions are a contract between two separately
 * shipped APKs, so R8 never renames them: this holds where R8 renamed all of GMA's
 * own API. The advertising ID service (com.google.android.gms.ads.identifier.*)
 * and the app's own class loads are left alone.
 */
public class GmaPlayServicesHook implements Hook {

    private static final String TAG = "PATCH";
    private static final String GMA_CLASS_PREFIX = "com.google.android.gms.ads.";
    private static final String GMA_CREATOR_SUFFIX = "CreatorImpl";
    private static final String GMA_SERVICE_PREFIX = "com.google.android.gms.ads.service.";

    private static final ClassLoader APP_LOADER = GmaPlayServicesHook.class.getClassLoader();

    // --- 1. ClassLoader.loadClass(String): instance target, leading thiz ---

    static native Class<?> load_class_backup(Object thiz, String name) throws ClassNotFoundException;

    static Class<?> load_class(Object thiz, String name) throws ClassNotFoundException {
        if (name != null && thiz != APP_LOADER && name.startsWith(GMA_CLASS_PREFIX) && name.endsWith(GMA_CREATOR_SUFFIX)) {
            Log.i(TAG, "GmaPlayServices: refused remote " + name);
            throw new ClassNotFoundException(name);
        }
        return load_class_backup(thiz, name);
    }

    // --- 2. the Play services ad request service, via BindServiceHook ---

    private static final BindServiceHook.Handler AD_SERVICE_HANDLER = new BindServiceHook.Handler() {
        @Override
        public BindServiceHook.Decision onBind(Context thiz, Intent intent, ServiceConnection conn,
                                               Executor executor) {
            String action = intent.getAction();
            if (action != null && action.startsWith(GMA_SERVICE_PREFIX)) {
                Log.i(TAG, "GmaPlayServices: refused bindService " + action);
                return BindServiceHook.Decision.REFUSE;
            }
            return BindServiceHook.Decision.PASS;
        }
    };

    private static void hook(Method original, String replacement, Class<?>... params) {
        try {
            boolean ok = ArtHooks.hook_function(original,
                    GmaPlayServicesHook.class.getDeclaredMethod(replacement, params),
                    GmaPlayServicesHook.class.getDeclaredMethod(replacement + "_backup", params));
            Log.i(TAG, "GmaPlayServices: hooked " + original.getDeclaringClass().getSimpleName() + "."
                    + original.getName() + "/" + original.getParameterCount() + " = " + ok);
        } catch (Throwable t) {
            Log.e(TAG, "GmaPlayServices: " + original + ": " + t);
        }
    }

    public void load() {
        if (!AdRules.layerEnabled()) {
            Log.i(TAG, "GmaPlayServices: ad artifacts absent, glue off");
            return;
        }
        try {
            hook(ClassLoader.class.getDeclaredMethod("loadClass", String.class), "load_class", Object.class, String.class);
        } catch (Throwable t) {
            Log.e(TAG, "GmaPlayServices: loadClass: " + t);
        }
        BindServiceHook.register(AD_SERVICE_HANDLER);
        Log.i(TAG, "GmaPlayServices: ad service bind handler registered");
    }

    public void unload() {
    }
}
