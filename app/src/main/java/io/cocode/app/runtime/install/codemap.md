# app/src/main/java/io/cocode/app/runtime/install/

## Responsibility

Builds the Alpine + Node + one or both server(s) + agent CLI environment in staging, verifies it, and activates it by an atomic directory swap. Also downloads and verifies the Alpine rootfs, the T3 Code bundle and OpenChamber's npm package, extracts/installs them without a host package manager, and recovers an environment swap that a process death interrupted. Every package mutation (install, update, agent change, server add/remove) is performed on a staging copy and committed by the same atomic swap.

## Design

- `RuntimeInstaller` (interface): `recover()`, `installedRuntime()`, `install(report, agents, servers)`, `update(report, versions)`, `applyAgents(report, selected)`, `applyServers(report, selected, agents)`, `discardEnvironment()`, `removeAll()`. `RuntimeInstallerImpl` implements it over the on-disk layout and is not thread-safe, since the controller serialises calls.
- `RuntimeMetadata` (serialized to `environment/metadata.json`): `schemaVersion=2`, `runtimeVersion`, `alpineVersion`, `abi`, `port`, `t3Version` (legacy/compat), `servers` (`ServerKind.id` to version), `agents` (id to installed version, includes required dependencies), `nodeVersion`, `installedAt`. Legacy v0.1.0 metadata has no `schemaVersion`/`servers`; `recordedServerVersions()` interprets a non-blank `t3Version` as T3, and `installedRuntime()` rewrites the normalized record once.
- `InstalledComponents`: server assets (`opt/t3/bin.mjs` + `client/index.html`; OpenChamber `bin/cli.js`, `dist/index.html`, `node_modules/node-pty`) and agent package/bin checks. `installedServerVersions(rootfs, metadata)` filters recorded servers to those with usable assets; `installedRuntime()` also filters agents and is not tied to `opt/t3`.
- `PackageVersions` (`t3`, effective agent map, installed `servers` map) and `PackageVersionSource` (`latest(pinned)`).
- `T3ReleaseSource` / `GithubT3ReleaseSource`: `latest()` reads `GET https://api.github.com/repos/<repo>/releases/latest`, accepts only tags shaped `v<x.y.z>-pw.<n>` and returns a `T3Release` with the GitHub download base. It returns null on any failure, so the installer and the update check fall back to the manifest's pinned version. `sha256Sums()` fetches `SHA256SUMS` and parses `<sha256>  <file>` lines (an optional `*` marks binary mode).
- `NpmPackageVersionSource`: latest stable npm version of one package (abbreviated registry metadata, 15 s timeout, injectable base URL). No major-version lock for agents; never below the installed version.
- `DefaultPackageVersionSource`: combines the GitHub release with npm for the installed agents, and checks OpenChamber only when it is in the pinned installed-server map. A GitHub failure uses the manifest fallback; an npm failure throws so the controller reports `checkFailed`.
- `RuntimeEnvironmentInstaller`: the pipeline. Its nested `GuestRunner` runs `/bin/sh -lc` inside PRoot with `ProotCommands`, streams output to `InstallReporter.log` and `logs/install.log`, and enforces a timeout. On timeout or interrupt, `awaitExit` kills the PRoot tree and waits for the launcher before returning. The live DNS monitor is not part of install: `configureRootfs` writes resolv.conf once, from the system DNS at that moment.
  - `AgentCommandRunner` (internal): the two guest operations agent selection needs (`mustSucceed`, `output`). `GuestRunner` implements it; `AgentSelectionApplyTest` fakes it to exercise the real apply flow without PRoot.
  - `install`: Alpine, apk (`nodejs npm bash git curl ripgrep openssh-client ca-certificates libstdc++ python3`, plus `make g++ linux-headers` when OpenChamber is selected), shim, T3 bundle when selected, OpenChamber when selected, effective agents, verify all servers/agents, metadata, swap. Commands run with a disposable `install-home` inside staging.
  - `installServer`: resolves the asset name from `assetPattern` and the ABI→arch map, downloads `SHA256SUMS` (fail closed when the asset is missing), downloads the tarball through `VerifiedRuntimeDownloader` with that SHA-256, extracts into `rootfs/opt/t3` with `stripComponents = 1`, and checks `bin.mjs`, `client/index.html`, and `ANDROID-BUNDLE.json` (`version`, `arch`).
  - `installOpenChamber`: removes only the OpenChamber program tree, installs `@openchamber/web` at latest (manifest pin fallback), requires Node `24.14.0` or newer, runs `npx --yes node-gyp rebuild` in its own `node_modules/node-pty`, and verifies `bin/cli.js`, `dist/index.html` and the PTY smoke. Build packages are installed with apk first.
  - `update`: requires an active environment. Checks free space (`treeSize` + 512 MiB, else `UpdateNotEnoughSpace`), copies to staging, updates only installed servers (T3 target from GitHub/manifest, OpenChamber target from npm), updates changed effective agents, verifies every survivor (all installed servers and agents), writes metadata and swaps. Failure deletes staging only.
  - `applyAgents`: copies to staging, then installs added/changed agents, removes no-longer-needed ones, restores any surviving selected agent whose files disappeared, verifies all effective agents and installed servers, writes metadata and swaps. It never mutates the active rootfs.
  - `applyServers`: copies to staging, removes deselected server trees, adds missing/changed server trees (with OpenChamber build tools), applies the effective agent set (including OpenChamber's OpenCode dependency), verifies every surviving server/agent, writes metadata and swaps. Home, workspace, the other server and agents are preserved.
  - `removeServerAssets`: deletes only `/opt/t3`, or the OpenChamber npm tree/policy/bin links; never home/config/workspace/agents.
- `GuestOutputPump` (internal): reads a guest command's combined output on a daemon thread and passes each line to `onLine`. Two entry points, both bounded by a caller-supplied deadline:
  - `drain(deadline)`: normal exit. Waits for end-of-stream so trailing lines are delivered. An unfinished reader returns `ReaderStillRunning`.
  - `abort(deadline)`: timeout, interrupt or error. Sets `closing` (no new line starts delivery), closes the stream on a helper thread, and joins both within the deadline. An interrupt is restored and returned.
  - Failure rules: an `IOException` from the read is expected only after `abort` or when the child is gone. An error from `onLine` is always a failure, including an `IOException`.
  - Limits: the deadline bounds how long the caller waits. It does not stop work already running. An `onLine` call in progress can finish after `abort` returns. A reader or a blocked `close()` that outlives the deadline is abandoned as a daemon thread, and it is not guaranteed to exit. The caller reads lines through a thread-safe snapshot only.
- `GuestOutputPump.awaitAndTearDown(...)`: the production orchestration `GuestRunner.run` calls. The teardown deadline is created only after `awaitCommand` returns, so a long command never spends the teardown budget.
- `GuestRunner.mustSucceed` reads its lines through a synchronised list and takes a snapshot before it classifies the output.
- `EnvironmentActivation.kt`: swap protocol (see Flow). Also `normalizeRuntimeSymlinks(environment, writeMarker = true)`, which keeps PRoot link2symlink links working at the final path. PRoot resolves its `.l2s.` intermediates only through **absolute** targets, and a relative target makes Node load `pty.node` as JavaScript. The rules:
  - An absolute target under `environment`, `environment.staging` or `environment.rollback` (or the `/data/data` ↔ `/data/user/N` spellings) is retargeted to the same relative location under the **PRoot host spelling** of this environment (`prootHostSpelling`). It stays absolute. `/data/user/0/<pkg>` maps to `/data/data/<pkg>`, which is what PRoot records and what the app process resolves. Other users (`/data/user/N`, N≠0) and non-`/data` paths are unchanged.
  - A relative target named `.l2s.*` (older v2 output) is made absolute under the link's directory, in the PRoot spelling, if it stays inside the environment.
  - Other links are untouched: npm's relative bin links, and guest-absolute targets such as `/usr/lib/...`.
  - Marker `.internal-symlinks-relative` = `absolute-l2s-v4`, written only at the end of a full pass. Older markers (`normalized-v2`, `absolute-l2s-v3`) are repaired. With `writeMarker = false` (staging) no marker is written. `clearSymlinkMarker` removes it.
- `VerifiedRuntimeDownloader`: validates the download URL and parameters, then uses OkHttp with a SHA-256 pin and optional expected size. After recovering `.backup`, it verifies the destination hash (and optional size) before networking; a valid cached destination succeeds directly and stale `.partial`/`.backup` files are cleaned up. It resumes through `.partial` with Range, restarts from zero on a 200 response, and treats 416 by discarding the partial. Uses `.backup` during destination replacement. Requires HTTPS, except loopback hosts.
- `RuntimeArchive`: `verifySha256`, `extractTarGz(input, destination, stripComponents = 0)` and `extractDebianPackage`. Rejects entries that escape the destination. Hard links fall back to byte copies. Symlinks are resolved after all entries are written, deepest first, to handle merged-/usr layouts.
- `InstallReporter` receives `step` and `log`.
- `NetworkFailure`: recognises apk/npm output markers such as `EAI_AGAIN`, `ETIMEDOUT`, `bad address`, and maps them to `MessageKey.MirrorsUnreachable`.

Invariants:
- The active environment is untouched until the commit rename. Every mutation (install/update/applyAgents/applyServers) is built in staging, and metadata is written inside staging, so the rename commits it too.
- `installedRuntime()` returns null only when no recorded server has usable assets and the ABI/metadata are unusable; a legacy T3-only record needs `opt/t3/bin.mjs`/`client/index.html`, while an OpenChamber-only record does not require `opt/t3`.
- A T3 bundle is never extracted without a checksum from `SHA256SUMS`.
- OpenChamber's node-pty is rebuilt in the guest from source; the PTY smoke must pass for every installed server before verify.
- Required OpenChamber agents (OpenCode) are part of the effective set; they are not written to the user-requested agent selection.

## Flow

Install (`RuntimeEnvironmentInstaller.install`):
1. `deleteTreeOrThrow(staging)`, then `mkdirs` for cache, logs, proot-tmp and home.
2. DownloadRootfs: `VerifiedRuntimeDownloader` fetches the Alpine tarball from `architecture.alpineUrl` to `cache/alpine-<ver>-<abi>.tar.gz`, verified by `alpineSha256`.
3. ExtractRootfs: `extractTarGz` into `staging/rootfs`, then `configureRootfs` (resolv.conf from `SystemDnsProvider`, `/etc/hosts`, libapk SONAME symlink) and `seedHome` (persistent home is only seeded when empty).
4. InstallPackages: `apk add nodejs npm bash git curl ripgrep openssh-client ca-certificates libstdc++ python3` plus `make g++ linux-headers` when OpenChamber is selected (`set -e`, 20 min).
5. Write `/opt/cocode/android-shim.cjs` (before npm).
6. Install selected servers: T3 via `installServer` (latest release, manifest fallback); OpenChamber via npm latest (manifest pin fallback) and node-pty rebuild.
7. InstallAgents: `npm i -g --no-fund --no-audit <effective packages>` (30 min), then `--version` for each.
8. Verify: `node --version` (>=24.14 for OpenChamber), every installed server's assets/PTY smoke, every effective agent.
9. Write `metadata.json` with `schemaVersion=2`, explicit `servers` map and effective `agents`. Then `activateRuntimeEnvironment(active, staging, rollback)`.
10. On any throwable: `deleteTree(staging)`, then rethrow.

Update (`RuntimeEnvironmentInstaller.update`, no Alpine download):
1. Read the active metadata and installed server map. `deleteTreeOrThrow(staging)`. Check free space (`treeSize(environment)` + 512 MiB), else `UpdateNotEnoughSpace`.
2. CopyEnvironment: `copyTreeStrict(environment, staging)`, clear the copied marker, retarget links into staging.
3. For each installed server whose target version differs or assets are missing: remove its program tree and install the target (T3 GitHub, OpenChamber npm + node-pty build). Never install an unselected server.
4. `applyAgentSelection` removes changed/removed agents, installs target versions, restores surviving selected agents if needed, and verifies every effective agent.
5. Verify every installed server (assets + PTY), Node version when OpenChamber is present, write metadata with the new server/agent maps, swap.
6. Any throwable: `deleteTree(staging)`, rethrow. The active environment is untouched.

ApplyAgents (`RuntimeEnvironmentInstaller.applyAgents`, server stopped):
1. Copy the active environment to staging (free-space check).
2. Apply the effective agent diff there: install added/changed, remove no-longer-needed, restore any surviving selected agent whose files disappeared.
3. Verify every effective agent and every installed server, write metadata, swap.
4. The active rootfs is never mutated; failures delete staging.

ApplyServers (`RuntimeEnvironmentInstaller.applyServers`, server stopped):
1. Copy the active environment to staging (free-space check).
2. Remove deselected server program trees, install added/changed ones (OpenChamber adds build tools; T3 uses the existing download/verify path), preserving the other server, agents, home and workspace.
3. Apply the effective agent set (OpenChamber implies OpenCode), verify all survivors, write metadata with the new server map, swap.
4. Failures delete staging; the previous environment and selection record stay active.

Swap (`activateRuntimeEnvironment`):
1. Delete leftover `rollback` (only committed garbage can be there).
2. Clear the staging marker. No normalization runs on staging, because absolute targets under staging do not survive the rename. The commit happens in step 4.
3. Rename `environment` → `rollback` (uncommitted; on failure, move back).
4. Rename `staging` → `environment`. COMMIT POINT.
5. `deleteTree(rollback)`. A failure here does not undo the commit.
6. `normalizeRuntimeSymlinks(environment)` on the committed environment. A failure is logged and does not undo the commit. Without a marker, `installedRuntime()` runs it again.

Recovery (`recoverInterruptedRuntimeEnvironment`, at every detection):
- rollback present, environment absent: move rollback back (pre-commit crash).
- both present: delete rollback (post-commit crash).

## Integration

- Controller: `runtime/LocalRuntimeController.kt` calls `recover`, `installedRuntime`, `install(report, agents, servers)`, `update`, `applyAgents`, `applyServers`, `removeAll`.
- Wiring: `di/AppModule.kt` builds `RuntimeInstallerImpl` with `VerifiedRuntimeDownloader(OkHttpClient)`, `GithubT3ReleaseSource(OkHttpClient, manifest.t3.repository)`, the manifest, and the shim asset.
- Depends on: `runtime/manifest/RuntimeManifest` (pins), `runtime/process/ProotCommands` and `EmbeddedCommandSuite` (launch), `runtime/process/ProcessTree` (kill on timeout), `runtime/network/ResolvConf`, `runtime/fs/SafeFiles`.
- External contracts:
  - Alpine tarball: HTTPS URL and SHA-256 from `runtime-manifest.json`.
  - T3 Code release: GitHub `doublepi123/t3code`, assets `t3-<version>-android-linux-<arch>-musl.tar.gz` and `SHA256SUMS`.
  - OpenChamber: npm `@openchamber/web` (fallback `2.5.3`), Node >=24.14.0, guest `bin/cli.js`, `dist/index.html`, its own nested `node-pty` rebuilt in place.
  - Agent CLIs: `@openai/codex` (`codex`), `@anthropic-ai/claude-code` (`claude`), `@opencode/cli` (`opencode`, `opencode2`).
  - Guest paths: `/opt/t3` (`bin.mjs`, `client/index.html`, `node_modules/node-pty`, `ANDROID-BUNDLE.json`), `/usr/local/lib/node_modules/@openchamber/web`, `/opt/cocode/android-shim.cjs`, `/opt/cocode/pty-smoke.cjs`, `/opt/cocode/openchamber-pty-smoke.cjs`.
  - `metadata.json` fields: `schemaVersion`, `runtimeVersion`, `alpineVersion`, `abi`, `port`, `t3Version` (legacy), `servers`, `agents`, `nodeVersion`, `installedAt`.
