# app/src/main/java/io/cocode/app/runtime/install/

## Responsibility

Builds the Alpine + Node + one or both server(s) + agent CLI environment in staging, verifies it, and activates it by an atomic directory swap. Downloads and verifies the Alpine rootfs, installs Cox and OpenChamber from npm, supports previously installed Cox/T3 bundles, and recovers an environment swap that a process death interrupted. Every package mutation (install, update, agent change, server add/remove) is performed on a staging copy and committed by the same atomic swap.

## Design

- `RuntimeInstaller` (interface): `recover()`, `installedRuntime()`, `install(report, agents, servers)`, `update(report, versions)`, `applyAgents(report, selected)`, `applyServers(report, selected, agents)`, `discardEnvironment()`, `removeAll()`. `RuntimeInstallerImpl` implements it over the on-disk layout and is not thread-safe, since the controller serialises calls.
- `RuntimeMetadata` (serialized to `environment/metadata.json`): `schemaVersion=2`, `runtimeVersion`, `alpineVersion`, `abi`, `port`, `t3Version` (legacy/compat), `servers` (`ServerKind.id` to version), `agents` (id to installed version, includes required dependencies), `nodeVersion`, `installedAt`. Legacy v0.1.0 metadata has no `schemaVersion`/`servers`; `recordedServerVersions()` interprets a non-blank `t3Version` as T3, and `installedRuntime()` rewrites the normalized record once.
- `InstalledComponents`: Cox npm package identity, `dist/bin.mjs`, `dist/client/index.html` and `node_modules/node-pty`; legacy `opt/t3/bin.mjs` + `client/index.html`; OpenChamber `bin/cli.js`, `dist/index.html`, `node_modules/node-pty`; agent package/bin checks. `installedServerVersions(rootfs, metadata)` filters recorded servers to usable assets. `coxModuleGuest` selects npm or legacy paths for verification.
- `PackageVersions` (`t3`, effective agent map, installed `servers` map) and `PackageVersionSource` (`latest(pinned)`).
- `NpmPackageVersionSource`: latest stable npm version of one package (abbreviated registry metadata, 15 s timeout, injectable base URL). No major-version lock for agents; never below the installed version.
- `DefaultPackageVersionSource`: checks npm for Cox, installed agents and OpenChamber when selected. Cox never downgrades; legacy T3 versions are not compared against Cox. Registry failures throw so the controller reports `checkFailed`.
- `RuntimeEnvironmentInstaller`: the pipeline. Its nested `GuestRunner` runs `/bin/sh -lc` inside PRoot with `ProotCommands`, streams output to `InstallReporter.log` and `logs/install.log`, and enforces a timeout. On timeout or interrupt, `awaitExit` kills the PRoot tree and waits for the launcher before returning. The live DNS monitor is not part of install: `configureRootfs` writes resolv.conf once, from the system DNS at that moment.
  - `AgentCommandRunner` (internal): the two guest operations agent selection needs (`mustSucceed`, `output`). `GuestRunner` implements it; `AgentSelectionApplyTest` fakes it to exercise the real apply flow without PRoot.
  - `install`: Alpine, apk (`nodejs npm bash git curl ripgrep openssh-client ca-certificates libstdc++ python3 gcompat make g++ linux-headers`), shim, selected npm servers, effective agents, verify all servers/agents, metadata, swap. Commands run with a disposable `install-home` inside staging.
  - `verifyCoxNativeModules` loads npm Cox's keyring and fff native dependencies before activation. `verifyCursorSdkRuntime` ensures `gcompat` for vendored glibc tree-sitter addons, then runs the full offline prewarm smoke (sandbox on/off/on). npm installs receive this script from the installer; old bundles retain their own smoke or a native-binding fallback. Bundles without Cursor SDK skip it. No model requests are sent; failure prevents activation.
  - `installCox`: ensures native build tools, resolves npm `dist-tags.latest` (manifest fallback if lookup fails), validates/pins the version, installs with lifecycle scripts enabled, rebuilds its own node-pty, verifies `build/Release/pty.node`, removes incompatible prebuilds, and validates package identity/assets/version. Updates use the exact target and migrate old program trees even at the same version. Cancellation and build/install/verification failures prevent activation.
  - `installOpenChamber`: removes only the OpenChamber program tree, installs `@openchamber/web` at latest (manifest pin fallback), requires Node `24.14.0` or newer, runs `npx --yes node-gyp rebuild` in its own `node_modules/node-pty`, and verifies `bin/cli.js`, `dist/index.html` and the PTY smoke. Build packages are installed with apk first.
  - `update`: requires an active environment. Checks free space (`treeSize` + 512 MiB, else `UpdateNotEnoughSpace`), copies to staging, updates only installed servers to npm targets, updates changed effective agents, verifies every survivor (all installed servers and agents), writes metadata and swaps. Failure deletes staging only.
  - `applyAgents`: copies to staging, then installs added/changed agents, removes no-longer-needed ones, restores any surviving selected agent whose files disappeared, verifies all effective agents and installed servers, writes metadata and swaps. It never mutates the active rootfs.
  - `applyServers`: copies to staging, removes deselected server trees, adds missing/changed server trees (with OpenChamber build tools), applies the effective agent set (including OpenChamber's OpenCode dependency), verifies every surviving server/agent, writes metadata and swaps. Home, workspace, the other server and agents are preserved.
  - `removeServerAssets`: Cox removes `/opt/t3`, its npm module and `cox` bin link; OpenChamber removes its npm tree/policy/bin links. Never deletes home/config/workspace/agents.
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
- New Cox installs identify themselves through the official npm package name, stable version and built assets; npm handles registry artifact integrity.
- Each npm server's node-pty is rebuilt in the guest from source; the PTY smoke must pass for every installed server before activation.
- Required OpenChamber agents (OpenCode) are part of the effective set; they are not written to the user-requested agent selection.

## Flow

Install (`RuntimeEnvironmentInstaller.install`):
1. `deleteTreeOrThrow(staging)`, then `mkdirs` for cache, logs, proot-tmp and home.
2. DownloadRootfs: `VerifiedRuntimeDownloader` fetches the Alpine tarball from `architecture.alpineUrl` to `cache/alpine-<ver>-<abi>.tar.gz`, verified by `alpineSha256`.
3. ExtractRootfs: `extractTarGz` into `staging/rootfs`, then `configureRootfs` (resolv.conf from `SystemDnsProvider`, `/etc/hosts`, libapk SONAME symlink) and `seedHome` (persistent home is only seeded when empty).
4. InstallPackages: `apk add nodejs npm bash git curl ripgrep openssh-client ca-certificates libstdc++ python3 gcompat make g++ linux-headers` (`set -e`, 20 min).
5. Write `/opt/cocode/android-shim.cjs` (before npm).
6. Install selected servers from npm (latest metadata, manifest fallback); rebuild their separate node-pty modules.
7. InstallAgents: `npm i -g --no-fund --no-audit <effective packages>` (30 min), then `--version` for each.
8. Verify: `node --version` (>=24.14 for OpenChamber), every installed server's assets/PTY smoke, every effective agent.
9. Write `metadata.json` with `schemaVersion=2`, explicit `servers` map and effective `agents`. Then `activateRuntimeEnvironment(active, staging, rollback)`.
10. On any throwable: `deleteTree(staging)`, then rethrow.

Update (`RuntimeEnvironmentInstaller.update`, no Alpine download):
1. Read the active metadata and installed server map. `deleteTreeOrThrow(staging)`. Check free space (`treeSize(environment)` + 512 MiB), else `UpdateNotEnoughSpace`.
2. CopyEnvironment: `copyTreeStrict(environment, staging)`, clear the copied marker, retarget links into staging.
3. For each installed server whose target version differs, assets are missing, or Cox still uses a legacy bundle: remove its program tree and install the npm target with node-pty rebuild. Never install an unselected server.
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
2. Remove deselected server program trees, install added/changed ones from npm with build tools, preserving the other server, agents, home and workspace.
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
- Wiring: `di/AppModule.kt` builds `RuntimeInstallerImpl` with `VerifiedRuntimeDownloader(OkHttpClient)`, the manifest, and the shim asset. No GitHub server release source remains.
- Depends on: `runtime/manifest/RuntimeManifest` (pins), `runtime/process/ProotCommands` and `EmbeddedCommandSuite` (launch), `runtime/process/ProcessTree` (kill on timeout), `runtime/network/ResolvConf`, `runtime/fs/SafeFiles`.
- External contracts:
  - Alpine tarball: HTTPS URL and SHA-256 from `runtime-manifest.json`.
  - Cox: npm `@doublepi123/cox` (fallback `0.1.14`), global module with `dist/bin.mjs` and `dist/client/index.html`. Existing `ANDROID-BUNDLE.json` remains readable for old Cox/T3 bundles. Same-version Cox migration is offered; legacy T3 versions are never compared across products.
  - OpenChamber: npm `@openchamber/web` (fallback `2.5.3`), Node >=24.14.0, guest `bin/cli.js`, `dist/index.html`, its own nested `node-pty` rebuilt in place.
  - Agent CLIs: `@openai/codex` (`codex`), `@anthropic-ai/claude-code` (`claude`), `@opencode/cli` (`opencode`, `opencode2`).
  - Guest paths: `/usr/local/lib/node_modules/@doublepi123/cox`, legacy `/opt/t3`, `/usr/local/lib/node_modules/@openchamber/web`, `/opt/cocode/android-shim.cjs`, `/opt/cocode/pty-smoke.cjs`, `/opt/cocode/openchamber-pty-smoke.cjs`.
  - `metadata.json` fields: `schemaVersion`, `runtimeVersion`, `alpineVersion`, `abi`, `port`, `t3Version` (legacy), `servers`, `agents`, `nodeVersion`, `installedAt`.
