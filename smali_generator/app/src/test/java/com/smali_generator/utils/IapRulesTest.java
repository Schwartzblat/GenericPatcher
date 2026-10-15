package com.smali_generator.utils;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class IapRulesTest {

    /**
     * The verifier's arity decides which replacement may hook it.
     *
     * Google's sample ships verifyPurchase(base64PublicKey, signedData,
     * signature); some copies drop the key. ArtHooks replaces a method with one
     * of the same shape, so hooking a 3-argument verifier with a 2-argument
     * replacement is an ABI mismatch -- found on a shipped game whose copy of
     * the sample keeps all three.
     */
    @Test
    public void argCountReadsTheParameterListOfASmaliSignature() {
        assertEquals(3, IapRules.argCount("(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Z"));
        assertEquals(2, IapRules.argCount("(Ljava/lang/String;Ljava/lang/String;)Z"));
    }

    @Test
    public void argCountOfAnUnfilledOrEmptySignatureIsZero() {
        assertEquals("no verifier found, so no hook", 0, IapRules.argCount(""));
        assertEquals("a placeholder the patch never filled", 0,
                IapRules.argCount("{{IAP_VERIFIER_METHOD_SIG}}"));
        assertEquals(0, IapRules.argCount(null));
        assertEquals(0, IapRules.argCount("()Z"));
    }

    @Test
    public void argCountCountsEveryDescriptorKind() {
        assertEquals("primitives, objects and arrays each count once", 4,
                IapRules.argCount("(ILjava/lang/String;[BLjava/security/PublicKey;)Z"));
    }
}
