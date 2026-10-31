# app/src/main/java/io/cocode/app/notify/

## Responsibility

Turns CoCode's own runtime failures into Android notifications. Nothing here reacts to the web UI's
agent events: T3 Code has no native notification bridge, so agent notifications are not implemented.

## Design

- `RuntimeErrorWatch.kt` (pure): `RuntimeAlert(key, title, body)` plus `onState(RuntimeState)`.
  - `Failed(Install)` → key `install:<reason>`, "Setup failed".
  - `Failed(Start)` → key `start:<reason>`, "T3 Code didn't start".
  - `Failed(Runtime)` → key `runtime:<reason>`, "T3 Code stopped".
  - A user Stop goes to `Stopped`/`NotInstalled`, never `Failed`, so it is not alerted. The same key
    is shown once until `reset()` clears the dedupe set.
  - Titles come from `RuntimeErrorWatch.Titles` (`di/AppModule.kt`, `R.string.alert_*`); the default
    keeps English text so logic tests need no `Context`.
- `NotificationObserver.kt`: process-wide coroutine (`SupervisorJob` + `Dispatchers.IO`) started once
  from `CoCodeApplication`. It collects `LocalRuntimeController.state`, posts
  `watch.onState(state)`, and resets the dedupe on `Stopped`/`NotInstalled`. It polls nothing while
  the server runs.
- `RuntimeAlertNotifier.kt`: the native side.
  - Creates the `errors` channel (`R.string.channel_errors`, HIGH) and re-applies its name on every
    post so a language change reaches system settings; user importance/mute settings are kept.
  - `postAlert(alert)`: silently returns without `POST_NOTIFICATIONS` (API 33+). Id =
    FNV-1a(`alert:<key>`), non-negative, so the same key replaces the earlier alert. Small icon
    `ic_notification`, accent `#EDB449`, `BigTextStyle`, `CATEGORY_ERROR`, auto-cancel; the tap opens
    `MainActivity` (no session extras).
- Alerts always post, even with the app in the foreground.

## Flow

1. `RuntimeState` change → `NotificationObserver` → `RuntimeErrorWatch.onState`.
2. Each new alert → `RuntimeAlertNotifier.postAlert`.
3. Tap → `MainActivity` opens as usual.

## Integration

- Depends on `runtime/` (`LocalRuntimeController`, `RuntimeState`, `FailurePhase`) and `R` strings
  (`channel_errors`, `alert_setup_failed`, `alert_start_failed`, `alert_stopped`).
- Wired in `di/AppModule.kt`: `RuntimeAlertNotifier`, `NotificationObserver(runtime, notifier,
  watch = RuntimeErrorWatch(Titles(...)))`.
- Started by `CoCodeApplication.onCreate`.
- The foreground service notification and the download notifications are separate (`service/`,
  `data/HttpDownloads.kt`); they do not use this package.
- Manifest: no components from this package; `POST_NOTIFICATIONS` is requested by the UI before a
  runtime start.
