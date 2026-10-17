package com.smali_generator.utils;

import android.content.Context;
import android.util.Log;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Decides what is ad code and what is ad traffic. Every hook asks here.
 *
 * The lists are not written in this file: artifactory_generator/ad_inventory.py
 * fills them in at patch time from ad_catalog.py, plus the hosts it harvested
 * from this APK's own ad SDK classes. Two kinds of rule:
 *
 *   host rules  - a curated suffix (blocks the host and its subdomains), a
 *                 harvested host (exact), or a host + path prefix. A protected
 *                 suffix overrides a harvested host, never a curated one.
 *   caller rule - the request was made by ad SDK code, whatever the host.
 *                 adInitiator() walks the stack from the top, skipping platform
 *                 and library frames, and answers with the first ad SDK frame
 *                 it meets. An app frame met first means the app made the
 *                 request (say, from inside an ad callback), and it is allowed.
 */
public final class AdRules {

    private static final String TAG = "PATCH";

    public static final String SDKS_PRESENT = "{{AD_SDKS_PRESENT}}";
    private static final boolean CALLER_BLOCK = Boolean.parseBoolean("{{AD_CALLER_BLOCK}}");

    private static final String[] AD_PACKAGES = split("{{AD_SDK_PACKAGES}}");
    private static final String[] APP_FRAME_PACKAGES = split("{{AD_APP_FRAME_PACKAGES}}");
    private static final String[] BLOCK_SUFFIXES = split("{{AD_HOST_SUFFIXES}}");
    private static final Set<String> HARVESTED = new HashSet<>(Arrays.asList(split("{{AD_HOSTS_HARVESTED}}")));
    private static final String[] PROTECTED_SUFFIXES = split("{{AD_PROTECTED_SUFFIXES}}");
    private static final String[] URL_RULES = split("{{AD_URL_RULES}}");

    // Never the initiator of a request: the platform, HTTP stacks, and this patch.
    private static final String[] PASS_THROUGH_PACKAGES = {
            "java.", "javax.", "jdk.", "sun.", "libcore.", "dalvik.", "android.", "androidx.", "com.android.",
            "kotlin.", "kotlinx.", "okhttp3.", "okio.", "retrofit2.", "org.chromium.", "org.json.", "org.apache.",
            "com.smali_generator.", "com.arthooks.",
    };

    private static final Set<String> logged = ConcurrentHashMap.newKeySet();
    private static final int MAX_LOGGED = 4000;

    private static volatile String appPackage;

    private AdRules() {
    }

    private static String[] split(String joined) {
        if (joined.isEmpty()) {
            return new String[0];
        }
        return joined.split(",");
    }

    public static int harvestedCount() {
        return HARVESTED.size();
    }

    public static int curatedCount() {
        return BLOCK_SUFFIXES.length;
    }

    public static boolean callerBlockEnabled() {
        return CALLER_BLOCK;
    }

    /** Logs a message the first time its key is seen, so a retry loop cannot flood logcat. */
    public static void logOnce(String key, String message) {
        if (logged.size() < MAX_LOGGED && logged.add(key)) {
            Log.i(TAG, message);
        }
    }

    private static boolean hasSuffix(String host, String[] suffixes) {
        for (String suffix : suffixes) {
            if (host.equals(suffix) || (host.endsWith(suffix) && host.charAt(host.length() - suffix.length() - 1) == '.')) {
                return true;
            }
        }
        return false;
    }

    private static boolean startsWithAny(String name, String[] prefixes) {
        for (String prefix : prefixes) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static String appPackage() {
        String pkg = appPackage;
        if (pkg == null) {
            Context context = Utils.getApplicationContext();
            if (context == null) {
                return null;
            }
            pkg = context.getPackageName() + ".";
            appPackage = pkg;
        }
        return pkg;
    }

    /** True for a class of an ad SDK. Never for the app's own classes, even if its package overlaps one. */
    public static boolean isAdClass(String className) {
        if (className == null || !startsWithAny(className, AD_PACKAGES)) {
            return false;
        }
        String pkg = appPackage();
        return pkg == null || !className.startsWith(pkg);
    }

    /** The host rule a host breaks ("ad host" / "harvested"), or null. */
    public static String hostRule(String host) {
        if (host == null || host.isEmpty()) {
            return null;
        }
        host = host.toLowerCase(Locale.ROOT);
        if (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        if (hasSuffix(host, BLOCK_SUFFIXES)) {
            return "ad host";
        }
        if (HARVESTED.contains(host) && !hasSuffix(host, PROTECTED_SUFFIXES)) {
            return "harvested";
        }
        return null;
    }

    /** Like hostRule, plus the host + path-prefix rules. */
    public static String urlRule(String host, String path) {
        String rule = hostRule(host);
        if (rule != null || host == null || path == null) {
            return rule;
        }
        String hostPath = host.toLowerCase(Locale.ROOT) + path;
        for (String prefix : URL_RULES) {
            if (hostPath.startsWith(prefix)) {
                return "ad path";
            }
        }
        return null;
    }

    /**
     * The ad SDK class that made the current call, or null if the app made it
     * (or the caller rule is off). Costs one stack walk; call it only on paths
     * that are not hot.
     */
    public static String adInitiator() {
        if (!CALLER_BLOCK) {
            return null;
        }
        String pkg = appPackage();
        for (StackTraceElement frame : new Throwable().getStackTrace()) {
            String cls = frame.getClassName();
            if (startsWithAny(cls, PASS_THROUGH_PACKAGES)) {
                continue;
            }
            if (isAdClass(cls)) {
                return cls;
            }
            if ((pkg != null && cls.startsWith(pkg)) || startsWithAny(cls, APP_FRAME_PACKAGES)) {
                return null;
            }
            // Anything else (an obfuscated library, or obfuscated app code) says
            // nothing about who is asking: keep walking.
        }
        return null;
    }
}
