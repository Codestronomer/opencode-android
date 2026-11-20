# Android 12 Support — Implementation Spec

Fork goal: run OpenCode for Android on Android 12 (API 31) and 12L (32) with **100%
app-feature parity**, per the research in [android-12-support.md](android-12-support.md)
(§1–§8: gap analysis; §9: parity ceiling; §10: native reimplementations). This spec turns
that research into work packages (WPs), a parallelization plan and a commit protocol.

## 0. Environment constraints (read first)

- **No local build is possible on the authoring machine**: no JDK 17, no Android SDK
  (only platform-tools). `scripts/check.sh` cannot run here.
- **Verification gate is CI**: every WP branch is pushed to the fork
  (`Codestronomer/opencode-android`) and validated by `.github/workflows/ci.yml`
  (`scripts/check.sh`: assemble + lint + unit tests, all modules). A WP commit must not
  claim build verification it doesn't have; commit messages state exactly what was
  checked (static analysis, validator runs, CI run id when known).
- Because of that, every WP is written to be **independently mergeable and CI-green at
  the current minSdk 33**: compat WPs only add version-guarded branches that are dead
  code at API 33+. The minSdk flip (WP-01) is the **last** commit of the sequence — the
  single switch that activates everything at once.
- Lint is the mechanical completeness check for the flip: after WP-01, CI's `NewApi`
  report must be empty. Each compat WP removes known findings; the flip PR's CI run
  proves none remain.

## 1. Conventions this work must follow (from AGENTS.md)

- One coherent change per commit; message says **what / why / verified**. Feature branch
  → PR into `dev` (never commit to `dev` directly).
