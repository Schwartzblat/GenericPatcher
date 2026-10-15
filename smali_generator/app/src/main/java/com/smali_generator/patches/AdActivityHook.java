package com.smali_generator.patches;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import android.os.PersistableBundle;
import android.util.Log;

import com.arthooks.ArtHooks;
import com.smali_generator.Hook;
import com.smali_generator.utils.AdRules;

import java.lang.reflect.Method;

/**
 * Backstop for an ad that got through anyway: full-screen ads in nearly every
 * SDK show in the SDK's own Activity (AdActivity, AppLovinFullscreenActivity,
 * AdUnitActivity, VungleActivity, ...). Those are declared in the manifest, so
 * R8 cannot rename them, and their package says whose they are.
 *
 * Such an activity is finished as soon as it has been created. To the SDK that
 * is the user pressing back at once: it reports the ad dismissed, and the app
 * carries on. It is created first (the backup runs) so the SDK's own state is
 * set up and its onDestroy has something to tear down.
 *
 * Hooked on Instrumentation.callActivityOnCreate, which ActivityThread calls for
 * every activity, rather than on Activity.onCreate, which a subclass overrides.
 */
public class AdActivityHook implements Hook {

    private static final String TAG = "PATCH";

    private static void closeIfAd(Activity activity) {
        try {
            String name = activity.getClass().getName();
            if (AdRules.isAdClass(name)) {
                Log.i(TAG, "AdActivity: closing " + name);
                activity.finish();
            }
        } catch (Throwable t) {
            Log.e(TAG, "AdActivity: " + t);
        }
    }

    static native void on_create_backup(Object thiz, Activity activity, Bundle state);

    static native void on_create_persistent_backup(Object thiz, Activity activity, Bundle state,
                                                   PersistableBundle persistentState);

    static void on_create(Object thiz, Activity activity, Bundle state) {
        on_create_backup(thiz, activity, state);
        closeIfAd(activity);
    }

    static void on_create_persistent(Object thiz, Activity activity, Bundle state, PersistableBundle persistentState) {
        on_create_persistent_backup(thiz, activity, state, persistentState);
        closeIfAd(activity);
    }

    private static void hook(Method original, String replacement, Class<?>... params) {
        try {
            boolean ok = ArtHooks.hook_function(original,
                    AdActivityHook.class.getDeclaredMethod(replacement, params),
                    AdActivityHook.class.getDeclaredMethod(replacement + "_backup", params));
            Log.i(TAG, "AdActivity: hooked " + original.getName() + "/" + original.getParameterCount() + " = " + ok);
        } catch (Throwable t) {
            Log.e(TAG, "AdActivity: " + t);
        }
    }

    public void load() {
        try {
            hook(Instrumentation.class.getDeclaredMethod("callActivityOnCreate", Activity.class, Bundle.class),
                    "on_create", Object.class, Activity.class, Bundle.class);
            hook(Instrumentation.class.getDeclaredMethod("callActivityOnCreate",
                            Activity.class, Bundle.class, PersistableBundle.class),
                    "on_create_persistent", Object.class, Activity.class, Bundle.class, PersistableBundle.class);
        } catch (Throwable t) {
            Log.e(TAG, "AdActivity: " + t);
        }
    }

    public void unload() {
    }
}
