# app/src/main/java/io/cocode/app/service/

## Responsibility

Keeps runtime work (install, start, server lifetime) alive in the background through one `specialUse` foreground service, `RuntimeService`. It:

- Shows the ongoing status notification (channel `t3-runtime`, IMPORTANCE_LOW, id 4101).
- Forwards queued `RuntimeOperation`s from intents to `LocalRuntimeController`.
- Decides, from the controller's `busy` and `state`, whether to stay foreground or stop.
- Holds the partial wake lock for native runtime work or while owned Cox activity is active/unconfirmed; this is independent of foreground-service lifetime.

Files: `RuntimeService.kt` (Android service), `RuntimeServicePolicy.kt` (pure foreground decision logic), `WakeHold.kt` (process-wide platform lock), `RuntimeWakeCoordinator` (shared lock-state serialization).

## Design

- `RuntimeService` is `Service()` with `exported=false`, `specialUse` type (manifest subtype text: keeps the on-device Cox web server running in the background).
- Dependency: `LocalRuntimeController` via Koin `inject()`. Exposes `busy: Flow<Boolean>` and `state: StateFlow<RuntimeState>`, and `perform(RuntimeOperation)`.
- Foreground barrier: `onStartCommand` calls `enterForeground(...)` before anything else (`ServiceCompat.startForeground`, type `FOREGROUND_SERVICE_TYPE_SPECIAL_USE`).
- Actions on the start intent:
  - `ACTION_OPERATION` with extra `operation` (enum name of `RuntimeOperation`) → `runtime.perform(op)`.
  - `ACTION_STOP_NOTIFICATION` → `runtime.perform(RuntimeOperation.Stop)`.
- `startedOnce` flag: set on the first start. Until then the policy returns `KeepRunning`, so resting states from before the first intent cannot stop the service.
- `latestStartId`: the newest startId seen. Completion uses `stopSelfResult(latestStartId)`, so an older request cannot stop a newer one.
- Policy (`RuntimeServicePolicy.decide(busy, state, startedOnce)`):
  - `!startedOnce` → `KeepRunning`.
  - `busy` → `KeepRunning`.
  - `Running` or `Starting` → `KeepRunning`. `Starting` covers a watchdog restart waiting out its backoff.
  - `Stopped`, `NotInstalled`, `Failed`, `UnsupportedAbi` → `Finish`.
  - Anything else (`Detecting`, `Installing`, `Stopping` with nothing queued) → `KeepRunning`.
  - `mayStop(latest, requested)` documents stale-startId behavior. Wake-lock policy is separate in `RuntimeWakeCoordinator`.
- `apply(action, busy, state)` updates the shared wake coordinator independently, then updates foreground notification/service state.
  - `KeepRunning`: notify or enter foreground; this does not by itself mean the CPU lock is held.
  - `Finish`: coordinator releases when native work and runtime state are terminal, then `leaveForeground()` removes the notification and stops the service.
- `buildNotification(state)`:
  - Installing: setup detail or `runtime_notification_setup` plus percent; ongoing.
  - Starting / Stopping: string resources; ongoing.
  - Running: `runtime_notification_running` with the URL; ongoing.
  - Others: `UserMessages.failureText` (Failed), or not-installed, stopped or unsupported strings; not ongoing.
  - Stop action only for Starting or Running (`stopIntent` → `ACTION_STOP_NOTIFICATION`).
  - Tap opens `MainActivity` with `SINGLE_TOP`.
- `onDestroy`: reconciles the current busy/runtime state through `RuntimeWakeCoordinator`, cancel observer job, cancel scope, super.
- Channel: `ensureChannel` (API 26+) recreates name and description on each start so language changes apply.
- `WakeHold`: singleton `PARTIAL_WAKE_LOCK`, tag `CoCode:runtime`, `setReferenceCounted(false)`, no timeout. `RuntimeWakeCoordinator` is the only policy owner and serializes lock updates with protocol ACKs.
- `RuntimeService.request(context, operation)` (companion): builds the intent and calls `startForegroundService` on API 26+, `startService` below. Called from the UI thread in the foreground.

## Flow

Start of an operation:
1. Caller → `LocalRuntimeController.perform` → `ForegroundStarter.start(op)` → `RuntimeService.request(context, op)`.
2. `startForegroundService(intent)` → `onCreate` (channel, observer started).
3. `onStartCommand` → `enterForeground(buildNotification(state))` → `latestStartId`, `startedOnce = true`.
4. `ACTION_OPERATION` → `runtime.perform(op)`.
5. Observer receives `combine(busy, state)` → foreground policy plus `RuntimeWakeCoordinator.observeService`; native busy/transition state acquires the lock synchronously.
6. For owned Cox, `ProotRuntimeServer` exports `COX_ANDROID_WAKE_NONCE` and the stdout pump consumes exact protocol frames, reconciling `WakeHold` before writing an ACK to child stdin. Frames never enter logs. Legacy/unknown runtimes remain locked because they cannot publish confirmed idle.

Lifecycle end:
1. Controller reaches terminal-at-rest with `busy == false`.
2. Observer reconciles lock and foreground separately; terminal state releases the lock and removes the foreground notification/stops the service.
3. `onDestroy` preserves the coordinator's running-server policy rather than unconditionally releasing.

Stop from the notification:
1. Stop button → `ACTION_STOP_NOTIFICATION` → synchronously mark native work busy and acquire the lock → `runtime.perform(Stop)`.
2. Controller state moves to Stopping then Stopped; native busy ends, terminal state releases the lock and foreground service stops.

## Integration

- Imports `MainActivity`, `R` (`runtime_notification_*`, `ic_notification`), `i18n/UserMessages.kt`, `runtime.LocalRuntimeController`, `runtime.RuntimeOperation`, `runtime.RuntimeState`.
- Started from `runtime/` (`ForegroundStarter` lambda in `di/AppModule.kt`) only. Not bound.
- Manifest entry: `app/src/main/AndroidManifest.xml`, `android:name=".service.RuntimeService"`, `exported="false"`, `foregroundServiceType="specialUse"`. Permissions: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, `WAKE_LOCK`, `POST_NOTIFICATIONS`.
- `RuntimeWakeCoordinator` is a Koin singleton shared with each `ProotRuntimeServer`; it serializes service busy/state updates, process-generation changes, and protocol frame application/ACK writes.
- No HTTP endpoints. Local Cox health is checked by `runtime/process/HealthProbe` (not here).
