package com.smali_generator.patches;

import android.net.Network;
import android.util.Log;

import com.arthooks.ArtHooks;
import com.smali_generator.Hook;
import com.smali_generator.utils.AdRules;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.URL;
import java.net.URLConnection;
import java.net.UnknownHostException;

/**
 * Ad traffic fails the way it does with no network: the lookup throws
 * UnknownHostException. Every ad SDK has to handle being offline, so each one
 * reports its own load failure through its own callbacks, and the app moves on
 * exactly as it would on a plane. That is what makes this generic: no SDK's
 * internals are touched.
 *
 * Name resolution in Java goes through InetAddress.getAllByName/getByName, or
 * Network.getAllByName/getByName for a request bound to one network: OkHttp's
 * Dns.SYSTEM, HttpURLConnection, Volley and the SDKs' own stacks all end there.
 * URL.openConnection is hooked too, for the host + path rules (ad endpoints on
 * hosts the app also uses) and for requests that would reuse a pooled
 * connection without a fresh lookup.
 *
 * Not covered here: native code and Cronet, which resolve names outside Java
 * (the SDKs seen using Cronet only do so once initialised, which UnityAdsHook
 * stops); WebViews (WebViewAdBlockHook); and requests Play services makes in its
 * own process on GMA's behalf (GmaPlayServicesHook).
 */
public class NetworkAdBlockHook implements Hook {

    private static final String TAG = "PATCH";

    static void check(String host, String via) throws UnknownHostException {
        String reason = AdRules.hostRule(host);
        if (reason == null && host != null) {
            String initiator = AdRules.adInitiator();
            if (initiator != null) {
                reason = "caller " + initiator;
            }
        }
        if (reason == null) {
            AdRules.logOnce("allow " + host, "Net: allow " + host);
            return;
        }
        AdRules.logOnce("block " + host + reason, "Net: block " + host + " (" + reason + ", " + via + ")");
        throw new UnknownHostException("Unable to resolve host \"" + host + "\": No address associated with hostname");
    }

    static void checkUrl(URL url) throws UnknownHostException {
        String protocol = url.getProtocol();
        if (!"http".equals(protocol) && !"https".equals(protocol)) {
            return;  // file:, jar:, content: -- assets and resources, never ads
        }
        String host = url.getHost();
        String reason = AdRules.urlRule(host, url.getPath());
        if (reason == null) {
            String initiator = AdRules.adInitiator();
            if (initiator != null) {
                reason = "caller " + initiator;
            }
        }
        if (reason != null) {
            AdRules.logOnce("url " + host + reason, "Net: block " + host + url.getPath() + " (" + reason + ", openConnection)");
            throw new UnknownHostException("Unable to resolve host \"" + host + "\": No address associated with hostname");
        }
    }

    // Backups: ArtHooks points each at its original. They must be static native,
    // because dex2oat would inline a Java body into the replacement.
    static native InetAddress[] get_all_by_name_backup(String host) throws UnknownHostException;

    static native InetAddress get_by_name_backup(String host) throws UnknownHostException;

    static native InetAddress[] network_get_all_by_name_backup(Object thiz, String host) throws UnknownHostException;

    static native InetAddress network_get_by_name_backup(Object thiz, String host) throws UnknownHostException;

    static native URLConnection open_connection_backup(Object thiz) throws IOException;

    static native URLConnection open_connection_proxy_backup(Object thiz, Proxy proxy) throws IOException;

    // Replacements. Static targets take no thiz; instance targets take a leading one.
    static InetAddress[] get_all_by_name(String host) throws UnknownHostException {
        check(host, "getAllByName");
        return get_all_by_name_backup(host);
    }

    static InetAddress get_by_name(String host) throws UnknownHostException {
        check(host, "getByName");
        return get_by_name_backup(host);
    }

    static InetAddress[] network_get_all_by_name(Object thiz, String host) throws UnknownHostException {
        check(host, "Network.getAllByName");
        return network_get_all_by_name_backup(thiz, host);
    }

    static InetAddress network_get_by_name(Object thiz, String host) throws UnknownHostException {
        check(host, "Network.getByName");
        return network_get_by_name_backup(thiz, host);
    }

    static URLConnection open_connection(Object thiz) throws IOException {
        checkUrl((URL) thiz);
        return open_connection_backup(thiz);
    }

    static URLConnection open_connection_proxy(Object thiz, Proxy proxy) throws IOException {
        checkUrl((URL) thiz);
        return open_connection_proxy_backup(thiz, proxy);
    }

    private static void hook(Method original, String replacement, Class<?>... params) {
        try {
            boolean ok = ArtHooks.hook_function(original,
                    NetworkAdBlockHook.class.getDeclaredMethod(replacement, params),
                    NetworkAdBlockHook.class.getDeclaredMethod(replacement + "_backup", params));
            Log.i(TAG, "Net: hooked " + original.getDeclaringClass().getSimpleName() + "." + original.getName() + " = " + ok);
        } catch (Throwable t) {
            Log.e(TAG, "Net: " + original.getName() + ": " + t);
        }
    }

    public void load() {
        try {
            hook(InetAddress.class.getDeclaredMethod("getAllByName", String.class), "get_all_by_name", String.class);
            hook(InetAddress.class.getDeclaredMethod("getByName", String.class), "get_by_name", String.class);
            hook(Network.class.getDeclaredMethod("getAllByName", String.class),
                    "network_get_all_by_name", Object.class, String.class);
            hook(Network.class.getDeclaredMethod("getByName", String.class),
                    "network_get_by_name", Object.class, String.class);
            hook(URL.class.getDeclaredMethod("openConnection"), "open_connection", Object.class);
            hook(URL.class.getDeclaredMethod("openConnection", Proxy.class),
                    "open_connection_proxy", Object.class, Proxy.class);
        } catch (Throwable t) {
            Log.e(TAG, "Net: " + t);
        }
    }

    public void unload() {
    }
}
