package com.smali_generator.patches;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;

import com.arthooks.ArtHooks;
import com.smali_generator.Hook;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;

/**
 * Google Mobile Ads: every load fails at once with "no fill", instead of after a
 * round trip to a blocked host. Apps that hold a splash screen or a level until
 * an interstitial loads or fails are released immediately.
 *
 * The targets are GMA's public API, hooked by name, and every one is optional:
 *  - in Unity games and most mediation setups the names are kept (the Unity
 *    plugins reach them over JNI), and these hooks apply;
 *  - where R8 renamed GMA's API (it may: only manifest-declared classes are safe),
 *    the classes are simply absent and NetworkAdBlockHook alone handles GMA.
 *
 * Two API generations:
 *  - v20+: static InterstitialAd.load(ctx, unit, request, callback) and friends,
 *    failure through AdLoadCallback.onAdFailedToLoad(LoadAdError);
 *  - before v20: instance InterstitialAd.loadAd(AdRequest), failure through the
 *    AdListener set on it, onAdFailedToLoad(int) (19.x also has the LoadAdError one).
 * Banners (BaseAdView.loadAd) are hidden and fail through their AdListener in both.
 *
 * MobileAds.initialize is left alone: apps wait on its completion listener.
 */
public class GmaHook implements Hook {

    private static final String TAG = "PATCH";
    private static final String ADS = "com.google.android.gms.ads.";
    private static final int ERROR_CODE_NO_FILL = 3;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    // v20+ failure plumbing; null on an older GMA.
    private static Constructor<?> loadAdErrorCtor;
    // Taken from the public base classes: invoking the override found on an
    // anonymous (non-public) callback class throws IllegalAccessException.
    private static Method loadCallbackFailed;
    // AdListener.onAdFailedToLoad(LoadAdError) on 19.x+, else onAdFailedToLoad(int).
    private static Method listenerFailedError;
    private static Method listenerFailedCode;

    private static Object newNoFillError() throws Exception {
        return loadAdErrorCtor.newInstance(ERROR_CODE_NO_FILL, "No fill", "com.google.android.gms.ads", null, null);
    }

    private static void failListener(Object listener, String kind) {
        if (listener == null) {
            return;
        }
        MAIN.post(() -> {
            try {
                if (listenerFailedError != null && loadAdErrorCtor != null) {
                    listenerFailedError.invoke(listener, newNoFillError());
                } else if (listenerFailedCode != null) {
                    listenerFailedCode.invoke(listener, ERROR_CODE_NO_FILL);
                }
            } catch (Throwable t) {
                Log.e(TAG, "Gma: " + kind + " failure callback: " + t);
            }
        });
    }

    private static Object adListenerOf(Object ad) {
        try {
            return ad.getClass().getMethod("getAdListener").invoke(ad);
        } catch (Throwable t) {
            Log.e(TAG, "Gma: getAdListener: " + t);
            return null;
        }
    }

    // --- v20+ full-screen formats: static, no thiz ---

    private static void postLoadFailure(final Object callback, final String kind, final String unit) {
        Log.i(TAG, "Gma: blocked " + kind + " " + unit);
        if (callback == null || loadCallbackFailed == null) {
            return;
        }
        MAIN.post(() -> {
            try {
                loadCallbackFailed.invoke(callback, newNoFillError());
            } catch (Throwable t) {
                Log.e(TAG, "Gma: " + kind + " failure callback: " + t);
            }
        });
    }

    static void interstitial_load(Context context, String unit, Object request, Object callback) {
        postLoadFailure(callback, "interstitial", unit);
    }

    static void admanager_interstitial_load(Context context, String unit, Object request, Object callback) {
        postLoadFailure(callback, "admanager interstitial", unit);
    }

    static void app_open_load(Context context, String unit, Object request, Object callback) {
        postLoadFailure(callback, "app-open", unit);
    }

    static void app_open_orientation_load(Context context, String unit, Object request, int orientation, Object callback) {
        postLoadFailure(callback, "app-open", unit);
    }

    static void app_open_admanager_orientation_load(Context context, String unit, Object request, int orientation,
                                                    Object callback) {
        postLoadFailure(callback, "app-open (admanager)", unit);
    }

