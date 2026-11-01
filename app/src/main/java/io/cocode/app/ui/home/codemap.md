# app/src/main/java/io/cocode/app/ui/home/

## Responsibility

The control-room home screen (`Routes.HOME`). Shows the on-device Cox runtime (setup, install progress, start and stop, failures, Cox / agent updates, agent selection, details, log), the shared-storage folder card, and saved remote servers with add, edit and remove.

Files:
- `HomeScreen.kt`: `HomeScreen`, `HomeActions` (lambdas passed in from the root), header, one-shot `HomeEntrance`.
- `AgentDraft.kt`: `SelectionDraft<T>` (aliases `AgentDraft`, `ServerDraft`), a picker's local draft: checked set vs. installed baseline, additions/removals/kept, `locked` dependencies (OpenCode for OpenChamber) that can't be unchecked and are only sent when the user asked for them, `canSetUp`/`canApply`, what a toggle saves. Never yields an empty selection.
- `LocalRuntimeCard.kt`: `LocalRuntimeCard`, `RuntimePhase` (coarse phase of `RuntimeState`), per-phase bodies, `AgentSelectionSection` (checkboxes for the manifest agents, installed versions, Apply after install), the update notice (`UpdateOfferRow`, `UpdateProblemRow`, `RawOutput`, `VersionChanges`), footer toggles, `AutoOpenRow`, `ScreenAwakeRow`, `UpdateCheckRow`.
- `SharedStorageCard.kt`: `SharedStorageCard` (switch, status, folder tree, restart notice, permission and unavailable bodies).
- `RemoteServers.kt`: `RemoteServersSection`, server rows, `RemoveServerDialog`, `ServerEditorDialog`.

## Design

