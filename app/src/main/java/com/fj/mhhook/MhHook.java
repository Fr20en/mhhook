package com.fj.mhhook;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * Reading-app ad-bypass hook (LSPosed / libxposed API 102).
 *
 * Targets are Flutter apps that aggregate ads through TopOn (AnyThink). The Dart side
 * talks to the SDK over one MethodChannel and dispatches by method name into a family
 * of manager singletons. Two of them matter here:
 *
 *   ATAdRewardVideoManger.handleMethodCall  -> RewardedVideoCall / load, ready, show
 *   ATAdSplashManger.handleMethodCall       -> SplashCall        / load, ready, show
 *
 * Both follow the same shape: the load/show actions do NOT complete the MethodChannel
 * result. The SDK pushes the real outcome to Dart later as a callback EVENT. So to skip
 * an ad we must do two things at once:
 *
 *   1. short-circuit the action so the SDK never loads or renders anything, and
 *   2. replay the callback sequence the Dart layer is waiting for, with a body that
 *      carries a syntactically valid ad transaction id.
 *
 * Rewarded flow:
 *
 *   loadRewardedVideo  -> fabricated rewardedVideoDidFinishLoading
 *   rewardedVideoReady -> true
 *   showRewardedVideo  -> replay StartPlaying -> EndPlaying -> RewardSuccess -> Close
 *
 * Splash flow:
 *
 *   loadSplash         -> fabricated splashDidFinishLoading
 *   splashReady        -> true
 *   showSplash         -> replay splashDidShowSuccess -> splashDidClose
 *
 * Design points that keep this stable across SDK updates:
 *
 *  1. Passive learning. Every real callback the SDK pushes to Flutter is cached per
 *     placement id, keyed by family, so a replay reuses the SDK's own field layout
 *     instead of a guessed one.
 *  2. Transaction-id re-forging. When a cached body is reused we keep its ad-source
 *     segment and only refresh the request part and the millisecond stamp, because the
 *     Dart validator parses exactly "<request_id>_<adsource_id>_<millis>".
 *  3. Splash hardening. The splash is rendered natively by the SDK and Dart only sees
 *     events, so we answer every status query positively and never let loadAd()/show()
 *     run at all.
 */
public class MhHook extends XposedModule {

    private static final String TAG = "MHH";

    /** Verbose tracing: every channel action and every SDK->Dart callback. */
    private static final boolean DBG = false;

    /** Packages this module arms in. */
    private static final Set<String> TARGETS = new HashSet<String>();
    static {
        TARGETS.add("com.hbsclj.uth");   // lizi comic, placement b69a01b605fc98
        TARGETS.add("com.cffyzp.szt");   // lanman comic, placement b6a76916e36064
    }

    private static final String CLS_METHOD_CALL = "io.flutter.plugin.common.MethodCall";
    private static final String CLS_RESULT      = "io.flutter.plugin.common.MethodChannel$Result";
    private static final String CLS_REWARD      = "com.anythink.flutter.reward.ATAdRewardVideoManger";
    private static final String CLS_SPLASH      = "com.anythink.flutter.splash.ATAdSplashManger";
    private static final String CLS_EVENT       = "com.anythink.flutter.ATFlutterEventManager";

    private static final String M_HANDLE    = "handleMethodCall";
    private static final String M_INSTANCE  = "getInstance";
    private static final String M_SEND_CB   = "sendCallbackMsgToFlutter";
    private static final String M_ARGUMENT  = "argument";
    private static final String M_SUCCESS   = "success";
    private static final String F_METHOD    = "method";

    private static final String K_PID       = "placementID";
    private static final String K_PID_MULTI = "placementIDMulti";

    /** Rewarded-video actions. */
    private static final String A_LOAD       = "loadRewardedVideo";
    private static final String A_READY      = "rewardedVideoReady";
    private static final String A_SHOW       = "showRewardedVideo";
    private static final String A_SHOW_SCENE = "showSceneRewardedVideo";
    private static final String A_SHOW_CFG   = "showRewardedVideoWithShowConfig";

    /** Splash actions. */
    private static final String S_LOAD     = "loadSplash";
    private static final String S_READY    = "splashReady";
    private static final String S_SHOW     = "showSplash";
    private static final String S_SHOW_SC  = "showSceneSplash";
    private static final String S_SHOW_CFG = "showSplashAdWithShowConfig";
    private static final String S_VALID    = "getSplashValidAds";
    private static final String S_STATUS   = "checkSplashLoadStatus";
    private static final String S_SCENARIO = "entrySplashScenario";

