import json

import golden
from artifactory_generator.ad_inventory import AdInventoryFinder
from artifactory_generator.iap_inventory import IapInventoryFinder


def test_golden_writes_only_ad_keys_sorted(tmp_path, monkeypatch):
    seen = {}

    class FakeStitch:
        def __init__(self, **kwargs):
            seen.update(kwargs)

        def find_artifacts(self):
            return {'AD_Z': 'z', 'OTHER': 'x', 'AD_A': 'a'}

    monkeypatch.setattr(golden, 'Stitch', FakeStitch)
    out = tmp_path / 'g.json'
    assert golden.main(['app.apk', '-o', str(out), '--temp', str(tmp_path / 'work')]) == 0
    assert list(json.loads(out.read_text())) == ['AD_A', 'AD_Z']
    assert json.loads(out.read_text()) == {'AD_A': 'a', 'AD_Z': 'z'}
    assert seen['apk_path'] == 'app.apk' and seen['should_sign'] is False and seen['external_modules'] == []
    assert seen['temp_path'] == str(tmp_path / 'work')


def test_golden_refuses_an_existing_temp_dir(tmp_path, monkeypatch):
    constructed = []
    monkeypatch.setattr(golden, 'Stitch', lambda **kwargs: constructed.append(kwargs))
    work = tmp_path / 'work'
    work.mkdir()
    assert golden.main(['app.apk', '-o', str(tmp_path / 'g.json'), '--temp', str(work)]) == 1
    assert constructed == [] and work.exists()


def test_golden_iap_records_iap_keys(tmp_path, monkeypatch):
    seen = {}

    class FakeStitch:
        def __init__(self, **kwargs):
            seen.update(kwargs)

        def find_artifacts(self):
            return {'AD_Z': 'z', 'IAP_B': 'b', 'OTHER': 'x', 'AD_A': 'a', 'IAP_A': 'a', 'ADS_X': 's'}

    monkeypatch.setattr(golden, 'Stitch', FakeStitch)
    out = tmp_path / 'g.json'
    assert golden.main(['app.apk', '-o', str(out), '--temp', str(tmp_path / 'work'), '--iap']) == 0
    assert list(json.loads(out.read_text())) == ['AD_A', 'AD_Z', 'IAP_A', 'IAP_B']
    assert json.loads(out.read_text()) == {'AD_A': 'a', 'AD_Z': 'z', 'IAP_A': 'a', 'IAP_B': 'b'}
    finders = seen['artifactory_list']
    assert isinstance(finders[1], IapInventoryFinder) and finders[1].enabled is True


def test_golden_without_iap_records_only_ad_keys(tmp_path, monkeypatch):
    seen = {}

    class FakeStitch:
        def __init__(self, **kwargs):
            seen.update(kwargs)

        def find_artifacts(self):
            return {'AD_Z': 'z', 'IAP_B': 'b', 'OTHER': 'x', 'AD_A': 'a', 'IAP_A': 'a', 'ADS_X': 's'}

    monkeypatch.setattr(golden, 'Stitch', FakeStitch)
    out = tmp_path / 'g.json'
    assert golden.main(['app.apk', '-o', str(out), '--temp', str(tmp_path / 'work')]) == 0
    assert list(json.loads(out.read_text())) == ['AD_A', 'AD_Z']
    assert json.loads(out.read_text()) == {'AD_A': 'a', 'AD_Z': 'z'}
    assert 'IAP_A' not in json.loads(out.read_text()) and 'IAP_B' not in json.loads(out.read_text())
    assert seen['apk_path'] == 'app.apk' and seen['should_sign'] is False and seen['external_modules'] == []
    assert seen['temp_path'] == str(tmp_path / 'work')
    assert len(seen['artifactory_list']) == 1 and isinstance(seen['artifactory_list'][0], AdInventoryFinder)
