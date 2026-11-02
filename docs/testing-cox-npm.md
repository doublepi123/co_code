# Testing the Cox npm runtime

Cox installs from `@doublepi123/cox`. These probes require network access to Alpine and npm, but do
not authenticate a model provider or send model requests. Keep generated artifacts, device addresses,
SSH configuration, credentials and raw logs outside the repository.

## Disposable Alpine guest (x64 or ARM64)

On a machine with Docker, mount this checkout read-only and specify a published Cox version:

```sh
docker run --rm -v "$PWD":/repo:ro alpine:3.24 \
    sh /repo/scripts/verify_cox_npm.sh 0.1.14
```

The script installs dependencies in the disposable container, installs the exact npm package,
rebuilds node-pty for musl, and requires all five markers:

- `NATIVE_OK`: keyring, fff and Cursor tree-sitter native modules load.
- `PTY_OK`: node-pty starts a shell and receives its output.
- `CURSOR_SDK_OFFLINE_OK`: workspace prewarm succeeds with sandbox enabled, disabled, then enabled.
- `SERVER_HEALTH_OK`: the server reports the requested version and an environment identity.
- `AUTH_AND_UI_OK`: an incorrect password is rejected; the correct password returns
  `authenticated:true`, a session cookie, and authenticated HTML.

Cox 0.1.15 and later prefer `COX_AUTH_PASSWORD`. The app and this probe also set
`T3CODE_AUTH_PASSWORD` to the same value for older Cox versions. Legacy T3 Code receives only its
original variable; OpenChamber and installer/login processes receive neither password variable.

Use an ARM64 Docker host for native ARM64 coverage; inspect the image architecture rather than
assuming it from the host. This Linux container test does **not** prove Android/PRoot compatibility.

## Android app-sandbox end-to-end probe

Use a dedicated emulator and the independent probe application described in
[testing-x86-runtime.md](testing-x86-runtime.md). Prepare the pinned native sources first. Build and
install both APKs with the same debug signing key:

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest \
    -Pcocode.testApplicationId=io.cocode.x86probe
adb -s <serial> install -r -t app/build/outputs/apk/debug/app-debug.apk
adb -s <serial> install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s <serial> shell am instrument -w \
    -e class io.cocode.app.runtime.CoxNpmRuntimeDeviceTest \
    -e cocode.npmRuntimeProbe true \
    io.cocode.x86probe.test/androidx.test.runner.AndroidJUnitRunner
```

The test is skipped unless explicitly enabled and refuses the production application id. It uses
the real installer and server inside the Android app process, under a separate private runtime
directory. It downloads Cox and Codex CLI, compiles node-pty in PRoot, and checks:

1. Fresh npm installation, npm identity/assets, PTY and offline Cursor SDK prewarm.
2. Server health/version, rejected and accepted password login, session cookie and HTML.
3. Startup through a synthetic legacy `/opt/t3` bundle layout using the same package's assets.
   This tests layout compatibility, not installation of a historical release artifact.
4. An unpublished update fails without replacing the active runtime, changing its metadata or
   losing a user-home marker; the active legacy layout still starts and authenticates.
5. Updating that layout at the **same version** installs npm, removes `/opt/t3`, retains the
   user-home marker and starts/authenticates again. Staging and rollback directories are absent.

Expected: `OK (1 test)`. The `CoxNpmDeviceProbe` log tag emits
`FRESH_INSTALL_AUTH_UI_AND_PTY_OK`, `LEGACY_BUNDLE_LAYOUT_START_OK`,
`FAILED_UPDATE_ROLLBACK_OK`, and `SAME_VERSION_NPM_MIGRATION_RESTART_AUTH_OK`.
The server is stopped in `finally`; uninstall the dedicated probe packages when finished.
Raw installation/server logs can contain local paths or temporary secrets; do not publish them.

## Verified scope

On 2026-11-02, Cox `0.1.14` passed the disposable Alpine test on native x64 and on a Raspberry Pi 400
ARM64 host. Android 15 x86_64 emulator coverage additionally includes the 12 PRoot baseline probes
and the opt-in npm end-to-end test. An ARM64 Linux pass is not an ARM64 Android test, and an emulator
pass is not a physical x86_64 Android device test. No paid model inference is part of these checks.

The dual password-variable environment additionally passed the x64 Alpine probe with Cox `0.1.14`
and `0.1.15`; this checks both sides of the `COX_AUTH_PASSWORD` rename.
