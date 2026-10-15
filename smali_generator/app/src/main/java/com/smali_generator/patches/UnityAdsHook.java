package com.smali_generator.patches;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.arthooks.ArtHooks;
import com.smali_generator.Hook;

import java.lang.reflect.Executable;
import java.lang.reflect.Method;

/**
 * Unity Ads, called directly or through a mediation adapter.
 *
 * NetworkAdBlockHook is not enough for it: once initialised, Unity Ads 4.x
 * prefers Cronet from Play services, which resolves names natively. So the SDK
 * is never initialised. initialize() reports failure, load() reports NO_FILL and
 * show() reports NOT_READY, each through the listener the caller passed, on the
 * main thread like the SDK's own callbacks.
 *
 * com.unity3d.ads.UnityAds is the SDK's public API, and the Unity C# plugin calls
 * it by name over JNI, so it keeps its name. Each overload gets its own
 * replacement; a missing overload is skipped.
 */
public class UnityAdsHook implements Hook {

    private static final String TAG = "PATCH";
    private static final String ADS = "com.unity3d.ads.";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static Method initFailed;
    private static Method loadFailed;
    private static Method showFailed;
    private static Object initError;
    private static Object loadError;
    private static Object showError;

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object enumConstant(String cls, String name) throws ClassNotFoundException {
        return Enum.valueOf((Class) Class.forName(ADS + cls), name);
    }

    private static void post(String what, Method callback, Object listener, Object... args) {
        Log.i(TAG, "UnityAds: blocked " + what);
        if (listener == null || callback == null) {
            return;
        }
        MAIN.post(() -> {
            try {
                callback.invoke(listener, args);
            } catch (Throwable t) {
                Log.e(TAG, "UnityAds: " + what + " callback: " + t);
            }
        });
    }

    // Static targets: no thiz.
    static void initialize_basic(Context context, String gameId) {
        post("initialize " + gameId, null, null);
    }

    static void initialize_test(Context context, String gameId, boolean testMode) {
        post("initialize " + gameId, null, null);
    }

    static void initialize_listener(Context context, String gameId, Object listener) {
        post("initialize " + gameId, initFailed, listener, initError, "Initialization blocked");
    }

    static void initialize_full(Context context, String gameId, boolean testMode, Object listener) {
        post("initialize " + gameId, initFailed, listener, initError, "Initialization blocked");
    }

    static void load_basic(String placementId) {
        post("load " + placementId, null, null);
    }

    static void load_listener(String placementId, Object listener) {
        post("load " + placementId, loadFailed, listener, placementId, loadError, "No fill");
    }

    static void load_options(String placementId, Object options, Object listener) {
        post("load " + placementId, loadFailed, listener, placementId, loadError, "No fill");
    }

    static void show_basic(Activity activity, String placementId) {
        post("show " + placementId, null, null);
    }

    static void show_listener(Activity activity, String placementId, Object listener) {
        post("show " + placementId, showFailed, listener, placementId, showError, "Not ready");
    }

    static void show_options(Activity activity, String placementId, Object options) {
        post("show " + placementId, null, null);
    }

    static void show_options_listener(Activity activity, String placementId, Object options, Object listener) {
        post("show " + placementId, showFailed, listener, placementId, showError, "Not ready");
    }

    private static void hook(Class<?> unityAds, String name, String sig, String replacement, Class<?>... params) {
        try {
            Executable original = ArtHooks.find_function(unityAds, name, sig);
            if (original == null) {
                Log.w(TAG, "UnityAds: " + name + sig + " not in this build");
                return;
            }
            Method repl = UnityAdsHook.class.getDeclaredMethod(replacement, params);
            Log.i(TAG, "UnityAds: hooked " + name + sig + " = " + ArtHooks.hook_function(original, repl));
        } catch (Throwable t) {
            Log.e(TAG, "UnityAds: " + name + sig + ": " + t);
        }
    }

    public void load() {
        Class<?> unityAds;
        try {
            unityAds = Class.forName(ADS + "UnityAds");
        } catch (Throwable t) {
            Log.i(TAG, "UnityAds: not in this app");
            return;
        }
        try {
            initFailed = Class.forName(ADS + "IUnityAdsInitializationListener").getMethod("onInitializationFailed",
                    Class.forName(ADS + "UnityAds$UnityAdsInitializationError"), String.class);
            loadFailed = Class.forName(ADS + "IUnityAdsLoadListener").getMethod("onUnityAdsFailedToLoad",
                    String.class, Class.forName(ADS + "UnityAds$UnityAdsLoadError"), String.class);
            showFailed = Class.forName(ADS + "IUnityAdsShowListener").getMethod("onUnityAdsShowFailure",
                    String.class, Class.forName(ADS + "UnityAds$UnityAdsShowError"), String.class);
            initError = enumConstant("UnityAds$UnityAdsInitializationError", "INTERNAL_ERROR");
            loadError = enumConstant("UnityAds$UnityAdsLoadError", "NO_FILL");
            showError = enumConstant("UnityAds$UnityAdsShowError", "NOT_READY");
        } catch (Throwable t) {
            // The hooks still stop the SDK; only the failure callbacks are lost.
            Log.w(TAG, "UnityAds: listener API not resolvable, failing silently: " + t);
        }

        final String ctx = "Landroid/content/Context;Ljava/lang/String;";
        final String initListener = "Lcom/unity3d/ads/IUnityAdsInitializationListener;";
        final String loadListener = "Lcom/unity3d/ads/IUnityAdsLoadListener;";
        final String showListener = "Lcom/unity3d/ads/IUnityAdsShowListener;";
        final String act = "Landroid/app/Activity;Ljava/lang/String;";
        final String showOptions = "Lcom/unity3d/ads/UnityAdsShowOptions;";

        hook(unityAds, "initialize", "(" + ctx + ")V", "initialize_basic", Context.class, String.class);
        hook(unityAds, "initialize", "(" + ctx + "Z)V", "initialize_test", Context.class, String.class, boolean.class);
        hook(unityAds, "initialize", "(" + ctx + initListener + ")V", "initialize_listener",
                Context.class, String.class, Object.class);
        hook(unityAds, "initialize", "(" + ctx + "Z" + initListener + ")V", "initialize_full",
                Context.class, String.class, boolean.class, Object.class);
        hook(unityAds, "load", "(Ljava/lang/String;)V", "load_basic", String.class);
        hook(unityAds, "load", "(Ljava/lang/String;" + loadListener + ")V", "load_listener", String.class, Object.class);
        hook(unityAds, "load", "(Ljava/lang/String;Lcom/unity3d/ads/UnityAdsLoadOptions;" + loadListener + ")V",
                "load_options", String.class, Object.class, Object.class);
        hook(unityAds, "show", "(" + act + ")V", "show_basic", Activity.class, String.class);
        hook(unityAds, "show", "(" + act + showListener + ")V", "show_listener",
                Activity.class, String.class, Object.class);
        hook(unityAds, "show", "(" + act + showOptions + ")V", "show_options",
                Activity.class, String.class, Object.class);
        hook(unityAds, "show", "(" + act + showOptions + showListener + ")V", "show_options_listener",
                Activity.class, String.class, Object.class, Object.class);
    }

    public void unload() {
    }
}
