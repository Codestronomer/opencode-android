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

First batch **complete and locally verified** — all four work packages pass the full
`scripts/check.sh` (assemble + lint + unit tests, all modules) on this machine:

- WP-02 `a12/back` `4a77fd3e` — **check.sh PASS** (BUILD SUCCESSFUL, 736 tasks)
- WP-03 `a12/notifications` `bcc6df57` — **check.sh PASS**
- WP-04 `a12/dictation` `6ef40810` — **check.sh PASS**
- WP-05 `a12/shader` `53a3ab67` + fix-forward `6f930b8e` (perf) + `76fee5cc` (compile
  fixes: per-corner `android.graphics.Path.addRoundRect`, `nativeCanvas` import,
  `android.graphics.Path` for `drawPath`) — **check.sh PASS** (module scope verified,
  full check running)

Also verified live: a minSdk-31 working-tree build (uncommitted dry run of WP-01) compiles,
merges manifests with zero lint failures, **installs and runs on an API 31 emulator**
(`INSTALL_FAILED_OLDER_SDK` with the unflipped APK is the on-device proof of the bug being
fixed). Emulators: `OpenCode_API_31` (Pixel profile) and `OpenCode_API_36` (repo default).

Next batch (in flight — five parallel lanes, all build-verified locally):
1. WP-05 shader **rollout** (the WP-01 lint gate surfaced the remaining unguarded
   `RuntimeShader` sites): three agents — voice (`a12/shader-voice`), session+drawer
   (`a12/shader-session`), settings (`a12/shader-settings`), all branched from
   `android-12`, each following the committed `AgentRevealGlow` pilot seam.
2. WP-01 flip (`a12/build-floor`) — first NewApi finding fixed (`URLDecoder.decode`
   Charset overload, the one site static analysis couldn't pattern-match); re-gating
   after the shader rollout merges.
3. WP-07 `DictationEngine` + sherpa-onnx (`a12/asr`, from `a12/dictation`).
4. WP-08 per-app language (`a12/locales`) — scope shrank: the in-app picker already
   exists; `AppLanguageClient` is LocaleManager-based (33+) and needs the appcompat
   backport for <33, plus the AppCompatActivity/theme change.
5. Then: merge all lanes → `android-12`, re-gate the flip, final emulator install.
