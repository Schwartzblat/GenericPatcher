import sys

import main
from artifactory_generator.ad_inventory import AdInventoryFinder
from artifactory_generator.iap_inventory import IapInventoryFinder


def test_main_writes_the_artifacts_asset(monkeypatch):
    calls = []

    class RecordingStitch:
        def __init__(self, **kwargs):
            calls.append(kwargs)

        def __enter__(self):
            return self

        def __exit__(self, *exc):
            return False

        def patch(self):
            pass

    monkeypatch.setattr(main, 'Stitch', RecordingStitch)
    monkeypatch.setattr(sys, 'argv', ['main.py', '-p', 'app.apk'])
    main.main()
    assert calls[0]['artifacts_asset'] == 'assets/patcher/artifactory.json'
    assert len(calls[0]['artifactory_list']) == 2
    assert isinstance(calls[0]['artifactory_list'][0], AdInventoryFinder)
    assert isinstance(calls[0]['artifactory_list'][1], IapInventoryFinder)


def run_main(monkeypatch, argv, cert):
    calls = []
    cert_calls = []

    class RecordingStitch:
        def __init__(self, **kwargs):
            calls.append(kwargs)

        def __enter__(self):
            return self

        def __exit__(self, *exc):
            return False

        def patch(self):
            pass

    def fake_cert(path):
        cert_calls.append(path)
        return cert

    monkeypatch.setattr(main, 'Stitch', RecordingStitch)
    monkeypatch.setattr(main, 'original_cert_sha1', fake_cert)
    monkeypatch.setattr(sys, 'argv', ['main.py', '-p', 'app.apk'] + argv)
    main.main()
    return calls[0]['extra_artifacts'], cert_calls


def test_main_passes_the_original_cert(monkeypatch):
    extra, cert_calls = run_main(monkeypatch, [], 'AB' * 20)
    assert extra['ORIGINAL_CERT_SHA1'] == 'AB' * 20
    assert cert_calls == ['app.apk']


def test_no_original_cert_flag_leaves_it_out(monkeypatch):
    extra, cert_calls = run_main(monkeypatch, ['--no-original-cert'], 'AB' * 20)
    assert 'ORIGINAL_CERT_SHA1' not in extra
    assert cert_calls == []


def test_explicit_extra_artifact_wins(monkeypatch):
    extra, cert_calls = run_main(monkeypatch, ['--extra-artifacts', 'ORIGINAL_CERT_SHA1:' + 'CD' * 20], 'AB' * 20)
    assert extra['ORIGINAL_CERT_SHA1'] == 'CD' * 20
    assert cert_calls == []


def test_unreadable_cert_leaves_it_out(monkeypatch):
    extra, cert_calls = run_main(monkeypatch, [], None)
    assert 'ORIGINAL_CERT_SHA1' not in extra
    assert cert_calls == ['app.apk']
