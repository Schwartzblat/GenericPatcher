package com.smali_generator.utils;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Bundle;
import android.os.Parcel;
import android.util.Log;

import com.smali_generator.PurchaseResultActivity;

import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Locale;

/**
 * A local Play Store billing service.
 *
 * The app binds com.android.vending.billing.InAppBillingService.BIND and gets
 * this instead of the real binder, so every purchase is answered in-process and
 * nothing needs a network or an installed Play Store.
 *
 * It answers the AIDL in Bundles of JSON, which is what makes the patch
 * generic: the keys and the interface descriptor are a contract with a
 * separately shipped APK and are never renamed, and the Play Billing Library
 * turns our JSON into its own Purchase/ProductDetails objects whatever those
 * classes were renamed to.
 *
 * attachInterface is deliberately NOT called: queryLocalInterface then returns
 * null, the client takes its normal Parcel path, and onTransact below runs.
 */
public final class FakePlayBilling extends Binder {

    private static final String TAG = "PATCH";

    public static final String DESCRIPTOR = "com.android.vending.billing.IInAppBillingService";

    // Derived from the APK's own proxy; IapInventoryFinder re-checks them at patch time.
    private static final int TX_IS_BILLING_SUPPORTED = 1;
    private static final int TX_GET_SKU_DETAILS = 2;
    private static final int TX_GET_BUY_INTENT = 3;
    private static final int TX_GET_PURCHASES = 4;
    private static final int TX_CONSUME_PURCHASE = 5;
    private static final int TX_GET_BUY_INTENT_EXTRA = 8;
    private static final int TX_GET_PURCHASE_HISTORY = 9;
    private static final int TX_IS_BILLING_SUPPORTED_EXTRA = 10;
    private static final int TX_GET_PURCHASES_EXTRA = 11;
    // Consume, not acknowledge: consumeAsync sends 12 when it can pass extra
    // params and 5 when it cannot, and acknowledgePurchase has a code of its
    // own below. The two take the same arguments, so only the caller tells
    // them apart -- and reading 12 as an acknowledgement leaves every
    // consumable owned for ever, which is a game whose coin pack can be
    // bought exactly once.
    private static final int TX_CONSUME_PURCHASE_EXTRA = 12;
    // Billing 5+ asks for product details here rather than on TX_GET_SKU_DETAILS:
    // both querySkuDetailsAsync and queryProductDetailsAsync go through it.
    private static final int TX_GET_SKU_DETAILS_V9 = 901;
    private static final int TX_ACKNOWLEDGE = 902;
    private static final int TX_GET_BILLING_CONFIG = 1201;

    private static final int OK = 0;
    private static final int ERROR = 6;
    private static final int ITEM_ALREADY_OWNED = 7;
    private static final int ITEM_NOT_OWNED = 8;
    private static final int FEATURE_NOT_SUPPORTED = -2;

    private final Context context;

    public FakePlayBilling(Context context) {
        this.context = context.getApplicationContext() == null
                ? context : context.getApplicationContext();
    }

    private PurchaseLedger ledger() {
        return IapStore.ledger(context);
    }

    private static String normaliseType(String type) {
        return "subs".equals(type) ? "subs" : "inapp";
    }

    private static boolean supported(String type) {
        return "inapp".equals(type) || "subs".equals(type) || "subscriptions".equals(type);
    }

    private String currency() {
        try {
            return Currency.getInstance(Locale.getDefault()).getCurrencyCode();
        } catch (Throwable t) {
            return "USD";
        }
    }

