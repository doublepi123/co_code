# app/src/main/java/io/cocode/app/ui/

## Responsibility

Root of the Compose UI. Hosts home, server web pages and the launch flow, plus system-bar and permission coordination. Screens live in `home/` and `web/`, shared widgets in `components/`, tokens in `theme/`.

Files:
- `CoCodeApp.kt`: `CoCodeApp(...)` root, `Routes`, `LocalActions`, launch and record-keeping coroutines.
- `LaunchDecision.kt`: pure rules (`launchTarget`, `resumeTarget`, `initialLaunchStage`, `shownLocalState`, `sanitizedPagePath`). No Android or Compose imports (okhttp only, via `canonicalWebOrigin`), so unit tests call them directly.
- `LaunchFlow.kt`: launch timing constants, `UiPrefs`, `LaunchCover`, `isLauncherLaunch()`.
- `LocalServers.kt`: on-device target ids (`local-t3`, `local-openchamber`; legacy `local` = Cox), `localKindOf`, `isLocalTargetId`, `migratedTargetId`, `ServersState.openableKind()` (active, or the installed one; never a non-installed server), `ServerKind.label()` (local product name) and `ServerKind.protocolLabel()` (remote "Cox / T3 Code").
- `AppUpdatePrompt.kt`: `AppUpdatePrompt` (CoCode's own GitHub release check: one process-wide `update.AppReleaseChecker` on `BuildConfig.VERSION_NAME`, checked on every `ON_START`, dialog over Home or a server page once the launch cover is gone), `AppUpdateDialog` (Later / View release, which opens the official https GitHub release page in the browser; nothing is downloaded or installed), `releaseNotesPreview` (Markdown notes as short plain text).
- `SystemUi.kt`: `findActivity()`, `SystemBarsEffect`, `WindowBackgroundEffect`.
- `ScreenAwake.kt`: opt-in local-runtime screen hold. `ScreenAwakeController` shares `ui_prefs` with `UiPrefs` and exposes the setting and recorded target as StateFlows; `MainActivity` combines them with runtime and lifecycle state and sets `FLAG_KEEP_SCREEN_ON` only while Running, foreground and not on a remote page. No WakeLock.
- `StorageAccess.kt`: `rememberStorageAccessRequest`, `hasStorageAccess`.
- `ComposeUtils.kt`: `rememberLastNonNull`, `revealOnEnter`, `rememberCopyAction`, `rememberNotificationPermissionRequest`.
- `Format.kt`: `formatBytes`, `uptimeLabel` (localised units via `DurationUnits`), `displayAddress`, `checkServerAddress` (returns `AddressCheck`), `isLoopbackHost`.
- `preview/`: Compose previews and fakes (not described here).

## Design

- Navigation: `NavHost` with `Routes.HOME = "home"` and `Routes.WEB = "web/{targetId}"`. `Routes.web(id)` URL-encodes the id. `ServerTarget.LOCAL_ID` denotes the on-device server.
- Launch decision is a pure function: `launchTarget(RuntimeState)` gives `Wait`, `Home`, `OpenLocal` or `StartAndOpenLocal`. Setup, failures, unsupported ABI, Installing and Stopping go to `Home`. Stopped starts and then opens. Running and Starting open. An app-icon tap on Home starts a fresh launch; a chat already in front is resumed as-is.
- `LaunchStage` (`Waiting`, `Opening`, `Done`) drives the cover. `initialLaunchStage` runs on frame one, so Home never flashes when an auto-open is possible.
- `LocalActions` wraps `LocalRuntimeController` for UI intents. `pendingStartFrom` makes a start read as Starting until the runtime reports back, with an 8 s safety timeout (`PENDING_START_TIMEOUT_MS`). `retry()` reinstalls on `FailurePhase.Install` and restarts otherwise. `restart()` is one queued runtime call.
- Flows are collected inside each destination, not at the root, so the graph is not rebuilt per log line. Shared-storage calls run in order on `Dispatchers.IO` through a `Channel`.
- Saved state: `launchPending` (decide once per launch).
- `UiPrefs` stores `auto_open_local` (default true), the last used instance (`last_target_id`) and the last page of every instance (`page_path:<targetId>`, `page_origin:<targetId>`) in SharedPreferences `ui_prefs`. `ScreenAwakeController` adds the independent `keep_screen_on_local` key (default false) to the same file and observes `last_target_id` without writing it. The old single-page keys (`last_target_path`, `last_target_origin`) move to the last instance on first read, then are removed.
- The remembered instance: entering `web/{targetId}` records the id, going Home clears only the id (Back from local leaves the app, so it survives). Page navigations report the trusted main-frame URL per instance; `sanitizedPagePath` keeps only same-origin path+query (never `/cdn-cgi/`, fragments or foreign URLs). Every fresh load of an instance (cold launch, or switching back from another instance whose WebView replaced it in the pool) opens that instance's own last page. Removing a server, or it disappearing from `RemoteServerStore`, drops its page.
- Insets and bars: `SystemBarsEffect` sets bar icon appearance from background luminance. `WindowBackgroundEffect` syncs the window background with the theme.
- While the cover is up, `NavHost` transitions are `EnterTransition.None`/`ExitTransition.None`. The cover fades out afterwards.
- Theme: `CoCodeTheme` wraps the root once. Dynamic color is off.

## Flow

Cold launch to the last used Cox:
0. `resumeTarget(rememberedId, remoteIds)` picks the instance: a remembered remote opens it directly (no detection wait, no local start); a remembered remote that no longer exists falls back to local; otherwise the local logic below runs. The launch cover covers a remote resume from frame one (`remoteResume`). The instance's remembered page (`page_path:<id>`) is loaded only if the target's canonical origin still matches `page_origin:<id>`.
1. `CoCodeApp` composes with `launchPending = true`. `initialLaunchStage` yields `Waiting` (Detecting, auto-open on), `Opening` (Running, Starting or Stopped, auto-open on, or a remembered remote), or `Done`.
2. `Waiting` shows only `LaunchCover` (background plus `CoCodeMark`). `NavHost` is not composed yet.
3. `openLocalOnLaunch` waits for `state != Detecting`, up to `LAUNCH_DETECT_TIMEOUT_MS` (8 s). On timeout it reveals Home (stage `Done`) and keeps waiting.
4. `launchTarget` returns `Home`: stage `Done`, return. Otherwise `onOpening()` sets `Opening`, composing `NavHost` under the cover.
5. Waits up to `NAV_READY_TIMEOUT_MS` (2 s) for the back stack, then for `RESUMED` (a service start needs the foreground). Checks the top route is still Home.
6. Re-decides on the current state. `StartAndOpenLocal` calls `local.start()`, which asks for notifications first. A failed start leaves the user on Home.
7. `navigate(web/local)` with `launchSingleTop`. Waits for the WEB entry plus two frames, then stage `Done`. The cover fades out over `LAUNCH_COVER_FADE_MS`.
8. Cover timing: the background shows alone for 120 ms, then the mark fades in. After 1.5 s the caption and breathing animation start.

Back behavior:
1. `WebScreen` `BackHandler`: if there is no overlay and the WebView can go back, `goBack()`. For local with no overlay, `moveTaskToBack(true)`, so Back leaves the app rather than stepping into Home. Otherwise `onExit()`.
2. `onExit` for a remote server, or from an overlay, calls `closeIfResumed` then `showHome()`. `showHome()` pops to HOME if it is on the stack, else navigates to HOME with `popUpTo(0)`.
3. `openIfResumed` ignores taps while a transition runs.

Local server start from UI:
1. Home card actions map to `HomeActions`: Set up (NotInstalled), Start (Stopped), Retry or Repair (Failed), Stop and Open (Running).
2. `local.start()` requests POST_NOTIFICATIONS once (Android 13+), sets `pendingStartFrom` when starting from Stopped or Failed, then calls `runtime.start()`.
3. Web overlays offer Start again or Try again through `onStartLocal = local.startOrRetry`.
4. Shared-folder change while running shows a restart notice; `local.restart()` queues stop then start.
5. Repair asks for confirmation, then `pool.discard(LOCAL_ID)` and `runtime.repair()`. Uninstall discards the pooled WebView before `runtime.uninstall()`.

Shared-storage permission:
1. `SharedStorageCard` shows when `storage != null`, not checking, and the state is not Detecting, UnsupportedAbi or NotInstalled.
2. The switch calls `storage.setEnabled` on the IO channel. Turning off while Active first asks for confirmation.
3. Allow access: on Android 11+ it opens "All files access" settings (per-app screen, then list, then app details). Below 30 it requests `READ/WRITE_EXTERNAL_STORAGE`. After two denials with no rationale, the next tap opens app details.
4. `ON_RESUME` (in the root and in `NeedsPermissionBody`) triggers `refreshPermission()` and `hasStorageAccess()`. If access is granted but status lags, the card waits 900 ms (`STATUS_SETTLE_MS`) before reporting the folder as unavailable, with Check again.

## Integration

- Entry point: `CoCodeApp(runtime, remotes, auth, storage)`, called by `MainActivity` (outside these folders). The launcher-tap listener needs `launchMode="singleTop"`.
- `runtime.LocalRuntimeController`: `state`, `info` and `logTail` flows, plus `install`, `start`, `stop`, `restart`, `repair`, `uninstall`. Consumes `RuntimeState` (`Running` carries `url`, `port`, `sinceEpochMs`), `FailurePhase`, `RuntimeInfo`, `InstallStep`, and `ServerTarget.Local(url)`.
- `runtime.RemoteServerStore`: `servers` flow, `upsert`, `remove`, `move`. The web session key uses the remote id, baseUrl and a hash of the UI password.
- `runtime.WebSessionAuthenticator.prepare(target, cookies)` → `WebSessionResult` (`Ready`, `NeedsLogin`, `Unreachable`). Used by `WebScreen` only after the target's profile is bound (`WebSessionProfiles` via `ui/web/WebDependencies.kt`). The root passes `WebViewPool(downloads)` the app's `HttpDownloadStarter`.
- `runtime.SharedStorageController`: `state` flow, `setEnabled`, `refreshPermission`. Drives `SharedStorageState` and `SharedStorageStatus`.
- `i18n.UserMessages.stepLabel(InstallStep)` maps install steps to `R.string`.
- All user-facing text is `R.string` and `R.plurals`, resolved with `stringResource` in composition or `context.getString` in WebView callbacks.

## Local servers

- `CoCodeApp` collects `runtime.servers`. Home opens `Routes.web(active.localTargetId)`. The web route builds `ServerTarget.Local(url, kind)` only for the active kind; another local server's page shows it as stopped and its Start calls `selectServer`. Session keys include the kind.
- Launch: `ResumeTarget.Local(kind)` remembers which local server was open; `localKindToOpen` reopens it only while installed (switching via `selectServer` instead of starting the old one), else the active one.
- `UiPrefs` migrates the legacy `local` last-target and page to `local-t3` once. Pages are kept per unique target id.
- `WebViewSlot.of` puts both local ids in the Local slot: one pooled WebView, rebound (destroyed and recreated) per identity, so each server keeps its own profile and cookies.
