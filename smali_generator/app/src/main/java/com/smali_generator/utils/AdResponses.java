package com.smali_generator.utils;

import java.util.Locale;

/**
 * What a blocked web ad request gets instead of an ad. Each of these was
 * needed in production.
 *
 * An empty body is fine for most ad requests, but not for these two:
 *  - an ad tag (GAM /gampad/ads): a video player waits for IMA to say whether
 *    there is an ad, and players act on IMA's error code. Measured with IMA in
 *    a news player: an empty VAST gives 303 VAST_NO_ADS_AFTER_WRAPPER, an empty
 *    body 1010 UNKNOWN_AD_RESPONSE, and an empty VMAP 1009 VAST_EMPTY_RESPONSE,
 *    "the response does not contain any valid ads". 1009 is the one players
 *    treat as no ads and go straight to content on; it stalls on the others.
 *  - gpt.js: pages call googletag.cmd.push / pubads() / defineSlot() unguarded,
 *    and with an empty script those calls throw and can take the rest of the
 *    page's script down with them.
 */
public final class AdResponses {

    public static final String EMPTY_VMAP =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<vmap:VMAP xmlns:vmap=\"http://www.iab.net/videosuite/vmap\" version=\"1.0\"/>";

    /**
     * Stand-in for gpt.js: answers the whole googletag API with chainable no-ops
     * and runs the queued commands, so no slot is ever requested.
     */
    public static final String GPT_STUB_JS = "(function(){var w=window,n=function(){};"
            + "var s={};['addService','clearCategoryExclusions','clearTargeting','defineSizeMapping','set',"
            + "'setCategoryExclusion','setClickUrl','setCollapseEmptyDiv','setConfig','setForceSafeFrame',"
            + "'setSafeFrameConfig','setTargeting','updateTargetingFromMap'].forEach(function(k){s[k]=function(){return s}});"
            + "s.getAdUnitPath=s.getSlotElementId=function(){return ''};"
            + "s.getTargeting=s.getTargetingKeys=s.getSizes=function(){return []};"
            + "s.getResponseInformation=function(){return null};"
            + "var p={};['addEventListener','removeEventListener','clear','clearCategoryExclusions',"
            + "'clearTagForChildDirectedTreatment','clearTargeting','collapseEmptyDivs','disableInitialLoad','display',"
            + "'enableAsyncRendering','enableLazyLoad','enableSingleRequest','enableSyncRendering','enableVideoAds',"
            + "'refresh','set','setCategoryExclusion','setCentering','setConfig','setCookieOptions','setForceSafeFrame',"
            + "'setLocation','setPrivacySettings','setPublisherProvidedId','setRequestNonPersonalizedAds',"
            + "'setSafeFrameConfig','setTagForChildDirectedTreatment','setTargeting','setVideoContent','updateCorrelator']"
            + ".forEach(function(k){p[k]=function(){return p}});"
            + "p.getSlots=p.getTargeting=p.getTargetingKeys=function(){return []};"
            + "var g=w.googletag||{},q=g.cmd||[];"
            + "g.apiReady=g.pubadsReady=true;g.pubads=g.companionAds=g.content=function(){return p};"
            + "g.defineSlot=g.defineOutOfPageSlot=function(){return s};"
            + "g.destroySlots=g.disablePublisherConsole=g.display=g.enableServices=g.openConsole=g.setAdIframeTitle=g.setConfig=n;"
            + "g.sizeMapping=function(){var m={addSize:function(){return m},build:function(){return []}};return m};"
            + "g.cmd={push:function(f){try{f.call(w)}catch(e){}return 1}};w.googletag=g;"
            + "for(var i=0;i<q.length;i++){try{q[i].call(w)}catch(e){}}})();";

    private AdResponses() {
    }

    /** A GAM VAST/VMAP ad-tag request, as built by IMA and by web players. */
    public static boolean isAdTagRequest(String host, String path) {
        if (host == null || path == null) {
            return false;
        }
        host = host.toLowerCase(Locale.ROOT);
        return (host.equals("pubads.g.doubleclick.net") || host.equals("securepubads.g.doubleclick.net"))
                && (path.startsWith("/gampad/ads") || path.startsWith("/gampad/live/ads"));
    }

    public static boolean isGptScript(String path) {
        return path != null && path.endsWith("/gpt.js");
    }
}
