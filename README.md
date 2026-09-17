# 栗子漫画 (com.hbsclj.uth) 免广告领奖 · LSPosed 模块

拦截 TopOn(AnyThink) 激励视频的 MethodChannel 调用，不加载、不播放广告，直接向 Flutter 层回放带广告交易编号的完整回调序列，使 Dart 侧通过校验并领取阅读奖励。

## 原理

```
Dart(libapp.so)
  └─ MethodChannel "anythink_sdk" / "RewardedVideoCall"
       └─ ATAdRewardVideoManger.handleMethodCall      <-- 本模块 Hook 点
            ├─ loadRewardedVideo   -> 回成功 + 伪造 DidFinishLoading
            ├─ rewardedVideoReady  -> 返回 true
            └─ showRewardedVideo   -> 回成功 + 回放
                                      StartPlaying -> EndPlaying
                                      -> RewardSuccess -> Close
                                          |
                                          v
                    Dart 校验 extraDic.id（广告交易编号）通过
                                          |
                                          v
                    POST /app/api/ad/reward/progress 领取奖励
```

关键点：Dart 侧要求回调 `extraDic` 内含 `id` 字段，格式为 `<req_id>_<adsourceId>_<毫秒时间戳>`。
缺少该字段时客户端会提示「未获取到广告交易编号，暂时无法确认任务结果」（该文案硬编码在 libapp.so）。

## 支持范围

- 目标包名：`com.hbsclj.uth`
- 生效入口：`showRewardedVideo` / `showSceneRewardedVideo` / `showRewardedVideoWithShowConfig`
- 同时接管 `loadRewardedVideo` / `rewardedVideoReady`，保证按钮状态正常

## 构建

### 本地

```bash
gradle assembleRelease
# 产物: app/build/outputs/apk/release/app-release.apk
```

### GitHub Actions

推送到 `main` 或打 `v*` 标签即自动构建；`.github/workflows/build.yml` 会上传 artifact，
打标签时自动创建 Release 并附带 APK。

## 安装使用

1. 安装本模块 APK（`com.fj.uthreward`）
2. 在 LSPosed 管理器中启用模块
3. 作用域勾选 `栗子漫画 (com.hbsclj.uth)`
4. 强制停止栗子漫画后重新打开
5. 进入阅读奖励页，点击看广告领奖 —— 不播放广告，直接到账

## 依赖

```kotlin
compileOnly("io.github.libxposed:api:102.0.0")
```

libxposed 组织在 GitHub 受限，源码与文档可从 Maven Central 获取：
<https://repo.maven.apache.org/maven2/io/github/libxposed>
