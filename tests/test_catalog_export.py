import json

from artifactory_generator.ad_catalog import (
    APP_FRAME_PACKAGES, HARVEST_BARE_TLDS, PROTECTED_HOSTS, SDKS, URL_RULES)
from artifactory_generator.ad_inventory import PACKAGE_ROOTS
from artifactory_generator.catalog_export import catalog_json
from artifactory_generator.iap_catalog import IAP_CLIENTS, VERIFIER_ANCHORS


def test_catalog_mirrors_ad_catalog():
    sdks = catalog_json()['sdks']
    assert len(sdks) == len(SDKS)
    for exported, sdk in zip(sdks, SDKS):
        assert exported['name'] == sdk.name
        assert exported['packages'] == list(sdk.packages)
        assert exported['hosts'] == list(sdk.hosts)
    [audience] = [s for s in sdks if s['name'] == 'Meta Audience Network']
    assert audience['hosts'] == []


def test_catalog_is_plain_json():
    catalog = catalog_json()
    assert json.loads(json.dumps(catalog)) == catalog


def test_lists_are_sorted_where_promised():
    catalog = catalog_json()
    assert catalog['harvest_bare_tlds'] == sorted(catalog['harvest_bare_tlds'])
    assert catalog['package_roots'] == sorted(catalog['package_roots'])


def test_rules_mirror_ad_catalog():
    catalog = catalog_json()
    assert catalog['protected_hosts'] == list(PROTECTED_HOSTS)
    assert catalog['url_rules'] == [[host, path] for host, path in URL_RULES]
    assert catalog['app_frame_packages'] == list(APP_FRAME_PACKAGES)


def test_catalog_exports_the_iap_section():
    iap = catalog_json()['iap']
    assert iap['verifier_anchors'] == list(VERIFIER_ANCHORS)
    assert iap['clients'] == [{'name': client.name, 'packages': list(client.packages)} for client in IAP_CLIENTS]


def test_catalog_ads_fields_unchanged_with_iap():
    catalog = catalog_json()
    ads = {key: value for key, value in catalog.items() if key != 'iap'}
    assert ads == {
        'format': 1,
        'sdks': [{'name': sdk.name, 'packages': list(sdk.packages), 'hosts': list(sdk.hosts)} for sdk in SDKS],
        'protected_hosts': list(PROTECTED_HOSTS),
        'url_rules': [[host, path] for host, path in URL_RULES],
        'app_frame_packages': list(APP_FRAME_PACKAGES),
        'harvest_bare_tlds': sorted(HARVEST_BARE_TLDS),
        'package_roots': sorted(PACKAGE_ROOTS),
    }
    assert json.loads(json.dumps(catalog)) == catalog
