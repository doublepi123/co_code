# app/src/main/java/io/cocode/app/runtime/manifest/

## Responsibility

Reads and validates `assets/runtime-manifest.json`, the single source of truth for which Alpine rootfs, which Cox release (T3 Code protocol), which OpenChamber npm package and which agent CLIs the app installs. It also supplies the ports and the per-ABI rootfs pins.

## Design

- `RuntimeManifest` (`@Serializable data class`): `schemaVersion`, `runtimeVersion`, `alpineVersion`, `port` (Cox/T3 protocol), `t3: T3Spec`, `openchamber: OpenChamberSpec`, `agents: List<AgentSpec>`, `architectures: Map<abi, RuntimeArchitecture>`.
- `T3Spec`: `npmPackage` (`@doublepi123/cox`) and stable `version` (fallback when npm metadata lookup fails). Program paths use npm's global prefix and `dist/bin.mjs` / `dist/client/index.html`.
- `OpenChamberSpec`: `npmPackage` (`@openchamber/web`), fallback `version` (`2.5.3`), fixed `port` (3000), guest `cliPath` and `uiPath`. The installer tries npm latest and falls back to this version.
- `AgentSpec`: `id` (`codex`, `claude`, `opencode`), `npmPackage`, `bin`, `displayName`.
- `RuntimeArchitecture`: `alpineUrl`, `alpineSha256`.
- `validate()` is strict: schema must be 1, versions non-blank, port in 1024..65535, at least one architecture. Each architecture URL must start with `https://`, and its SHA-256 must match `^[a-f0-9]{64}$`. Cox must use the supported npm package and a stable three-part version. OpenChamber needs a stable npm package, a dotted fallback version and `/usr/local/lib/node_modules/` paths. Agents must be non-empty, uniquely identified and fully described.
- `architecture(abi)` throws when the ABI is missing; `agent(id)` looks up an agent.
- `RuntimeManifestReader(readAsset)`: takes a lambda so tests can supply the JSON string. Decodes with `ignoreUnknownKeys = true`, then validates.
- `ASSET_NAME = "runtime-manifest.json"`.

Pure data; no Android APIs.

## Flow

1. The DI graph supplies `readAsset` from `assets/` (`AppModule`).
2. `RuntimeManifestReader.read()` decodes and calls `validate()`. Any failure throws, which fails construction.
3. `RuntimeInstallerImpl` and `RuntimeEnvironmentInstaller` use the result. `architecture(abi)` selects the rootfs URL and SHA-256 pin; `t3` and `openchamber` supply npm packages and fallback versions. The fallback Cox/OC versions and the agent list feed the update check; the legacy port is written into `metadata.json`.

## Integration

- Consumers: `runtime/install/RuntimeEnvironmentInstaller.kt`, `runtime/install/RuntimeInstallerImpl.kt`, `runtime/install/NpmPackageVersionSource.kt` (`DefaultPackageVersionSource`), and `runtime/LocalRuntimeController.kt` (agents, pinned Cox version, port through metadata).
- External contract: the asset file at `app/src/main/assets/runtime-manifest.json`. Current values: `runtimeVersion` 2026.11.02.1, `alpineVersion` 3.24.1, Cox `0.1.15`, OpenChamber `2.5.3` on port 3000, Cox port 3773, agents `codex` / `claude` / `opencode`. Schema version 1. Unknown keys are ignored.
- Changing a pin changes what the installer downloads and pins. An existing environment is not reinstalled until the user runs Repair, Install or Update.
