import argparse

import stitch.common

from artifactory_generator.ad_inventory import AdInventoryFinder


def make_finder(tmp_path, smali_files):
    smali = tmp_path / 'temp' / stitch.common.EXTRACTED_PATH / 'smali'
    smali.mkdir(parents=True)
    for rel, body in smali_files.items():
        path = smali / rel
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(body, encoding='utf8')
    return AdInventoryFinder(argparse.Namespace(
        temp_path=str(tmp_path / 'temp'), block_hosts=[], allow_hosts=[], harvest=True, caller_block=True))


def run(finder):
    artifacts = {}
    finder.extract_artifacts(artifacts, '')
    return artifacts


def test_finds_sdks_and_harvests_their_hosts(tmp_path):
    finder = make_finder(tmp_path, {
        'com/applovin/a/B.smali': '    const-string v0, "https://ms.applvn.com/x"\n'
                                  '    const-string/jumbo v1, "https://edge.adnet-test.io/v1"\n',
        'com/example/app/C.smali': '    const-string v0, "https://other.adnet-test.io/"\n',
    })
    artifacts = run(finder)
    assert artifacts['AD_SDKS_PRESENT'] == 'AppLovin'
    assert artifacts['AD_HOSTS_HARVESTED'].split(',') == ['edge.adnet-test.io']
    assert artifacts['AD_CALLER_BLOCK'] == 'true'


def test_harvest_is_not_truncated(tmp_path):
    # The hosts now travel in a JSON asset, not a 64k Java string literal.
    body = ''.join(f'    const-string v0, "https://h{i}.adnet-test.io/"\n' for i in range(3000))
    finder = make_finder(tmp_path, {'com/applovin/a/B.smali': body})
    assert len(run(finder)['AD_HOSTS_HARVESTED'].split(',')) == 3000


def test_no_ads_still_fires_with_empty_values(tmp_path):
    finder = make_finder(tmp_path, {})
    artifacts = run(finder)
    assert artifacts['AD_SDKS_PRESENT'] == ''
    assert artifacts['AD_HOSTS_HARVESTED'] == ''
    assert artifacts['AD_HOST_SUFFIXES'] != ''
    assert finder.is_found
