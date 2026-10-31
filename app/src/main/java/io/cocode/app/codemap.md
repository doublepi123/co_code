# app/src/main/java/io/cocode/app/

## Responsibility

Package root of the CoCode Android app. Two top-level classes wire the process together:

- `CoCodeApplication.kt`: process entry. Starts Koin with `appModule`, then starts `NotificationObserver` once (runtime error alerts).
- `MainActivity.kt`: the single launcher activity. Hosts Compose `CoCodeApp(runtime, remotes, auth, storage)` with dependencies resolved from Koin.

Everything else lives in subpackages:

- `data/`: WebView login (`WebSessionAuthenticatorImpl.kt`), per-target WebView profiles (`WebSessionProfiles.kt`), native http(s) downloads (`HttpDownloads*.kt`), encrypted remote server list (`EncryptedRemoteServerStore`).
- `di/`: Koin module `appModule` (the only composition root).
- `i18n/`: `UserMessages`, maps runtime keys to string resources.
- `notify/`: runtime error alerts only (no agent-event notification bridge).
- `service/`: `RuntimeService` (foreground service), its pure `RuntimeServicePolicy`, and `WakeHold`.
- `runtime/`: runtime controller, install, embedded PRoot process, shared storage, network DNS, manifest, fs helpers (see `runtime/codemap.md`).
- `ui/`: Compose screens, theme, components, web view, previews (see `ui/codemap.md`).

## Design

- Composition root is `di/AppModule.kt`. Koin `single`s only. Runtime lookups via `get()`/`inject()` happen in `CoCodeApplication`, `MainActivity`, and `service/RuntimeService` (`inject`).
- Process-wide singletons: `OkHttpClient` (connect 5 s, read 10 s), `RemoteServerStore`, `RuntimeAlertNotifier`, `NotificationObserver`, `SharedStorageControllerImpl`, `T3Server`, `LocalRuntimeController`, `WebSessionAuthenticator`, `WebSessionProfiles`, `HttpDownloadStarter`.
- WebView session order: the page's profile is bound first (`WebSessionProfiles.acquire` → `WebViewPool.obtain`), and only then is `WebSessionAuthenticator.prepare(target, cookies)` called. A failed binding never reaches authentication.
- Manifest (`app/src/main/AndroidManifest.xml`):
  - Permissions: INTERNET, ACCESS_NETWORK_STATE, FOREGROUND_SERVICE, FOREGROUND_SERVICE_SPECIAL_USE, POST_NOTIFICATIONS, RECORD_AUDIO (WebView voice input), WAKE_LOCK, MANAGE_EXTERNAL_STORAGE; READ_EXTERNAL_STORAGE (maxSdk 32), WRITE_EXTERNAL_STORAGE (maxSdk 29).
  - Application: `allowBackup=false`, `largeHeap=true`, `networkSecurityConfig=@xml/network_security_config`, `localeConfig=@xml/locales_config`, `requestLegacyExternalStorage=true`.
  - `MainActivity`: exported, `launchMode=singleTop`, `windowSoftInputMode=adjustResize`, LAUNCHER filter.
  - `service.RuntimeService`: `exported=false`, `foregroundServiceType=specialUse` with the `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` property.

## Flow

1. Cold start: `CoCodeApplication.onCreate` → `startKoin(appModule)` → `get<NotificationObserver>().start()`.
2. Activity start: `MainActivity.onCreate` → `enableEdgeToEdge` → `setContent { CoCodeApp(...) }` with `LocalRuntimeController`, `RemoteServerStore`, `WebSessionAuthenticator`, `SharedStorageController` from Koin.
3. Process-wide alerts: see `notify/codemap.md`.

## Integration

- Depends on `runtime/` (controllers, `RuntimePaths`, `RuntimeSecrets`, `ProcessScanner`, `HealthProbe`, storage, installer) and `ui/` (`CoCodeApp`).
- `di/AppModule.kt` wires `data/`, `notify/`, `service/`, `runtime/*`, and `i18n` consumers.
- `service/RuntimeService.kt` is started only through `RuntimeService.request` (from `runtime/` via `ForegroundStarter`).
- Local T3 Code HTTP endpoints used from this folder tree (loopback only): `/api/auth/browser-session` and `/.well-known/t3/environment`.
- Manifest is consumed by the Android system; `res/` strings are referenced as `R.string.*` across `notify/`, `service/`, `i18n/`, and `di/`.
