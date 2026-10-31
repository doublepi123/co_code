# app/src/main/java/io/cocode/app/di/

## Responsibility

The only Koin composition root: `AppModule.kt` declares `val appModule = module { ... }`. It builds every long-lived object (OkHttp, stores, notification pipeline, runtime controller, T3 Code server adapter, web session authenticator) from Android `Context`, filesDir, nativeLibraryDir and assets. No business logic lives here beyond wiring and two small helpers.

## Design

Koin graph (`single` declarations, all process-wide):

- `OkHttpClient`: connect 5 s, read 10 s. Shared by `VerifiedRuntimeDownloader` and `DefaultWebSessionAuthenticator`.
- `WebSessionProfiles()`: hands out one WebView profile binding per (target id, canonical origin). It is the only WebView cookie path; there is no global `CookieManager` fallback. Legacy default-profile data is neither cleared nor migrated. Removing a remote clears only that server's named profile after its write gate drains.
- `ScreenAwakeController`: one process-wide preference/navigation reader in `ui_prefs`, used by `MainActivity` for the optional foreground-only local-runtime screen hold and by Home for its switch.
- `HttpDownloadStarter` → `AndroidHttpDownloadStarter(context)`: a single application-scoped starter, so a download keeps running after its page is gone. It has its own default scope.
- `RemoteServerStore` → `EncryptedRemoteServerStore.create(context)`.
- `OpenChamberNotifier(context)`.
- `LocalApiClient`:
  - `baseUrl = { (LocalRuntimeController.state.value as? RuntimeState.Running)?.url }`. A stopped or restarting server is never called.
  - `password = { RuntimeSecrets.existingUiPassword(paths) }`.
  - `localReady = LocalReadiness { T3Server.readyLaunchId() }`. Without it, every request is denied by default.
- `NotificationSink(get(), ProcessForeground, get())`: notifier, foreground source, API.
- `NotificationObserver(runtime, notifier, api, bootstrap = NotificationSettingsBootstrap(api, store), watch = RuntimeErrorWatch(Titles(...)))`. Titles come from `R.string.alert_*`.
- `NotificationBootstrapStore`: SharedPreferences `cocode_notify`, key `notifications_bootstrapped` (private const in this file).
- `SharedStorageControllerImpl` (paths, `SharedStorageAccess.defaultHostRoot()`, `PrefsSharedStoragePrefs`, `SharedStoragePermission`); also bound as `SharedStorageController`.
- `T3Server` → `ProotT3Server`:
  - `suite = EmbeddedCommandSuite(nativeDir, paths).resolve()`.
  - `scanner = ProcessScanner(myUid, myPid)`, `health = HealthProbe.Loopback`.
  - `signal = Process.killProcess`.
  - `refreshDns = ResolvConf.write(rootfs, AndroidSystemDnsProvider.dnsServers())`: one write before each launch.
  - `dnsMonitor = DnsRefresherMonitor(DnsRefresher(AndroidSystemDnsProvider, AndroidNetworkEvents, reporter → Log.w("CoCodeDns")))`: live refresh while the server runs. The server calls `start(rootfs)` once ready and `stop()` first in its own `stop()`, before process teardown.
  - `cleanBeforeStart` → `ProotStaleServerCleaner`.
  - `bindsForStart` / `onStarted` / `onStopped` → `SharedStorageControllerImpl.prepareForStart` / `recordStarted` / `recordStopped`.
- `LocalRuntimeController` → `DefaultLocalRuntimeController`:
  - `installer = RuntimeInstallerImpl(paths, abi, manifest from assets, suite, VerifiedRuntimeDownloader(okHttp), GithubT3ReleaseSource(okHttp, manifest.t3.repository), shim asset "android-shim.cjs", dnsProvider)`.
  - `manifestAgents = manifest.agents`, `selectionStore = PrefsAgentSelectionStore` (`cocode_agents`), `pinnedT3Version = manifest.t3.version`.
  - `versionSource = DefaultPackageVersionSource(NpmPackageVersionSource(okHttp), GithubT3ReleaseSource(okHttp, repository), agents, fallbackT3Version)`.
  - `foregroundStarter = { RuntimeService.request(context, op) }`.
  - `killOwned = killOwnedRuntime(scanner, prootPath, rootfs)`.
  - Calls `.also { it.initialize() }`.
