# GenericPatcher

Patches **any** Android app to remove its ads, and with `--iap` to make its
Google Play in-app purchases succeed with no network and no Play Store. No
per-app reversing, and no obfuscated name is ever written down: everything the
patch needs is discovered in the APK at patch time.

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

# ads only
.venv/bin/python main.py -p ./game.apk -o ./game-patched.apk

# ads + free in-app purchases
.venv/bin/python main.py --iap -p ./game.xapk -o ./game-patched.xapk

# a bundle in gives a zip of signed splits out
unzip -o game-patched.xapk -d out && adb install-multiple out/*.apk
```

Set `--arch` to the ABI the **app** ships (not the phone's): a bundle carrying
only `config.armeabi_v7a.apk` needs `--arch armeabi-v7a`, or no hook loads.

| Flag | Meaning |
|---|---|
| `--iap` | Free the in-app purchases too. Off by default. |
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

**Purchases are answered in-process.** Every IAP client reaches the store the
same way: it binds `com.android.vending.billing.InAppBillingService.BIND` and
speaks the `IInAppBillingService` AIDL. The patch answers that bind with its own
local service, so the Play Store is never contacted. The protocol is a contract
between two separately shipped APKs, so its action, descriptor and Bundle keys
cannot be obfuscated — which is why one choke point covers the Play Billing
Library, Unity IAP and the rest, whatever R8 renamed their classes to. Nothing
is owned at launch; a purchase is granted when the player taps Buy and then
persists.

## Status

Ads: verified on an emulator against real Google test ads, and on a phone
against shipping apps from the Play Store.

Purchases: verified end to end with the network off — tap Buy, no Play dialog,
the goods credited. How far a given app gets varies with the three limits
below.

Three limits worth knowing before you start:

- **A game that validates receipts on its own server is not fooled.** Its shop
  opens and the purchase completes, but the goods are granted by the
  publisher's own backend once Google confirms the receipt, so nothing is
  credited — and a game built that way often refuses to start a purchase at
  all with the network off. This is the usual reason a patched app still shows
  no coins.
- **A product can be bought once**, as with the real store, so a game that
  never consumes its purchases cannot re-buy them.
- **An app with Play's anti-tamper protection (PairIP) will not run re-signed**
  — patched or not. Check that the untouched APK runs before blaming the patch.

```bash
adb logcat -s PATCH ArtHooks     # every decision, one line each
```

## Disclaimer

For educational purposes only. I am not responsible for any misuse of this
software.