    /** Rewarded callbacks. */
    private static final String CB_LOADED   = "rewardedVideoDidFinishLoading";
    private static final String CB_START    = "rewardedVideoDidStartPlaying";
    private static final String CB_END      = "rewardedVideoDidEndPlaying";
    private static final String CB_REWARD   = "rewardedVideoDidRewardSuccess";
    private static final String CB_CLOSE    = "rewardedVideoDidClose";
    private static final String CB_PLAYING  = "Playing";
    private static final String CB_RSUCCESS = "RewardSuccess";

    /** Splash callbacks. */
    private static final String SB_LOADED = "splashDidFinishLoading";
    private static final String SB_SHOW   = "splashDidShowSuccess";
    private static final String SB_CLOSE  = "splashDidClose";
    private static final String SB_PREFIX = "splash";

    /**
     * First argument of ATFlutterEventManager#sendCallbackMsgToFlutter is an
     * invokeMethod name, NOT a channel name. The channel itself is chosen inside the
     * event manager. Rewarded and splash use different invokeMethod names.
     */
    private static final String M_CALL_RV     = "RewardedVideoCall";
    private static final String M_CALL_SPLASH = "SplashCall";
    private static final String WORKER        = "mh-worker";

    private static final String KEY_ID  = "id";
    private static final String KEY_REQ = "req_id";

    /** Fallback body, used only when nothing real has been observed for that placement yet. */
    private static final int    FALLBACK_ADSOURCE = 13138903;
    private static final String FALLBACK_NETWORK  = "8";

    private static final String TPL =
        "{\"id\":\"%s\",\"req_id\":\"%s\",\"adunit_id\":\"%s\",\"adunit_format\":\"%s\","
      + "\"network_type\":\"Network\",\"network_firm_id\":%s,\"network_name\":\"Tencent Ads\","
      + "\"adsource_id\":\"%s\",\"adsource_index\":0,\"currency\":\"CNY\",\"country\":\"CN\","
      + "\"precision\":\"exact\",\"scenario_reward_name\":\"reward_item\",\"scenario_reward_number\":1,"
      + "\"placement_type\":1,\"s_id\":0,\"bid_type\":2,\"bid_floor\":0,\"ad_source_type\":1,"
      + "\"publisher_revenue\":0.1,\"publisher_revenue_cny\":0.1,\"reward_custom_data\":\"\","
      + "\"show_custom_ext\":\"\",\"url_tag_params\":\"{}\"}";

    private static final String FMT_RV     = "RewardedVideo";
    private static final String FMT_SPLASH = "Splash";

    /** family-prefixed placementID -> last observed callback body */
    private static final Map<String, String> POOL = new ConcurrentHashMap<String, String>();

    private static final String FAM_RV = "R:";
    private static final String FAM_SP = "S:";