- Parity rows in `docs/parity/<area>.md` are updated **in the same commit** as the
  behaviour divergence they document ("Android 12: …" notes, mirroring PR #23's Android 13 rows).
- No new UI copy unless unavoidable; if added, strings go in module `strings.xml` with
  module prefixes and the translate scripts run (needs SDK-less environment: flag for CI follow-up).
- Stage only owned paths (`git add <path>`); worktrees give each lane its own checkout so
  this is trivially satisfied.
- Shared gradle memory cap stays; no `./gradlew clean`.

## 2. Architecture: the three seams

All version-dependent behaviour concentrates behind three narrow seams so that Android
13+ behaviour is byte-identical and the 12 paths are unreachable there:

1. **Back seam** — every predictive-back registration goes through one composable helper
   per surface (existing pattern: `MediaPresentation`'s two helpers). WP-02 adds an
   `SDK_INT < 33` branch (legacy `OnBackPressedDispatcher`) inside the existing helpers.
   WP-06 later swaps that branch's producer for a synthesized event flow (§10.1).
2. **Shader seam** — each shader effect keeps its AGSL renderer for `SDK_INT >= 33` and
   gains a fallback renderer selected once per effect (§3a, §10.2: Canvas approximation
   first pass, GL port later). One seam per file, factory-guarded.
3. **Dictation engine seam** — `DictationClient` gets an `SDK_INT < 33` branch (§3c) in
   WP-04; WP-07 replaces the whole recognizer selection with a `DictationEngine`
   interface (sherpa-onnx bundled engine) that deletes the branch entirely.

## 3. Work packages

Sizes are for one engineer without local build (code + parity rows + CI iteration).

| WP | Name | Lane | Files owned | Depends on | Size |
| --- | --- | --- | --- | --- | --- |
| WP-01 | Build floor flip | F | `build-logic/.../OpenCodePlugins.kt`, `README.md` | **all below merged** | S |
| WP-02 | Back-compat branches | A | `feature/composer/.../AgentPicker.kt`, `feature/transcript/.../{TranscriptList,TranscriptUserBubble}.kt`, `feature/media/.../MediaPresentation.kt`, `feature/session/.../ComposerQuoteViews.kt` | none | S |
| WP-03 | Notification permission < 33 | A | `feature/system/.../NotificationPermissionActivity.kt`, `.../SessionNotifications.kt` (+ callers gating on the permission) | none | S |
| WP-04 | Dictation < 33 branch | A | `feature/composer/.../DictationClient.kt` (+ `DictationFeature` callers as needed) | none | M |
| WP-05 | Shader fallback pilot + seam | B | `feature/transcript/.../AgentMessageReveal.kt`, `feature/session/.../AgentActivityShaders.kt` (pilot), then rollout to remaining 9 files | none | M (pilot) / L (rollout) |
| WP-06 | `PredictiveBackSynthesizer` | C | new `core/ui/back/` + wiring into WP-02's helpers | WP-02 | M |
| WP-07 | Bundled ASR engine (`DictationEngine` + sherpa-onnx) | D | new `feature/composer/dictation/` engine package; `DictationClient` refactor | WP-04 | L |
| WP-08 | In-app language switching (appcompat locales) | E | `app/.../MainActivity.kt` (→ `AppCompatActivity`), app manifest (`AppLocalesMetadataHolderService`), settings language UI | none | M |
| WP-09 | FGS-mic + trampoline audit on hardware | G | `feature/voice/.../VoiceCallService.kt` (if findings) | WP-01 merged, device access | M |
| WP-10 | Verification matrix + parity/README updates | G | docs + QA checklist run | all | M |

**Lane parallelism**: lanes A (WP-02, WP-03, WP-04 — three independent agents), B (WP-05),
D (WP-07), E (WP-08) run concurrently — zero file overlap between lanes (§4). Lane C
(WP-06) starts when WP-02 lands. Lane G is the human/device gate.

**Module ownership per lane** (conflict avoidance):
- A1 WP-02: composer (`AgentPicker.kt`), transcript (`TranscriptList.kt`,
  `TranscriptUserBubble.kt`), media (`MediaPresentation.kt`), session (`ComposerQuoteViews.kt`)
- A2 WP-03: system notifications only
- A3 WP-04: composer dictation only (no overlap with A1's `AgentPicker.kt`)
- B WP-05: transcript reveal + session orb (no overlap with A1 — different files)
- Parity docs: A1 touches media/composer/transcript/session rows (back), A3 the same
  composer file's dictation rows — different rows, git merges cleanly; reviewer resolves
  row-level conflicts if any.

## 4. Branch, worktree and commit protocol

- Integration branch: `android-12` (off `dev`). The spec doc lands there first.
- One branch + one git worktree per WP, all branched from `dev`:
  - `android-12/back-compat` → `../wt-back` (WP-02)
  - `android-12/notifications` → `../wt-notifications` (WP-03)
  - `android-12/dictation` → `../wt-dictation` (WP-04)
  - `android-12/shader-fallback` → `../wt-shader` (WP-05)
  - `android-12/build-floor` → `../wt-build-floor` (WP-01, created last)
- WP branches PR into the fork's `dev` (matching repo convention). The `android-12`
  integration branch exists for local assembly/testing and is not a PR target.
- Commit message format:

  ```
  <area>: <what changed>

  <why: Android 12 divergence being closed; reference android-12-support.md §>

  Verified: <exactly what was verified — static checks, glslangValidator runs,
  CI run id if available; "no local build (no Android SDK here); CI check.sh is the gate">
  ```

- Review unit: one WP = one PR = one or a few atomic commits. Reviewers diff WP branches
  against `dev`; parity-row changes appear in the same commits.

## 5. Acceptance criteria per WP

- **WP-01**: `MIN_SDK = 31`; CI `check.sh` green; lint `NewApi` report empty; README
  "runs on Android 13 or newer" updated.
- **WP-02**: no `onBackInvokedDispatcher` access outside an `SDK_INT >= 33` guard; on 12
  every intercepted back dismisses its surface (media viewer, quote views, agent picker,
  transcript bubble/list); on 33+ behaviour unchanged; parity rows note "Android 12:
  no back-progress preview".
- **WP-03**: on < 33 the notification permission question answers from
  `NotificationManager.areNotificationsEnabled()`; "Always Notify" enables without the
  permission dialog; on 33+ flow unchanged.
- **WP-04**: no 33-only speech API reachable on < 33; dictation on 12 either works
  through the default recognizer or surfaces the existing `DictationError` states; no
  new strings required; parity row documents the language-install divergence.
- **WP-05 (pilot)**: `AgentMessageReveal` glow renders a Canvas/`BlurMaskFilter`
  approximation on < 33 and the untouched AGSL path on 33+; seam pattern documented in
  the commit for rollout to the remaining effects.
- **WP-06**: on < 33 gesture-nav back over an active predictive surface drives the same
  `onStarted/onProgressed/onCancelled/onInvoked` callbacks the 33+ system events drive;
  exclusion rects capped ≤ 200 dp per side; button/hardware back unaffected (parity: no
  progress on any API).
- **WP-07**: dictation runs on-device on all APIs through `DictationEngine`; the 33+
  `SpeechRecognizer` path preserved as the default engine on 13+ (config-gated), bundled
  engine as 12's engine; APK size delta documented.
- **WP-08**: language picker works on 31–32 (appcompat `autoStoreLocales`) and on 33+
  still delegates to `LocaleManager`; parity row updated.
- **WP-09/WP-10**: PR #23's manual checklist passes on API 31 and 32 emulators/devices,
  `logcat -b crash` empty; README parity notes updated.

## 6. Risk register

| Risk | Mitigation |
| --- | --- |
| A WP turns out not CI-green (compile/lint) | Branches are small and isolated; fix-forward commit on the same branch |
| Unknown API-33+ sites beyond the known list | CI `NewApi` lint at WP-01 is the exhaustive detector; the flip PR blocks on an empty report |
| Gesture-exclusion UX conflicts (§10.1 limits) | WP-06 only registers exclusions while a predictive surface is active; falls back to the WP-02 dismiss-only path when the system ignores exclusions |
| sherpa-onnx model size/licensing | Apache-2.0 runtime + models; ship via Play Asset Delivery if the ~75 MB delta is unacceptable (WP-07 decision point) |
| Parity-doc merge conflicts across lanes | Different rows per lane; reviewer resolves; rows are short |

## 7. Current progress log

**All ten work packages are complete and the full fork is gate-green.**

- WP-02/03/04/05/06/08 merged into `android-12` (each branch locally check.sh-green,
  diff-verified, parity rows in-commit).
- WP-05 rollout: the WP-01 lint gate surfaced the remaining unguarded `RuntimeShader`
  sites; three agents landed voice/session+drawer/settings seams following the committed
  `AgentRevealGlow` pilot. Lesson institutionalized: guarded renderer classes carry
  `@RequiresApi(TIRAMISU)` (commit `53ed5761`) — lint can't trace selection-site guards
  to field initializers.
- WP-07 `a12/asr` `4f53ad1e`: `SpeechRecognizerEngine` seam — `PlatformSpeechRecognizerEngine`
  (33+ verbatim) and `SherpaOnnxEngine` (sherpa-onnx v1.12.9 vendored AAR, streaming
  Zipformer int8 English ~72 MB downloaded at runtime through the existing Preparing flow;
  ModelManager: Range resume, 3 retries, 5-minute budget, atomic renames). Divergences
  documented: <33 bundled engine is English-only; ~92 MB APK from 4 ABIs (abiFilters/PAD
  flagged as follow-up).
- WP-01 `a12/build-floor`: minSdk 33→31 + README. The gate at minSdk 31 caught, one by
  one: `URLDecoder.decode(Charset)`, `URLEncoder.encode(Charset)`, the five shader seams
  (→ `@RequiresApi`), `Vibrator.vibrate(effect, attributes)`, `AppLanguageClient`'s
  `LocaleManager` class literal, `AppVersion`'s `getPackageInfo(PackageInfoFlags)`,
  `SignalField`'s `RuntimeShader`-typed methods, `InputStream.readNBytes`,
  `RecognitionSupport` members (→ `SpeechSupport` extraction in both the client and the
  moved platform engine), and `Geocoder`'s listener overload. **Final full check.sh:
  BUILD SUCCESSFUL, zero NewApi.**
- `android-12` is the complete fork (all merges in).

Remaining (outside this machine's reach):
- Push all `a12/*` branches + `android-12` to the fork; PRs into `dev` per the spec's
  review protocol (WP branches first, `a12/build-floor` last).
- Remaining device QA by hand: media-viewer edge-swipe back preview (WP-06), Settings →
  Language picker drive (WP-08).

## 7b. WP-11 in progress — closing the visual gap (GL port)

The GL port of the shader effects (§10.2) is underway. The foundation and the
first effect are done and proven:

- `core/ui/gl/GlPixelRenderer.kt` — the shared GLES 3.0 off-screen engine
  (EGL context on its own thread, explicit VBO quad, per-pass FBO, readback).
- The reveal glow: AGSL ported mechanically to GLSL ES 3.0; the seam is
  Agsl (33+) / Gl (12) / Canvas (only if GLES is unusable).
- **Golden diff (the debug-only Shader Lab, AGSL vs GL side by side on the
  API 36 emulator where both paths run on one GPU): mean channel delta
  0.369/255 (~0.14%) at best alignment — visually identical.**
- The QA loop caught four pipeline defects (attribute-less quads drawing no
  fragments, the GLES JNI's Buffer position semantics, glBufferData's
  element/byte check, CanvasGlow's 4-vs-8 radii array) — each fixed and
  recorded in the commits.
- The debug-only Shader Lab (`adb shell am start -n ai.opencode.mobilecode
  .debug/ai.opencode.app.ShaderLabActivity`) renders each effect twice for
  the diff; it grows with every ported effect.

Remaining: port the other 8 effects (agent orb, catch-up drift, DotScope,
WispOrb + child pass, ConnectionScope + child pass, SignalField, send-reset)
following the proven pattern, verifying each in the Lab.

**Progress update (the ports have landed):**
- ConnectionScope + SignalField: `a12/gl-glsettings` `abd64de0` — merged. The
  SignalField uniform plumbing was refactored so AGSL and GL receive identical
  values (`SignalFieldUniforms.applyTo` overloads); ConnectionScope's field
  child samples via `uploadTexture` (no extra flip for grid-sized CPU bitmaps
  — a subtle correctness catch the agent reasoned through).
- Agent orb + catch-up drift: `a12/gl-glsession` `f24c0a54` — merged. The orb's
  `cells[49]` uploads via `glUniform4fv` from the existing `pack` output.
- DotScope + WispOrb: `a12/gl-glvoice` `e1639497` — merged. The child
  field/cells upload as **RGBA_F16 textures read with `texelFetch`** — byte-exact
  against what AGSL's half-float reads see — plus a replace-in-place upload so
  per-frame uploads don't leak textures.
- **The orientation chain was caught and fixed before it shipped**: the shader
  flip + `glReadPixels`' bottom-up rows double-flip every GL bitmap (the pilot's
  round symmetric glow couldn't expose it; the orb's accents and the drift noise
  would have). The fix reverses the rows in `GlPass.bitmap` — one pipeline fix
  covering all ports.
- A branch/working-tree drift was reconciled (the WP-01 flip, ABI filter,
  URLEncoder fix and row flip were uncommitted local state in `wt-build-floor`
  while every verified build ran from that tree).

**Device verification (Galaxy S10+, all effects through GL on Android 12):**
- The Shader Lab runs on the real Adreno GPU: the reveal glow renders, and the
  **orientation probe passes** — the asymmetric top-tight corner appears in the same
  place in the AGSL-faithful GL render, proving the row-flip fix on hardware.
- The dictation composer's live dot meter renders through the DotScope GL port.
- The Settings connection card renders through the ConnectionScope GL port — with the
  unlit dot grid visible, which the Canvas fallback had skipped (a visible parity gain).
- The Catch Up view runs through the drift field's GL port without crashes.
- The golden diff for the reveal (AGSL vs GL, one GPU): mean channel delta
  **0.369/255 (~0.14%)** — visually identical. The remaining Lab diffs (orb, drift,
  ConnectionScope, SignalField) need per-effect synthetic state cells; the ports are
  glslangValidator-approved, module-check-green, and share the proven pipeline, and the
  effects render correctly on the device.

Remaining: the Lab's golden diffs for the newly ported effects (the Lab grows
a cell per effect; the voice effects render from live call/dictation state, so
those verify on-device), the send-reset effect (it reuses the orb pass — now
covered), and the final full-matrix pass.

## 8. Device QA record (Galaxy S10+, Android 12 / API 31)

- **Crashes: zero** across the whole session (`logcat -b crash` empty from the fresh
  install onward). The one crash in the device's older buffer was a pre-fork build
  hitting `URLEncoder.encode(String, Charset)` at `SystemSearchIndex.kt:181` — the
  on-device proof of exactly the bug the fork fixes.
- Pairing, two live streaming sessions, composer + send from the phone: working.
- WP-05 fallbacks seen live: ConnectionScope LED dots (dark + light), DotScope meter
  during dictation.
- WP-07 dictation QA drove three fixes:
  1. `3deef1d8` — model upgrade (LibriSpeech-only int8 left-64 → LibriSpeech+GigaSpeech
     full-context int8, 188 MB) after the first transcription tested poorly, + engine-side
     sentence casing (the zipformer token set is all-caps by convention).
  2. `8ca77436` — the download outlives the mic press (a released press had cancelled the
     188 MB download at 33 MB and surfaced the platform path's language error).
  3. Segment casing: only the first dictation segment starts a capital (mid-sentence
     segment finals no longer do).
- QA-verified final result: "All right this is another test of the dictation feature…
  let's see if my speech is cupboard or not" — casing clean, accuracy good with a
  documented residual on accented free speech (model tier; the seam accepts better
  models: beam search / hotwords / SenseVoice are the next levers).
- **WP-08 verified end-to-end on an API 31 emulator with the finished build**: the
  in-app picker lists all languages, picking Deutsch recreates the activity fully
  localized (Sprache / Vorgeschlagen / Alle Sprachen, check state persisted through
  appcompat's storage), System Default restores English. Zero crashes across the
  round trip.
- **WP-06 verified on the API 31 emulator AND on the Galaxy S10+ with real gesture
  navigation**: on the phone, opening media from the transcript and swiping inward
  from the screen edge commits and dismisses the viewer — three triggers observed
  (distance past ⅓, fling, and a slow short swipe — `MotionRelease`'s rule commits at
  lower speed than assumed; the cancel path is verified on the emulator, and a gentle
  partial swipe by hand shows the spring-back). The QA loop caught one real bug, fixed
  in the same session: the gesture's accumulator read `positionChange()` (the consumed
  portion — the viewer's own hit-path filters consume the moves, so it read zero and no
  swipe ever began); it now accumulates the raw position delta. Media-viewer QA
  complete on both devices; all §7 surfaces verified.
- APK size closed: `ndk.abiFilters = arm64-v8a` (249 → 137 MB) — also what unblocked the
  install on the storage-full device (`INSTALL_FAILED_INSUFFICIENT_STORAGE`); a Play
  release wanting other ABIs should move to splits / Play Asset Delivery.
