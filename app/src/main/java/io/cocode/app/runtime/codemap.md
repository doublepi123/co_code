# app/src/main/java/io/cocode/app/runtime/

## Responsibility

Lifecycle of the on-device Linux runtime (one Alpine rootfs under PRoot, one or both web servers, shared user-selected agent CLIs): install, start, stop, repair, restart, uninstall, server/agent selection, server switching and crash recovery. Also defines the contract types (state, failure phases, message keys, server targets, shared-storage state) that the UI and foreground service depend on. Implementations live in the subfolders.

## Design

- `RuntimeContract.kt`: the UI-facing contract. `RuntimeState` is a sealed interface (Detecting, UnsupportedAbi, NotInstalled, Installing, Stopped, Starting, Running, Stopping, Failed). `FailurePhase` (Install, Start, Runtime) decides what Retry redoes. `MessageKey` names localised headlines; `UserFacingException` carries a key plus untranslated detail. Also `ServerTarget` (`kind: ServerKind`; local id `kind.localTargetId`), `RemoteServerStore`, `RuntimeInfo` (nullable legacy `t3Version`, `serverVersions`, per-agent versions), and `RuntimeUpdate` (`t3`, `agents`, `servers: Map<ServerKind, VersionChange>`).
- Web session capability (`RuntimeContract.kt`, web session section):
  - `WebSessionIdentity(targetId, origin)`: one server target at one canonical origin. A WebView profile is bound to exactly one identity.
  - `WebSessionCookies`: the cookie capability of that one bound profile. It exposes `identity`, `cookieHeader(url)` and `storeCookies(url, headers)`. Any URL outside `identity.origin` gets no cookie back, and nothing is written for it.
  - `WebSessionAuthenticator.prepare(target, cookies: WebSessionCookies): WebSessionResult`. There is no global cookie manager and no `prepare(target)` overload. The caller must pass the capability of the target's own profile.
  - `WebSessionResult`: `Ready(url)`, `NeedsLogin(url)`, `NeedsGatewayLogin(url)` for interactive remote Access, or `Unreachable(reason)`.
  - Implementations live in `data/` (`WebSessionAuthenticatorImpl`, `WebSessionProfiles`). This package only defines the contract.
- `AgentSelection.kt`: `AgentsState(available, selected, installed, required)` and `AgentSelectionStore` (`InMemoryAgentSelectionStore`, `PrefsAgentSelectionStore` in `cocode_agents`). The default selection is `{opencode}`; an empty or all-unknown set is ignored, so the stored set never becomes empty. `selected` is the user request; `required` holds server dependencies (OpenChamber → `opencode`); `installed` is the effective set from metadata. `agentSelectionChange(selected, installed)` is the server-agnostic diff: additions are installed, removals are uninstalled, and a still-selected agent never appears in either.
- `ServerSelection.kt`: `ServersState(selected, installed, active)`, `ServerSelectionStore` (`InMemory`, file-backed `FileServerSelectionStore` under the runtime root, and `PrefsServerSelectionStore`), `sanitizeServerSelection` and `requiredAgentsForServers`. `selected` is the user's server choice (default T3), `installed` maps each installed kind to its version, `active` is the one process allowed to run. Empty input is ignored by the controller; a read with no valid selection adopts the installed set or T3.
- `LocalRuntimeController.kt`: `DefaultLocalRuntimeController`. Every operation runs under one `operationLock` (a Mutex). `wantsRunning` records user intent and gates automatic recovery. The watchdog and recovery jobs take the same lock.
  - `agents: StateFlow<AgentsState>` combines the manifest's agents, the saved user selection, required dependencies and the effective versions in `metadata.json`. `setAgents` ignores an empty/all-unknown draft, otherwise persists the user set and, when a runtime is installed, queues `RuntimeOperation.ApplyAgents`. It is a no-op only when the effective set (selected + required) equals both the saved selection and the actually installed set, so retrying after a failed apply runs again. The OpenCode dependency cannot be removed while OpenChamber is selected/installed.
  - `servers: StateFlow<ServersState>` combines the saved server selection, the installed map and the active kind. `setServers` ignores an empty set, persists before setup and queues `RuntimeOperation.ApplyServers` after setup when the installed set differs. `selectServer` only accepts installed kinds; it queues `SelectServer`, which stops the former server, commits/persists the new active kind and starts it. `active` changes only after the old process is stopped, so a new local target is never shown while the old one is trusted.
  - Updates (installed servers and effective agent npm packages, no Alpine re-download):
    - `updates: StateFlow<RuntimeUpdateState>` holds `checking`, `available` (`RuntimeUpdate`, a `VersionChange` for Cox, one `AgentChange` per effective agent, and `servers` for other installed kinds), `lastCheckedAtMs`, `checkFailed`, `lastFailure`.
    - Available = per component the higher of the source (`PackageVersionSource`: GitHub Cox + npm agents/OpenChamber) and the installed version, with the manifest's Cox version as the bundled floor. Only installed servers/agents are checked/offered; no downgrade within Cox. When the installed T3-kind bundle is the legacy T3 Code build (`-pw.N`, or `ANDROID-BUNDLE.json` without `server:"cox"`), the latest Cox release (or the manifest pin) is always offered as a product replacement: the two products' versions are never compared. `RuntimeUpdate.replacesLegacyT3` tells the UI to name both.
    - `checkForUpdates()` runs outside `operationLock` so the network never holds up the runtime. It is ignored while a check runs. Automatic checks run after detect and after install, throttled by `updateCheckIntervalMillis` (6 h). Manual checks ignore the throttle.
    - `update()` → `RuntimeOperation.Update` → `updateLocked()`. It stops a running server, calls `installer.update`, and publishes `Installing(updating = true)` while it runs. Success recomputes `available`. Failure does not publish `Failed`: it sets `lastFailure` and keeps the old environment. The active server restarts only if it was running and no Stop arrived.
    - `ApplyAgents` / `ApplyServers` copy to staging and swap atomically; both verify all effective agents and all installed server assets before the swap. A failure reports `lastFailure`, keeps the previous environment and selection record, and only restarts if allowed.
  - A fresh install/repair installs the selected servers and effective agents; a Cox-only install remains valid, and an OpenChamber-only install needs no `opt/t3`.