    private Class<?> cCall;
    private Class<?> cResult;
    private Field fMethod;
    private Method mArg;
    private Method mOk;
    private Object evt;
    private Method mSend;

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        String pkg = param.getPackageName();
        if (pkg == null || !TARGETS.contains(pkg)) {
            return;
        }
        try {
            ClassLoader cl = param.getClassLoader();

            cCall = Class.forName(CLS_METHOD_CALL, false, cl);
            cResult = Class.forName(CLS_RESULT, false, cl);
            Class<?> cEvt = Class.forName(CLS_EVENT, false, cl);

            fMethod = cCall.getField(F_METHOD);
            mArg = cCall.getMethod(M_ARGUMENT, String.class);
            mOk = cResult.getMethod(M_SUCCESS, Object.class);

            Method mInst = cEvt.getMethod(M_INSTANCE);
            mInst.setAccessible(true);
            evt = mInst.invoke(null);
            mSend = cEvt.getMethod(M_SEND_CB, String.class, String.class, String.class,
                    Object.class, String.class, Map.class);
            mSend.setAccessible(true);

            learn();

            armRewarded(cl);
            armSplash(cl);

            log(4, TAG, "armed " + pkg);
        } catch (Throwable t) {
            log(6, TAG, "arm failed " + pkg, t);
        }
    }

    /** Rewarded video: skip the load and replay the lifecycle on show. */
    private void armRewarded(ClassLoader cl) throws Throwable {
        Class<?> cMgr = Class.forName(CLS_REWARD, false, cl);
        final Method target = cMgr.getMethod(M_HANDLE, cCall, cResult);
        hook(target).intercept(chain -> {
            Object call = chain.getArg(0);
            Object res = chain.getArg(1);

            String act = (String) fMethod.get(call);
            if (act == null) {
                return chain.proceed();
            }
            String pid = pidOf(call);
            if (DBG) {
                log(4, TAG, "act=" + act + " pid=" + pid);
            }
            if (pid.isEmpty()) {
                return chain.proceed();
            }


            if (A_LOAD.equals(act)) {
                // The real SDK never completes this result; the Dart side relies on the
                // callback event instead. Completing it keeps pending awaiters sane.
                mOk.invoke(res, "");
                fire(M_CALL_RV, CB_LOADED, pid, build(pid, FAM_RV, FMT_RV));
                return adapt(target, Boolean.TRUE);
            }
            if (A_READY.equals(act)) {
                mOk.invoke(res, Boolean.TRUE);
                return adapt(target, Boolean.TRUE);
            }
            if (A_SHOW.equals(act) || A_SHOW_SCENE.equals(act) || A_SHOW_CFG.equals(act)) {
                mOk.invoke(res, "");
                replayRewarded(pid);
                return adapt(target, Boolean.TRUE);
            }
            return chain.proceed();
        });
    }

    /**
     * Splash: the SDK renders the splash view natively, so Dart only ever sees events.
     * Every status query is answered positively and no load/show is allowed through.
     */
    private void armSplash(ClassLoader cl) throws Throwable {
        Class<?> cMgr = Class.forName(CLS_SPLASH, false, cl);
        final Method target = cMgr.getMethod(M_HANDLE, cCall, cResult);
        hook(target).intercept(chain -> {
            Object call = chain.getArg(0);
            Object res = chain.getArg(1);

            String act = (String) fMethod.get(call);
            if (act == null) {
                return chain.proceed();
            }
            String pid = pidOf(call);
            if (DBG) {
                log(4, TAG, "splash act=" + act + " pid=" + pid);
            }
            if (pid.isEmpty()) {
                return chain.proceed();
            }

            if (S_LOAD.equals(act)) {
                // Keep the Dart state machine moving: announce a loaded ad but never
                // let ATSplashHelper.loadSplash() run, so nothing hits the network.
                fire(M_CALL_SPLASH, SB_LOADED, pid, build(pid, FAM_SP, FMT_SPLASH));
                return adapt(target, Boolean.TRUE);
            }
            if (S_READY.equals(act)) {
                mOk.invoke(res, Boolean.TRUE);
                return adapt(target, Boolean.TRUE);
            }
            if (S_SHOW.equals(act) || S_SHOW_SC.equals(act) || S_SHOW_CFG.equals(act)) {
                replaySplash(pid);
                return adapt(target, Boolean.TRUE);
            }
            if (S_VALID.equals(act)) {
                // "[]" is a well-formed empty JSON array, which the Dart side parses.
                mOk.invoke(res, "[]");
                return adapt(target, Boolean.TRUE);
            }
            if (S_STATUS.equals(act)) {
                Map<String, Object> st = new HashMap<String, Object>(4);
                st.put("isLoading", Boolean.FALSE);
                st.put("isReady", Boolean.TRUE);
                mOk.invoke(res, st);
                return adapt(target, Boolean.TRUE);
            }
            if (S_SCENARIO.equals(act)) {
                mOk.invoke(res, Boolean.TRUE);
                return adapt(target, Boolean.TRUE);
            }
            return chain.proceed();
        });
    }

    private String pidOf(Object call) throws Throwable {
        Object p = mArg.invoke(call, K_PID);
        if (p == null) {
            p = mArg.invoke(call, K_PID_MULTI);
        }
        return p == null ? "" : p.toString();
    }

    /** Cache the real callback bodies so replays carry the SDK's own field layout. */
    private void learn() {
        hook(mSend).intercept(chain -> {
            try {
                if (!WORKER.equals(Thread.currentThread().getName())) {
                    Object name = chain.getArg(1);
                    Object pid = chain.getArg(2);
                    Object body = chain.getArg(3);
                    if (name != null && pid != null && body != null) {
                        String n = name.toString();
                        String b = body.toString();
                        if (DBG) {
                            log(4, TAG, "cb " + n + " pid=" + pid
                                    + " body=" + (b.length() > 120 ? b.substring(0, 120) : b));
                        }
                        if (b.contains("\"" + KEY_ID + "\"")) {
                            if (n.startsWith(SB_PREFIX)) {
                                POOL.put(FAM_SP + pid, b);
                                if (DBG) {
                                    log(4, TAG, "learned S pid=" + pid + " len=" + b.length());
                                }
                            } else if (n.contains(CB_PLAYING) || n.contains(CB_RSUCCESS)
                                    || n.contains(CB_CLOSE) || n.contains(CB_START)) {
                                POOL.put(FAM_RV + pid, b);
                                if (DBG) {
                                    log(4, TAG, "learned R pid=" + pid + " len=" + b.length());
                                }
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
            return chain.proceed();
        });
    }

    /** Push one callback into Flutter through the SDK's own event manager. */
    private void fire(String call, String name, String pid, String body) {
        try {
            mSend.invoke(evt, call, name, pid, body, null, null);
            if (DBG) {
                log(4, TAG, "fire " + name + " pid=" + pid);
            }
        } catch (Throwable t) {
            log(6, TAG, "fire " + name, t);
        }
    }

    /** Replay the full rewarded lifecycle off the caller thread, the SDK does the same. */
    private void replayRewarded(final String pid) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                pause(250);
                fire(M_CALL_RV, CB_START, pid, build(pid, FAM_RV, FMT_RV));
                pause(1200);
                fire(M_CALL_RV, CB_END, pid, build(pid, FAM_RV, FMT_RV));
                pause(150);
                fire(M_CALL_RV, CB_REWARD, pid, build(pid, FAM_RV, FMT_RV));
                pause(150);
                fire(M_CALL_RV, CB_CLOSE, pid, build(pid, FAM_RV, FMT_RV));
            }
        }, WORKER);
        t.setDaemon(true);
        t.start();
    }

    /**
     * Replay the splash lifecycle. A real splash emits ShowSuccess and then Close when
     * it is dismissed by the countdown or the user; ~1.5s keeps the transition short so
     * the home page appears almost immediately.
     */
    private void replaySplash(final String pid) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                pause(120);
                fire(M_CALL_SPLASH, SB_SHOW, pid, build(pid, FAM_SP, FMT_SPLASH));
                pause(1500);
                fire(M_CALL_SPLASH, SB_CLOSE, pid, build(pid, FAM_SP, FMT_SPLASH));
            }
        }, WORKER);
        t.setDaemon(true);
        t.start();
    }

    private static void pause(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    private static String build(String pid, String fam, String format) {
        String req = UUID.randomUUID().toString().replace("-", "");
        long ts = System.currentTimeMillis();

        String hit = POOL.get(fam + pid);
        if (hit == null || hit.isEmpty()) {
            return String.format(TPL, req + "_" + FALLBACK_ADSOURCE + "_" + ts, req, pid, format,
                    FALLBACK_NETWORK, String.valueOf(FALLBACK_ADSOURCE));
        }
        String old = getStr(hit, KEY_ID);
        String out = putStr(hit, KEY_ID, old == null ? req + "_0_" + ts : reshape(old, req, ts));
        return putStr(out, KEY_REQ, req);
    }

    /** Keep the ad-source segment, refresh request id and timestamp. */
    private static String reshape(String old, String req, long ts) {
        String[] a = old.split("_");
        if (a.length >= 3) {
            return req + "_" + a[1] + "_" + ts;
        }
        return req + "_" + FALLBACK_ADSOURCE + "_" + ts;
    }

    private static String getStr(String json, String key) {
        String[] pats = {"\"" + key + "\":\"", "\"" + key + "\": \""};
        for (String p : pats) {
            int i = json.indexOf(p);
            if (i >= 0) {
                int s = i + p.length();
                int e = json.indexOf(34, s);
                if (e > s) {
                    return json.substring(s, e);
                }
            }
        }
        return null;
    }

    private static String putStr(String json, String key, String val) {
        String[] pats = {"\"" + key + "\":\"", "\"" + key + "\": \""};
        for (String p : pats) {
            int i = json.indexOf(p);
            if (i >= 0) {
                int s = i + p.length();
                int e = json.indexOf(34, s);
                if (e > s) {
                    return json.substring(0, s) + val + json.substring(e);
                }
            }
        }
        return json;
    }

    /** Mirror the hooked method's return type so the caller never sees a wrong boxed type. */
    private static Object adapt(Method m, Object value) {
        Class<?> r = m.getReturnType();
        if (r == void.class) {
            return null;
        }
        if (r == boolean.class) {
            return Boolean.TRUE;
        }
        if (r == int.class) {
            return Integer.valueOf(1);
        }
        if (value == null) {
            return null;
        }
        return r.isInstance(value) ? value : null;
    }
}
