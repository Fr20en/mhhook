# mhhook

An LSPosed module (libxposed API **102**) for reading apps that serve ads through
**TopOn / AnyThink inside Flutter**. It answers the ad MethodChannels itself, so

* rewarded-video rewards are granted without a video being loaded or played, and
* the **splash ad is skipped entirely** - no request, no native view.

Builds with GitHub Actions (`.github/workflows/build.yml`); the release build is R8-obfuscated.

## Supported apps

| App | Package | rewarded placementID | splash placementID |
|---|---|---|---|
| 栗子漫画 | `com.hbsclj.uth` | `b69a01b605fc98` | - |
| 懒漫画 | `com.cffyzp.szt` | `b6a76916e36064` | `b6a76916fa31e2` |

Both apps share the same TopOn/AnyThink Flutter bridge, which is why one module covers both.
Add a package to `TARGETS` in `MhHook.java` and to
`app/src/main/resources/META-INF/xposed/scope.list` to cover another one.

## How it works

The Dart side talks to the SDK over a single `MethodChannel` and dispatches by method name
into a family of manager singletons. Two of them matter:

```
ATAdRewardVideoManger.handleMethodCall   "RewardedVideoCall"   load / ready / show
ATAdSplashManger.handleMethodCall        "SplashCall"          load / ready / show
```

Both have the same shape: `load*` and `show*` **do not complete the MethodChannel result**.
The real outcome is delivered later as a callback *event* pushed to Dart. So to skip an ad the
module must do two things at once:

1. short-circuit the action so the SDK never loads or renders anything, and
2. replay the callback sequence Dart is waiting for, carrying a valid ad transaction id.

### Rewarded video

```
Flutter (libapp.so)
  -> MethodChannel "anythink_sdk" / "RewardedVideoCall"
     -> ATAdRewardVideoManger#handleMethodCall            <- hook point
          loadRewardedVideo    -> fabricated rewardedVideoDidFinishLoading
          rewardedVideoReady   -> true
          showRewardedVideo    -> replayed lifecycle
                                  StartPlaying -> EndPlaying
                                  -> RewardSuccess -> Close
                                      |
                                      v
              Dart validates extraDic.id == "<req_id>_<adsourceId>_<ms>"
                                      |
                                      v
              POST /app/api/ad/reward/progress          -> reward granted
```

### Splash ad

The splash is rendered **natively** by the SDK, so Dart never observes the view layer - it
only receives events. Answering the channel is therefore not enough; the render has to be
suppressed *and* the event sequence replayed.

```
Flutter (libapp.so)
  -> MethodChannel "anythink_sdk" / "SplashCall"
     -> ATAdSplashManger#handleMethodCall                  <- hook point
          loadSplash              -> fabricated splashDidFinishLoading
          splashReady             -> true
          checkSplashLoadStatus   -> { isLoading: false, isReady: true }
          getSplashValidAds       -> "[]"
          entrySplashScenario     -> true
          showSplash              -> replayed splashDidShowSuccess -> splashDidClose
```

Because `loadSplash` / `showSplash` never reach `ATSplashHelper`, no ad request is made and no
view is attached. The first screen the user sees is the app's own home page.

### Why it survives SDK updates

1. **Passive learning.** The module also hooks
   `ATFlutterEventManager#sendCallbackMsgToFlutter` and caches every real callback body per
   placement id (bucketed per ad family). Replays therefore reuse the SDK's own field layout
   instead of a guessed one.
2. **Transaction-id re-forging.** A cached body keeps its ad-source segment; only the request
   id and the millisecond stamp are refreshed, which is what the Dart validator parses:
   `<request_id>_<adsource_id>_<millis>`.
3. **Positive status answers.** Every splash status query (`splashReady`,
   `checkSplashLoadStatus`, `getSplashValidAds`) is answered positively so the Dart state
   machine never falls back to a real load path.

## Layout

```
app/src/main/java/com/fj/mhhook/MhHook.java     entry class (java_init.list)
app/src/main/resources/META-INF/xposed/          module.prop / java_init.list / scope.list
app/proguard-rules.pro                           keeps the entry class, renames the rest
.github/workflows/build.yml                      assembleRelease + artifact/release upload
```

## Build

Locally, with Gradle 8.11.1 and JDK 17:

```
gradle assembleRelease      # R8-obfuscated, signed with mh.jks if present
gradle assembleDebug        # plain, easily patchable
```

CI generates a throwaway keystore unless a `MH_KEYSTORE_B64` secret (base64 `mh.jks`) is set.
Set `MH_STORE_PASS` / `MH_KEY_ALIAS` / `MH_KEY_PASS` to override the defaults.

## Notes

The default keystore in this repository is a development key. Signing identity matters for
LSPosed: replacing an installed module with a differently-signed build requires uninstalling
it first, so keep one key per install.
