package com.smali_generator.patches;

import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Build;
import android.util.Log;

import com.arthooks.ArtHooks;
import com.smali_generator.Hook;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;

/**
 * Owns the bindService hooks, because ArtHooks installs one replacement per
 * method: several hooks can each register a handler to decide a bind.
 *
 * Handlers are asked in registration order and the first non-PASS decision wins:
 *
 *   REFUSE  bindService returns false, as for a service that does not exist
 *   PASS    not ours; the original bindService runs
 */
public class BindServiceHook implements Hook {

    private static final String TAG = "PATCH";

    public enum Decision { PASS, REFUSE }

    public interface Handler {
        Decision onBind(Context thiz, Intent intent, ServiceConnection conn, Executor executor);
    }

    private static final List<Handler> handlers = new CopyOnWriteArrayList<>();

    public static void register(Handler handler) {
        handlers.add(handler);
    }

    private static Decision decide(Context thiz, Intent intent, ServiceConnection conn,
                                   Executor executor) {
        if (intent == null) {
            return Decision.PASS;
        }
        for (Handler handler : handlers) {
            try {
                Decision decision = handler.onBind(thiz, intent, conn, executor);
                if (decision != null && decision != Decision.PASS) {
                    return decision;
                }
            } catch (Throwable t) {
                Log.e(TAG, "Bind: handler " + handler.getClass().getSimpleName() + " failed", t);
            }
        }
        return Decision.PASS;
    }

    // --- the three ContextImpl.bindService overloads ---

    static native boolean bind_backup(Object thiz, Intent intent, ServiceConnection conn, int flags);

    static boolean bind(Object thiz, Intent intent, ServiceConnection conn, int flags) {
        Decision decision = decide((Context) thiz, intent, conn, null);
        if (decision == Decision.REFUSE) {
            return false;
        }
        return bind_backup(thiz, intent, conn, flags);
    }

    static native boolean bind_executor_backup(Object thiz, Intent intent, int flags,
                                               Executor executor, ServiceConnection conn);

    static boolean bind_executor(Object thiz, Intent intent, int flags, Executor executor,
                                 ServiceConnection conn) {
        Decision decision = decide((Context) thiz, intent, conn, executor);
        if (decision == Decision.REFUSE) {
            return false;
        }
        return bind_executor_backup(thiz, intent, flags, executor, conn);
    }

    static native boolean bind_flags_backup(Object thiz, Intent intent, ServiceConnection conn,
                                            Object flags);

    static boolean bind_flags(Object thiz, Intent intent, ServiceConnection conn, Object flags) {
        Decision decision = decide((Context) thiz, intent, conn, null);
        if (decision == Decision.REFUSE) {
            return false;
        }
        return bind_flags_backup(thiz, intent, conn, flags);
    }

    private static void hook(Method original, String replacement, Class<?>... params) {
        try {
            boolean ok = ArtHooks.hook_function(original,
                    BindServiceHook.class.getDeclaredMethod(replacement, params),
                    BindServiceHook.class.getDeclaredMethod(replacement + "_backup", params));
            Log.i(TAG, "Bind: hooked " + original.getDeclaringClass().getSimpleName() + "."
                    + original.getName() + "/" + original.getParameterCount() + " = " + ok);
        } catch (Throwable t) {
            Log.e(TAG, "Bind: " + original + ": " + t);
        }
    }

    public void load() {
        Class<?> contextImpl;
        try {
            contextImpl = Class.forName("android.app.ContextImpl");
        } catch (Throwable t) {
            Log.e(TAG, "Bind: no ContextImpl: " + t);
            return;
        }
        try {
            hook(contextImpl.getDeclaredMethod("bindService", Intent.class, ServiceConnection.class,
                            int.class),
                    "bind", Object.class, Intent.class, ServiceConnection.class, int.class);
        } catch (Throwable t) {
            Log.e(TAG, "Bind: bindService/3: " + t);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                hook(contextImpl.getDeclaredMethod("bindService",
                                Intent.class, int.class, Executor.class, ServiceConnection.class),
                        "bind_executor", Object.class, Intent.class, int.class, Executor.class,
                        ServiceConnection.class);
            } catch (Throwable t) {
                Log.e(TAG, "Bind: bindService/executor: " + t);
            }
        }
        if (Build.VERSION.SDK_INT >= 34) {
            try {
                Class<?> bindFlags = Class.forName("android.content.Context$BindServiceFlags");
                hook(contextImpl.getDeclaredMethod("bindService", Intent.class,
                                ServiceConnection.class, bindFlags),
                        "bind_flags", Object.class, Intent.class, ServiceConnection.class,
                        Object.class);
            } catch (Throwable t) {
                Log.e(TAG, "Bind: bindService/flags: " + t);
            }
        }
    }

    public void unload() {
    }
}
