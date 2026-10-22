package com.smali_generator.utils;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * What the patch knows about this app's billing: whether the purchase layer is
 * on, which client strings the APK showed, and which verifier it ships.
 *
 * Also remembers the receipts this patch minted, which is how the signature
 * bypass stays narrow: a verification is let through only when the bytes being
 * checked are one of ours, so the app's own signature checks are untouched.
 *
 * The values are not written in this file: artifactory_generator/
 * iap_inventory.py finds the verifier in the APK and iap_catalog.py decides the
 * rest, and they reach the app as assets/patcher/artifactory.json. load()
 * reads them from there at startup, so one prebuilt module serves every app.
 */
public final class IapRules {

    // Each load() replaces every field, one volatile write each. A hook racing
    // the load sees the old or the new value, never a half-filled one.
    public static volatile boolean ENABLED = false;
    public static volatile String CLIENTS_PRESENT = "";
    public static volatile String VERIFIER_CLASS = "";
    public static volatile String VERIFIER_METHOD = "";
    public static volatile String VERIFIER_SIG = "";

    private static final int MAX_REMEMBERED = 256;
    private static final Set<String> minted =
            Collections.synchronizedSet(new LinkedHashSet<String>());

    private IapRules() {
    }

    /**
     * Reads the rules from {@link Artifacts}, replacing any earlier ones —
     * the same file/live pattern as {@link AdRules#load()}. A missing key is
     * an empty string, and only the exact string "true" turns the layer on.
     * Returns {@link #ENABLED}.
     */
    public static boolean load() {
        String clientsPresent = Artifacts.get("IAP_CLIENTS_PRESENT");
        String verifierClass = Artifacts.get("IAP_VERIFIER_CLASS_NAME");
        String verifierMethod = Artifacts.get("IAP_VERIFIER_METHOD_NAME");
        String verifierSig = Artifacts.get("IAP_VERIFIER_METHOD_SIG");
        CLIENTS_PRESENT = clientsPresent == null ? "" : clientsPresent;
        VERIFIER_CLASS = verifierClass == null ? "" : verifierClass;
        VERIFIER_METHOD = verifierMethod == null ? "" : verifierMethod;
        VERIFIER_SIG = verifierSig == null ? "" : verifierSig;
        ENABLED = "true".equals(Artifacts.get("IAP_ENABLED"));
        return ENABLED;
    }

    public static boolean hasVerifier() {
        return !VERIFIER_CLASS.isEmpty() && !VERIFIER_METHOD.isEmpty();
    }

    /**
     * How many parameters a smali method signature declares.
     *
     * The verifier is hooked with a replacement of the same shape, and the
     * sample verifier ships in two arities -- verifyPurchase(key, data, sig)
     * and verifyPurchase(data, sig) -- so the hook has to pick by this count.
     * Returns 0 for an empty or unfilled signature, which reads as "do not
     * hook" rather than as a guess.
     */
    public static int argCount(String sig) {
        if (sig == null) {
            return 0;
        }
        int open = sig.indexOf('(');
        int close = sig.indexOf(')', open + 1);
        if (open < 0 || close < 0) {
            return 0;
        }
        int count = 0;
        for (int i = open + 1; i < close; i++) {
            char c = sig.charAt(i);
            if (c == '[') {
                continue;               // an array prefix, not a parameter of its own
            }
            if (c == 'L') {
                int end = sig.indexOf(';', i);
                if (end < 0 || end > close) {
                    return count;       // malformed: count what was parseable
                }
                i = end;
            }
            count++;
        }
        return count;
    }

    public static void rememberReceipt(String json) {
        if (json == null) {
            return;
        }
        synchronized (minted) {
            // Evict the oldest. next() before remove(): an iterator with no
            // preceding next() throws IllegalStateException, which would kill
            // the bypass as soon as the set filled up.
            while (minted.size() >= MAX_REMEMBERED) {
                Iterator<String> oldest = minted.iterator();
                if (!oldest.hasNext()) {
                    break;
                }
                oldest.next();
                oldest.remove();
            }
            minted.add(json);
        }
    }

    /** True when these bytes are a receipt this patch produced. */
    public static boolean isMintedReceipt(byte[] data) {
        if (data == null || data.length == 0) {
            return false;
        }
        String text = new String(data, StandardCharsets.UTF_8);
        synchronized (minted) {
            return minted.contains(text);
        }
    }
}
