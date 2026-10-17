import json

import golden


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