- `WebSessionAuthenticator` → `DefaultWebSessionAuthenticator(client, localPassword, localReady = { T3Server.readyLaunchId() })`. Cookies are no longer a constructor argument: they arrive per call through `prepare(target, cookies)`, from the target's bound profile.

Private helpers: `primaryAbi()` (first of `Build.SUPPORTED_ABIS`), `KEY_BOOTSTRAPPED`.

Note the lambda-based wiring: the controller and the notification sink reach each other through `get()` inside lambdas, so construction order does not create a cycle at build time.

## Flow

1. `CoCodeApplication.onCreate` → `startKoin { androidContext; modules(appModule) }`.
2. `get<NotificationObserver>()` (in `Application.onCreate`) resolves its constructor arguments eagerly: `LocalRuntimeController` (so `T3Server`, the installer and `initialize()` run at this point, not at first UI use), `OpenChamberNotifier`, `LocalApiClient`, and `NotificationSettingsBootstrap`. `NotificationSink` is no longer resolved by the runtime; the T3 Code server produces no notification lines (the bridge is scheduled for removal).
3. `MainActivity` then resolves the same `LocalRuntimeController` singleton, plus `RemoteServerStore`, `WebSessionAuthenticator` and `SharedStorageController`.
4. Server stdout lines go to the bounded log only; there is no notification sink.
5. Local login → `DefaultWebSessionAuthenticator.prepare` → `localReady` → `T3Server.readyLaunchId()`.
6. Start/stop transitions → `storage.prepareForStart()` / `recordStarted` / `recordStopped` for shared storage binds.

## Integration

- Depends on every package under `io.cocode.app`: `data/` (`LocalApiClient`, `DefaultWebSessionAuthenticator`, `EncryptedRemoteServerStore`, `LocalReadiness`, `WebSessionProfiles`, `AndroidHttpDownloadStarter`, `HttpDownloadStarter`), `notify/` (`NotificationObserver`, `NotificationSink`, `NotificationBootstrapStore`, `NotificationSettingsBootstrap`, `RuntimeErrorWatch`, `OpenChamberNotifier`, `ProcessForeground`), `service/RuntimeService.kt`, `runtime/` (`DefaultLocalRuntimeController`, `RuntimePaths`, `RuntimeSecrets`, `RuntimeState`, `ForegroundStarter`), `runtime/install/` (`RuntimeInstallerImpl`, `VerifiedRuntimeDownloader`), `runtime/process/` (`ProotT3Server`, `ProcessScanner`, `HealthProbe`, `EmbeddedCommandSuite`, `ProotStaleServerCleaner`, `killOwnedRuntime`), `runtime/network/` (`AndroidSystemDnsProvider`, `ResolvConf`, `AndroidNetworkEvents`, `DnsRefresher`, `DnsRefresherMonitor`, `RuntimeDnsReporter`), `runtime/manifest/RuntimeManifestReader.kt`, `runtime/storage/` (`SharedStorageControllerImpl`, `SharedStorageAccess`, `PrefsSharedStoragePrefs`, `SharedStoragePermission`).
- Resources: `R.string.alert_setup_failed`, `alert_start_failed`, `alert_stopped`, `alert_opencode_error` (RuntimeErrorWatch titles).
- Assets: `RuntimeManifestReader.ASSET_NAME`, `android-shim.cjs`.
- Android: `nativeLibraryDir` (the bundled `proot` binary), `filesDir`, `Os.getuid/getpid`, `Process.killProcess`.
- Consumers: `CoCodeApplication.kt` (`startKoin`, `get<NotificationObserver>`), `MainActivity.kt` (`get<LocalRuntimeController>`, `RemoteServerStore`, `WebSessionAuthenticator`, `SharedStorageController`), `ui/` via Koin `get()`.
- No HTTP endpoints are defined here; the endpoints are used by the classes it constructs (see `data/codemap.md`).
