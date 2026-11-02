# Repository Atlas: CoCode

## Project Responsibility

CoCode is an Android app (`io.cocode.app`, Kotlin + Jetpack Compose, minSdk 26, target 35) that installs Cox, OpenChamber, or both alongside user-selected agent CLIs in one Alpine Linux 3.24/Node.js 24 environment under PRoot (no root). One server runs at a time, kept alive by a foreground service. Both share the persistent home and OpenCode configuration; explicit server kinds select separate ports, passwords, logs, authentication protocols and isolated WebView profiles (AndroidX WebKit 1.14.0). Remote servers support both kinds too; Cox keeps the T3 Code protocol, so remote T3 Code servers remain usable.

Key constraints:
- On-device mode runs on arm64-v8a and x86_64 (PRoot 5.1.107.98 rewrites the guest's raw `fork` to `clone`, which Android's x86_64 app seccomp policy allows). Other ABIs can only use remote servers. The x86_64 gate is the emulator probe in `docs/testing-x86-runtime.md`; it is not yet verified on a physical x86_64 device.
- The on-device server is Cox, a local-only fork of T3 Code, installed from npm `@doublepi123/cox`. Setup resolves the latest dist-tag and pins that install; the manifest supplies a fallback if lookup fails. Updates use npm stable versions without downgrading Cox. Existing Cox bundles can migrate even at the same version; legacy T3 Code bundles are replaced without comparing versions across products. Agent CLIs are installed from npm without version locks (`codex`, `claude`, `opencode`), and at least one must be selected.
- Optional shared storage `/sdcard/CoCode`: `projects/` → `~/projects`, `opencode/` → `~/.config/opencode`, `codex/` → `~/.codex`, `claude/` → `~/.claude`. Codex and Claude directories intentionally include credentials/history after explicit risk acknowledgement. OpenCode service secrets/plugin modules, `~/.local/share/opencode`, `~/.claude.json` and both servers' data stay private. Disabling sharing reveals older private copies; no automatic copy-back. The private workspace is `~/projects-private`.
- OpenChamber is installed from npm and requires the shared OpenCode CLI. Each server's node-pty is rebuilt for musl and smoke-tested separately. All environment package mutations stage, verify survivors and swap atomically; metadata schema 2 records both servers and migrates legacy T3-only installs. Older Cox/T3 bundles remain runnable until migrated.
- Guest paths: home `~` = `/root` (bind of `<runtime>/home`), server working directory `/root`, Cox data `~/.cox` (a legacy T3 bundle reads `~/.t3`; an existing `.t3` is renamed once when Cox first starts); OpenChamber state is separate under the same home. Cox port 3773, OpenChamber port 3000, both loopback-only. Local target IDs are `local-t3` and `local-openchamber`; legacy `local` migrates to Cox (id `t3`).
- All embedded pages require `WebViewFeature.MULTI_PROFILE`: unsupported or failed binding blocks page loading before authentication. Home and the local runtime remain available; remote addresses can be opened explicitly in a browser without adding credentials.
- Profiles are keyed by target ID and canonical origin. Old default-profile data is neither cleared nor migrated; isolation between target profiles does not change browser Cookie rules for subresources inside one profile.
- Native HTTP downloads use controlled redirects and the app process; they do not resume after process death.
- Removing a remote server clears only its current named WebView profile after started cookie writes drain; failed cleanup keeps the saved server. Optional screen awake is off by default and holds the foreground Activity window only while the local runtime is Running and no remote page is shown.
- Launch can restore the last used server, and every instance remembers its own last page, so switching between instances returns to where each one was left. A launch with a remembered remote opens that remote; otherwise (a remembered local instance, nothing remembered, or Home left last) it starts the installed local runtime if needed and opens local chat. An app-icon tap on Home counts as a new launch, while a page already in front is resumed as-is. Cloudflare Access handoff stops the old page before reading cookies and logging in, so a late 401 cannot delete the new login cookie.
- PRoot JNI libraries are compiled from pinned sources with NDK 29.0.14206865. That source build is not a claim that the whole 0.x pre-release is reproducible, and this is not an F-Droid build.

## System Entry Points

