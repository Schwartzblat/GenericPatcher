package com.smali_generator.patches;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.util.Log;

import com.arthooks.ArtHooks;
import com.smali_generator.Hook;
import com.smali_generator.utils.FakePlayBilling;
import com.smali_generator.utils.IapRules;
import com.smali_generator.utils.Utils;

import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.security.Signature;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.Executor;

/**
 * Makes in-app purchases succeed with no network and no Play Store.
 *
 * Three hooks:
 *
 *  1. bindService (through BindServiceHook): the billing bind is answered with
 *     FakePlayBilling instead of the Play Store's binder.
 *  2. queryIntentServices: BillingClientImpl requires a resolvable
 *     com.android.vending service *before* it calls bindService -- without a
 *     Play Store it reports "The device doesn't have valid Play Store." and
 *     never binds. A synthetic ResolveInfo gets it to the bind.
 *  3. the receipt verifier: the app's own check of our signature. Scoped two
 *     ways -- the sample Security class the finder located, and otherwise only
 *     Signature instances fed bytes this patch minted.
 */
public class IapHook implements Hook {

    private static final String TAG = "PATCH";

    private static final String BIND_ACTION = "com.android.vending.billing.InAppBillingService.BIND";
    private static final String VENDING_PACKAGE = "com.android.vending";
    private static final String VENDING_SERVICE = "com.android.vending.billing.InAppBillingService";

    private static Context appContext;

    // --- 1. the billing bind ---

    private static final BindServiceHook.Handler BILLING_HANDLER = new BindServiceHook.Handler() {
        @Override
        public BindServiceHook.Decision onBind(Context thiz, Intent intent, ServiceConnection conn,
                                               Executor executor) {
            if (!BIND_ACTION.equals(intent.getAction())) {
                return BindServiceHook.Decision.PASS;
            }
            Context context = thiz != null ? thiz : appContext;
            if (context == null || conn == null) {
                return BindServiceHook.Decision.PASS;
            }
            Log.i(TAG, "Iap: faked the Play billing bind");
            BindServiceHook.deliver(conn, executor,
                    new ComponentName(VENDING_PACKAGE, VENDING_SERVICE),
                    new FakePlayBilling(context));
            return BindServiceHook.Decision.FAKE;
        }
    };

    // --- 2. PackageManager.queryIntentServices ---

    static native List<ResolveInfo> query_services_backup(Object thiz, Intent intent, int flags);

    static List<ResolveInfo> query_services(Object thiz, Intent intent, int flags) {
        List<ResolveInfo> resolved = query_services_backup(thiz, intent, flags);
        if (intent == null || !BIND_ACTION.equals(intent.getAction())) {
            return resolved;
        }
        if (resolved != null) {
            for (ResolveInfo info : resolved) {
                if (info != null && info.serviceInfo != null
                        && VENDING_PACKAGE.equals(info.serviceInfo.packageName)) {
                    return resolved;
                }
            }
        }
        List<ResolveInfo> out = resolved == null ? new ArrayList<ResolveInfo>()
                : new ArrayList<>(resolved);
        ResolveInfo fake = new ResolveInfo();
        fake.serviceInfo = new ServiceInfo();
        fake.serviceInfo.name = VENDING_SERVICE;
        fake.serviceInfo.packageName = VENDING_PACKAGE;
        fake.serviceInfo.applicationInfo = new ApplicationInfo();
        fake.serviceInfo.applicationInfo.packageName = VENDING_PACKAGE;
        fake.serviceInfo.applicationInfo.enabled = true;
        fake.serviceInfo.exported = true;
        fake.serviceInfo.enabled = true;
        // At index 0: BillingClientImpl reads intentServices.get(0) and refuses
        // anything whose package is not com.android.vending, so appending would
        // lose to any other package that resolves the action.
        out.add(0, fake);
        Log.i(TAG, "Iap: advertised a Play billing service to the app");
        return out;
    }

    // --- 3. the receipt verifier ---

