package com.smali_generator.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class BillingJsonTest {

    @Test
    public void purchaseCarriesTheFieldsTheLibraryParses() {
        String json = BillingJson.purchase("com.example.game", "coins_1",
                "tok-1", "order-1", 1700000000000L, true);
        assertTrue(json.contains("\"packageName\":\"com.example.game\""));
        assertTrue(json.contains("\"productId\":\"coins_1\""));
        assertTrue(json.contains("\"purchaseToken\":\"tok-1\""));
        assertTrue(json.contains("\"orderId\":\"order-1\""));
        assertTrue(json.contains("\"purchaseTime\":1700000000000"));
        assertTrue(json.contains("\"purchaseState\":0"));
        assertTrue(json.contains("\"acknowledged\":true"));
        assertTrue(json.contains("\"quantity\":1"));
    }

    @Test
    public void skuDetailsCarriesBothTheFlatAndTheV5Shapes() {
        String inapp = BillingJson.skuDetails("coins_1", "inapp", "USD");
        assertTrue(inapp.contains("\"productId\":\"coins_1\""));
        assertTrue(inapp.contains("\"type\":\"inapp\""));
        assertTrue(inapp.contains("\"price_amount_micros\":0"));
        assertTrue(inapp.contains("\"price_currency_code\":\"USD\""));
        assertTrue(inapp.contains("oneTimePurchaseOfferDetails"));
        assertFalse(inapp.contains("subscriptionOfferDetails"));

        String subs = BillingJson.skuDetails("premium_monthly", "subs", "USD");
        assertTrue(subs.contains("\"subscriptionPeriod\":\"P1M\""));
        assertTrue(subs.contains("subscriptionOfferDetails"));
        assertFalse(subs.contains("oneTimePurchaseOfferDetails"));
    }

    /**
     * Billing 8 moved the one-time offer into a list.
     *
     * A game that parses the product JSON itself reads whichever shape its
     * library version taught it to expect: a native shop built against Billing 8
     * looks for oneTimePurchaseOfferDetailsList and for nothing else, so a reply
     * carrying only the singular object left it with no price and it reported
     * "Purchase via Google Play failed" before any purchase could start.
     */
    @Test
    public void anInappProductCarriesTheBillingEightOfferList() {
        String inapp = BillingJson.skuDetails("coins_1", "inapp", "USD");
        int at = inapp.indexOf("\"oneTimePurchaseOfferDetailsList\":[");
        assertTrue("Billing 8 reads the one-time offer out of a list", at >= 0);

        String list = inapp.substring(at, inapp.indexOf("}]", at) + 2);
        assertTrue(list.contains("\"formattedPrice\":\"Free\""));
        assertTrue(list.contains("\"priceAmountMicros\":0"));
        assertTrue(list.contains("\"priceCurrencyCode\":\"USD\""));

        assertFalse("a subscription has no one-time offer",
                BillingJson.skuDetails("premium_monthly", "subs", "USD")
                        .contains("oneTimePurchaseOfferDetailsList"));
    }

    /** Review Focus 3: a quote in a product id must not break the JSON. */
    @Test
    public void specialCharactersAreEscaped() {
        String json = BillingJson.purchase("p", "ev\"il\\sku", "t", "o", 1L, false);
        assertTrue(json.contains("\"productId\":\"ev\\\"il\\\\sku\""));
        assertEquals("a\\\"b", BillingJson.escape("a\"b"));
        assertEquals("a\\\\b", BillingJson.escape("a\\b"));
        assertEquals("a\\nb", BillingJson.escape("a\nb"));
    }

    /**
     * Billing 5+ reads the offer structures with camelCase keys, not the
     * snake_case the flat SkuDetails fields use. Getting this wrong leaves
     * getFormattedPrice() empty, and a game that labels its buy button from it
     * renders a blank, dead button, which is how this was found. Verified
     * against a shipped APK: it contains formattedPrice, priceAmountMicros,
     * priceCurrencyCode, basePlanId, pricingPhases, billingPeriod,
     * recurrenceMode and billingCycleCount, and no snake_case counterparts.
     */
    @Test
    public void theV5OfferStructuresUseCamelCaseKeys() {
        String inapp = BillingJson.skuDetails("coins_1", "inapp", "USD");
        assertTrue(inapp.contains("\"formattedPrice\":\"Free\""));
        assertTrue(inapp.contains("\"priceAmountMicros\":0"));
        assertTrue(inapp.contains("\"priceCurrencyCode\":\"USD\""));

        String subs = BillingJson.skuDetails("premium_monthly", "subs", "USD");
        assertTrue(subs.contains("\"formattedPrice\":\"Free\""));
        assertTrue(subs.contains("\"basePlanId\""));
        assertTrue(subs.contains("\"pricingPhases\""));
        assertTrue(subs.contains("\"billingPeriod\":\"P1M\""));
        assertTrue(subs.contains("\"recurrenceMode\":1"));
        assertTrue(subs.contains("\"billingCycleCount\":0"));
    }

    /**
     * Google's sample verifier rejects an empty signature before it ever reaches
     * java.security.Signature, so an empty one would silently defeat the bypass:
     *
     *   if (TextUtils.isEmpty(signedData) || TextUtils.isEmpty(signature)) return false;
     */
    @Test
    public void theReceiptSignatureIsNotEmpty() {
        assertFalse(BillingJson.signature().isEmpty());
    }

    /** Review Focus 5: two purchases in one session must not collide. */
    @Test
    public void tokensAndOrderIdsAreDistinctPerCall() {
        assertNotEquals(BillingJson.newToken("coins_1"), BillingJson.newToken("coins_1"));
        assertNotEquals(BillingJson.newOrderId(), BillingJson.newOrderId());
    }
}
