"""The transaction map must be read out of the APK, not trusted from memory."""
from argparse import Namespace
from pathlib import Path

import pytest

from artifactory_generator import iap_inventory
from artifactory_generator.iap_catalog import EXPECTED_TX, TX_ARG_SHAPES

WORK = Path(__file__).resolve().parents[1] / '.patcher-work'
FIXTURES = Path(__file__).parent / 'fixtures'


@pytest.fixture(scope='session')
def shipped_proxy() -> dict:
    """The transaction map of a real, obfuscated proxy out of whatever is extracted.

    The finder's own scan does the finding, so the proxy is reached the way a
    patch reaches it rather than down a path that already knows where to look.
    """
    for work in sorted(path for path in WORK.glob('*') if (path / 'extracted').is_dir()):
        finder = iap_inventory.IapInventoryFinder(Namespace(temp_path=str(work), iap=True))
        proxy, _ = finder._scan_for_targets(finder._smali_roots())
        if proxy is not None:
            return proxy[1]
    pytest.skip('no APK extracted into .patcher-work ships a billing proxy')


def test_a_shipped_proxy_sends_the_shapes_the_catalog_expects(shipped_proxy):
    shared = {code: shape for code, shape in TX_ARG_SHAPES.items() if code in shipped_proxy}
    assert shared, 'the proxy sent none of the codes the catalog knows'
    for code, shape in shared.items():
        assert shipped_proxy[code] == shape, f'code {code} sender has the wrong shape'


def test_no_mismatch_reported_for_a_real_apk(shipped_proxy):
    assert iap_inventory.tx_mismatches(shipped_proxy) == []


def test_classic_aidl_proxy_is_parsed_too():
    """A non-obfuscated IInAppBillingService sends sget TRANSACTION_x, not a const."""
    tx_map = iap_inventory.parse_tx_map((FIXTURES / 'classic_proxy.smali').read_text(encoding='utf8'))
    assert tx_map[2] == 'ILjava/lang/String;Ljava/lang/String;Landroid/os/Bundle;'


def test_a_renumbered_proxy_is_reported():
    renumbered = {2: TX_ARG_SHAPES[1], 1: TX_ARG_SHAPES[2]}
    assert iap_inventory.tx_mismatches(renumbered) != []


def test_the_product_details_transaction_is_known(shipped_proxy):
    """Billing 5.2.1 queries product details on 901, not on the classic code 2.

    Answering 901 with a null -- the default for an unknown code -- tells the
    game it has no products, so its store shows no prices and Buy does nothing.
    A library older than 5.2.1 never sends it, so the proxy is only checked
    when it does.
    """
    assert 901 in EXPECTED_TX
    if 901 in shipped_proxy:
        assert shipped_proxy[901] == TX_ARG_SHAPES[901]


def test_twelve_consumes_and_nine_hundred_and_two_acknowledges():
    """The two codes a modern library actually uses, and they are not the same.

    Both take (int, package, token, Bundle), so their shapes cannot tell them
    apart -- only the caller can. In Play Billing 8 consumeAsync sends 12 and
    acknowledgePurchase sends 902, and the same two codes are in libraries
    years older, because the numbering is a contract with the separately
    shipped Play Store. Reading 12 as an acknowledgement leaves
    every consumable owned for ever: the game consumes it, the store says ok,
    and the product can never be bought again.
    """
    assert EXPECTED_TX[12] == 'consumePurchaseExtraParams'
    assert EXPECTED_TX[902] == 'acknowledgePurchase'
    assert TX_ARG_SHAPES[902] == TX_ARG_SHAPES[12]
