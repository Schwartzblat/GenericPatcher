"""Emits its five IAP_* keys only when --iap was passed; else, nothing at all."""
import argparse
from pathlib import Path

import stitch.common

from artifactory_generator.iap_catalog import IAP_CLIENTS
from artifactory_generator.iap_inventory import IapInventoryFinder

# A desk-built extracted tree: the classic AIDL proxy out of the shared fixture
# (it embeds com.android.vending.billing -- the catalog knows the package as a
# client), one billing-library class for the census, and a sample receipt
# verifier whose signature is the two-String shape.
CLASSIC_PROXY = (Path(__file__).parent / 'fixtures' / 'classic_proxy.smali').read_text(encoding='utf8')

VERIFIER = """
.class public Lcom/game/Security;
.super Ljava/lang/Object;

.method public static verifyPurchase(Ljava/lang/String;Ljava/lang/String;)Z
    .locals 1
    const-string v0, "IABUtil/Security"
    return v0
.end method
"""

BILLING_CLIENT = """
.class public Lcom/android/billingclient/api/BillingClient;
.super Ljava/lang/Object;
"""

SMALI_FILES = {
    'com/android/vending/billing/IInAppBillingService$Stub$Proxy.smali': CLASSIC_PROXY,
    'com/android/billingclient/api/BillingClient.smali': BILLING_CLIENT,
    'com/game/Security.smali': VERIFIER,
}


def run_finder(tmp_path, iap: bool):
    roots = tmp_path / 'temp' / stitch.common.EXTRACTED_PATH / 'smali'
    for rel, body in SMALI_FILES.items():
        path = roots / rel
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(body, encoding='utf8')
    finder = IapInventoryFinder(argparse.Namespace(temp_path=str(tmp_path / 'temp'), iap=iap))
    artifacts = {}
    finder.extract_artifacts(artifacts, '')
    return finder, artifacts


def test_finder_emits_nothing_with_iap_off(tmp_path):
    finder, artifacts = run_finder(tmp_path, iap=False)
    assert not any(key.startswith('IAP_') for key in artifacts)
    assert finder.is_found


def test_enabled_finder_emits_every_key(tmp_path):
    finder, artifacts = run_finder(tmp_path, iap=True)
    assert finder.is_found
    assert artifacts['IAP_ENABLED'] == 'true'
    # The census joins exactly the catalogued clients this tree carries, in the
    # catalog order: the billing-client package, then the embedded AIDL package.
    assert artifacts['IAP_CLIENTS_PRESENT'] == 'Play Billing Library, Embedded Play billing AIDL'
    # IAP_CLIENTS itself carries more clients; only the ones this tree carries join the string.
    assert any(client.name == 'Unity IAP' for client in IAP_CLIENTS)
    assert artifacts['IAP_VERIFIER_CLASS_NAME'] == 'com.game.Security'
    assert artifacts['IAP_VERIFIER_METHOD_NAME'] == 'verifyPurchase'
    assert artifacts['IAP_VERIFIER_METHOD_SIG'] == '(Ljava/lang/String;Ljava/lang/String;)Z'
