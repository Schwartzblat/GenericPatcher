package com.smali_generator.patches;

import android.net.Uri;
import android.util.Log;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.arthooks.ArtHooks;
import com.smali_generator.Hook;
import com.smali_generator.utils.AdResponses;
import com.smali_generator.utils.AdRules;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Ads inside WebViews. Chromium has its own network stack and resolves names
 * natively, so NetworkAdBlockHook never sees these requests.
 *
 * Two layers:
 *  - WebViewClient.shouldInterceptRequest, on the framework base class: a
 *    request to an ad host gets a reply with no ad in it (AdResponses: an empty
 *    VMAP for an ad tag, a no-op gpt.js, else an empty body). A client that
 *    overrides the method without calling super bypasses this, hence:
 *  - WebView.loadUrl / loadData / loadDataWithBaseURL / postUrl: an ad SDK's
 *    own WebView subclass loads nothing, and nobody loads a page from an ad
 *    host. A blocked URL load is sent to an address that refuses the
 *    connection, so it fails through the SDK's onReceivedError as it would
 *    offline; a blocked creative (loadData*) is just not rendered.
 *
 * Whose WebView it is is read from its class, never from the call stack (an app
 * that opens its own page from inside an ad callback must still get it), and
 * never from its WebViewClient: IMA's client hosts IMA's own loader page, and
 * with that page blanked IMA never answers, so a player waiting on it hangs
 * (measured on a live player). IMA must load and be told there are no ads.
 */
public class WebViewAdBlockHook implements Hook {

    private static final String TAG = "PATCH";
    private static final byte[] EMPTY = new byte[0];

