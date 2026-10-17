"""What counts as ad code and ad traffic. Data only, no finder.

This is the one place the blocklists live. AdInventoryFinder reads it, checks
it against the APK being patched, and hands the result to AdRules.java through
the artifactory, so the Java side never keeps a second copy that could drift.

Everything here is a public, stable identifier: an SDK's Java package (kept by
the SDK's own consumer ProGuard rules, or by the manifest, or because Unity's C#
side reaches it by name over JNI) or a hostname. Nothing app-specific and
nothing obfuscated.
"""
from typing import NamedTuple


class AdSdk(NamedTuple):
    name: str
    # Java package prefixes, dotted, with the trailing dot. A class under one of
    # these is ad code: a request it makes is blocked, an activity of it is closed.
    packages: tuple
    # Hostname suffixes this SDK serves ads, config or tracking from. A suffix
    # blocks the host itself and every subdomain.
    hosts: tuple = ()


SDKS = (
    AdSdk('Google Mobile Ads', ('com.google.android.gms.ads.', 'com.google.android.gms.internal.ads.', 'com.google.ads.'),
          ('doubleclick.net', 'googlesyndication.com', 'googleadservices.com', 'googletagservices.com', '2mdn.net',
           'admob.com', 'adservice.google.com')),
    AdSdk('AppLovin', ('com.applovin.',),
          ('applovin.com', 'applvn.com', 'appl.vn', 'safedk.com', 'axon.ai')),
    AdSdk('ironSource / LevelPlay', ('com.ironsource.', 'com.unity3d.mediation.', 'com.unity3d.ironsourceads.'),
          ('supersonicads.com', 'supersonic.com', 'ironsrc.com', 'ironsrc.mobi', 'ironsource.mobi',
           'mediation.unity3d.com')),
    AdSdk('Unity Ads', ('com.unity3d.ads.', 'com.unity3d.services.'),
          ('unityads.unity3d.com', 'unityads.unitychina.cn', 'acquire.unity3dusercontent.com')),
    AdSdk('Meta Audience Network', ('com.facebook.ads.',)),  # shares facebook.com, see URL_RULES
    AdSdk('Liftoff / Vungle', ('com.vungle.',), ('vungle.com', 'vungle.akadns.net', 'liftoff.io', 'liftoff-creatives.io')),
    AdSdk('Mintegral', ('com.mbridge.', 'com.mintegral.'),
          ('mintegral.com', 'mintegral.net', 'rayjump.com', 'mtgglobals.com', 'mobvista.com')),
    AdSdk('Pangle', ('com.bytedance.sdk.openadsdk.', 'com.bytedance.sdk.component.', 'com.pgl.'),
          ('pangle.io', 'pangleglobal.com', 'pangolin-sdk-toutiao.com', 'pangolin-sdk-toutiao-b.com',
           'tiktokpangle.us', 'tiktokpangle-cdn-us.com', 'pglstatp.com', 'pglstatp-toutiao.com')),
    AdSdk('InMobi', ('com.inmobi.',), ('inmobi.com', 'inmobicdn.net')),
    AdSdk('Chartboost', ('com.chartboost.',), ('chartboost.com',)),
    AdSdk('DT Exchange / Fyber', ('com.fyber.', 'com.digitalturbine.'),
          ('fyber.com', 'inner-active.mobi', 'inner-active.com')),
    AdSdk('Yandex Mobile Ads', ('com.yandex.mobile.ads.',),
          ('an.yandex.ru', 'adsdk.yandex.ru', 'yandexadexchange.net', 'adfox.ru')),
    AdSdk('Amazon Publisher Services', ('com.amazon.device.ads.', 'com.amazon.aps.'), ('amazon-adsystem.com',)),
    AdSdk('Smaato', ('com.smaato.',), ('smaato.net', 'smaato.com')),
    AdSdk('BidMachine', ('io.bidmachine.',), ('bidmachine.io',)),
    AdSdk('HyprMX', ('com.hyprmx.',), ('hyprmx.com',)),
    AdSdk('Verve / PubNative', ('net.pubnative.',), ('pubnative.net', 'verve.com')),
    AdSdk('Ogury', ('io.presage.', 'com.ogury.'), ('ogury.io', 'ogury.co', 'presage.io')),
    AdSdk('Moloco', ('com.moloco.',), ('moloco.com', 'adsmoloco.com')),
    AdSdk('SuperAwesome', ('tv.superawesome.',), ('superawesome.tv',)),
    AdSdk('Kidoz', ('com.kidoz.',), ('kidoz.net',)),
    AdSdk('Tapjoy', ('com.tapjoy.',), ('tapjoy.com', 'tapjoyads.com')),
    AdSdk('AdColony', ('com.adcolony.',), ('adcolony.com', 'adtilt.com')),
    AdSdk('Start.io', ('com.startapp.',), ('startappservice.com', 'start.io')),
    AdSdk('MoPub', ('com.mopub.',), ('mopub.com',)),
    AdSdk('Bigo Ads', ('sg.bigo.ads.',)),
    AdSdk('Criteo', ('com.criteo.',), ('criteo.com', 'criteo.net')),
    AdSdk('PubMatic', ('com.pubmatic.',), ('pubmatic.com',)),
    AdSdk('Ad tech (web)', (),
          # Exchanges and verification vendors whose scripts end up inside ad WebViews.
          ('adnxs.com', 'adsrvr.org', 'rubiconproject.com', 'openx.net', 'casalemedia.com', 'taboola.com',
           'outbrain.com', 'adform.net', 'smartadserver.com', 'teads.tv', 'adsafeprotected.com', 'moatads.com',
           'doubleverify.com', 'serving-sys.com', 'flashtalking.com', 'yieldmo.com', 'sharethrough.com')),
)

