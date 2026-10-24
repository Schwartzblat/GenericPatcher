package com.smali_generator.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class IapRulesTest {

    /** The same JSON every load test starts from. */
    private static final String FULL =
            "{"
            + "\"IAP_ENABLED\":\"true\","
            + "\"IAP_CLIENTS_PRESENT\":\"Play Billing Library\","
            + "\"IAP_VERIFIER_CLASS_NAME\":\"com.example.Security\","
            + "\"IAP_VERIFIER_METHOD_NAME\":\"verifyPurchase\","
            + "\"IAP_VERIFIER_METHOD_SIG\":\"(Ljava/lang/String;Ljava/lang/String;)Z\"}";

    @Test
    public void loadReadsEveryKeyFromTheArtifacts() {
        Artifacts.load(FULL);
        assertTrue("a rule set that says so enables the layer", IapRules.load());

        assertTrue(IapRules.ENABLED);
        assertEquals("Play Billing Library", IapRules.CLIENTS_PRESENT);
        assertEquals("com.example.Security", IapRules.VERIFIER_CLASS);
        assertEquals("verifyPurchase", IapRules.VERIFIER_METHOD);
        assertEquals("(Ljava/lang/String;Ljava/lang/String;)Z", IapRules.VERIFIER_SIG);
        assertTrue(IapRules.hasVerifier());

        /**
         * The verifier's arity decides which replacement may hook it.
         *
         * Google's sample ships verifyPurchase(base64PublicKey, signedData,
         * signature); some copies drop the key. ArtHooks replaces a method with one
         * of the same shape, so hooking a 3-argument verifier with a 2-argument
         * replacement is an ABI mismatch -- found on a shipped game whose copy of
         * the sample keeps all three.
         */
        assertEquals(3, IapRules.argCount("(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Z"));
        assertEquals(2, IapRules.argCount("(Ljava/lang/String;Ljava/lang/String;)Z"));
    }

    @Test
    public void enabledIsExact() {
        Artifacts.load("{\"IAP_ENABLED\":\"1\"}");
        IapRules.load();
        assertFalse("only the exact string \"true\" switches the layer on", IapRules.ENABLED);

        Artifacts.load("{\"IAP_ENABLED\":\"TRUE\"}");
        IapRules.load();
        assertFalse("a case-insensitive read would enable by accident", IapRules.ENABLED);

        Artifacts.load("{\"IAP_ENABLED\":\"true\"}");
        IapRules.load();
        assertTrue(IapRules.ENABLED);
    }

    @Test
    public void missingArtifactsAreEmptyAndDisabled() {
        String[] absent = {null, "not json", "{}"};
        for (String json : absent) {
            Artifacts.load(json);
            assertFalse(IapRules.load());
            assertFalse(IapRules.ENABLED);
            assertEquals("", IapRules.CLIENTS_PRESENT);
            assertEquals("", IapRules.VERIFIER_CLASS);
            assertEquals("", IapRules.VERIFIER_METHOD);
            assertEquals("", IapRules.VERIFIER_SIG);
            assertFalse(IapRules.hasVerifier());
        }
    }

    @Test
    public void hasVerifierFalseWhenKeysEmpty() {
        Artifacts.load("{"
                + "\"IAP_ENABLED\":\"true\","
                + "\"IAP_CLIENTS_PRESENT\":\"Play Billing Library\","
                + "\"IAP_VERIFIER_CLASS_NAME\":\"\","
                + "\"IAP_VERIFIER_METHOD_NAME\":\"\","
                + "\"IAP_VERIFIER_METHOD_SIG\":\"\"}");
        IapRules.load();

        assertTrue("the layer itself is on", IapRules.ENABLED);
        // The empty string counts as missing, as Artifacts.get defines it: a
        // purchase-only build ships no verifier, and its signature hooks must
        // not fire against an unfilled target.
        assertFalse(IapRules.hasVerifier());
    }

    @Test
    public void reloadingReplacesEverything() {
        Artifacts.load(FULL);
        IapRules.load();
        assertTrue(IapRules.hasVerifier());

        Artifacts.load("{}");
        IapRules.load();
        assertFalse(IapRules.ENABLED);
        assertEquals("", IapRules.CLIENTS_PRESENT);
        assertEquals("", IapRules.VERIFIER_CLASS);
        assertEquals("", IapRules.VERIFIER_METHOD);
        assertEquals("", IapRules.VERIFIER_SIG);
        assertFalse(IapRules.hasVerifier());
    }

    @Test
    public void argCountOfAnUnfilledOrEmptySignatureIsZero() {
        assertEquals("no verifier found, so no hook", 0, IapRules.argCount(""));
        // A template that was never filled in: no descriptor, so no arguments.
        assertEquals("a placeholder the patch never filled", 0,
                IapRules.argCount("unfilled"));
        assertEquals(0, IapRules.argCount(null));
        assertEquals(0, IapRules.argCount("()Z"));
    }

    @Test
    public void argCountCountsEveryDescriptorKind() {
        assertEquals("primitives, objects and arrays each count once", 4,
                IapRules.argCount("(ILjava/lang/String;[BLjava/security/PublicKey;)Z"));
    }

    /**
     * The receipts this patch answered with are the only ones its signature
     * hooks let through, which is what keeps the bypass narrow: the app's own
     * signature checks are untouched.
     */
    @Test
    public void theReceiptsRememberedAreTheOnesMinted() {
        String receipt = "{\"orderId\":\"order\",\"purchaseToken\":\"token\"}";
        IapRules.rememberReceipt(receipt);
        assertTrue(IapRules.isMintedReceipt(receipt.getBytes(StandardCharsets.UTF_8)));

        assertFalse("somebody else's receipt is not one of ours",
                IapRules.isMintedReceipt("{\"orderId\":\"other\",\"purchaseToken\":\"other\"}"
                        .getBytes(StandardCharsets.UTF_8)));
        assertFalse("no receipt was minted from nothing", IapRules.isMintedReceipt(null));
        assertFalse(IapRules.isMintedReceipt(new byte[0]));
    }

    /**
     * The remembered set is bounded so a long session cannot grow it for ever,
     * and the eviction is FIFO: receipts the patch answered long ago are the
     * forgettable ones.
     */
    @Test
    public void rememberingPastTheLimitEvictsTheOldest() {
        String oldest = "{\"orderId\":\"oldest\"}";
        IapRules.rememberReceipt(oldest);
        for (int i = 1; i <= 256; i++) {
            IapRules.rememberReceipt("{\"orderId\":\"" + i + "\"}");
        }
        assertFalse("the oldest receipt was evicted to keep the set bounded",
                IapRules.isMintedReceipt(oldest.getBytes(StandardCharsets.UTF_8)));
        assertTrue("the newest receipt is still known",
                IapRules.isMintedReceipt("{\"orderId\":\"256\"}".getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * Restart: the ledger is persisted, the remembered set is not, so startup
     * refills it from the ledger.
     *
     * The catch is that acknowledge rewrites the stored receipt while the app
     * keeps the copy it was handed at purchase time, so the two spellings of
     * one minted receipt are both ours -- replaying the ledger's exact JSON
     * alone left the app's saved copy unrecognised after every restart.
     */
    @Test
    public void replayingTheLedgerAfterARestartRecognisesTheSavedCopy() {
        Map<String, String> backing = new HashMap<>();
        PurchaseLedger ledger = ledgerOver(backing);
        String saved = ledger.grant("remove_ads", "inapp");
        assertTrue(ledger.acknowledge(PurchaseLedger.field(saved, "purchaseToken")));
        String stored = ledger.owned("inapp").get(0);
        assertNotEquals("acknowledge rewrote the stored receipt", saved, stored);

        // What IapHook.load() does at startup, before any hook can fire.
        assertEquals(1, IapRules.rememberReceipts(ledger.owned("inapp"))
                + IapRules.rememberReceipts(ledger.owned("subs")));

        assertTrue("the receipt the app saved at purchase time",
                IapRules.isMintedReceipt(saved.getBytes(StandardCharsets.UTF_8)));
        assertTrue("and the ledger's acknowledged spelling of it",
                IapRules.isMintedReceipt(stored.getBytes(StandardCharsets.UTF_8)));
    }

    /** The same flip seen from the other side, without a ledger in the way. */
    @Test
    public void rememberingAReceiptAlsoRemembersItsAcknowledgedSpelling() {
        String before = "{\"orderId\":\"flip\",\"acknowledged\":false}";
        String after = "{\"orderId\":\"flip\",\"acknowledged\":true}";
        IapRules.rememberReceipt(before);
        assertTrue(IapRules.isMintedReceipt(after.getBytes(StandardCharsets.UTF_8)));

        String other = "{\"orderId\":\"other\",\"acknowledged\":true}";
        assertFalse("only this receipt's spelling changes",
                IapRules.isMintedReceipt(other.getBytes(StandardCharsets.UTF_8)));
    }

    /** A ledger over a map, the same store PurchaseLedgerTest drives. */
    private static PurchaseLedger ledgerOver(Map<String, String> backing) {
        return new PurchaseLedger(new PurchaseLedger.KeyValueStore() {
            @Override public String get(String key) { return backing.get(key); }
            @Override public void put(String key, String value) { backing.put(key, value); }
            @Override public void remove(String key) { backing.remove(key); }
            @Override public Set<String> keys() { return new HashSet<>(backing.keySet()); }
        }, "com.example.game");
    }
}