    @Override
    protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
        try {
            data.enforceInterface(DESCRIPTOR);
        } catch (Throwable t) {
            // Not a billing transaction after all: let Binder deal with it.
            try {
                return super.onTransact(code, data, reply, flags);
            } catch (Throwable ignored) {
                return false;
            }
        }
        try {
            return dispatch(code, data, reply);
        } catch (Throwable t) {
            Log.e(TAG, "Iap: tx " + code + " failed", t);
            try {
                reply.writeNoException();
                reply.writeInt(0);
            } catch (Throwable ignored) {
                // nothing more we can do; the client sees a null response
            }
            return true;
        }
    }

    private boolean dispatch(int code, Parcel data, Parcel reply) {
        switch (code) {
            case TX_IS_BILLING_SUPPORTED: {
                data.readInt();
                data.readString();
                String type = data.readString();
                Log.i(TAG, "Iap: isBillingSupported(" + type + ") = ok");
                return writeInt(reply, supported(type) ? OK : FEATURE_NOT_SUPPORTED);
            }
            case TX_IS_BILLING_SUPPORTED_EXTRA: {
                data.readInt();
                data.readString();
                String type = data.readString();
                Log.i(TAG, "Iap: isBillingSupportedExtraParams(" + type + ") = ok");
                return writeInt(reply, supported(type) ? OK : FEATURE_NOT_SUPPORTED);
            }
            case TX_GET_SKU_DETAILS: {
                data.readInt();
                data.readString();
                String type = data.readString();
                // A Bundle argument arrives as a typed object -- writeInt(1) then
                // writeToParcel -- exactly like the reply. readBundle() would take
                // that leading 1 for a byte length and throw.
                Bundle request = data.readTypedObject(Bundle.CREATOR);
                return writeBundle(reply, skuDetails(type, request));
            }
            case TX_GET_SKU_DETAILS_V9: {
                data.readInt();
                data.readString();
                String type = data.readString();
                // Two typed-object Bundles: the ids, then the offer lists.
                Bundle first = data.readTypedObject(Bundle.CREATOR);
                Bundle second = data.readTypedObject(Bundle.CREATOR);
                return writeBundle(reply, skuDetails(type, idsBundle(first, second)));
            }
            case TX_GET_PURCHASES:
            case TX_GET_PURCHASES_EXTRA: {
                data.readInt();
                data.readString();
                String type = data.readString();
                return writeBundle(reply, purchases(type));
            }
            case TX_GET_PURCHASE_HISTORY: {
                data.readInt();
                data.readString();
                String type = data.readString();
                return writeBundle(reply, purchases(type));
            }
            case TX_GET_BUY_INTENT:
            case TX_GET_BUY_INTENT_EXTRA: {
                data.readInt();
                data.readString();
                String sku = data.readString();
                String type = data.readString();
                return writeBundle(reply, buyIntent(sku, type));
            }
            case TX_CONSUME_PURCHASE: {
                data.readInt();
                data.readString();
                String token = data.readString();
                boolean removed = ledger().consume(token);
                Log.i(TAG, "Iap: consumePurchase " + (removed ? "ok" : "not owned"));
                return writeInt(reply, removed ? OK : ITEM_NOT_OWNED);
            }
            case TX_CONSUME_PURCHASE_EXTRA: {
                // Same consume, answered as a Bundle: the client reads
                // RESPONSE_CODE out of it rather than taking a bare int.
                data.readInt();
                data.readString();
                String token = data.readString();
                boolean removed = ledger().consume(token);
                Log.i(TAG, "Iap: consumePurchase " + (removed ? "ok" : "not owned"));
                Bundle consumed = new Bundle();
                consumed.putInt("RESPONSE_CODE", removed ? OK : ITEM_NOT_OWNED);
                return writeBundle(reply, consumed);
            }
            case TX_ACKNOWLEDGE: {
                data.readInt();
                data.readString();
                String token = data.readString();
                boolean known = ledger().acknowledge(token);
                Log.i(TAG, "Iap: acknowledgePurchase " + (known ? "ok" : "not owned"));
                Bundle out = new Bundle();
                out.putInt("RESPONSE_CODE", known ? OK : ITEM_NOT_OWNED);
                return writeBundle(reply, out);
            }
            case TX_GET_BILLING_CONFIG: {
                // Takes a callback binder whose interface we do not know. Answer the
                // transaction so the caller does not block on the parcel; an app that
                // calls getBillingConfigAsync gets no callback (see README limits).
                Log.i(TAG, "Iap: getBillingConfig unanswered");
                reply.writeNoException();
                return true;
            }
            default: {
                Log.i(TAG, "Iap: unhandled tx " + code + ", answering null");
                reply.writeNoException();
                reply.writeInt(0);
                return true;
            }
        }
    }

    /** Billing 5+ puts ITEM_ID_LIST in the first bundle and the offer lists in the second. */
    private static Bundle idsBundle(Bundle first, Bundle second) {
        if (first != null && first.containsKey("ITEM_ID_LIST")) {
            return first;
        }
        if (second != null && second.containsKey("ITEM_ID_LIST")) {
            return second;
        }
        // Neither carried the ids: log what did arrive, so the next run says why.
        Log.i(TAG, "Iap: no ITEM_ID_LIST; bundle keys "
                + (first == null ? "[]" : first.keySet().toString())
                + " / " + (second == null ? "[]" : second.keySet().toString()));
        return first;
    }

    private Bundle skuDetails(String type, Bundle request) {
        ArrayList<String> ids = request == null
                ? null : request.getStringArrayList("ITEM_ID_LIST");
        ArrayList<String> details = new ArrayList<>();
        if (ids != null) {
            for (String id : ids) {
                if (id != null && !id.isEmpty()) {
                    details.add(BillingJson.skuDetails(id, type, currency()));
                }
            }
        }
        Bundle out = new Bundle();
        out.putInt("RESPONSE_CODE", OK);
        out.putStringArrayList("DETAILS_LIST", details);
        // Billing 5+ reads the product-details response under its own key.
        out.putStringArrayList("PRODUCT_DETAILS", details);
        // The ids are logged here because this is the one place they are exact:
        // they come from the game's own request, not from a static guess.
        Log.i(TAG, "Iap: getSkuDetails(" + type + ") -> " + details.size() + " item(s) "
                + (ids == null ? "[no ITEM_ID_LIST]" : ids.toString()));
        return out;
    }

    private Bundle purchases(String type) {
        PurchaseLedger ledger = ledger();
        List<String> data = ledger.owned(type);
        ArrayList<String> items = new ArrayList<>(ledger.ownedProductIds(type));
        ArrayList<String> receipts = new ArrayList<>(data);
        ArrayList<String> signatures = new ArrayList<>();
        for (String json : data) {
            IapRules.rememberReceipt(json);
            signatures.add(BillingJson.signature());
        }
        Bundle out = new Bundle();
        out.putInt("RESPONSE_CODE", OK);
        out.putStringArrayList("INAPP_PURCHASE_ITEM_LIST", items);
        out.putStringArrayList("INAPP_PURCHASE_DATA_LIST", receipts);
        out.putStringArrayList("INAPP_DATA_SIGNATURE_LIST", signatures);
        Log.i(TAG, "Iap: getPurchases(" + type + ") -> " + items.size() + " owned");
        return out;
    }

    private Bundle buyIntent(String sku, String type) {
        Bundle out = new Bundle();
        if (sku != null && ledger().owns(sku, normaliseType(type))) {
            // What the real store answers. Returning the first receipt again
            // instead hands the game a purchaseToken it has already processed:
            // it grants nothing, wedges its purchase state and persists that,
            // after which no product can be bought until its data is cleared.
            out.putInt("RESPONSE_CODE", ITEM_ALREADY_OWNED);
            Log.i(TAG, "Iap: getBuyIntent(" + sku + ") -> already owned");
            return out;
        }
        PendingIntent intent = pendingBuyIntent(context, sku, type);
        if (intent == null) {
            out.putInt("RESPONSE_CODE", ERROR);
            return out;
        }
        out.putInt("RESPONSE_CODE", OK);
        out.putParcelable("BUY_INTENT", intent);
        Log.i(TAG, "Iap: getBuyIntent(" + sku + ", " + type + ") -> instant success");
        return out;
    }

    /**
     * The intent the library launches to "show the Play Store". It goes straight
     * to PurchaseResultActivity, which returns a completed purchase and finishes.
     *
     * FLAG_IMMUTABLE and a per-sku request code are both required: API 31+
     * insists on explicit mutability, and without distinct request codes one
     * sku's intent would be handed back for another.
     */
    public static PendingIntent pendingBuyIntent(Context context, String sku, String type) {
        if (sku == null || sku.isEmpty()) {
            return null;
        }
        Intent intent = new Intent(context, PurchaseResultActivity.class);
        intent.putExtra(PurchaseResultActivity.EXTRA_SKU, sku);
        intent.putExtra(PurchaseResultActivity.EXTRA_TYPE, type == null ? "inapp" : type);
        return PendingIntent.getActivity(context, Math.abs(sku.hashCode()), intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static boolean writeInt(Parcel reply, int value) {
        reply.writeNoException();
        reply.writeInt(value);
        return true;
    }

    /**
     * A Bundle return is a typed object: writeInt(1) then the bundle's own
     * writeToParcel. Parcel.writeBundle is a different format and desyncs the
     * reader -- the proxy reads readInt() and then Bundle.CREATOR.
     */
    private static boolean writeBundle(Parcel reply, Bundle value) {
        reply.writeNoException();
        if (value == null) {
            reply.writeInt(0);
            return true;
        }
        reply.writeInt(1);
        value.writeToParcel(reply, 0);
        return true;
    }
}
