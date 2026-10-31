# app/src/main/java/io/cocode/app/runtime/manifest/

## Responsibility

Reads and validates `assets/runtime-manifest.json`, the single source of truth for which Alpine rootfs, which T3 Code release and which agent CLIs the app installs. It also supplies the port and the per-ABI rootfs pins.

## Design

- `RuntimeManifest` (`@Serializable data class`): `schemaVersion`, `runtimeVersion`, `alpineVersion`, `port`, `t3: T3Spec`, `agents: List<AgentSpec>`, `architectures: Map<abi, RuntimeArchitecture>`.
- `T3Spec`: `repository`, `version` (fallback when the GitHub API is unreachable), `assetPattern` (`t3-{version}-android-linux-{arch}-musl.tar.gz`) and `architectures` (ABI → bundle arch: `arm64-v8a` → `arm64`, `x86_64` → `x64`). `assetName(version, arch)` fills the pattern.
- `AgentSpec`: `id` (`codex`, `claude`, `opencode`), `npmPackage`, `bin`, `displayName`.
- `RuntimeArchitecture`: `alpineUrl`, `alpineSha256`.
- `validate()` is strict: schema must be 1, versions non-blank, port in 1024..65535, at least one architecture. Each architecture URL must start with `https://`, and its SHA-256 must match `^[a-f0-9]{64}$`. The T3 repository is `owner/name`, its version matches `^\d+\.\d+\.\d+-pw\.\d+$`, the asset pattern contains `{version}` and `{arch}`, and the ABI map covers every rootfs architecture. Agents must be non-empty, uniquely identified and fully described.
- `architecture(abi)` throws when the ABI is missing; `t3Arch(abi)` returns the bundle arch; `agent(id)` looks up an agent.
- `RuntimeManifestReader(readAsset)`: takes a lambda so tests can supply the JSON string. Decodes with `ignoreUnknownKeys = true`, then validates.
- `ASSET_NAME = "runtime-manifest.json"`.

Pure data; no Android APIs.

## Flow

1. The DI graph supplies `readAsset` from `assets/` (`AppModule`).
2. `RuntimeManifestReader.read()` decodes and calls `validate()`. Any failure throws, which fails construction.
3. `RuntimeInstallerImpl` and `RuntimeEnvironmentInstaller` use the result. `architecture(abi)` selects the rootfs URL and SHA-256 pin; `t3Arch(abi)` and `assetName` select the T3 bundle. The fallback T3 version and the agent list feed the update check; the port is written into `metadata.json`.

## Integration

- Consumers: `runtime/install/RuntimeEnvironmentInstaller.kt`, `runtime/install/RuntimeInstallerImpl.kt`, `runtime/install/NpmPackageVersionSource.kt` (`DefaultPackageVersionSource`), and `runtime/LocalRuntimeController.kt` (agents, pinned T3 version, port through metadata).
- External contract: the asset file at `app/src/main/assets/runtime-manifest.json`. Current values: `runtimeVersion` 2026.10.31.1, `alpineVersion` 3.24.1, T3 `0.1.6-pw.5`, port 3773, agents `codex` / `claude` / `opencode`. Schema version 1. Unknown keys are ignored.
- Changing a pin changes what the installer downloads and pins. An existing environment is not reinstalled until the user runs Repair, Install or Update.
