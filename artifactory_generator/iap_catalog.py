"""What counts as Google Play billing. Data only.

Nothing here is an obfuscated name. The descriptor, the bind action and the
Bundle keys are a contract between the app and the separately shipped Play
Store APK, so R8 never renames them -- the same reason GmaPlayServicesHook can
name GMA's intent actions. The AIDL transaction numbers are part of that same
contract: Google cannot reorder them without breaking every published app.
"""
from collections import namedtuple

IapClient = namedtuple('IapClient', 'name packages')

# The AIDL interface every consumer talks, whatever its own class is renamed to.
BILLING_DESCRIPTOR = 'com.android.vending.billing.IInAppBillingService'
BIND_ACTION = 'com.android.vending.billing.InAppBillingService.BIND'
VENDING_PACKAGE = 'com.android.vending'
VENDING_SERVICE = 'com.android.vending.billing.InAppBillingService'

# Google's sample Security class, shipped verbatim in a lot of games. These
# strings are the anchor; the class and method names are not. The log tag is the
# dependable one -- copies vary in which messages they kept (one shipped game logs
# "Invalid key specification." and has no PublicKey message at all).
VERIFIER_ANCHORS = (
    'IABUtil/Security',
    'Error generating PublicKey from encoded key: ',
)

# Reported at patch time so the user can see what the app actually uses.
IAP_CLIENTS = [
    IapClient('Play Billing Library', ('com.android.billingclient',)),
    IapClient('Unity IAP', ('com.unity.purchasing',)),
    IapClient('Unity Ads store bridge', ('com.unity3d.services.store',)),
    IapClient('Facebook IAP tracker', ('com.facebook.appevents.iap',)),
    # Older games embed the AIDL and an IabHelper themselves rather than taking
    # the library. Without this they reported "0 billing client(s)" and were
    # skipped by the descriptor scan entirely.
    IapClient('Embedded Play billing AIDL', ('com.android.vending.billing',)),
]

# code -> AIDL method, derived from the proxy of Play Billing 5.2.1 and stable
# across versions. FakePlayBilling implements the same numbering.
EXPECTED_TX = {
    1: 'isBillingSupported',
    2: 'getSkuDetails',
    3: 'getBuyIntent',
    4: 'getPurchases',
    5: 'consumePurchase',
    8: 'getBuyIntentExtraParams',
    9: 'getPurchaseHistory',
    10: 'isBillingSupportedExtraParams',
    11: 'getPurchasesExtraParams',
    # Consume, not acknowledge: consumeAsync sends 12 when the client can pass
    # extra params and falls back to 5 when it cannot. Both codes are in
    # libraries years apart, because the numbering is a contract with the
    # separately shipped Play Store.
    12: 'consumePurchaseExtraParams',
    # Billing 5+ queries product details here, not on code 2: both
    # querySkuDetailsAsync and queryProductDetailsAsync go through it, with the
    # ids in the first Bundle and the offer lists in the second.
    901: 'getSkuDetailsV9',
    # acknowledgePurchase, which takes the same arguments as 12 and means
    # something else entirely.
    902: 'acknowledgePurchase',
}

# The smali parameter string each code's sender must have, as a cross-check
# that a code still means what we think it means.
TX_ARG_SHAPES = {
    1: 'ILjava/lang/String;Ljava/lang/String;',
    2: 'ILjava/lang/String;Ljava/lang/String;Landroid/os/Bundle;',
    3: 'ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;',
    4: 'ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;',
    5: 'ILjava/lang/String;Ljava/lang/String;',
    8: 'ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Landroid/os/Bundle;',
    9: 'ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;Landroid/os/Bundle;',
    10: 'ILjava/lang/String;Ljava/lang/String;Landroid/os/Bundle;',
    11: 'ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;Landroid/os/Bundle;',
    12: 'ILjava/lang/String;Ljava/lang/String;Landroid/os/Bundle;',
    901: 'ILjava/lang/String;Ljava/lang/String;Landroid/os/Bundle;Landroid/os/Bundle;',
    902: 'ILjava/lang/String;Ljava/lang/String;Landroid/os/Bundle;',
}
