package com.smali_generator.utils;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The billing wire format: the JSON strings the Play Store would have returned.
 *
 * Deliberately has no Android imports, so it is unit-testable on the JVM and so
 * the format can be got right away from a device. Built by hand rather than with
 * org.json, for the same reason.
 *
 * The field names are a contract with the Play Billing Library's own parser, not
 * a choice: the library reads INAPP_PURCHASE_DATA and the DETAILS_LIST entries
 * with fixed keys, whatever its own classes are renamed to.
 */
public final class BillingJson {

    private static final AtomicLong COUNTER = new AtomicLong();

    private BillingJson() {
    }

    public static String escape(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(raw.length() + 8);
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '"':  out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        return out.toString();
    }

    /** A purchase receipt, in the shape INAPP_PURCHASE_DATA carries. */
    public static String purchase(String pkg, String productId, String token, String orderId,
                                  long purchaseTime, boolean acknowledged) {
        return "{"
                + "\"orderId\":\"" + escape(orderId) + "\","
                + "\"packageName\":\"" + escape(pkg) + "\","
                + "\"productId\":\"" + escape(productId) + "\","
                + "\"purchaseTime\":" + purchaseTime + ","
                + "\"purchaseState\":0,"
                + "\"purchaseToken\":\"" + escape(token) + "\","
                + "\"quantity\":1,"
                + "\"acknowledged\":" + acknowledged + ","
                + "\"developerPayload\":\"\""
                + "}";
    }

    /**
     * One DETAILS_LIST entry. Carries the flat Billing-3 fields and the
     * Billing-5 offer structures together, so any library version finds what it
     * parses: a v5 client reading subscriptionOfferDetails and a v3 client
     * reading "price" both see a complete answer.
     */
    public static String skuDetails(String productId, String type, String currency) {
        boolean subscription = "subs".equals(type);
        StringBuilder out = new StringBuilder(512);
        out.append("{")
                .append("\"productId\":\"").append(escape(productId)).append("\",")
                .append("\"type\":\"").append(escape(type)).append("\",")
                .append("\"title\":\"").append(escape(productId)).append("\",")
                .append("\"name\":\"").append(escape(productId)).append("\",")
                .append("\"description\":\"Unlocked\",")
                .append("\"price\":\"Free\",")
                .append("\"price_amount_micros\":0,")
                .append("\"price_currency_code\":\"").append(escape(currency)).append("\",")
                .append("\"skuDetailsToken\":\"\"");
        if (subscription) {
            out.append(",\"subscriptionPeriod\":\"P1M\"")
                    .append(",\"subscriptionOfferDetails\":[{")
                    .append("\"offerIdToken\":\"\",\"offer_id_token\":\"\",")
                    .append("\"basePlanId\":\"").append(escape(productId)).append("\",")
                    .append("\"base_plan_id\":\"").append(escape(productId)).append("\",")
                    .append("\"pricingPhases\":[{").append(pricingPhase(currency)).append("}],")
                    .append("\"pricing_phases\":[{").append(pricingPhase(currency)).append("}]")
                    .append("}]");
        } else {
            // The same offer in both shapes. Billing 3-7 read the singular
            // object; Billing 8 moved it into a list, and an app that parses
            // the JSON itself knows only the shape its library taught it --
            // a shop built against Billing 8 reads the list and nothing else.
            out.append(",\"oneTimePurchaseOfferDetails\":{")
                    .append(offerPrice(currency))
                    .append("}")
                    .append(",\"oneTimePurchaseOfferDetailsList\":[{")
                    .append(offerPrice(currency))
                    .append("}]");
        }
        return out.append("}").toString();
    }

    /**
     * The receipt signature.
     *
     * Must not be empty. Google's sample Security.verifyPurchase short-circuits
     * on an empty signature before it ever calls java.security.Signature, so an
     * empty one would make the verifier return false without the signature hooks
     * ever running -- defeating the bypass for exactly the apps it exists for.
     * The bytes are never checked, only their presence, because verification
     * itself is hooked.
     */
    public static String signature() {
        return "MEUCIQCstitchMEUCIQCstitchMEUCIQCstitchMEUCIQCstitchMEUCIQ"
                + "CstitchMEUCIQCstitchMEUCIQCstitchMEUCIQCstitchMEUCIQ==";
    }

    /**
     * The price fields inside a v5 offer, in BOTH casings.
     *
     * Billing 5+ reads these with camelCase keys -- formattedPrice,
     * priceAmountMicros, priceCurrencyCode -- while the flat SkuDetails fields
     * above use snake_case. Emitting only snake_case leaves
     * getFormattedPrice() empty, and a game that labels its buy button from it
     * draws a blank, dead button, which is how this was found.
     * Both are written because library versions differ and an unread key costs
     * nothing.
     */
    private static String offerPrice(String currency) {
        return "\"formattedPrice\":\"Free\","
                + "\"priceAmountMicros\":0,"
                + "\"priceCurrencyCode\":\"" + escape(currency) + "\","
                + "\"formatted_price\":\"Free\","
                + "\"price_amount_micros\":0,"
                + "\"price_currency_code\":\"" + escape(currency) + "\"";
    }

    /** One pricing phase of a subscription offer, in both casings. */
    private static String pricingPhase(String currency) {
        return offerPrice(currency)
                + ",\"billingPeriod\":\"P1M\",\"billing_period\":\"P1M\""
                + ",\"recurrenceMode\":1,\"recurrence_mode\":1"
                + ",\"billingCycleCount\":0,\"billing_cycle_count\":0";
    }

    /** Distinct per call: the app's own bookkeeping keys off these. */
    public static String newToken(String productId) {
        return "stitch." + Long.toHexString(COUNTER.incrementAndGet())
                + "." + UUID.randomUUID().toString().replace("-", "");
    }

    public static String newOrderId() {
        return "GPA.STITCH-" + Long.toHexString(System.currentTimeMillis())
                + "-" + Long.toHexString(COUNTER.incrementAndGet());
    }
}