| File | Role |
|---|---|
| `app/src/main/java/io/cocode/app/CoCodeApplication.kt` | Process entry: starts Koin (`di/AppModule.kt`) and the runtime-alert observer |
| `app/src/main/java/io/cocode/app/MainActivity.kt` | Single launcher activity (singleTop): hosts Compose `CoCodeApp` and owns the optional foreground local-runtime screen hold |
| `app/src/main/java/io/cocode/app/service/RuntimeService.kt` | `specialUse` foreground service that runs runtime operations and keeps the server alive |
| `app/src/main/AndroidManifest.xml` | Components, permissions (FGS, notifications, all-files access, microphone), locale config |
| `app/src/main/assets/runtime-manifest.json` | Pinned runtime: Alpine rootfs per ABI (URL + SHA-256), Cox repository/fallback version/asset pattern/arch map, agent CLI list, port 3773 |
| `app/build.gradle.kts` | App build; offline Exec task `buildCoCodeRuntimeNative` compiles PRoot JNI libs from prepared sources (NDK 29.0.14206865); Sync task `syncNativeLicenseAssets` packages `third_party/native-runtime/` as `assets/licenses/native/` in every variant; env-driven release signing (`COCODE_*`); debug-only `cocode.testApplicationId` override |
| `gradle.properties` | Canonical release version as literal `cocode.versionName` / `cocode.versionCode` (currently 0.3.5 / 305); the release workflow checks them against the tag |
| `build.gradle.kts`, `settings.gradle.kts` | AGP 8.5.2, Kotlin 2.0.21; single `:app` module |
| `.github/workflows/ci.yml` | Push/PR: offline Python tests, JVM unit tests and debug APK |
| `.github/workflows/release.yml` | Tag `v*`: Python/JVM tests, signed release APK, `apksigner` verify, sha256, GitHub release (versioned notes or generated fallback) |

## Architecture at a Glance

```text
MainActivity ──► ui/ (Compose: home, web WebView, launch flow)
                   │  last-instance restore and Cloudflare Access gateway handoff stay in ui/web
                   │  runtime contract (LocalRuntimeController, SharedStorageController,
                   │  RemoteServerStore, WebSessionAuthenticator, WebSessionCookies)
                   ▼
service/RuntimeService (FGS) ──► runtime/LocalRuntimeController (state machine, watchdog, update check)
                                   ├─ runtime/install   (Alpine → apk → npm servers + musl PTY builds → agents → verify → atomic swap)
                                   ├─ runtime/process   (PRoot argv/env, T3 Code server, readiness, process ownership)
                                   ├─ runtime/storage   (bind decisions, shared folder prep, git config for FUSE)
                                   ├─ runtime/network   (live system DNS → atomic resolv.conf)
                                   ├─ runtime/fs        (no-follow delete/size/copy)
                                   └─ runtime/manifest  (pinned versions, agent list, T3 asset mapping)
ui/web ──► data/WebSessionProfiles ──► bound WebSessionCookies ──► Cox login and WebView
HTTP downloads ──► data/HttpDownloadStarter ──► MediaStore or app-external Downloads
```

Notifications are native only for CoCode's own runtime errors (`notify/RuntimeErrorWatch.kt` +
`RuntimeAlertNotifier`): setup failure, start failure and an unexpected stop. The web UI's agent events
have no native notification bridge; agent notifications are not implemented yet.

## Directory Map

