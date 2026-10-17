import sys

import main
from artifactory_generator.ad_inventory import AdInventoryFinder


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
    assert len(calls[0]['artifactory_list']) == 1
    assert isinstance(calls[0]['artifactory_list'][0], AdInventoryFinder)
