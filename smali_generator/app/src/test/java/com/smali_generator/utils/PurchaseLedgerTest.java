package com.smali_generator.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class PurchaseLedgerTest {

    private Map<String, String> backing;
    private PurchaseLedger ledger;

    @Before
    public void setUp() {
        backing = new HashMap<>();
        ledger = new PurchaseLedger(new PurchaseLedger.KeyValueStore() {
            @Override public String get(String key) { return backing.get(key); }
            @Override public void put(String key, String value) { backing.put(key, value); }
            @Override public void remove(String key) { backing.remove(key); }
            @Override public Set<String> keys() { return new HashSet<>(backing.keySet()); }
        }, "com.example.game");
    }

    @Test
    public void nothingIsOwnedBeforeAPurchase() {
        assertTrue(ledger.owned("inapp").isEmpty());
        assertTrue(ledger.owned("subs").isEmpty());
    }

    @Test
    public void aGrantedPurchaseIsOwnedAndSurvivesANewLedger() {
        ledger.grant("coins_1", "inapp");
        assertEquals(1, ledger.owned("inapp").size());
        assertTrue(ledger.ownedProductIds("inapp").contains("coins_1"));

        PurchaseLedger reopened = new PurchaseLedger(new PurchaseLedger.KeyValueStore() {
            @Override public String get(String key) { return backing.get(key); }
            @Override public void put(String key, String value) { backing.put(key, value); }
            @Override public void remove(String key) { backing.remove(key); }
            @Override public Set<String> keys() { return new HashSet<>(backing.keySet()); }
        }, "com.example.game");
        assertEquals(1, reopened.owned("inapp").size());
    }

    /** Review Focus 4: a subs query must never see an inapp purchase. */
    @Test
    public void typesAreKeptApart() {
        ledger.grant("coins_1", "inapp");
        ledger.grant("premium_monthly", "subs");
        assertEquals(List.of("coins_1"), ledger.ownedProductIds("inapp"));
        assertEquals(List.of("premium_monthly"), ledger.ownedProductIds("subs"));
    }

    @Test
    public void consumingRemovesItSoItIsNotReGrantedOnRelaunch() {
        String json = ledger.grant("coins_1", "inapp");
        String token = tokenOf(json);
        assertTrue(ledger.consume(token));
        assertTrue(ledger.owned("inapp").isEmpty());
        assertFalse("a second consume of the same token is not an error but changes nothing",
                ledger.consume(token));
    }

    /** Review Focus 1: a consumable must be buyable again after being consumed. */
    @Test
    public void aConsumedProductCanBeBoughtAgain() {
        String first = ledger.grant("coins_1", "inapp");
        ledger.consume(tokenOf(first));
        String second = ledger.grant("coins_1", "inapp");
        assertEquals(1, ledger.owned("inapp").size());
        assertNotEquals(tokenOf(first), tokenOf(second));
    }

    @Test
    public void acknowledgingFlipsTheFlagInThePersistedReceipt() {
        String json = ledger.grant("coins_1", "inapp");
        assertTrue(json.contains("\"acknowledged\":false"));
        assertTrue(ledger.acknowledge(tokenOf(json)));
        assertTrue(ledger.owned("inapp").get(0).contains("\"acknowledged\":true"));
    }

    @Test
    public void buyingTheSameProductTwiceWithoutConsumingKeepsOneEntry() {
        ledger.grant("remove_ads", "inapp");
        ledger.grant("remove_ads", "inapp");
        assertEquals(1, ledger.owned("inapp").size());
    }

    /**
     * A repeat buy must be refusable, not answerable with the first receipt.
     *
     * Handing the same purchaseToken back a second time gives the game a token
     * it has already processed: it grants nothing, wedges its purchase state and
     * saves that state, after which no purchase of any product works again --
     * observed twice on a real game, surviving a restart. The real store
     * answers ITEM_ALREADY_OWNED instead, which owns() lets the service do.
     */
    @Test
    public void ownsReportsAProductAlreadyBought() {
        assertFalse(ledger.owns("coins_1", "inapp"));
        String json = ledger.grant("coins_1", "inapp");
        assertTrue(ledger.owns("coins_1", "inapp"));

        assertFalse("a different product is not owned", ledger.owns("gems_1", "inapp"));
        assertFalse("and neither is the same id as a subscription", ledger.owns("coins_1", "subs"));

        ledger.consume(tokenOf(json));
        assertFalse("consuming frees it to be bought again", ledger.owns("coins_1", "inapp"));
    }

    private static String tokenOf(String json) {
        int at = json.indexOf("\"purchaseToken\":\"") + 17;
        return json.substring(at, json.indexOf('"', at));
    }
}
