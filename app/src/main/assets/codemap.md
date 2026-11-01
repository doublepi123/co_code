# app/src/main/assets/

## Responsibility

Packaged static inputs read at runtime by the app, not by the build:

- `runtime-manifest.json`: pins the guest runtime (Alpine rootfs per ABI, the Cox repository/fallback version/asset pattern/arch map, the agent CLI list, port 3773).
- `android-shim.cjs`: a Node preload script copied into the guest rootfs to work around an Android SELinux failure.

## Design

- **Pinned manifest, not discovery.** `runtime-manifest.json` is the single source of truth for the guest setup. `runtimeVersion` (date-based), `alpineVersion`, `port`, `t3` and `agents` are top-level. Per-ABI entries (`arm64-v8a`, `x86_64`) carry `alpineUrl` and `alpineSha256`, so the rootfs download is verified before extraction. `t3.architectures` maps each ABI to the bundle arch (`arm64`, `x64`); `t3.assetPattern` builds the release asset name; `t3.version` is the offline fallback when the GitHub API is unreachable.
- **Schema versioned.** `schemaVersion: 1` lets the Kotlin parser reject unknown layouts. Validation also checks the semver tag shape (Cox `0.1.1`, legacy `-pw.N`), the asset pattern placeholders, that every rootfs ABI has a bundle arch, and that the agent list is non-empty and uniquely identified.
- **Shim is a plain CJS file.** `android-shim.cjs` wraps `os.networkInterfaces`. If the original throws (`uv_interface_addresses` gives EACCES under SELinux on Android), it returns a synthetic loopback-only `lo` entry. It is `'use strict'`, has no dependencies, and is deliberately small.
- **Assets are not compiled.** Files are read as bytes or text, so changing them needs no Kotlin change, only a rebuild.

## Flow

1. Build: Gradle packages `app/src/main/assets/` into the APK. Generated Termux output is not merged here (see `scripts/codemap.md`).
2. Runtime manifest: `RuntimeManifest.kt` (`ASSET_NAME = "runtime-manifest.json"`) reads the JSON from `context.assets`. The installer then picks the ABI entry matching the device and downloads the Alpine rootfs from `alpineUrl`, checking `alpineSha256`; `t3.assetName` and `t3Arch` select the Cox bundle.
3. Shim: `di/AppModule.kt` reads `android-shim.cjs` as text from assets. `RuntimeEnvironmentInstaller.kt` (`SHIM_GUEST_PATH = /opt/cocode/android-shim.cjs`) writes it into the guest rootfs.
4. Launch: `ProotCommands` sets `NODE_OPTIONS=--require /opt/cocode/android-shim.cjs` in the guest environment, so every Node process loads the shim before Cox starts.

## Integration

- `app/src/main/java/io/cocode/app/runtime/manifest/`: parses `runtime-manifest.json`.
- `app/src/main/java/io/cocode/app/runtime/install/RuntimeEnvironmentInstaller.kt`: consumes the Alpine pin, Cox release metadata and agent list, and writes the shim.
- `app/src/main/java/io/cocode/app/runtime/process/ProotCommands.kt`: sets `NODE_OPTIONS`. Tests `ProotCommandsTest.kt` and `RuntimeEnvironmentInstallerTest.kt` assert both the path and the env value.
- `app/src/main/java/io/cocode/app/di/AppModule.kt`: loads the shim text and the manifest via Koin wiring.
- Versions here must stay consistent with the `runtimeVersion` and release notes. Changing `alpineSha256` without changing `alpineUrl` breaks installs. Verify both together.
- Runtime `2026.11.01.5` pins Cox `0.1.9`; the installer supplies `gcompat` for Cursor SDK's glibc native bindings and repairs older staged environments before activation.
- `.slim/codemap.json` tracks content hashes of these files.
