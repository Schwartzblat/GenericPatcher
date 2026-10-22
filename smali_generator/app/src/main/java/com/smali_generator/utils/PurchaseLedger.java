package com.smali_generator.utils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Which products are owned, and the receipts for them.
 *
 * Nothing is owned until the player taps Buy: a real purchase is the only thing
 * that writes here. That is what keeps consumables sane -- claiming every known
 * product is owned at launch makes a game re-grant its coin packs on every
 * start, or wedge its store UI on an owned-but-unconsumed consumable.
 *
 * Storage is injected so the rules can be tested on the JVM; IapStore is the
 * SharedPreferences adapter used on a device.
 */
public final class PurchaseLedger {

    /** One entry per owned purchase, keyed by token. */
    public interface KeyValueStore {
        String get(String key);

        void put(String key, String value);

        void remove(String key);

        Set<String> keys();
    }

    private static final String PREFIX = "purchase.";
    private static final String TYPE_PREFIX = "type.";

    private final KeyValueStore store;
    private final String packageName;

    public PurchaseLedger(KeyValueStore store, String packageName) {
        this.store = store;
        this.packageName = packageName == null ? "" : packageName;
    }

    /** Records a purchase of productId and returns its receipt JSON. */
    public synchronized String grant(String productId, String type) {
        String existing = findByProductId(productId, type);
        if (existing != null) {
            return existing;
        }
        String token = BillingJson.newToken(productId);
        String json = BillingJson.purchase(packageName, productId, token,
                BillingJson.newOrderId(), System.currentTimeMillis(), false);
        store.put(PREFIX + token, json);
        store.put(TYPE_PREFIX + token, normalise(type));
        return json;
    }

    public synchronized List<String> owned(String type) {
        List<String> out = new ArrayList<>();
        for (String token : sortedTokens()) {
            if (normalise(type).equals(store.get(TYPE_PREFIX + token))) {
                String json = store.get(PREFIX + token);
                if (json != null) {
                    out.add(json);
                }
            }
        }
        return out;
    }

    public synchronized List<String> ownedProductIds(String type) {
        List<String> out = new ArrayList<>();
        for (String json : owned(type)) {
            out.add(field(json, "productId"));
        }
        return out;
    }

    public synchronized boolean acknowledge(String token) {
        String json = store.get(PREFIX + token);
        if (json == null) {
            return false;
        }
        store.put(PREFIX + token, json.replace("\"acknowledged\":false", "\"acknowledged\":true"));
        return true;
    }

    /** Removes the purchase, as the real service does, so it can be bought again. */
    public synchronized boolean consume(String token) {
        if (store.get(PREFIX + token) == null) {
            return false;
        }
        store.remove(PREFIX + token);
        store.remove(TYPE_PREFIX + token);
        return true;
    }

    /** True when this product is already owned, so a repeat buy must be refused. */
    public synchronized boolean owns(String productId, String type) {
        return findByProductId(productId, type) != null;
    }

    private String findByProductId(String productId, String type) {
        for (String json : owned(type)) {
            if (productId != null && productId.equals(field(json, "productId"))) {
                return json;
            }
        }
        return null;
    }

    private List<String> sortedTokens() {
        List<String> tokens = new ArrayList<>();
        for (String key : store.keys()) {
            if (key.startsWith(PREFIX)) {
                tokens.add(key.substring(PREFIX.length()));
            }
        }
        Collections.sort(tokens);
        return tokens;
    }

    private static String normalise(String type) {
        return "subs".equals(type) ? "subs" : "inapp";
    }

    static String field(String json, String name) {
        String needle = "\"" + name + "\":\"";
        int at = json.indexOf(needle);
        if (at < 0) {
            return "";
        }
        at += needle.length();
        StringBuilder out = new StringBuilder();
        for (int i = at; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                out.append(json.charAt(++i));
                continue;
            }
            if (c == '"') {
                break;
            }
            out.append(c);
        }
        return out.toString();
    }
}
