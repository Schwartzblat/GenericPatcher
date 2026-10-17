import json

from artifactory_generator.ad_catalog import APP_FRAME_PACKAGES, PROTECTED_HOSTS, SDKS, URL_RULES
from artifactory_generator.catalog_export import catalog_json


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