    private static String blockReason(Object webView, String url) {
        if (webView != null && AdRules.isAdClass(webView.getClass().getName())) {
            return "ad WebView " + webView.getClass().getName();
        }
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) {
            return null;
        }
        Uri uri = Uri.parse(url);
        return AdRules.urlRule(uri.getHost(), uri.getPath());
    }

    // --- WebViewClient.shouldInterceptRequest (runs on a Chromium IO thread) ---

    static native WebResourceResponse intercept_backup(Object thiz, WebView view, WebResourceRequest request);

    static WebResourceResponse intercept(Object thiz, WebView view, WebResourceRequest request) {
        try {
            if (request != null) {
                Uri url = request.getUrl();
                String reason = AdRules.urlRule(url.getHost(), url.getPath());
                if (reason != null) {
                    return adFreeReply(request, url, reason);
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "Web: intercept: " + t);
        }
        return intercept_backup(thiz, view, request);
    }

    private static WebResourceResponse adFreeReply(WebResourceRequest request, Uri url, String reason) {
        String host = url.getHost();
        String path = url.getPath() == null ? "" : url.getPath();
        if (AdResponses.isAdTagRequest(host, path)) {
            Log.i(TAG, "Web: empty VMAP <- " + host + path + " iu=" + url.getQueryParameter("iu"));
            return respond(request, "text/xml", AdResponses.EMPTY_VMAP.getBytes(StandardCharsets.UTF_8));
        }
        AdRules.logOnce("web " + host + reason, "Web: empty reply for " + host + " (" + reason + ", first: " + path + ")");
        String lower = path.toLowerCase(Locale.ROOT);
        if (AdResponses.isGptScript(lower)) {
            return respond(request, "application/javascript", AdResponses.GPT_STUB_JS.getBytes(StandardCharsets.UTF_8));
        }
        String mime;
        if (lower.endsWith(".js")) {
            mime = "application/javascript";
        } else if (lower.endsWith(".html") || lower.endsWith(".htm")) {
            mime = "text/html";
        } else if (lower.endsWith(".gif") || lower.endsWith(".png") || lower.endsWith(".jpg")) {
            mime = "image/gif";
        } else {
            mime = "text/plain";
        }
        return respond(request, mime, EMPTY);
    }

    /** A 200 with body. An XHR made with credentials rejects "*", so echo the page origin when there is one. */
    private static WebResourceResponse respond(WebResourceRequest request, String mime, byte[] body) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Cache-Control", "no-store");
        String origin = null;
        Map<String, String> requestHeaders = request.getRequestHeaders();
        if (requestHeaders != null) {
            for (Map.Entry<String, String> entry : requestHeaders.entrySet()) {
                if ("Origin".equalsIgnoreCase(entry.getKey())) {
                    origin = entry.getValue();
                }
            }
        }
        if (origin != null && !origin.equals("null")) {
            headers.put("Access-Control-Allow-Origin", origin);
            headers.put("Access-Control-Allow-Credentials", "true");
        } else {
            headers.put("Access-Control-Allow-Origin", "*");
        }
        return new WebResourceResponse(mime, "utf-8", 200, "OK", headers, new ByteArrayInputStream(body));
    }

    // --- WebView loads (main thread) ---

    // Refuses connections at once. A page load of it fails the way it does
    // offline, through the SDK's own onReceivedError, instead of never finishing:
    // GMA, for one, waits 10 s for its SDK-core page before giving up on it.
    private static final String UNREACHABLE = "https://127.0.0.1:1/";

    /** A javascript: URL has nothing to fail; any other blocked URL becomes UNREACHABLE. */
    private static String unreachable(String url) {
        return url != null && url.startsWith("javascript:") ? "javascript:void(0)" : UNREACHABLE;
    }

    private static boolean skipLoad(Object webView, String url, String what) {
        try {
            String reason = blockReason(webView, url);
            if (reason != null) {
                String shown = url == null ? "" : url.length() > 80 ? url.substring(0, 80) + "..." : url;
                AdRules.logOnce("load " + webView.getClass().getName() + what + reason,
                        "Web: blocked " + what + " " + shown + " (" + reason + ")");
                return true;
            }
        } catch (Throwable t) {
            Log.e(TAG, "Web: " + what + ": " + t);
        }
        return false;
    }

    static native void load_url_backup(Object thiz, String url);

    static native void load_url_headers_backup(Object thiz, String url, Map<String, String> headers);

    static native void post_url_backup(Object thiz, String url, byte[] body);

    static native void load_data_backup(Object thiz, String data, String mimeType, String encoding);

    static native void load_data_base_backup(Object thiz, String baseUrl, String data, String mimeType,
                                             String encoding, String historyUrl);

    static void load_url(Object thiz, String url) {
        load_url_backup(thiz, skipLoad(thiz, url, "loadUrl") ? unreachable(url) : url);
    }

    static void load_url_headers(Object thiz, String url, Map<String, String> headers) {
        load_url_headers_backup(thiz, skipLoad(thiz, url, "loadUrl") ? unreachable(url) : url, headers);
    }

    static void post_url(Object thiz, String url, byte[] body) {
        if (skipLoad(thiz, url, "postUrl")) {
            load_url_backup(thiz, unreachable(url));
        } else {
            post_url_backup(thiz, url, body);
        }
    }

    static void load_data(Object thiz, String data, String mimeType, String encoding) {
        if (!skipLoad(thiz, null, "loadData")) {
            load_data_backup(thiz, data, mimeType, encoding);
        }
    }

    static void load_data_base(Object thiz, String baseUrl, String data, String mimeType, String encoding,
                               String historyUrl) {
        if (!skipLoad(thiz, baseUrl, "loadDataWithBaseURL")) {
            load_data_base_backup(thiz, baseUrl, data, mimeType, encoding, historyUrl);
        }
    }

    private static void hook(Method original, String replacement, Class<?>... params) {
        try {
            boolean ok = ArtHooks.hook_function(original,
                    WebViewAdBlockHook.class.getDeclaredMethod(replacement, params),
                    WebViewAdBlockHook.class.getDeclaredMethod(replacement + "_backup", params));
            Log.i(TAG, "Web: hooked " + original.getDeclaringClass().getSimpleName() + "." + original.getName()
                    + " = " + ok);
        } catch (Throwable t) {
            Log.e(TAG, "Web: " + original.getName() + ": " + t);
        }
    }

    public void load() {
        try {
            hook(WebViewClient.class.getDeclaredMethod("shouldInterceptRequest", WebView.class, WebResourceRequest.class),
                    "intercept", Object.class, WebView.class, WebResourceRequest.class);
            hook(WebView.class.getDeclaredMethod("loadUrl", String.class), "load_url", Object.class, String.class);
            hook(WebView.class.getDeclaredMethod("loadUrl", String.class, Map.class),
                    "load_url_headers", Object.class, String.class, Map.class);
            hook(WebView.class.getDeclaredMethod("postUrl", String.class, byte[].class),
                    "post_url", Object.class, String.class, byte[].class);
            hook(WebView.class.getDeclaredMethod("loadData", String.class, String.class, String.class),
                    "load_data", Object.class, String.class, String.class, String.class);
            hook(WebView.class.getDeclaredMethod("loadDataWithBaseURL",
                            String.class, String.class, String.class, String.class, String.class),
                    "load_data_base", Object.class, String.class, String.class, String.class, String.class, String.class);
        } catch (Throwable t) {
            Log.e(TAG, "Web: " + t);
        }
    }

    public void unload() {
    }
}
