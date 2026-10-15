package com.smali_generator.utils;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.Set;

/**
 * The ledger's storage on a device: a SharedPreferences file in the host app's
 * own data directory, so purchases survive a restart.
 *
 * commit() rather than apply(): a purchase must be on disk before the app is
 * told about it, in case the game is killed immediately afterwards.
 */
public final class IapStore {

    private static final String FILE = "stitch_iap";

    private static volatile PurchaseLedger ledger;

    private IapStore() {
    }

    public static PurchaseLedger ledger(Context context) {
        PurchaseLedger local = ledger;
        if (local != null) {
            return local;
        }
        synchronized (IapStore.class) {
            if (ledger == null) {
                final SharedPreferences prefs =
                        context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
                ledger = new PurchaseLedger(new PurchaseLedger.KeyValueStore() {
                    @Override
                    public String get(String key) {
                        return prefs.getString(key, null);
                    }

                    @Override
                    public void put(String key, String value) {
                        prefs.edit().putString(key, value).commit();
                    }

                    @Override
                    public void remove(String key) {
                        prefs.edit().remove(key).commit();
                    }

                    @Override
                    public Set<String> keys() {
                        return new HashSet<>(prefs.getAll().keySet());
                    }
                }, context.getPackageName());
            }
            return ledger;
        }
    }
}
