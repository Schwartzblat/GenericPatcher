package com.smali_generator.utils;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * What the patch knows about this app's billing, filled in at patch time by
 * artifactory_generator/iap_inventory.py.
 *
 * Also remembers the receipts this patch minted, which is how the signature
 * bypass stays narrow: a verification is let through only when the bytes being
 * checked are one of ours, so the app's own signature checks are untouched.
 */
public final class IapRules {

    public static final boolean ENABLED = Boolean.parseBoolean("{{IAP_ENABLED}}");
    public static final String CLIENTS_PRESENT = "{{IAP_CLIENTS_PRESENT}}";
    public static final String VERIFIER_CLASS = "{{IAP_VERIFIER_CLASS_NAME}}";
    public static final String VERIFIER_METHOD = "{{IAP_VERIFIER_METHOD_NAME}}";
    public static final String VERIFIER_SIG = "{{IAP_VERIFIER_METHOD_SIG}}";

    private static final int MAX_REMEMBERED = 256;
    private static final Set<String> minted =
            Collections.synchronizedSet(new LinkedHashSet<String>());

    private IapRules() {
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
