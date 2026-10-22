package com.smali_generator;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import com.smali_generator.utils.BillingJson;
import com.smali_generator.utils.IapRules;
import com.smali_generator.utils.IapStore;

/**
 * Stands in for the Play Store's purchase screen.
 *
 * The billing library launches this through the BUY_INTENT PendingIntent and
 * waits for a result in its own ProxyBillingActivity, which then tells the app's
 * PurchasesUpdatedListener -- the same path a real purchase takes, so nothing
 * app-specific has to be hooked.
 *
 * It has no UI and must finish before onResume, which is what
 * @android:style/Theme.NoDisplay requires. No layout, so no StitchResources is
 * involved.
 */
public class PurchaseResultActivity extends Activity {

    private static final String TAG = "PATCH";

    public static final String EXTRA_SKU = "stitch_sku";
    public static final String EXTRA_TYPE = "stitch_type";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent result = new Intent();
        try {
            String sku = getIntent().getStringExtra(EXTRA_SKU);
            String type = getIntent().getStringExtra(EXTRA_TYPE);
            String json = IapStore.ledger(this).grant(sku, type == null ? "inapp" : type);
            IapRules.rememberReceipt(json);
            result.putExtra("RESPONSE_CODE", 0);
            result.putExtra("INAPP_PURCHASE_DATA", json);
            result.putExtra("INAPP_DATA_SIGNATURE", BillingJson.signature());
            setResult(RESULT_OK, result);
            Log.i(TAG, "Iap: purchased " + sku + " (" + type + ")");
        } catch (Throwable t) {
            // 6 == BillingResponseCode.ERROR: the library reports a failed purchase
            // rather than hanging on a result that never comes.
            result.putExtra("RESPONSE_CODE", 6);
            setResult(RESULT_OK, result);
            Log.e(TAG, "Iap: purchase failed", t);
        }
        finish();
    }
}
