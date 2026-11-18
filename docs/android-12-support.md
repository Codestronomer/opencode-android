# Android 12 Support (minSdk 33 → 31)

A plan for lowering this app's minimum supported version from Android 13 (API 33) to
Android 12 (API 31), covering Android 12 (31) and 12L (32). Written from a static analysis
of the codebase at commit `70854803` plus the git history of the previous minSdk lowerings
(commits `24487687`, PR #23 / `d9522ec8`), which established the pattern this plan follows.

Read the paired doc `docs/parity/README.md` first: the app is an iOS port tracked file by
file, and every divergence accepted by this plan must be recorded in the parity tables.

---

## 1. Where the floor is enforced

| Setting | Value | Location |
| --- | --- | --- |
| `minSdk` | 33 | `build-logic/src/main/kotlin/OpenCodePlugins.kt:17` (`OpenCodeBuild.MIN_SDK`) |
| `targetSdk` | 37 | same file (`TARGET_SDK`) |
| `compileSdk` | 37 | same file (`COMPILE_SDK`) |

`minSdk` is set once for every module through the shared convention plugins
(`OpenCodeLibraryPlugin` → `configureCommon()` at line 38, `OpenCodeApplicationPlugin`).
No module overrides it. Lowering the floor is a **one-line change** — everything else in
this document is the cost of that line.

History of the floor:

1. `b75832bb` — scaffolded at minSdk 35 (Android 15).
2. `24487687` — lowered to 34 (Android 14).
3. `d9522ec8` (PR #23) — lowered to 33 (Android 13), with a documented list of the
   remaining API-34 call sites and their compat shims. This is where the staircase
   stopped; this plan continues it.

---

## 2. Prerequisites

1. **Build environment**: JDK 17 and the Android SDK with platform 37 (same requirements
   as the main README's Build section). AGP 9.4.1 / Kotlin 2.4.21 / Compose BOM 2026.09
   come from the repo's own Gradle setup.
2. **The mechanical work-list**: the convention plugins set `lint.abortOnError = true`.
   After flipping `MIN_SDK` to 31, `scripts/check.sh` fails with a `NewApi` lint report
   naming every API-33+ call site. Treat that report as the work-list and iterate until
   CI is clean again — it is the same gate PR #23 used ("Lint at minSdk 33 found 13
   NewApi findings in 4 files").
3. **Test matrix**:
   - API 31 emulator (Pixel, Play image) and API 32 (12L) if available.
   - Keep at least one API 33+ emulator for regression.
   - A real Android 12 device if possible — the mic foreground-service and notification
     behaviours below are only fully trustworthy on hardware.
4. **A scope decision on the shader effects** (§3a): full visual parity on Android 12 is
   not achievable without a second rendering stack. Decide up front which of the two
   fallback strategies applies to each effect (§3a) — this is the largest chunk of work
   and the fork's main product decision.
5. **Fork discipline**: this plan diverges from upstream intentionally. Record each
   divergence in `docs/parity/*.md` as an "Android 12" row (PR #23 did exactly this for
   Android 13); without it the fork will drift silently from upstream syncs.

---

## 3. Requirement 1 — Hard crashes on Android 12 (unguarded API-33+ usage)

These are `NoSuchMethodError` / crash-level breaks: the code assumes API ≥ 33
unconditionally (there are **zero** `Build.VERSION_CODES.TIRAMISU` guards in the
codebase; the only guards present are for API 34+).

### a) AGSL shaders (`RuntimeShader`) — the long pole

AGSL (Android Graphics Shading Language, `android.graphics.RuntimeShader`) was
introduced in API 33. The iOS app's Metal shaders are ported line for line to AGSL and
the parity docs explicitly note "minSdk 33 so no fallback". On Android 12 every one of
these instantiates a class that does not exist.

| Area | Files | Direct `RuntimeShader(...)` sites |
| --- | --- | --- |
| Drawer catch-up drift | `feature/drawer/.../catchup/CatchUpDriftField.kt` (278 lines) | 1 |
| Session agent activity | `feature/session/.../agentactivity/AgentActivityShaders.kt` (133) | 1 |
| Transcript message reveal | `feature/transcript/.../user/AgentMessageReveal.kt` (237) | 1 |
| Voice orb / scope | `feature/voice/.../WispOrbShaders.kt` (214), `DotScopeShaders.kt` (162), `DotScopeView.kt` (408), `LivingOrbView.kt` (351) | several |
| Session send reset | `feature/session/.../ComposerSendReset.kt` (102) | 1 |
| Settings connection scope | `feature/settings/.../connection/ConnectionScope.kt` (447), `ConnectionScopeShaders.kt` (85) | 2 |
| Settings onboarding signal | `feature/settings/.../onboarding/SignalField.kt` (371) | 1 |

**Options** (choose per effect):

- **Graceful degradation** — draw the base content without the effect (no glow, no drift,
  a static orb). Cheapest, honest, and matches how the app already degrades elsewhere
  (e.g. Reduce Motion renders a still frame of the catch-up drift).
- **Canvas approximation** — simple glows (message reveal, send reset, signal field) can
  be approximated with `RadialGradient` + `BlurMaskFilter`. The drift ripple and voice orb
  realistically get a simple/static variant, not a port.

**Recommended structure**: put every shader file behind one seam — an interface or a
factory guarded by `Build.VERSION.SDK_INT >= 33` returning either the AGSL renderer or a
fallback renderer — so the `< 33` path is unreachable code on 33+ and upstream syncs only
touch the AGSL side.

### b) `onBackInvokedDispatcher` call sites — 6 files

`Activity.getOnBackInvokedDispatcher` is API 33+. These registration sites crash on 31/32:

| File | Line |
| --- | --- |
| `feature/composer/.../AgentPicker.kt` | 370 |
| `feature/transcript/.../user/TranscriptUserBubble.kt` | 581 |
| `feature/transcript/.../list/TranscriptList.kt` | 171 |
| `feature/media/.../MediaPresentation.kt` | 1553, 1598 |
| `feature/session/.../ComposerQuoteViews.kt` | 301 |

Each needs an `SDK_INT < 33` branch registering the same callbacks with the legacy
`OnBackPressedDispatcher` instead. Two semantics notes:

- **No back-progress events below 33.** The interactive previews (media viewer's
  swipe-down preview, quote-view dismiss drag) become plain "back = dismiss" on 12.
  PR #23 already accepted exactly this on Android 13 ("Android 13 sends apps no back
  progress"); extending it to 12 is the same pattern one version lower.
- `MediaPresentation.kt:1579` already branches `< 34` → plain `OnBackInvokedCallback`
  vs `OnBackAnimationCallback`; it needs a third branch `< 33` → `OnBackPressedCallback`.

The androidx `PredictiveBackHandler` usages elsewhere (~10 files) are already version
compat — below 33 the flow completes on back press with no progress events. Leave them.

Also fine: `android:enableOnBackInvokedCallback="true"` in the app manifest is ignored
below 33.

### c) Dictation — `feature/composer/.../DictationClient.kt`

Subtle: the on-device recognizer itself is fine on 12 (`createOnDeviceSpeechRecognizer`
and `isOnDeviceRecognitionAvailable` are API 31), but the support-check and
model-install machinery is API **33**, not 34:

| API | Level |
| --- | --- |
| `SpeechRecognizer.createOnDeviceSpeechRecognizer` | 31 ✓ |
| `SpeechRecognizer.isOnDeviceRecognitionAvailable` | 31 ✓ |
| `checkRecognitionSupport` / `RecognitionSupport` | **33** |
| `triggerModelDownload` (no listener) | **33** |
| `triggerModelDownload(intent, executor, listener)` | 34 (already shimmed in PR #23) |

So on 12 there is **no support check and no model-download request at all**. Add an
`SDK_INT < 33` branch:

- Skip `support()` / `install()`; go straight to the recognizer.
- Surface "language not installed" via the recognizer's error codes, or fall back to the
  default (cloud) recognizer the way `DictationError` already models.
- The existing `< 34` branch (request + `awaitInstalled` polling with a 15 s grace) is a
  good template for the shape of the fallback; on 12 even its `support()` calls must go.

---

## 4. Requirement 2 — Behaviour differences to handle

These won't appear in the `NewApi` lint report but break flows on 12.

### a) `POST_NOTIFICATIONS` runtime permission

The permission exists only on API 33+. Affected code:

- `feature/system/.../notifications/NotificationPermissionActivity.kt` —
  `requestPermissions(arrayOf(POST_NOTIFICATIONS))` auto-denies on 12.
- `SessionNotifications.kt` (the "Always Notify" gate, which asks for the permission
  before enabling — see `docs/parity/session.md`), `ShareNotifier.kt`,
  `VoiceCallService.kt` notification.

Fix: an `SDK_INT < 33` branch in `NotificationPermissionActivity` (and any permission
client) answering **granted** when `NotificationManagerCompat.areNotificationsEnabled()`
is true — on 12 notifications are on by default and only toggleable per-app in Settings.

### b) Microphone foreground service from background

Android 12 introduced foreground-service background-launch restrictions and while-in-use
mic/camera access rules: a mic-type FGS started while the app is in the background
cannot access the microphone without exemptions.

- Audit the Assistant → `BackgroundCallActivity` → `VoiceCallService`
  (`foregroundServiceType="microphone"`, `startForeground(..., TYPE_MICROPHONE)`) path on
  a real API 31 device. May need a full-screen-intent notification start or
  visible-activity reordering.
- `FloatingCall.kt` — same rules for the floating call UI.
- 12 also shows mic/camera privacy indicators; verify the UX still reads correctly.

### c) Notification trampolines

Android 12 bans starting activities from services / broadcast receivers used as
notification *content* trampolines. Audit the PendingIntents posted by
`SessionNotifications` and `LiveActivityActionReceiver`:

- Activity `PendingIntents` as content, and receiver-targeted notification *actions*,
  are fine.
- Activity-start-from-receiver/service as content is not — restructure if found.

### d) Per-app language

Per-app language ("Settings › Apps › OpenCode › Language", `docs/translating.md`) is an
Android 13 system feature. On 12 the app follows the system language only. Decide:

- **Accept** the divergence and document it (recommended), or
- **Add an in-app language picker** — needs appcompat's
  `AppCompatDelegate.setApplicationLocales`, which this pure-Compose app does not use
  today. Real scope; do not attempt in the first pass.

### e) Manifest items needing no work (verified)

- `android:enableOnBackInvokedCallback` — ignored below 33.
- `PROPERTY_SUPPORTS_MULTI_INSTANCE_SYSTEM_UI` — a 35+ property, ignored below.
- `android:dataExtractionRules` — supported since API 31 ✓ (the backup path PR #23
  touched is compatible).

---

## 5. Requirement 3 — Verify the dependency floor actually reaches 31

The maintainers verified when lowering to 33 that "no library has a minSdk above 33"
(PR #23 commit message) — that says nothing about 31/32. The dependency set
(`gradle/libs.versions.toml`) is bleeding-edge:

Material3 1.5.0-beta02, Compose BOM 2026.09, Navigation3 1.2.0, activity 1.13.0,
fragment 1.9.1, lifecycle 2.11.0, core-ktx 1.19.1, CameraX 1.6.2, Media3 1.11.1,
WebKit 1.18.0, Work 2.12.0, Glance 1.2.0, WebRTC, Coil 3.6.3, OkHttp 5.5.1,
Firebase Messaging 26.0.1.

Most androidx artifacts stay at minSdk 21, but 2026-era versions may have raised some
floors. **Do not research this by hand**: set `MIN_SDK = 31` and run `assembleDebug`.
AGP's manifest merger fails loudly with `requires minSdkVersion X`, naming the offending
artifact; downgrade only those libraries. This is also the point where any transitive
floor surfaces.

---

## 6. Already fine on Android 12 (verified — spend no time here)

| Feature | Why it is safe |
| --- | --- |
| Platform AppSearch session indexing (`feature/system/.../SystemSearchIndex.kt`) | `android.app.appsearch` is API 31+ — lands exactly on 12 |
| Increase Contrast (`core/ui/OpenCodeTheme.kt:135`) | guarded at API 34 (`UPSIDE_DOWN_CAKE`); the 13 behaviour (no contrast setting, hairlines only) already exists |
| Quick Settings tiles (`StartCallTileService` etc.) | use `TileServiceCompat` + `PendingIntentActivityWrapper`; the existing `< 34` branch (Intent, not PendingIntent) is 12-safe |
| Local network permission (`server/.../LocalNetworkPermission.kt`) | guards at API 37 (`SDK_INT < 37`) |
| Photo picker | `PickVisualMedia` ActivityResult backports below 13 |
| Splashscreen, Glance widgets, Work, DataStore | standard androidx compat ranges, well below 31 |
| `POST_PROMOTED_NOTIFICATIONS` manifest permission | a 16+ permission declaration; inert below |

---

## 7. Verification plan

1. **Build gate**: flip `MIN_SDK = 31`; run `scripts/check.sh` (assemble + lint + unit
   tests, what CI runs) until the `NewApi` report is clean and the build passes.
   Manifest-merger failures here also answer §5.
2. **Robolectric**: the unit tests already run with `isIncludeAndroidResources`; consider
   adding `@Config(sdk = [31])` variants for the notification-permission and back-handling
   paths once they have version branches.
3. **Manual matrix** (adapted from PR #23's checklist) on API 31 and 32 emulators, plus a
   real Android 12 device where possible. Watch `logcat -b crash` throughout:
   - Onboarding, manual connect, streamed reply
   - Camera, photo picker, Files, Location, QR scanner
   - Drawer with edge-swipe back; media viewer back (expect: dismiss without preview)
   - Message reveal / send reset / catch-up drift / voice orb render with fallbacks
   - Dictation with an uninstalled language (expect: graceful fallback, no crash)
   - "Always Notify" enable flow; share-to-OpenCode delivering a message
   - New Session / Call tiles and home widget
   - App Actions "call" from Assistant → mic FGS started (background-call path is the
     risky one, §4b)
   - Ask OpenCode overlay waiting for Send
4. **Regression** on API 33+ (the shimmed paths must be unchanged) and on the newest
   available emulator.
5. **Documentation**: add "Android 12" divergence rows to the affected
   `docs/parity/*.md` tables (shader effects, back handling, dictation, notifications,
   per-app language) and update the README's "runs on Android 13 or newer" line.

---

## 8. Suggested order of attack

| Phase | Work | Size |
| --- | --- | --- |
| 1 | Flip minSdk; fix `NewApi` list: back-handler branches (6 files, mechanical), `DictationClient` `< 33` branch, notification permission branch | 1–3 days |
| 2 | Dependency-floor fixes from the manifest merger (§5), if any | 0–1 day |
| 3 | Shader fallback layer (pilot one effect — recommend `AgentMessageReveal` as the smallest — then replicate the seam) | the long pole; driven by fidelity goals |
| 4 | Behaviour QA on API 31/32: mic FGS from background, notification flows, dictation | 1–2 days |
| 5 | Parity docs + README, regression pass on 33+ | 0.5 day |

**Key decision to make before phase 3**: which of the ~9 shader effects get Canvas
approximations and which get plain degradation. Everything else in this plan is
mechanical relative to that choice.

---

## 9. Parity ceiling on Android 12 (researched addendum)

Follow-up research (Nov 2026) on how close to **full** parity the fork can get. Summary:
**~95–98% functional parity is achievable; what cannot be reached is only OS-provided
animation plumbing, not app features.** Sources are cited inline.

### 9.1 The dependency floor is a non-issue

Google's own samples prove the 2026-era androidx stack runs far below 31:

- [Jetcaster](https://github.com/android/compose-samples/blob/main/Jetcaster/gradle/libs.versions.toml)
  builds with `minSdk = 24` using the **same BOM 2026.09.00**, material3 1.5.0-alpha22,
  activity 1.13.0, core-ktx 1.19.0, lifecycle 2.11.0, coroutines 1.11.0, robolectric 4.16 —
  near-identical versions to this app's catalog.
- The [connectbot](https://github.com/connectbot/connectbot/blob/main/gradle/libs.versions.toml)
  app ships `minSdk = 24` with material3 **1.5.0-beta01** and compileSdk 37.

Practical consequence: no expected dependency downgrades; the manifest-merger check in §5
is a formality, not a research task.

### 9.2 Shaders — full visual parity is reachable, at a cost

Ecosystem precedent for the fallback tiers:

- [KMPLiquidGlass](https://github.com/Kashif-E/KMPLiquidGlass) ships a tiered renderer:
  blur/color effects via **RenderEffect (API 31+)**, AGSL custom shaders on 33+, graceful
  degradation below — the same tiering this fork needs.
- [react-native-liquid-glassmorphism](https://himanshu-lal4.github.io/react-native-liquid-glassmorphism/android-liquid-glass/)
  documents exactly this split: "API 31–32: RenderEffect blur + Canvas tint/specular — no
  refraction lensing" with minSdk 24.

Two implementation paths, by ambition:

1. **Tiered approximation (recommended first pass)** — RenderEffect 31 covers blur and
   color-filter tiers; Canvas gradients/`BlurMaskFilter` cover glows. Gaps: no lens/
   refraction, no procedural fields. Fast (days).
2. **Full parity via OpenGL ES port** — Google's
   [AGSL documentation](https://developer.android.com/reference/android/graphics/RuntimeShader)
   states AGSL is "very similar to OpenGL ES Shading Language" (differences: fragCoord
   origin top-left vs bottom-left, premultiplied output, `uniform shader` → `sampler2D`
   child inputs). Procedural shaders port almost 1:1; cross-language shader porting is
   routine work (see [paper-shaders-android](https://github.com/AndreFrelicot/paper-shaders-android),
   which maintains golden-image parity across GLSL/Metal/AGSL). The 3 child-shader
   effects (`ConnectionScopeShaders.kt`, `WispOrbShaders.kt`, `DotScopeShaders.kt` use
   `uniform shader field/cells`) need the child shader rendered into an FBO texture and
   sampled — a standard GL technique, more plumbing but well-understood. Render in a
   custom `View` with an EGL context (or `TextureView`) behind the same interface seam
   from §3a. Estimate: 2–4 weeks for all 9 effects with golden-parity checks against the
   AGSL renders.

### 9.3 Back handling — functional parity, animation gap is OS-level

- Functional: `OnBackPressedDispatcher` branch (§3b) restores 100% of behaviour —
  dismiss on back. No new work beyond §3b.
- Gesture feel: the iOS-parity touch paths already exist and are API-agnostic —
  `MediaDismissDrag` (`MediaPresentation.kt:563`, used at :887/:945/:1145) drives
  drag-to-dismiss from touch input, not back events. So on 12 the viewer is still fully
  dismissible by the same swipe; only the *system back gesture preview* disappears.
- Impossible: predictive-back progress events come from the OS, which only sends them on
  33+. Not an app limitation; document as divergence (PR #23 already accepted it on 13).

### 9.4 Dictation — near-full parity on 12

Verified API levels (Android
[SpeechRecognizer reference](https://developer.android.com/reference/android/speech/SpeechRecognizer)):

- `createOnDeviceSpeechRecognizer`, `isOnDeviceRecognitionAvailable` — **API 31** ✓
  (on-device dictation itself works on 12).
- `checkRecognitionSupport` / `RecognitionSupport` / `triggerModelDownload` — API 33 only.

The parity gap is smaller than §3c assumed, because pre-13 devices have their own
language-discovery path:

- **Language list**: `RecognizerIntent.ACTION_GET_LANGUAGE_DETAILS` broadcast returns
  `EXTRA_SUPPORTED_LANGUAGES` on ≤ API 32 (it goes empty on 13, which is exactly when
  `checkRecognitionSupport` takes over). This restores `bestLocale()`'s language query.
- **Not-installed detection**: recognizer error codes —
  `ERROR_LANGUAGE_NOT_SUPPORTED` (12) and `ERROR_LANGUAGE_UNAVAILABLE` (13) — surface
  when the language isn't there; the existing `DictationError` path can map these.
- **Remaining gap**: programmatic model download does not exist on 12. The user installs
  the language via Gboard/Google app Settings. Divergence: the "Preparing → install
  prompt" UX becomes an instruction to install the language pack.

### 9.5 Per-app language — full parity via appcompat

Official guidance for Compose apps
([Per-app language preferences](https://developer.android.com/guide/topics/resources/app-languages)):
use `AppCompatDelegate.setApplicationLocales` + `AppCompatDelegate.getApplicationLocales`
with the `AppLocalesMetadataHolderService` `autoStoreLocales` manifest entry, and host
Compose in `AppCompatActivity`. There is a
[Google sample](https://github.com/android/user-interface-samples/tree/main/PerAppLanguages/compose_app)
for exactly this setup. Costs:

- `MainActivity` extends `AppCompatActivity` instead of `ComponentActivity` (Compose
  works unchanged on top).
- The `AppLocalesMetadataHolderService` manifest entry (persists the choice ≤ 32).
- On 33+ appcompat delegates to the framework `LocaleManager`, so 13+ behaviour —
  including the system Settings UI — is unchanged; the in-app picker can even sync both.
- Reading localized strings outside activity contexts (services, widgets) needs
  `ContextCompat.getString` / `getContextForLanguage` on ≤ 32.

Net: **in-app language switching reaches full functional parity on 12**.

### 9.6 Remaining items — same conclusion as the body

- `POST_NOTIFICATIONS`: 12 needs no runtime permission; with the §4a branch, parity is
  trivially full.
- Mic FGS from background: on ≤ 14 the app already treats external callers (Assistant)
  as unverified, so background calls start only after a user tap with the app visible —
  the same 13/14 behaviour. Expected parity: full; verify the Assistant →
  `BackgroundCallActivity` path on hardware anyway (§7).
- Notification trampolines: audit only (§4c).

### 9.7 Scorecard

| Surface | Parity on 12 | Path |
| --- | --- | --- |
| All shader effects | **Full** (ambitious GL port) or near-full (RenderEffect approximations) | §9.2 |
| Back interception (6 sites) | **Full** (functional) | §3b + §9.3 |
| Back-gesture progress preview | **None** — OS-level, impossible | documented divergence |
| Touch dismiss gestures (media, sheets) | **Full** — existing iOS-parity touch paths | §9.3 |
| Dictation (on-device) | **Near-full** — no programmatic model download | §9.4 |
| In-app language switching | **Full** | §9.5 |
| Language in system Settings UI | N/A on 12 (OS feature) | in-app picker substitutes |
| Notifications / Always Notify | **Full** | §4a |
| Tiles, widgets, App Actions, share, AppSearch, photo picker | **Full** (already compat) | §6 |
| Mic foreground service | **Full** (expected; verify) | §9.6 |
| Increase Contrast | Already divergent on 13 — unchanged | existing |

**Verdict**: with the tiered shader path the fork ships ~2–3 weeks of work for ~90–95%
parity (only lens refraction and system back preview absent). With the full GL shader
port the ceiling is ~95–98%: the only true gaps left are the OS's predictive-back
progress animation and 12's lack of programmatic speech-model download prompts — both
inherently OS-gated, neither an app feature.

---

## 10. Reaching 100% parity — reimplementing the OS-gated pieces in the app

Follow-up research (Nov 19 2026) into whether the last three gaps can be closed by
**reimplementing the missing OS behaviour natively**. Findings: two of the three can be
closed outright (shaders via a GL port, dictation via a bundled ASR engine), and the
third (predictive-back progress) can be closed for the app's own surfaces with a gesture
synthesizer, leaving only true OS chrome (system-drawn back-to-home animation) — which
does not exist inside the app's window at all.

### 10.1 Predictive-back progress on 12 — synthesize the `Flow<BackEventCompat>`

**How androidx actually works** (from the
[androidx source](https://android.googlesource.com/platform/frameworks/support/+/ef78d28e33c62d304a3203cb384bc36043538a65/activity/activity-compose/src/main/java/androidx/activity/compose/PredictiveBackHandler.kt)):
`PredictiveBackHandler` registers a `PredictiveBackHandlerCallback : OnBackPressedCallback`
and feeds a `Channel<BackEventCompat>` (`OnBackInstance.send(backEvent)`) that the
consumer collects as the progress flow. The channel is just a pipe — **on API < 33 the
only missing piece is the producer** (the system's `OnBackInvokedCallback` progress
callbacks). Everything downstream — the flow, the animations the app's handlers draw —
is version-independent.

**The native producer**: on API < 33, while a predictive surface (media viewer, agent
picker, quote view) is active:

1. Claim the edge zone with
   [`ViewCompat.setSystemGestureExclusionRects`](https://developer.android.com/develop/ui/views/touch-and-input/gestures/gesturenav)
   (API 29+) so the system's `EdgeBackGestureHandler` does not intercept the swipe and the
   app receives the raw touch stream. The SystemUI
   [EdgeBackGestureHandler source](https://android.googlesource.com/platform/frameworks/base/+/master/packages/SystemUI/src/com/android/systemui/navigationbar/gestural/EdgeBackGestureHandler.java)
   confirms the system honours app exclusions.
2. Detect the edge swipe in the claimed zone (`Modifier.pointerInput` + velocity tracker),
   compute `progress = horizontal distance / commit threshold` (match the system's
   thresholds/velocity curves as closely as observable behaviour allows), and synthesize
   `BackEventCompat(touchX, touchY, progress, swipeEdge)` — the class has a public
   5-arg constructor for exactly this kind of use.
3. Feed the synthesized events into the existing handlers
   (`MediaPredictiveBackHandler`'s `onStarted/onProgressed/onCancelled/onInvoked`, and
   androidx `PredictiveBackHandler` flows where used) — the app's own predictive
   animations (`MediaBackPreview`, drawer progress) are **app-drawn**, so they reproduce
   on 12 identically given the same event stream.

**Verified limits and why they don't break parity:**

- *200 dp exclusion cap per side* — the edge zones used for back are well within it.
- *Corner areas stay system-reserved and the system may ignore some exclusions* —
  same failure mode as 33+ (the system can also suppress progress there); degrade to the
  plain `OnBackPressedCallback` path from §3b, which is the 13 fallback anyway.
- *3-button / hardware back* — `BackEventCompat.EDGE_NONE` exists precisely because
  button back never produces progress on **any** API level. No progress on 12 == no
  progress on 33+ for button back. Parity holds.
- *Exclusions only honoured under gesture navigation* — that is also the only mode in
  which 33+ produces progress at all.

**What still can't be replicated**: the *system-choreographed* back-to-home /
cross-activity animations (the window-shrinks-to-icon effect from the
[support-animations doc](https://developer.android.com/guide/navigation/custom-back/support-animations))
are rendered by SystemUI outside the app window. They are cross-*activity* animations;
this app is single-activity (`MainActivity` + Compose), so every back inside the app is
an in-app surface transition — which the synthesizer covers. The only place the
difference could ever appear is leaving the app to the launcher, which no back handler
in this app drives.

**Estimated cost**: 3–5 days (one `PredictiveBackSynthesizer` component + wiring into the
6 sites from §3b, which already share the `onStarted/onProgressed/onCancelled/onInvoked`
shape).

### 10.2 AGSL → GLSL ES port — full visual parity

Ground truth from the codebase: `AgentActivityShaders.kt`'s header states the AGSL is
"**SkSL ES2**" and Google's
[RuntimeShader reference](https://developer.android.com/reference/android/graphics/RuntimeShader)
states AGSL is "very similar to OpenGL ES Shading Language". The differences are a fixed
mechanical set
([cross-runtime port table](https://andrefrelicot.dev/en/2026/07/porting-paper-shaders-to-swift-metal-and-flutter/),
[AGSL porting notes](https://github.com/AndreFrelicot/paper-shaders-android)):

| AGSL | GLSL ES 3.0 |
| --- | --- |
| `float2 / float3 / float4` | `vec2 / vec3 / vec4` |
| `half / half3 / half4` | `float / vec3 / vec4` |
| `half4 main(float2 position)` | `out vec4 fragColor;` + `void main()` reading `gl_FragCoord.xy` |
| top-left origin (matches Android Canvas) | bottom-left `gl_FragCoord` → flip `y` (`y = size.y - y`) |
| premultiplied-alpha return value | same output, blend with `glBlendFuncSeparate(ONE, ONE_MINUS_SRC_ALPHA, ONE, ONE_MINUS_SRC_ALPHA)` |
| `uniform shader child` + `child.eval(p)` | render the child shader into an FBO texture, sample `texture(childTex, p / childSize)` |
| `uniform float4 cells[49]`, indexed by loop var | identical in ES 3.0 (dynamic indexing of uniform arrays allowed; it was an ES 1.00 restriction) |
| `smoothstep/exp/pow/mix/clamp/length/floor/mod` | identical GLSL builtins |

**Evidence — a complete mechanical port of `AgentActivityShaders.ACTIVITY_ORB_FIELD`**
(the 49-cell activity orb, fully procedural, no child shaders). Body is verbatim after
type renames:

```glsl
#version 300 es
precision highp float;

uniform vec4 header0;   // originX, originY, pitch, scale
uniform vec4 header1;   // glowAmount, glowPeak, dark, unlitAlpha
uniform vec3 glowRGB;
uniform vec3 neutral;
uniform vec3 neutralCore;
uniform vec3 accent;
uniform vec3 accentCore;
uniform vec4 cells[49];
uniform vec2 uSize;     // render target size in px (for the y-flip)

out vec4 fragColor;

vec4 orbField(vec2 position) {
  vec2 origin = header0.xy;
  float pitch = header0.z;
  float scale = max(1.0, header0.w);
  float glowAmount = header1.x;
  float glowPeak = header1.y;
  float isDark = header1.z;
  float a0 = header1.w;

  float gridSide = 7.0 * pitch;
  vec2 centre = origin + vec2(gridSide * 0.5);

  vec3 premul = vec3(0.0);
  float alphaAcc = 0.0;

  if (glowAmount > 1e-3) {
    float sigma = 2.2;
    float reach = 3.6 * sigma;
    float distCells = length(position - centre) / pitch;
    if (distCells < reach) {
      float g = min(1.0, glowAmount * exp(-distCells * distCells / (2.0 * sigma * sigma))) * glowPeak;
      premul = glowRGB * g;
      alphaAcc = g;
    }
  }

  vec2 rel = (position - origin) / pitch;
  if (rel.x < -0.6 || rel.y < -0.6 || rel.x > 7.6 || rel.y > 7.6) {
    return vec4(premul, alphaAcc);
  }
  float halfPx = 0.5 / scale;
  float top = isDark > 0.5 ? 0.96 : 1.0;

  for (int i = 0; i < 49; i++) {
    vec4 cell = cells[i];
    float dotSize = cell.w;
    if (dotSize <= 0.0) continue;
    float r = floor(float(i) / 7.0);
    float c = float(i) - r * 7.0;

    vec2 dotCenter = origin + vec2((c + 0.5) * pitch, (r + 0.5) * pitch);
    float dist = length(position - dotCenter);
    if (dist > pitch + halfPx) continue;

    float P = clamp(cell.x, 0.0, 1.0);
    float haloPeak = 0.22 * P * P;
    if (haloPeak <= 0.008 && dist > 0.48 * pitch + halfPx) continue;

    float fresh = clamp(cell.y, 0.0, 1.0);
    float dotAccent = clamp(cell.z, 0.0, 1.0);

    float sstep = smoothstep(0.03, 0.2, P);
    float shrink = 1.0 + ((0.82 + 0.18 * fresh) - 1.0) * sstep;
    float radius = (0.25 + 0.17 * pow(max(P, 1e-5), 0.6)) * shrink * pitch * dotSize;
    float outer = radius + 0.5 * pitch;
    if (dist > (haloPeak > 0.008 ? outer : radius) + halfPx) continue;

    float expPow = (isDark > 0.5 ? 0.9 : 0.75) * (1.4 + (1.0 - 1.4) * fresh);
    float dotAlpha = a0 + (top - a0) * pow(max(P, 1e-5), expPow);
    float core = clamp(P * P * 1.1, 0.0, 0.9) * (0.3 + 0.7 * fresh);
    vec3 rgb = mix(mix(neutral, neutralCore, core), mix(accent, accentCore, core), dotAccent);

    if (haloPeak > 0.008) {
      float inner = max(0.0, radius - 0.01 * outer);
      if (dist >= inner && dist <= outer + halfPx) {
        float innerRamp = dist < radius ? clamp((dist - inner) / max(1e-4, radius - inner), 0.0, 1.0) : 1.0;
        float d = max(0.0, dist - radius);
        float outerCov = clamp((outer - dist) * scale + 0.5, 0.0, 1.0);
        float hAlpha = haloPeak * exp(-d / (0.12 * pitch)) * innerRamp * outerCov;
        premul = premul * (1.0 - hAlpha) + rgb * hAlpha;
        alphaAcc = alphaAcc * (1.0 - hAlpha) + hAlpha;
      }
    }

    if (dist <= radius + halfPx) {
      float cov = clamp((radius - dist) * scale + 0.5, 0.0, 1.0);
      float a = dotAlpha * cov;
      premul = premul * (1.0 - a) + rgb * a;
      alphaAcc = alphaAcc * (1.0 - a) + a;
    }
  }

  return vec4(premul, alphaAcc);
}

void main() {
  vec2 position = vec2(gl_FragCoord.x, uSize.y - gl_FragCoord.y); // AGSL y-origin is top-left
  fragColor = orbField(position);
}
```

**Rendering shell** (one shared component behind the §3a seam): a `TextureView` (or
`GLSurfaceView`) in an `AndroidView {}` with an EGL ES 3.0 context, drawing a single
fullscreen quad with the ported fragment shader; per-frame uniform updates
(`glUniform4fv` for `cells[49]`); premultiplied blending so it composites like the AGSL
brush did. GLES 3.0 is guaranteed on every Android 12 device (ES 3.2 mandatory since
Android 5+ hardware).

**Child shaders** (`ConnectionScopeShaders`, `WispOrbShaders`, `DotScopeShaders` use
`uniform shader field/cells`): render the child pass to an offscreen FBO texture first,
then sample it. Standard multi-pass GL; the children are procedural shaders from the same
codebase.

**Parity verification**: golden-image harness — render each effect with the AGSL brush on
an API 33+ device and with the GL port on API 31, diff the bitmaps (the
[paper-shaders port](https://github.com/AndreFrelicot/paper-shaders-android) runs exactly
such golden parity across GLSL/Metal/AGSL). Floating-point differences should stay
sub-LSB for the analytic formulas these shaders use.

**Compile validation**: the orb port above was extracted verbatim from this document and
compiles cleanly under the Khronos `glslangValidator` (ESSL 3.00 profile, exit 0), with a
negative control confirming the validator catches errors. The port is valid GLSL ES 3.0,
not just plausible.

**Rejected alternative**: bundling Skia to use `SkSL::RuntimeEffect` directly (AGSL *is*
SkSL under Android's Skia) — Skia is not in the NDK public API and shipping a custom
libskia dwarfs the GL port. Not worth it.

**Estimated cost**: shader-by-shader; the procedural ones (≈6, like the orb above) are
mostly verbatim renames; the 3 child-shader effects need FBO plumbing. 2–4 weeks total
including the golden harness — unchanged from §9.2's estimate, but now it buys
**identical pixels**, not approximations.

### 10.3 Dictation on 12 — bundle an ASR engine (parity by replacement)

The missing 33-only APIs (`checkRecognitionSupport`/`triggerModelDownload`) are the OS's
interface to *Google's* recognizer models. The native reimplementation is to not depend
on that pipeline at all: **bundle our own on-device ASR**. Research findings
([voiceping benchmark](https://github.com/voiceping-ai/android-offline-transcribe),
[2026 on-device ASR survey](https://dev.to/voxrtio/on-device-streaming-asr-in-2026-five-approaches-and-when-to-pick-what-5h1n)):

- **sherpa-onnx** (k2-fsa, Apache-2.0) is the mobile-first choice: ships **prebuilt
  Android AARs**, streaming Zipformer int8 models at **~68–80 MB**, **RTF 0.14** on a
  Snapdragon 690 (2020 mid-range) — comfortably real-time — and works fully offline on
  any Android version, no Google app involvement. Multi-language via the model zoo.
  (Alternative: whisper.cpp — MIT, but batch-oriented with windowed streaming and higher
  first-partial latency; Vosk — Apache-2.0, smaller 40 MB models but ~3× the WER.)
- **Fit with the existing pipeline**: `DictationClient` already produces 16 kHz mono
  Int16 PCM (`BufferConverter` downmix + resample) — exactly sherpa-onnx's input format.
  The engine replaces only the recognizer object; `DictationFeature`'s reducer, segmented
  sessions, partials-as-volatile-tail and the mic permission flow are untouched.
- **Architecture**: extract a `DictationEngine` interface (`warmUp`, `feed(pcm)`,
  `partials`, `finals`) with two implementations — the current `SpeechRecognizer` client
  (33+ path, unchanged) and a `SherpaOnnxEngine` (all APIs). On 12 the bundled engine
  runs; the language picker reads the bundled model's language list instead of
  `RecognitionSupport`.
- **Net effect**: this is not just parity — it removes the app's dependence on Google
  app model downloads on *every* version (iOS dictation is similarly self-contained),
  and it deletes the `< 33` fallback complexity from §3c entirely.
- **Costs**: APK size (+~70–80 MB model, ship via Play Asset Delivery if wanted), 3–6
  days to integrate the AAR + engine, plus device QA on battery/thermals for the call
  flow.

### 10.4 Final scorecard after native reimplementations

| Surface | §9 ceiling | With native reimplementation (§10) |
| --- | --- | --- |
| Shader effects | near-full (RenderEffect approximations) | **Full** — identical pixels via GL port (§10.2) |
| Back-progress animations on app surfaces | none (dismiss-only) | **Full** — synthesized `BackEventCompat` flow (§10.1) |
| System back-to-home/cross-activity chrome | none | N/A — single-activity app; never renders inside this app's window |
| Dictation on-device | near-full (no download prompts) | **Full** — bundled sherpa-onnx engine (§10.3) |
| In-app language switching | full | full (unchanged, §9.5) |
| Per-app language in system Settings UI | N/A (OS feature) | N/A (OS feature; in-app picker covers function) |
| Notifications, tiles, widgets, App Actions, share, AppSearch | full | full |
| Increase Contrast system setting | divergent on 13 already | unchanged (OS setting doesn't exist < 34) |

**Verdict**: 100% *app-feature* parity on Android 12 is achievable. The residual
differences after §10 are (a) SystemUI-owned animations that by definition never render
inside this app's single-activity window, and (b) an OS Settings page that doesn't exist
on 12 — both outside the app's code surface. Combined plan budget: ~4–6 weeks for one
engineer, front-loaded on the GL shader port.

### 10.5 Suggested implementation order (revised)

1. §3 mechanical fixes (back branches, notifications) — 1–3 days.
2. §10.1 `PredictiveBackSynthesizer` — 3–5 days (unlocks the interactive feel early).
3. §10.3 sherpa-onnx engine — 3–6 days (removes the dictation branch entirely).
4. §10.2 GL shader port — 2–4 weeks (one pilot shader golden-harnessed first).
5. §7 verification matrix + parity docs throughout.

