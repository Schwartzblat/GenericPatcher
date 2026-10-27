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
# Stitch 1.6 is not on PyPI: install it from the monorepo's utils/Stitch
.venv/bin/pip install -e ../../utils/Stitch
.venv/bin/pip install -r requirements.txt

# an APK in, a signed APK out
.venv/bin/python main.py -p ./game.apk -o ./game-patched.apk

# purchases: Google Play billing succeeds offline
.venv/bin/python main.py -p ./game.apk -o ./game-patched.apk --iap

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
| `--iap` | Also make Google Play in-app purchases succeed, offline. |
| `--no-original-cert` | Leave the re-signed certificate in Google API requests: no Maps token fix, no X-Android-Cert swap. |

## How it works

**Ads fail the way they do on a plane.** Every ad SDK has to handle being
offline, so each reports its own "no fill" and the app carries on. Requests to
ad hosts are refused, as is any request made with ad SDK code on the stack; ad
WebViews get empty replies; a full-screen ad activity is closed as it opens.
What counts as an ad lives in one data file,
[`ad_catalog.py`](artifactory_generator/ad_catalog.py) — about 30 SDKs, plus the
hosts that are never blocked. At patch time the finder also *harvests* the hosts
named inside the SDKs it finds, which catches endpoints no static list knows.

**Google Maps keeps working re-signed.** The Maps SDK asks Play services for an
API token, and Play services vouches for the app with its re-signed certificate,
which Google refuses for API keys tied to the original certificate. The patch
answers that bind itself, in-process, and fetches the token with the original
certificate; `X-Android-Cert` headers carry it too. `--no-original-cert` (a
desktop flag) turns this off. On the phone the fix applies with a store whose
engine writes the original certificate (store builds from this release on);
older stores and today's live store write no key, so the fix stays off there.

## Bundle and golden tools

- `bundle.py` builds the signed generic bundle the store applies on the phone:
  this module, PatchPaywall and `catalog.json` (the ad catalog). Pass
  `--signing-key` (EC P-256 PEM) and `--paywall` (the PatchPaywall checkout).
  With `--upload` it also sends the bundle to the patch server, reading
  `PATCH_SERVER_URL` and `BUNDLE_UPLOAD_SECRET` from the environment.
- `golden.py` records the desktop finder's `AD_*` output for one APK, and with
  `--iap` its `IAP_*` keys too, so the phone's port of the finder can be checked
  against it.

## Status

Ads: verified on an emulator against real Google test ads, and on a phone
against shipping apps from the Play Store.

Purchases (`--iap`): verified on an emulator running Defender II 1.4.9, a
shipping 2013-era game, across four runs: the bind, sku-details and buy flows
served on its three SKUs, receipts were minted and persisted in the ledger,
`getPurchases` kept them owned across restarts, and re-buying an owned SKU got
the real store's `Item Already Owned` answer. Per-app limits: Defender II's
coin/crystal counters did not credit, because that game grants entitlements
only after confirming on its own developer server, a response the patch's
minted receipts can never satisfy. So: the billing-layer contract works on
shipping apps, and each app's economy then depends on how it trusts the
store's response.

Google APIs after re-signing: verified on a Pixel 9a (Android 17) with Moovit
5.201.6.1811 patched by the store at 1.2.0. Before, its Stations map stayed
blank with `Authorization failure`. After, with the Maps token cache cleared,
the Maps SDK asked the in-app token service, the token came back from Google,
and the map drew. Search suggestions loaded with the original certificate
swapped into `X-Android-Cert` on `maps.googleapis.com` and Firebase Remote
Config.

Limits worth knowing before you start:

- **An app with Play's anti-tamper protection (PairIP) will not run re-signed**
  — patched or not. Check that the untouched APK runs before blaming the patch.
- **Purchases grant what the store answers, not what the app's counters do.**
  An app that re-confirms with its own server before crediting an entitlement
  never sees the patch's minted receipts as valid, so the store layer works
  while the app's counters stay put (Defender II does exactly this).
- **The Maps token fix is the patch's call, not Play services'.** It always
  answers the token request, so if Google changes the token service, an app whose
  Maps key is not tied to the original certificate loses a map that worked re-signed
  until a new bundle ships. Only the Play services Maps SDK is covered, and the
  header swap reaches only the platform `HttpURLConnection` (not OkHttp, Cronet or gRPC).
  The client header is rebuilt from the request's `CLIENT_TYPE`, so it may differ from
  what Play services would send, and every patched app that uses Maps sends Play
  services' API key from its own process.

```bash
adb logcat -s PATCH ArtHooks     # every decision, one line each
```

## Disclaimer

For educational purposes only. I am not responsible for any misuse of this
software.
