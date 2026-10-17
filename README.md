# GenericPatcher

Patches **any** Android app to remove its ads. No per-app reversing, and no
obfuscated name is ever written down: everything the patch needs is discovered
in the APK at patch time.

It injects a hook module built with
[stitch](https://github.com/Schwartzblat/Stitch) and
[ArtHooks](https://github.com/Schwartzblat/ArtHooks). Rather than any one app's
code, it hooks the platform and the SDKs' stable, public interfaces, which is
what makes it survive app updates.

## Quickstart

Needs Python 3.11+, JDK 17+ and an Android SDK.

```bash
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt

# an APK in, a signed APK out
.venv/bin/python main.py -p ./game.apk -o ./game-patched.apk

# a bundle in gives a zip of signed splits out
.venv/bin/python main.py -p ./game.xapk -o ./game-patched.xapk

unzip -o game-patched.xapk -d out && adb install-multiple out/*.apk
```

Set `--arch` to the ABI the **app** ships (not the phone's): a bundle carrying
only `config.armeabi_v7a.apk` needs `--arch armeabi-v7a`, or no hook loads.

| Flag | Meaning |
|---|---|
| `--arch` | ABI whose `libarthooks.so` is injected. Default `arm64-v8a`. |
| `--allow-host H` | Never block this host — the app needs it. |
| `--block-host H` | Block this host as well. |

## How it works

**Ads fail the way they do on a plane.** Every ad SDK has to handle being
offline, so each reports its own "no fill" and the app carries on. Requests to
ad hosts are refused, as is any request made with ad SDK code on the stack; ad
WebViews get empty replies; a full-screen ad activity is closed as it opens.
What counts as an ad lives in one data file,
[`ad_catalog.py`](artifactory_generator/ad_catalog.py) — about 30 SDKs, plus the
hosts that are never blocked. At patch time the finder also *harvests* the hosts
named inside the SDKs it finds, which catches endpoints no static list knows.

## Status

Ads: verified on an emulator against real Google test ads, and on a phone
against shipping apps from the Play Store.

One limit worth knowing before you start:

- **An app with Play's anti-tamper protection (PairIP) will not run re-signed**
  — patched or not. Check that the untouched APK runs before blaming the patch.

```bash
adb logcat -s PATCH ArtHooks     # every decision, one line each
```

## Disclaimer

For educational purposes only. I am not responsible for any misuse of this
software.