- `WorkCounter.kt`: counts queued and running work and publishes `busy = count > 0` under one lock. Decrement uses `invokeOnCompletion`, so a job cancelled before it starts still releases its count.
- `RestartPolicy.kt`: pure backoff budget (2 s, 5 s, 15 s, 30 s). The budget refills only after 120 s of continuous health. A manual start calls `reset()`. The clock is injected.
- `RuntimePaths.kt`: layout under `filesDir/runtime`. `environment/` (rootfs and `metadata.json`), `environment.staging`, `environment.rollback`, `home/` (shared guest /root, survives repair; holds Cox `~/.cox` or a legacy T3 `~/.t3`), `cache`, `logs` (per-kind `t3.log`/`openchamber.log`), `workspace`, `proot-tmp`, `command-suite`, per-kind password files (`ui-password` for the T3 kind/Cox).
- `RuntimeSecrets.kt`: a 32-byte random URL-safe password per server kind, persisted in that kind's file. The T3 kind (Cox) keeps the historical `ui-password`; OpenChamber has its own. The controller passes the active kind's password to its server.
- `SharedStorageContract.kt`: `SharedStorageController`, `SharedStorageState`, and `SharedStorageStatus` (Off, NeedsPermission, Active).
- `UserFacingException.kt`: the typed failure carrying a `MessageKey`.

Invariants:
- Stop sets `cancelRequested`, `stopRequested` and cancels `restartJob` before queuing behind the lock. Maintenance operations never auto-start after a Stop; a manual Start/Install/Repair/Restart/selectServer clears the intent.
- Repair never deletes the live environment first. Install builds in staging, and the swap is the only mutation.
- Every package mutation (install, update, agent/server change) is staged and committed by the same atomic swap; the active rootfs is never mutated directly.
- `active` changes only after the former server is stopped; only the active kind's watchdog/health/start/stop run.
- A cancelled start lands in Stopped or NotInstalled, never in Starting.
- `supportedAbis` is `arm64-v8a` and `x86_64`. Other ABIs report UnsupportedAbi. x86_64 relies on PRoot 5.1.107.98: Android's x86_64 app seccomp policy rejects the raw `SYS_fork` (57), and PRoot rewrites it to `clone(SIGCHLD)` with the child's registers restored. The gate is `docs/testing-x86-runtime.md` (emulator probe, not yet a physical device).

## Flow