    // Which Signature instances were last fed one of our receipts. Weak so a
    // Signature the app drops is not held alive; synchronized because update()
    // and verify() can be called from any thread.
    private static final Set<Object> minted =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<Object, Boolean>()));

    // One replacement per arity the sample verifier ships in. ArtHooks replaces
    // a method with one of the same shape, so the arity has to match what the
    // finder reported: hooking verifyPurchase(key, data, sig) with a two-string
    // replacement hands ART a frame that does not describe the call.
    static native boolean verifier_backup(String signedData, String signature);

    static boolean verifier(String signedData, String signature) {
        Log.i(TAG, "Iap: receipt verification allowed (sample verifier)");
        return true;
    }

    static native boolean verifier3_backup(String publicKey, String signedData, String signature);

    static boolean verifier3(String publicKey, String signedData, String signature) {
        Log.i(TAG, "Iap: receipt verification allowed (sample verifier)");
        return true;
    }

    static native void signature_update_backup(Object thiz, byte[] data);

    static void signature_update(Object thiz, byte[] data) {
        if (IapRules.isMintedReceipt(data)) {
            minted.add(thiz);
        } else {
            minted.remove(thiz);
        }
        signature_update_backup(thiz, data);
    }

    static native boolean signature_verify_backup(Object thiz, byte[] signature);

    static boolean signature_verify(Object thiz, byte[] signature) {
        if (minted.remove(thiz)) {
            Log.i(TAG, "Iap: receipt verification allowed (minted receipt)");
            return true;
        }
        return signature_verify_backup(thiz, signature);
    }

    private static void hook(Method original, String replacement, Class<?>... params) {
        try {
            boolean ok = ArtHooks.hook_function(original,
                    IapHook.class.getDeclaredMethod(replacement, params),
                    IapHook.class.getDeclaredMethod(replacement + "_backup", params));
            Log.i(TAG, "Iap: hooked " + original.getDeclaringClass().getSimpleName() + "."
                    + original.getName() + " = " + ok);
        } catch (Throwable t) {
            Log.e(TAG, "Iap: " + original + ": " + t);
        }
    }

    public void load() {
        if (!IapRules.ENABLED) {
            Log.i(TAG, "Iap: layer off (patch without --iap)");
            return;
        }
        appContext = Utils.getApplicationContext();
        Log.i(TAG, "Iap: on; billing clients at patch time: "
                + (IapRules.CLIENTS_PRESENT.isEmpty() ? "none" : IapRules.CLIENTS_PRESENT));

        BindServiceHook.register(BILLING_HANDLER);

        // ApplicationPackageManager, not PackageManager: the latter's method is
        // abstract, and hooking an abstract method's entry point intercepts
        // nothing -- the concrete override is what callers reach. Same reason
        // the bind hooks target ContextImpl rather than Context.
        try {
            Class<?> appPm = Class.forName("android.app.ApplicationPackageManager");
            hook(appPm.getDeclaredMethod("queryIntentServices", Intent.class, int.class),
                    "query_services", Object.class, Intent.class, int.class);
        } catch (Throwable t) {
            Log.e(TAG, "Iap: queryIntentServices: " + t);
        }

        if (IapRules.hasVerifier()) {
            hookVerifier();
        }

        try {
            hook(Signature.class.getDeclaredMethod("update", byte[].class),
                    "signature_update", Object.class, byte[].class);
            hook(Signature.class.getDeclaredMethod("verify", byte[].class),
                    "signature_verify", Object.class, byte[].class);
        } catch (Throwable t) {
            Log.e(TAG, "Iap: Signature hooks: " + t);
        }
    }

    private void hookVerifier() {
        int args = IapRules.argCount(IapRules.VERIFIER_SIG);
        String name = args == 3 ? "verifier3" : "verifier";
        Class<?>[] params = args == 3
                ? new Class<?>[]{String.class, String.class, String.class}
                : new Class<?>[]{String.class, String.class};
        if (args != 2 && args != 3) {
            // Not a shape this patch has a replacement for. The Signature hooks
            // below still let our own receipts through, so the purchase works;
            // only this extra layer is skipped.
            Log.e(TAG, "Iap: verifier " + IapRules.VERIFIER_METHOD + IapRules.VERIFIER_SIG
                    + " has no matching replacement, leaving it alone");
            return;
        }
        try {
            Class<?> owner = Class.forName(IapRules.VERIFIER_CLASS);
            Executable target = ArtHooks.find_function(owner,
                    IapRules.VERIFIER_METHOD, IapRules.VERIFIER_SIG);
            if (target == null) {
                Log.e(TAG, "Iap: verifier " + IapRules.VERIFIER_METHOD + " not found at runtime");
                return;
            }
            boolean ok = ArtHooks.hook_function(target,
                    IapHook.class.getDeclaredMethod(name, params),
                    IapHook.class.getDeclaredMethod(name + "_backup", params));
            Log.i(TAG, "Iap: hooked verifier " + IapRules.VERIFIER_CLASS + "."
                    + IapRules.VERIFIER_METHOD + "/" + args + " = " + ok);
        } catch (Throwable t) {
            Log.e(TAG, "Iap: verifier hook: " + t);
        }
    }

    public void unload() {
    }
}