    static void rewarded_load(Context context, String unit, Object request, Object callback) {
        postLoadFailure(callback, "rewarded", unit);
    }

    static void rewarded_admanager_load(Context context, String unit, Object request, Object callback) {
        postLoadFailure(callback, "rewarded (admanager)", unit);
    }

    static void rewarded_interstitial_load(Context context, String unit, Object request, Object callback) {
        postLoadFailure(callback, "rewarded interstitial", unit);
    }

    static void rewarded_interstitial_admanager_load(Context context, String unit, Object request, Object callback) {
        postLoadFailure(callback, "rewarded interstitial (admanager)", unit);
    }

    // --- pre-v20 full-screen formats: instance, leading thiz ---

    static void legacy_interstitial_load(Object thiz, Object request) {
        Log.i(TAG, "Gma: blocked legacy interstitial");
        failListener(adListenerOf(thiz), "legacy interstitial");
    }

    static void legacy_publisher_interstitial_load(Object thiz, Object request) {
        Log.i(TAG, "Gma: blocked legacy publisher interstitial");
        failListener(adListenerOf(thiz), "legacy publisher interstitial");
    }

    // --- banners, both generations: instance, leading thiz ---

    private static void blockBanner(Object adView, String kind) {
        Log.i(TAG, "Gma: blocked " + kind);
        try {
            ((View) adView).setVisibility(View.GONE);
        } catch (Throwable t) {
            Log.e(TAG, "Gma: hide " + kind + ": " + t);
        }
        failListener(adListenerOf(adView), kind);
    }

    static void banner_load(Object thiz, Object request) {
        blockBanner(thiz, "banner");
    }

    static void adview_load(Object thiz, Object request) {
        blockBanner(thiz, "banner (AdView)");
    }

    static void admanager_banner_load(Object thiz, Object request) {
        blockBanner(thiz, "admanager banner");
    }

    static void publisher_banner_load(Object thiz, Object request) {
        blockBanner(thiz, "publisher banner");
    }

