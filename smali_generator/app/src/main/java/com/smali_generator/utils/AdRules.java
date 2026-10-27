package com.smali_generator.utils;

import android.content.Context;
import android.util.Log;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Decides what is ad code and what is ad traffic. Every hook asks here.
 *
 * The lists are not written in this file: artifactory_generator/ad_inventory.py
 * builds them from ad_catalog.py, plus the hosts it harvested from the APK's own
 * ad SDK classes, and they reach the app as assets/patcher/artifactory.json.
 * load() reads them from there at startup, so one prebuilt module serves every
 * app. Two kinds of rule:
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

    private static final String[] NONE = new String[0];

    // Each load() replaces every field, one volatile write each. A hook racing the
    // load sees the old or the new list, never a half-built one.
    private static volatile String sdksPresent = "";
    private static volatile boolean callerBlock = false;
    private static volatile String[] adPackages = NONE;
    private static volatile String[] appFramePackages = NONE;
    private static volatile String[] blockSuffixes = NONE;
    private static volatile Set<String> harvested = Collections.emptySet();
    private static volatile String[] protectedSuffixes = NONE;
    private static volatile String[] urlRules = NONE;

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
        if (joined == null || joined.isEmpty()) {
            return NONE;
        }
        return joined.split(",");
    }

    /**
     * Reads the rules from {@link Artifacts}, replacing any earlier ones. A missing
     * key is an empty list. Returns false when no curated host suffix was loaded,
     * i.e. there are no rules at all. Does not log (Log is absent in JVM tests);
     * the caller reports it.
     */
    public static boolean load() {
        String[] suffixes = split(Artifacts.get("AD_HOST_SUFFIXES"));
        String present = Artifacts.get("AD_SDKS_PRESENT");
        adPackages = split(Artifacts.get("AD_SDK_PACKAGES"));
        appFramePackages = split(Artifacts.get("AD_APP_FRAME_PACKAGES"));
        harvested = new HashSet<>(Arrays.asList(split(Artifacts.get("AD_HOSTS_HARVESTED"))));
        protectedSuffixes = split(Artifacts.get("AD_PROTECTED_SUFFIXES"));
        urlRules = split(Artifacts.get("AD_URL_RULES"));
        sdksPresent = present == null ? "" : present;
        callerBlock = "true".equals(Artifacts.get("AD_CALLER_BLOCK"));
        blockSuffixes = suffixes;
        return suffixes.length > 0;
    }

    /**
     * Whether the glue hooks that reach into ad SDK classes apply. True when
     * the artifacts carry an ad layer, i.e. the finder wrote AD_HOST_SUFFIXES
     * (it does for every app with ad code, ad-free ones included), and when
     * there are no artifacts at all: a missing or corrupt artifactory.json
     * says nothing about what was chosen, so the SDK hooks keep blocking as
     * they did before purchases. A purchases-only build has artifacts but no
     * ad layer, and the glue hooks stay off.
     */
    public static boolean layerEnabled() {
        return Artifacts.get("AD_HOST_SUFFIXES") != null || Artifacts.isEmpty();
    }

    public static String sdksPresent() {
        return sdksPresent;
    }

    public static int harvestedCount() {
        return harvested.size();
    }

    public static int curatedCount() {
        return blockSuffixes.length;
    }

    public static boolean callerBlockEnabled() {
        return callerBlock;
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
        if (className == null || !startsWithAny(className, adPackages)) {
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
        if (hasSuffix(host, blockSuffixes)) {
            return "ad host";
        }
        if (harvested.contains(host) && !hasSuffix(host, protectedSuffixes)) {
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
        for (String prefix : urlRules) {
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
        if (!callerBlock) {
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
            if ((pkg != null && cls.startsWith(pkg)) || startsWithAny(cls, appFramePackages)) {
                return null;
            }
            // Anything else (an obfuscated library, or obfuscated app code) says
            // nothing about who is asking: keep walking.
        }
        return null;
    }
}