1. `initialize()` → `detectLocked()`: ABI check → `installer.recover()` → `installedRuntime()` → NotInstalled, or Stopped with the normalized server map, `refreshInfo`/`adopt` and the effective agent versions.
2. A public action (`install/start/stop/repair/uninstall/restart/update/applyAgents/applyServers/selectServer`) → `ForegroundStarter.request(op)`. The service then calls `perform(op)`, which runs `launch(phase)` under `operationLock`.
3. Start: `startLocked(manual = true)` uses the active kind's `RuntimeServer`/`HealthProbe`, port `kind.port` and per-kind password, sets `wantsRunning`, calls `start`, then publishes Running and schedules the watchdog.
4. Stop: `cancelRequested`/`stopRequested` true, `wantsRunning` false, cancel `restartJob`, then `stopLocked()`: stop the active server and `killOwned()`, then Stopped.
5. Repair: `stopLocked()` → `installLocked(thenStart = true)` with selected servers/effective agents. Uninstall: `stopLocked()` → `installer.removeAll()` → NotInstalled, and server/agent installed maps reset.
5a. Update: `updateLocked()`, described under Design. Only installed servers/agents are targeted; the active environment is never replaced unless copy, server/agent work and verification all pass.
5b. Agent change: `setAgents` persists a non-empty valid set; `ApplyAgents` → `applyAgentsLocked()` copies to staging, applies the effective diff, verifies all agents/servers and swaps. The active rootfs is never mutated directly.
5c. Server change: `setServers` persists a non-empty selection; `ApplyServers` → `applyServersLocked()` copies to staging, adds/removes only server program trees, applies the effective agent set (OpenChamber implies OpenCode), verifies survivors and swaps.
5d. Server switch: `selectServer(kind)` only for installed kinds; `SelectServer` stops the former, sets/persists `active`, then starts the target on its fixed port.
6. Restart: stop and start the active kind as one queued job, used to apply new storage binds.
7. Watchdog (every 5 s while Running): a dead child starts recovery at once. A live child with a failed environment read counts a miss, and 3 consecutive misses start recovery.
8. Recovery: `restartAfterCrash()` loops over the budget: delay, `server.stop()`, `startLocked(manual = false)`. Budget exhaustion is ordered: `stopAfterExhaustion()` runs `server.stop()` and then `killOwned()`, both under `NonCancellable`. Each step runs in its own try, so a throwing `stop()` does not skip `killOwned()`. Errors are logged. Only after both steps does the controller clear `wantsRunning` and publish the terminal Failed. Because the service decides from `busy` and `state`, the foreground service may finish only after that terminal state with no pending work. A cancellation that arrives during cleanup is rethrown after both steps have run. Owned processes are never left behind when the child is still alive at exhaustion.
9. Teardown ordering is covered by `RestartBudgetCleanupTest`, including a user Stop issued from inside the final `stop()`. That Stop runs after the cleanup, and the controller ends Stopped with busy false.

## Integration

- `di/AppModule.kt` builds `DefaultLocalRuntimeController` with `serverFor: (ServerKind) -> RuntimeServer` / `healthFor`, `RuntimeSecrets::uiPassword`, `RuntimeInstallerImpl`, the manifest, `DefaultPackageVersionSource`, and `PrefsAgentSelectionStore`. The controller defaults its server selection to a file-backed store under `runtime/`; DI may inject `PrefsServerSelectionStore`.
- `di/AppModule.kt` wires `ForegroundStarter { op -> RuntimeService.request(context, op) }`, so the service is started only through this lambda. The service runs `perform(op)` and reads `busy`. The UI observes `state`, `logTail`, `info`, `agents`, `servers` and `updates`.
- `data/WebSessionAuthenticatorImpl.kt` implements `WebSessionAuthenticator.prepare(target, cookies)` with `target.kind`; local readiness checks `servers.value.active` and that kind's `readyLaunchId()`.
- Live DNS: `ProotRuntimeServer(kind=...)` receives `DnsRefresherMonitor(...)` from `di/AppModule.kt`. The monitor starts after readiness and stops before teardown. See `network/codemap.md`.
- Watchdog probes: `healthFor(active)` (`HealthProbe.loopback(kind)`, GET the kind's health path) and `serverFor(active).isAlive()`.
- Subfolders:
  - `fs/`: symlink-safe tree operations.
  - `install/`: build, verify, and atomic environment swap.
  - `manifest/`: reads `assets/runtime-manifest.json`.
  - `network/`: guest `resolv.conf`.
  - `process/`: PRoot launch, readiness, and process ownership.
  - `storage/`: shared-storage binds and git config.