| Directory | Responsibility Summary | Detailed Map |
|---|---|---|
| `app/src/main/java/io/cocode/app/` | Package root: Application (Koin + notification observer) and the single launcher activity | [View Map](app/src/main/java/io/cocode/app/codemap.md) |
| `…/app/runtime/` | Lifecycle of the on-device runtime (install, start, stop, repair, restart, uninstall, agent selection, crash recovery) and the contract types the UI and service depend on | [View Map](app/src/main/java/io/cocode/app/runtime/codemap.md) |
| `…/app/runtime/install/` | Builds Alpine + Node + Cox (or a still-installed legacy T3 bundle) + agent CLIs in staging, verifies it, activates it by atomic directory swap; verified downloads; interrupted-swap recovery | [View Map](app/src/main/java/io/cocode/app/runtime/install/codemap.md) |
| `…/app/runtime/process/` | Runs, probes and stops the Cox server (or a legacy T3 bundle) inside PRoot: command lines, guest env, `.t3`→`.cox` data move, readiness and process identity | [View Map](app/src/main/java/io/cocode/app/runtime/process/codemap.md) |
| `…/app/runtime/storage/` | Optional shared storage `/sdcard/CoCode`: three CLI directory mappings, migration, explicit public credential exposure, git config for FUSE repos | [View Map](app/src/main/java/io/cocode/app/runtime/storage/codemap.md) |
| `…/app/runtime/fs/` | Tree operations that never follow symbolic links (delete, size, copy, replace) | [View Map](app/src/main/java/io/cocode/app/runtime/fs/codemap.md) |
| `…/app/runtime/network/` | Lifecycle-bound network observation and atomic guest `/etc/resolv.conf` updates from active-network DNS | [View Map](app/src/main/java/io/cocode/app/runtime/network/codemap.md) |
| `…/app/runtime/manifest/` | Reads and validates `runtime-manifest.json` (rootfs pins, Cox release, agents, port) | [View Map](app/src/main/java/io/cocode/app/runtime/manifest/codemap.md) |
| `…/app/service/` | `RuntimeService` foreground service: status notification, operation dispatch, wake lock, lifetime policy | [View Map](app/src/main/java/io/cocode/app/service/codemap.md) |
| `…/app/notify/` | Runtime error notifications (`RuntimeErrorWatch` + `RuntimeAlertNotifier`, one "Errors" channel); no agent-event bridge | [View Map](app/src/main/java/io/cocode/app/notify/codemap.md) |
| `…/app/data/` | Profile-bound authentication, controlled HTTP downloads and encrypted remote server persistence | [View Map](app/src/main/java/io/cocode/app/data/codemap.md) |
| `…/app/di/` | Koin composition root (`appModule`) | [View Map](app/src/main/java/io/cocode/app/di/codemap.md) |
| `…/app/i18n/` | Maps runtime message keys and install steps to string resources (English / Simplified Chinese) | [View Map](app/src/main/java/io/cocode/app/i18n/codemap.md) |
| `…/app/ui/` | Compose root: navigation (home, web), launch decision and cover, system bars, permission requests | [View Map](app/src/main/java/io/cocode/app/ui/codemap.md) |
| `…/app/ui/home/` | Control-room home: local runtime card with agent selection, shared-storage card, remote servers | [View Map](app/src/main/java/io/cocode/app/ui/home/codemap.md) |
| `…/app/ui/web/` | Profile-bound WebView pool, session/page loading, navigation and JS bridge, HTTP download handoff, page remembering and isolation/error overlays | [View Map](app/src/main/java/io/cocode/app/ui/web/codemap.md) |
| `…/app/ui/components/` | Stateless shared Compose widgets | [View Map](app/src/main/java/io/cocode/app/ui/components/codemap.md) |
| `…/app/ui/theme/` | Material 3 color schemes, typography, shapes, extended `ChamberColors` | [View Map](app/src/main/java/io/cocode/app/ui/theme/codemap.md) |
| `…/app/update/` | Independent CoCode APK release check: published releases including prereleases, numeric version comparison, bounded network calls, cooldown and dismissal; UI opens the official download page |
| `app/src/main/assets/` | Runtime manifest pins and the Node preload shim | [View Map](app/src/main/assets/codemap.md) |
| `scripts/` | Build-time tools: network source preparation for the native runtime, offline NDK compilation, the legacy Termux pipeline, and mirror health checks | [View Map](scripts/codemap.md) |
| `runtime_tools/` | Python library and SHA-256 lock files for the PRoot build (Termux lock and runtime source lock) | [View Map](runtime_tools/codemap.md) |
| `third_party/native-runtime/` | License texts (PRoot COPYING, talloc LGPL-3.0 and GPL-3.0, libandroid-shmem BSD-3-Clause) and the provenance record for native components; packaged into the APK by `syncNativeLicenseAssets` | [View README](third_party/native-runtime/README.md) |

## Not Mapped

- `app/src/test/` — JVM unit tests (runtime, storage, UI launch rules, i18n string parity).
- `app/src/androidTest/` — dedicated-app PRoot baseline probes and opt-in `CoxNpmRuntimeDeviceTest` for real npm installation, synthetic legacy-layout migration, rollback, restart and authenticated HTML; see `docs/testing-cox-npm.md`.
- `scripts/tests/` — offline Python tests for the native source build and the license assets (see [scripts/codemap.md](scripts/codemap.md)).
- `app/src/main/java/io/cocode/app/ui/preview/` — Compose previews and fake contract implementations.
- `app/src/main/res/` — resources (`values/` English, `values-zh/` Simplified Chinese, launcher/notification icons).
- `docs/` — icon, screenshots and the remaining testing documents.

Change detection excludes tests, documentation and translations. The current codemap utility skips dot-directories, so review `.github/workflows/` directly when checking mapping changes.