- State is hoisted. Composables take plain data and `HomeActions` lambdas. The root (`ui/CoCodeApp.kt`) owns flows and side effects. `HomeScreen` is also used by previews.
- `RuntimePhase` collapses `RuntimeState` to a phase. `AnimatedContent` keys on the phase, so progress ticks update in place. `rememberLastNonNull` keeps the outgoing body during exit transitions.
- `checking` overrides the phase to `Checking` during detection, showing a spacer, not a Set up offer.
- Installing lists `InstallStep.setupSteps`, or `InstallStep.updateSteps` when `Installing.updating` (then with an "Updating Cox…" title, the version changes being installed, an update-specific background note, and the header pill reading Updating). Earlier steps are Done, the current one is Active (ring, percent, bar, detail), later ones are Pending. A step missing from the list counts as the first. A vertical rail is drawn with `drawBehind`.
- Running shows the URL, uptime (a 30 s `produceState` ticker), port and a copy action. Failed shows `failed.reason`. Recoverable failures get Retry and Repair. Non-recoverable ones get only Repair, with an explanation.
- Agent selection: `AgentSelectionSection` shows one checkbox per manifest agent with the installed version. The draft and its rules live in `AgentDraft.kt` (pure, unit tested). Before setup (or after a failed first setup) a toggle calls `setAgents` immediately (the saved set is used by Set up); after install the toggles are staged against the *installed* agents (not the saved set) and Apply calls `setAgents`, which queues `ApplyAgents`. The draft may be empty for a moment: it is then never sent, a warning asks for one agent, and Set up / Try again (first setup) / Apply are disabled. After setup each row tags staged changes ("Will install" / "Will uninstall") and the hint says that checking adds next to installed agents while unchecking + Apply uninstalls. Applying a draft that unchecks an installed agent (`AgentDraft.needsRemovalConfirm`) opens `RemoveAgentsDialog`, which lists what is uninstalled, installed and kept and says settings, sign-ins and projects stay; Cancel sends nothing and keeps the staged checkboxes. Pure additions apply without a dialog.
- Repair, Uninstall and Update go through `ConfirmDialog` (`PendingConfirm`). The uninstall wording changes when the shared folder is in use, since projects stay in place. The update wording changes when the server is up (it is stopped and started again, so running tasks are interrupted); the dialog closes itself if the offer disappears.
- Update notice: a slot under the phase body, shown only with `info != null` in Stopped, Starting, Running, Stopping or Failed. `RuntimeUpdateState.lastFailure` wins over `available`. The offer (`primaryContainer`) lists only changed components ("Cox 0.1.0 → Cox 0.1.1", versions in mono) with an Update button on the title line; a legacy T3 Code bundle is shown as "T3 Code 0.1.6-pw.6 → Cox 0.1.1" from `RuntimeUpdate.replacesLegacyT3`. `TitleWithAction` (a small custom `Layout`) gives the version lines the full width so they don't wrap on a 360 dp phone. The failure row (`errorContainer`, like `FailedBody`) shows the localised `UserMessages.headline(messageKey)`, a note that the current version still works, Details (English reason plus raw `detail` in a selectable, copyable terminal box, max 180 dp, scrolls), Try again (only while an offer exists; asks again) and a close button (`onDismissUpdateFailure`). Update and Try again are disabled while Starting or Stopping. After confirming, `updateRequested` hides the slot until the runtime reports Installing, or 15 s (`UPDATE_PICKUP_GRACE_MS`).
- Details: `AutoOpenRow` and `ScreenAwakeRow` share one switch-row layout. Screen awake is opt-in and persisted by `ScreenAwakeController`; it holds the Activity window only while the local runtime is Running, the app is foreground and no remote page is in view. `UpdateCheckRow` sits above the version table. The whole row calls `onCheckForUpdates` (disabled while checking or Installing) and summarises the state: Checking… with a spinner, Couldn't check for updates (`checkFailed`, warning tint), Update available or Up to date with "checked N minutes ago" (`R.plurals.checked_*_ago`, 30 s ticker), or Not checked yet.
- `NotInstalledBody` shows free space from `filesDir.usableSpace` and warns below 3 GiB (`LOW_SPACE_BYTES`).
- `SharedStorageCard` has three states: `Off`, `NeedsPermission`, `Active`. Claude Code is shared in full; Codex shares config only via ~/.codex-shared (its sign-in, locks and sessions stay in the app's ~/.codex). So: turning it on always opens `EnableSharedStorageDialog` (folder map `CoCode/{projects,opencode,codex,claude}` → `~/projects`, `~/.config/opencode`, `~/.codex-shared`, `~/.claude`, an error-tinted warning, and an acknowledgement checkbox that gates Turn on; Cancel sends nothing). Every state shows `ExposureWarning`. The Active tree marks only claude/ as holding credentials (codex/ is config only) and lists what stays in the app (`~/projects-private`, `~/.claude.json`, `~/.local/share/opencode`, OpenCode service password, node_modules). Turning it off while Active asks first and says the in-app copies may be stale and nothing is copied back. `SharedStorageState.needsConsent` (legacy v0.1.0 users, projects + OpenCode only): header reads "On · projects and OpenCode only", a `ConsentNeededBanner` explains the partial mode with a "Review and share" action that opens the same consent dialog; only an accepted dialog calls `onEnabledChange(true)`. In that mode codex/ and claude/ are never listed as mapped and the exposure warning is not shown as current; the switch can still turn it off (with a partial-mode off text). `codeSpans()` sets paths in mono.
- Remote editor: address and name use `rememberSaveable`. The password uses plain `remember`, so it is never saved in instance state. The address is normalized as the user types (`checkServerAddress`). Plain HTTP to a non-loopback host needs an explicit opt-in checkbox.
- Dialog state: `editingId` and `removingId` are `rememberSaveable` ids. `NEW_SERVER` is a sentinel for the add flow. A dialog closes itself if its server disappears.
- Layout: a `verticalScroll` column with safe-drawing horizontal insets, `widthIn(max = 640.dp)`, and top and bottom spacers. Content scrolls under the transparent system bars.
- Entrance: `revealOnEnter(index)` staggers items by 70 ms. `HomeEntrance` makes it run once per process. Inspection mode skips it.

## Flow

Render:
1. The root collects `runtime.state`, `info`, `logTail`, `updates`, `agents`, `remotes.servers` and `storage.state` inside the HOME destination.
2. `HomeScreen(state = shownLocalState(state, pendingStartFrom), checking, updates, ...)`.
3. `LocalRuntimeCard` derives the phase and renders the matching body. The log toggle appears when there is a log. The details toggle appears when `info != null` or the phase is Failed.
4. `SharedStorageCard` shows when `storage != null && !checking && state.allowsSharedStorage()`, which excludes Detecting, UnsupportedAbi and NotInstalled.

Install and start:
1. Set up → `HomeActions.onSetUp` → `local.install()`. The card moves to Installing with the current step and progress.
2. Stopped → Start → `local.start()`. The card shows the starting transition while `pendingStartFrom` is set.
3. Running → Open → `nav.openIfResumed(entry, Routes.web(LOCAL_ID))`.
4. Failed → Retry → `local.retry()`. Repair opens a confirm dialog, then `local.repair()`.

Update:
1. The runtime fills `updates.available` (bundled T3 version at detection, then the GitHub + npm check). The card shows the offer.
2. Update → confirm → `HomeActions.onUpdate` → `local.update()`: asks for notifications, discards the pooled local WebView, then `runtime.update()`.
3. The runtime stops the server and reports `Installing(updating = true)` through `updateSteps`, then swaps the environment in and starts the server again if it was running.
4. Failure: the state goes back to Stopped or Running (never Failed) and `updates.lastFailure` is set. The card shows the failure row; Try again repeats from step 2, the close button calls `local.dismissUpdateFailure()`.
5. Details → Check for updates → `local.checkForUpdates()` (no-op while a check runs).

Remote servers:
1. Empty list: `EmptyServersCard`. Otherwise, a `SectionHeader` with an Add button.
2. Row tap → `onOpenRemote` → `openIfResumed(Routes.web(server.id))`.
3. Row menu: Edit → `ServerEditorDialog`. Remove → `RemoveServerDialog` → `onRemoveRemote`. The root freezes that server, drains started cookie writes, discards the pooled WebView and clears its named profile's cookies/WebStorage. Only success commits `remotes.remove` and forgets the page; failure keeps the server and shows a retry message.
4. Save: `checkServerAddress` must return `Valid`. An insecure remote with a password needs the opt-in. Then `onSaveRemote(ServerTarget.Remote(...))`. New servers get a UUID, and the id is kept on edit.

Auto-open setting: `AutoOpenRow` appears in Details when `autoOpenLocal != null` and `info != null`. It calls `onAutoOpenChange`, which updates `UiPrefs`.

Screen-awake setting: `ScreenAwakeRow` appears beside Auto-open once installed. `HomeScreen` obtains the process-wide `ScreenAwakeController` directly (previews hide it), so the root does not need another action; `MainActivity` owns the foreground/runtime/window-flag binding.

Storage card:
1. Switch on or upgrade legacy sharing → acknowledge the full-directory warning → `storage.setEnabledWithFullDirectoryConsent(true)` on the IO channel. Switch off while Active → confirm → `setEnabled(false)`. A legacy enabled preference alone never grants full-directory consent.
2. Allow access → `requestStorageAccess()`. Check again → `refreshStorage()`.
3. Restart notice (Running and `restartRequired`) → `local.restart()`.

## Integration

- Used by `ui/CoCodeApp.kt`, which builds `HomeActions` and passes state. The `HomeActions` doc says previews share this surface (`ui/preview/`).
- Runtime types: `RuntimeState`, `FailurePhase`, `RuntimeInfo`, `InstallStep` (`setupSteps`, `updateSteps`), `AgentsState`, `RuntimeUpdateState`, `RuntimeUpdate`, `VersionChange`, `AgentChange`, `UpdateFailure`, `ServerTarget.Remote`, `ServerTarget.LOCAL_ID`, `SharedStorageState`, `SharedStorageStatus`.
- From `ui/`: `hasStorageAccess`, `rememberStorageAccessRequest`, `checkServerAddress`, `AddressCheck`, `displayAddress`, `isLoopbackHost`, `rememberCopyAction`, `rememberLastNonNull`, `revealOnEnter`, `formatBytes`, `uptimeLabel`.
- From `ui/components/`: `ChamberCard`, `ConfirmDialog`, `IconTile`, `DetailRow`, `LogConsole`, `SectionHeader`, `StatusPill`, `CoCodeMark`.
- `i18n.UserMessages.stepLabel(InstallStep)`, `UserMessages.headline(MessageKey)` (update failures), and `R.string.*` / `R.plurals`.
- `ON_RESUME` observed via `LifecycleEventEffect` in `SharedStorageCard`.

## Local servers (Cox / OpenChamber)

- `LocalRuntimeCard` takes `ServersState` and `onServersChange` / `onSelectServer`. The header, status, actions and log are the active server's. A "Servers" `SelectionSection` (same rules as agents) picks Cox, OpenChamber or both; unchecking an installed server asks first (`servers_remove_*`). With both installed an `ActiveServerSelector` (segmented) switches; the switch dialog says running tasks are interrupted, Cancel sends nothing. While OpenChamber is installed or picked, OpenCode is locked in the agent list ("Required by OpenChamber"), from `AgentsState.required` plus the picked servers.
- `RemoteServers.kt`: the editor has a Cox / T3 Code / OpenChamber segmented choice saved as `ServerTarget.Remote.kind` (sign-in protocol; never guessed from the port); rows show the kind before the address.

## Codex sign-in

- `CodexSignIn.kt`: `CodexSignInStatus` (end of the Codex agent row: nothing while unknown, "Sign in" when signed out, "Signed in" + overflow "Sign in again", "Continue" for a hidden running flow), `CodexSignInDialog` (Starting/Verifying spinner; WaitingForUser with step 1 open page via `openUrl`, step 2 large mono code + copy + `m:ss` countdown, "Use an API key instead" password field calling `signInWithApiKey`; SignedIn with Done; Failed with collapsible technical detail, Try again, Close; Cancelled auto-dismisses), `formatCountdown`, `CodexSignInActions` (test seam), `openInBrowser` (ACTION_VIEW + NEW_TASK, toast when nothing can open it), `rememberCodexSignInUi()` (Koin `CodexLogin` singleton, null in previews).
- `LocalRuntimeCard` takes `codexLogin: CodexSignInUi?`. Shown only when the runtime is installed (`info != null`) and Codex is in `agents.installed`. Unknown status triggers one `refreshStatus()`. A running flow's dialog can be hidden (not cancelled) and reopened from the row; results (SignedIn/Failed) pop a dialog only for a flow started from this card, never for the background status check.
