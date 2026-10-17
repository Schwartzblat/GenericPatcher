package com.smali_generator.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AdRulesTest {

    private static final String FULL = "{"
            + "\"AD_HOST_SUFFIXES\":\"doubleclick.net,applovin.com\","
            + "\"AD_HOSTS_HARVESTED\":\"b.adhost-test.net,a.example.com\","
            + "\"AD_PROTECTED_SUFFIXES\":\"example.com\","
            + "\"AD_URL_RULES\":\"www.facebook.com/adnw_\","
            + "\"AD_SDK_PACKAGES\":\"com.applovin.\","
            + "\"AD_APP_FRAME_PACKAGES\":\"com.unity3d.player.\","
            + "\"AD_SDKS_PRESENT\":\"AppLovin\","
            + "\"AD_CALLER_BLOCK\":\"true\"}";

    @Test public void loadReadsEveryListFromTheArtifacts() {
        Artifacts.load(FULL);
        assertTrue(AdRules.load());
        assertEquals("ad host", AdRules.hostRule("ads.doubleclick.net"));
        assertEquals("harvested", AdRules.hostRule("b.adhost-test.net"));
        assertNull(AdRules.hostRule("a.example.com"));
        assertEquals("ad path", AdRules.urlRule("www.facebook.com", "/adnw_x"));
        assertEquals(2, AdRules.curatedCount());
        assertEquals(2, AdRules.harvestedCount());
        assertTrue(AdRules.callerBlockEnabled());
        assertEquals("AppLovin", AdRules.sdksPresent());
    }

    @Test public void missingOrCorruptArtifactsMeanNoRules() {
        for (String json : new String[]{null, "not json", "{}"}) {
            Artifacts.load(json);
            assertFalse(AdRules.load());
            assertNull(AdRules.hostRule("ads.doubleclick.net"));
            assertFalse(AdRules.callerBlockEnabled());
        }
    }

    @Test public void reloadReplacesEarlierRules() {
        Artifacts.load(FULL);
        AdRules.load();
        Artifacts.load("{}");
        AdRules.load();
        assertEquals(0, AdRules.curatedCount());
    }
}
