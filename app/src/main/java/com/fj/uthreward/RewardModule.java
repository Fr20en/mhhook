
package com.fj.uthreward;

import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

public class RewardModule extends XposedModule {

    private static final String PKG = "com.hbsclj.uth";
    private static final String CHANNEL = "RewardedVideoCall";
    private static final String TAG = "FJUTH";

    private Class<?> cMethodCall;
    private Class<?> cResult;
    private Field fMethod;
    private Method mArg;
    private Method mSuccess;
    private Object evtMgr;
    private Method mSendCallback;

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        if (!PKG.equals(param.getPackageName())) {
            return;
        }
        try {
            ClassLoader cl = param.getClassLoader();

            cMethodCall = Class.forName("io.flutter.plugin.common.MethodCall", false, cl);
            cResult = Class.forName("io.flutter.plugin.common.MethodChannel$Result", false, cl);
            Class<?> cMgr = Class.forName("com.anythink.flutter.reward.ATAdRewardVideoManger", false, cl);
            Class<?> cEvt = Class.forName("com.anythink.flutter.ATFlutterEventManager", false, cl);

            fMethod = cMethodCall.getField("method");
            mArg = cMethodCall.getMethod("argument", String.class);
            mSuccess = cResult.getMethod("success", Object.class);

            evtMgr = cEvt.getMethod("getInstance").invoke(null);
            mSendCallback = cEvt.getMethod("sendCallbackMsgToFlutter",
                    String.class, String.class, String.class, Object.class, String.class, Map.class);

            Method handle = cMgr.getMethod("handleMethodCall", cMethodCall, cResult);
            hook(handle).intercept(chain -> {
                Object mc = chain.getArg(0);
                Object res = chain.getArg(1);

                String name = (String) fMethod.get(mc);
                Object pidObj = mArg.invoke(mc, "placementID");
                if (pidObj == null) {
                    pidObj = mArg.invoke(mc, "placementIDMulti");
                }
                String pid = pidObj == null ? "" : pidObj.toString();
                if (pid.isEmpty()) {
                    return chain.proceed();
                }

                if ("loadRewardedVideo".equals(name)) {
                    emit("rewardedVideoDidFinishLoading", pid);
                    return Boolean.TRUE;
                }
                if ("rewardedVideoReady".equals(name)) {
                    mSuccess.invoke(res, Boolean.TRUE);
                    return Boolean.TRUE;
                }
                if ("showRewardedVideo".equals(name)
                        || "showSceneRewardedVideo".equals(name)
                        || "showRewardedVideoWithShowConfig".equals(name)) {
                    mSuccess.invoke(res, "");
                    replay(pid);
                    return Boolean.TRUE;
                }
                return chain.proceed();
            });

            log(Log.INFO, TAG, "hooked ATAdRewardVideoManger");
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "hook failed", t);
        }
    }

    private void emit(String name, String pid) {
        try {
            mSendCallback.invoke(evtMgr, CHANNEL, name, pid, buildExtra(pid), null, null);
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "emit failed " + name, t);
        }
    }

    private static void nap(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    private void replay(final String pid) {
        Thread th = new Thread(new Runnable() {
            @Override
            public void run() {
                nap(250);
                emit("rewardedVideoDidStartPlaying", pid);
                nap(1200);
                emit("rewardedVideoDidEndPlaying", pid);
                nap(150);
                emit("rewardedVideoDidRewardSuccess", pid);
                nap(150);
                emit("rewardedVideoDidClose", pid);
            }
        }, "fj-reward");
        th.setDaemon(true);
        th.start();
    }

    private static String buildExtra(String pid) {
        String req = UUID.randomUUID().toString().replace("-", "");
        long ts = System.currentTimeMillis();
        StringBuilder sb = new StringBuilder(512);
        sb.append("{\"id\":\"").append(req).append("_13138903_").append(ts).append("\",");
        sb.append("\"req_id\":\"").append(req).append("\",");
        sb.append("\"adunit_id\":\"").append(pid).append("\",");
        sb.append("\"adunit_format\":\"RewardedVideo\",");
        sb.append("\"network_type\":\"Network\",");
        sb.append("\"network_firm_id\":8,");
        sb.append("\"network_name\":\"Tencent Ads\",");
        sb.append("\"network_placement_id\":\"2278320022160354\",");
        sb.append("\"adsource_id\":\"13138903\",");
        sb.append("\"adsource_index\":0,");
        sb.append("\"publisher_revenue\":0.08,");
        sb.append("\"publisher_revenue_cny\":0.09,");
        sb.append("\"currency\":\"CNY\",");
        sb.append("\"country\":\"CN\",");
        sb.append("\"precision\":\"exact\",");
        sb.append("\"scenario_reward_name\":\"reward_item\",");
        sb.append("\"scenario_reward_number\":1,");
        sb.append("\"bid_type\":2,");
        sb.append("\"placement_type\":1,");
        sb.append("\"s_id\":0,");
        sb.append("\"reward_custom_data\":\"\",");
        sb.append("\"show_custom_ext\":\"\",");
        sb.append("\"url_tag_params\":\"{}\"}");
        return sb.toString();
    }
}