# Never blocked by a host rule (curated suffixes above still win). Mostly here to
# keep harvesting safe: ad SDK code also mentions shared infrastructure, help
# pages and store links, and blocking those would break the app, not its ads.
# A request that ad code itself makes to one of these is still blocked by the
# caller rule, which does not look at the host.
PROTECTED_HOSTS = (
    # Google, apart from its ad hosts above
    'google.com', 'googleapis.com', 'gstatic.com', 'googleusercontent.com', 'ggpht.com', 'gvt1.com', 'gvt2.com',
    'youtube.com', 'ytimg.com', 'googlevideo.com', 'android.com', 'firebaseio.com', 'firebaseapp.com',
    'crashlytics.com', 'app-measurement.com', 'goo.gl', 'g.co',
    # Meta, apart from Audience Network (URL_RULES)
    'facebook.com', 'fb.com', 'fbcdn.net', 'facebook.net', 'instagram.com', 'whatsapp.com',
    # Other platforms and stores
    'apple.com', 'amazon.com', 'microsoft.com', 'twitter.com', 'x.com', 't.co', 'tiktok.com', 'unity3d.com',
    'unity.com', 'yandex.ru', 'yandex.net', 'yandex.com', 'bytedance.com', 'samsung.com', 'huawei.com',
    # CDNs and hosting the app may share with an SDK
    'amazonaws.com', 'cloudfront.net', 'akamai.net', 'akamaihd.net', 'akamaized.net', 'edgesuite.net', 'edgekey.net',
    'fastly.net', 'cloudflare.com', 'cloudflare.net', 'jsdelivr.net', 'unpkg.com', 'azureedge.net', 'windows.net',
    'jquery.com', 'github.com', 'githubusercontent.com',
    # Attribution, deep links, crash reporting, push: not ads, and blocking them breaks things
    'adjust.com', 'adjust.net.in', 'appsflyer.com', 'onelink.me', 'branch.io', 'app.link', 'kochava.com',
    'singular.net', 'sentry.io', 'bugsnag.com', 'onesignal.com',
    # IP / geo lookups an app may also use
    'ipify.org', 'ip-api.com', 'ipinfo.io', 'ipapi.co', 'ifconfig.me', 'icanhazip.com',
    # Specs and schema URIs
    'w3.org', 'iana.org', 'iab.com', 'iabtechlab.com', 'iab.net', 'apache.org', 'xmlpull.org', 'json.org',
    'schema.org', 'example.com', 'localhost',
)

# host + path prefix. For ad endpoints on hosts the app also needs.
URL_RULES = (
    ('graph.facebook.com', '/network_ads_common'),
    ('www.facebook.com', '/adnw_'),
    ('www.facebook.com', '/an/'),
    ('www.gstatic.com', '/admob/'),
    ('www.google.com', '/pagead/'),
    ('www.google.com', '/ads/'),
)

# Frames that end the call-stack walk without blocking: code that is clearly
# not ad code, so a request it makes is the app's own even if ad code is further
# down the stack (an ad callback that goes on to do app work). The app's own
# package is added at runtime.
APP_FRAME_PACKAGES = (
    'com.unity3d.player.', 'io.flutter.', 'com.facebook.react.', 'org.cocos2dx.', 'org.godotengine.',
    'com.google.firebase.', 'com.google.android.gms.', 'com.google.android.play.', 'com.facebook.',
    'com.android.billingclient.', 'com.android.vending.', 'com.unity.purchasing.',
    'com.adjust.', 'com.appsflyer.', 'io.branch.',
)

# TLDs a bare hostname literal (one with no scheme) must end in to be harvested.
# Bare literals are mostly package and class names, which this keeps out.
HARVEST_BARE_TLDS = frozenset((
    'com', 'net', 'org', 'io', 'co', 'tv', 'ai', 'app', 'mobi', 'xyz', 'info', 'biz', 'me', 'ly', 'gg',
    'cn', 'ru', 'de', 'jp', 'kr', 'sg', 'in', 'cc', 'link', 'cloud', 'tech',
))