    private static Class<?> optionalClass(String name) {
        try {
            return Class.forName(ADS + name);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Hooks cls.name(sig) if this GMA declares it there, with replacement(params). */
    private static void hook(String cls, String name, String sig, String replacement, Class<?>... params) {
        Class<?> owner = optionalClass(cls);
        if (owner == null) {
            return;
        }
        try {
            Executable original = ArtHooks.find_function(owner, name, sig);
            // find_function searches superclasses; only hook a method this class declares.
            if (original == null || original.getDeclaringClass() != owner) {
                return;
            }
            Method repl = GmaHook.class.getDeclaredMethod(replacement, params);
            Log.i(TAG, "Gma: hooked " + cls + "." + name + " = " + ArtHooks.hook_function(original, repl));
        } catch (Throwable t) {
            Log.e(TAG, "Gma: " + cls + "." + name + ": " + t);
        }
    }

    private static void resolveFailurePlumbing() {
        Class<?> loadAdError = optionalClass("LoadAdError");
        Class<?> adListener = optionalClass("AdListener");
        if (loadAdError != null) {
            try {
                loadAdErrorCtor = loadAdError.getConstructor(int.class, String.class, String.class,
                        Class.forName(ADS + "AdError"), Class.forName(ADS + "ResponseInfo"));
            } catch (Throwable t) {
                Log.w(TAG, "Gma: no LoadAdError(int, String, String, AdError, ResponseInfo): " + t);
            }
            try {
                loadCallbackFailed = Class.forName(ADS + "AdLoadCallback").getMethod("onAdFailedToLoad", loadAdError);
            } catch (Throwable ignored) {
                // pre-v20: no AdLoadCallback
            }
            if (adListener != null) {
                try {
                    listenerFailedError = adListener.getMethod("onAdFailedToLoad", loadAdError);
                } catch (Throwable ignored) {
                    // only the int variant
                }
            }
        }
        if (adListener != null) {
            try {
                listenerFailedCode = adListener.getMethod("onAdFailedToLoad", int.class);
            } catch (Throwable ignored) {
                // v21+: removed
            }
        }
    }

    public void load() {
        try {
            if (optionalClass("MobileAds") == null) {
                Log.i(TAG, "Gma: GMA API not present under its own names, leaving GMA to the network block");
                return;
            }
            resolveFailurePlumbing();

            final Class<?>[] staticLoad = {Context.class, String.class, Object.class, Object.class};
            final Class<?>[] staticLoadOrientation = {Context.class, String.class, Object.class, int.class, Object.class};
            final Class<?>[] instanceLoad = {Object.class, Object.class};
            final String ctx = "Landroid/content/Context;Ljava/lang/String;";
            final String adRequest = "Lcom/google/android/gms/ads/AdRequest;";
            final String amRequest = "Lcom/google/android/gms/ads/admanager/AdManagerAdRequest;";
            final String pubRequest = "Lcom/google/android/gms/ads/doubleclick/PublisherAdRequest;";

            hook("interstitial.InterstitialAd", "load",
                    "(" + ctx + adRequest + "Lcom/google/android/gms/ads/interstitial/InterstitialAdLoadCallback;)V",
                    "interstitial_load", staticLoad);
            hook("admanager.AdManagerInterstitialAd", "load",
                    "(" + ctx + amRequest + "Lcom/google/android/gms/ads/admanager/AdManagerInterstitialAdLoadCallback;)V",
                    "admanager_interstitial_load", staticLoad);
            hook("appopen.AppOpenAd", "load",
                    "(" + ctx + adRequest + "Lcom/google/android/gms/ads/appopen/AppOpenAd$AppOpenAdLoadCallback;)V",
                    "app_open_load", staticLoad);
            hook("appopen.AppOpenAd", "load",
                    "(" + ctx + adRequest + "ILcom/google/android/gms/ads/appopen/AppOpenAd$AppOpenAdLoadCallback;)V",
                    "app_open_orientation_load", staticLoadOrientation);
            hook("appopen.AppOpenAd", "load",
                    "(" + ctx + amRequest + "ILcom/google/android/gms/ads/appopen/AppOpenAd$AppOpenAdLoadCallback;)V",
                    "app_open_admanager_orientation_load", staticLoadOrientation);
            hook("rewarded.RewardedAd", "load",
                    "(" + ctx + adRequest + "Lcom/google/android/gms/ads/rewarded/RewardedAdLoadCallback;)V",
                    "rewarded_load", staticLoad);
            hook("rewarded.RewardedAd", "load",
                    "(" + ctx + amRequest + "Lcom/google/android/gms/ads/rewarded/RewardedAdLoadCallback;)V",
                    "rewarded_admanager_load", staticLoad);
            hook("rewardedinterstitial.RewardedInterstitialAd", "load",
                    "(" + ctx + adRequest + "Lcom/google/android/gms/ads/rewardedinterstitial/RewardedInterstitialAdLoadCallback;)V",
                    "rewarded_interstitial_load", staticLoad);
            hook("rewardedinterstitial.RewardedInterstitialAd", "load",
                    "(" + ctx + amRequest + "Lcom/google/android/gms/ads/rewardedinterstitial/RewardedInterstitialAdLoadCallback;)V",
                    "rewarded_interstitial_admanager_load", staticLoad);

            hook("InterstitialAd", "loadAd", "(" + adRequest + ")V", "legacy_interstitial_load", instanceLoad);
            hook("doubleclick.PublisherInterstitialAd", "loadAd", "(" + pubRequest + ")V",
                    "legacy_publisher_interstitial_load", instanceLoad);

            hook("BaseAdView", "loadAd", "(" + adRequest + ")V", "banner_load", instanceLoad);
            // Old AdView overrides loadAd; new AdView does not (then this is skipped).
            hook("AdView", "loadAd", "(" + adRequest + ")V", "adview_load", instanceLoad);
            hook("admanager.AdManagerAdView", "loadAd", "(" + amRequest + ")V", "admanager_banner_load", instanceLoad);
            hook("doubleclick.PublisherAdView", "loadAd", "(" + pubRequest + ")V", "publisher_banner_load", instanceLoad);
        } catch (Throwable t) {
            Log.e(TAG, "Gma: " + t);
        }
    }

    public void unload() {
    }
}
