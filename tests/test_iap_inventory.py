"""The finder must fire on every APK, with or without billing code.

These run against whatever has been extracted into .patcher-work. The finder's
whole claim is that it needs no per-app knowledge, so what is asserted here is
what has to hold for any app, not what one build happens to contain.
"""
import functools
from argparse import Namespace
from pathlib import Path

import pytest

from artifactory_generator.iap_inventory import IapInventoryFinder

WORK = Path(__file__).resolve().parents[1] / '.patcher-work'

KEYS = {'IAP_ENABLED', 'IAP_CLIENTS_PRESENT',
        'IAP_VERIFIER_CLASS_NAME', 'IAP_VERIFIER_METHOD_NAME', 'IAP_VERIFIER_METHOD_SIG'}

# Google's sample, with and without the public key argument.
VERIFIER_SIGS = ('(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Z',
                 '(Ljava/lang/String;Ljava/lang/String;)Z')


def extracted_apks() -> list:
    """Every APK decompiled into the work directory, whichever ones they are."""
    if not WORK.is_dir():
        return []
    return sorted(path.name for path in WORK.iterdir() if (path / 'extracted').is_dir())


APKS = extracted_apks()
needs_an_apk = pytest.mark.skipif(not APKS, reason='no APK extracted into .patcher-work')


@functools.lru_cache(maxsize=None)
def run_finder(stem: str, iap: bool = True) -> dict:
    """Cached: a scan is the same answer every time, and these tests re-ask a lot."""
    args = Namespace(temp_path=str(WORK / stem), iap=iap)
    finder = IapInventoryFinder(args)
    artifacts = {}
    finder.extract_artifacts(artifacts, '')
    return artifacts


@needs_an_apk
@pytest.mark.parametrize('stem', APKS)
def test_every_key_is_filled_whatever_the_app_ships(stem):
    """A finder that matches nothing must still fill its placeholders.

    An unsubstituted {{IAP_...}} compiles and then throws inside the hook's own
    try/catch at runtime, so the patch installs, runs, and silently hooks nothing.
    """
    artifacts = run_finder(stem)
    assert artifacts.keys() == KEYS
    assert all(isinstance(value, str) for value in artifacts.values())
    assert artifacts['IAP_ENABLED'] == 'true'


@needs_an_apk
def test_an_app_without_billing_reports_empty_values():
    for stem in APKS:
        artifacts = run_finder(stem)
        if artifacts['IAP_CLIENTS_PRESENT'] == '':
            assert artifacts['IAP_VERIFIER_CLASS_NAME'] == ''
            assert artifacts['IAP_VERIFIER_METHOD_SIG'] == ''
            return
    pytest.skip('every extracted APK ships a billing client')


@needs_an_apk
def test_an_app_that_embeds_the_aidl_itself_is_reported():
    """Older games ship com/android/vending/billing/IInAppBillingService directly.

    Gating the descriptor scan on a list of SDK packages made the finder report
    "0 billing client(s)" for an app that is entirely billing-driven, and skipped
    both its verifier and the transaction cross-check.
    """
    pattern = 'smali*/com/android/vending/billing/IInAppBillingService*.smali'
    embedded = [stem for stem in APKS if any((WORK / stem / 'extracted').glob(pattern))]
    if not embedded:
        pytest.skip('no extracted APK embeds the AIDL itself')
    for stem in embedded:
        assert run_finder(stem)['IAP_CLIENTS_PRESENT'] != '', stem


@needs_an_apk
def test_a_sample_verifier_is_reported_in_a_shape_arthooks_can_hook():
    """Google's canonical sample is verifyPurchase(key, signedData, signature).

    Matching only the two-argument variant missed the shape most apps ship, and
    a replacement method has to have the same arity as the one it replaces.
    """
    verifiers = [(stem, run_finder(stem)) for stem in APKS
                 if run_finder(stem)['IAP_VERIFIER_CLASS_NAME']]
    if not verifiers:
        pytest.skip('no extracted APK ships a sample verifier')
    for stem, artifacts in verifiers:
        assert artifacts['IAP_VERIFIER_METHOD_NAME'] == 'verifyPurchase', stem
        assert artifacts['IAP_VERIFIER_METHOD_SIG'] in VERIFIER_SIGS, stem
