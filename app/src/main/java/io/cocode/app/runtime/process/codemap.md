# app/src/main/java/io/cocode/app/runtime/process/

## Responsibility

Runs, probes and stops one of the two supported web servers (Cox or OpenChamber) inside PRoot. Cox keeps the T3 Code HTTP protocol, so a still-installed legacy T3 Code bundle runs through the same paths. Covers the PRoot command lines and guest environment of each kind, the legacy `.t3`→`.cox` data move, readiness and identity checks, and process ownership. Only one kind is active at a time; switching kinds stops the previous server. A process is only ever signalled when it can be proven to belong to this app's runtime.

Both kinds use the same `RuntimePaths.home` and therefore the same guest `/root`: OpenCode config, sessions and agent credentials are shared. Cox's own state lives in `~/.cox`; while a legacy T3 Code bundle is installed it still uses `~/.t3`. Ports, UI passwords, logs and health documents are per kind.

## Design

- `RuntimeServer` (interface): `start(runtime, uiPassword, onLog, isCancelled): Long` (launch id), `stop()`, `isAlive()`, `readyLaunchId(): Long?`.
- `ProotRuntimeServer`: the implementation for a `ServerKind` (constructor default `T3`, whose on-device product is Cox). It has a single `lock` guarding `process`, `launchId`, `readyId`, `listenSignal` and `recentOutput`. Launch ids are monotonic. A launch is trusted only while `readyId` matches. The port is always `kind.port` (Cox 3773, OpenChamber 3000), never `runtime.metadata.port`. Before a Cox start it runs `CoxHomeMigration.prepare`; the bundle identity (`ANDROID-BUNDLE.json` `server`) selects `COX_HOME` vs the legacy `T3CODE_HOME`.
- `ServerKind` (`runtime/ServerKind.kt`): `T3` / `OpenChamber`, stable ids, display names (Cox), fixed ports and `localTargetId`.
- `Readiness` (pure object): trust order is (1) the child printed its kind's exact listen line, (2) the kind's health document answered with a stable identity, and for OpenChamber `isOpenCodeReady` true. A reported OpenCode failure (`openCodeRunning == false` plus `lastOpenCodeError`) fails immediately; anything else keeps waiting until the 180 s deadline.
  - T3 protocol marker (Cox and legacy T3 Code alike): `Listening on http://127.0.0.1:<port>`; identity: `environmentId`.
  - OpenChamber marker: `OpenChamber server listening on 127.0.0.1:<port>`; identity: `serverId`.
- `HealthProbe` (`fun interface`): `read(port): HealthReport?`. `loopback(kind)` does GET on that kind's path (connect 2 s, read 5 s, never follows redirects). `parse(body, kind)` is pure and preserves the T3 protocol's `environmentId`/`serverVersion` shape (Cox answers it unchanged). OpenChamber reports `status`, `serverId`, `isOpenCodeReady`, `openCodeRunning`, `lastOpenCodeError`. `HealthReport.ready` is kind-specific; `isPortFree` tests for a refused connection.
- `OpenChamberPolicy`: before an OpenChamber launch, writes `rootfs/etc/openchamber/policy.json` atomically with `{"opencodeBinary":"/usr/local/bin/opencode"}`. This pins the guest OpenCode and makes OpenChamber's upgrade route refuse (409). The write never follows a symlink.
- `ProcessScanner` and `OwnedProcess` (`ProcessTree.kt`): the only place that scans `/proc`. Ownership needs all of: real uid equals the app uid, `argv[0]` equals the PRoot launcher path, and argv contains the rootfs path. Kills recheck the start time (`/proc/<pid>/stat` field 22) right before signalling, so a recycled pid is never signalled. `treePostOrder` lists children first. Only nodes that pass the uid check are included.
- `terminateProcessTree(process, scanner, roots, signal)`: `destroy()`, wait 750 ms, kill owned tree, then `destroyForcibly()` if still alive.
- `StaleServerCleanup.kt`: `ProotStaleServerCleaner` kills owned runtime processes, then waits up to 5 s for the port to be free. Neither server uses a pid file this app can trust, so cleanup is owned-process-only; a foreign listener is never touched and the start refuses with `PortInUseException`. `killOwnedRuntime` is shared with Stop, Repair and Uninstall.
- `ProotCommands` (pure builders): `prootArguments`, `guestShellCommand` (installer, `/bin/sh -lc`), `serverCommand(kind)`, `serverEnvironment(kind, coxBundle)`, `installEnvironment`.
  - Cox: `node /usr/local/lib/node_modules/@doublepi123/cox/dist/bin.mjs`, `T3CODE_*` host/port env, `COX_HOME=/root/.cox`, `COX_AUTH_PASSWORD`, `IS_SANDBOX=1`. The identical password is also passed as `T3CODE_AUTH_PASSWORD` for older Cox versions. Existing bundles still launch `/opt/t3/bin.mjs`; a legacy T3 bundle gets only `T3CODE_AUTH_PASSWORD` and `T3CODE_HOME=/root/.t3`, with no Cox-specific variables. OpenChamber and installer/login environments receive neither Cox nor T3 passwords.
  - OpenChamber: `node /usr/local/lib/node_modules/@openchamber/web/bin/cli.js serve --foreground --port 3000`, `OPENCHAMBER_*` env, `OPENCHAMBER_UI_PASSWORD`, `OPENCHAMBER_RELAY_HOST=off`, pinned `OPENCHAMBER_OPENCODE_BIN`, OpenCode health timeout 15 s. No T3 variable and no desktop-notification flag is present.
  - Both: guest base env, `OPENCODE_DISABLE_AUTOUPDATE`, `NODE_OPTIONS=--require /opt/cocode/android-shim.cjs`.
- `EmbeddedCommandSuite`: resolves the PRoot binaries from `nativeLibraryDirectory`. Names: `libcocode_android_proot.so`, `libcocode_android_proot_loader.so`, `libcocode_android_proot_loader32.so`. Also creates `command-suite/{home,tmp}`.
- `truncateLogFile`: keeps the newest half of an oversized log, preferring a line boundary, using a bounded tail read rather than loading the whole file. The output pump also uses `BoundedLogWriter` to cap bytes on every append.
- DNS in this package has two hooks, both injected:
  - `refreshDns(runtime)`: a synchronous rewrite of the guest `resolv.conf` before each launch. It is the only DNS write before readiness.
  - `dnsMonitor: RuntimeDnsMonitor` (default `None`; production passes `DnsRefresherMonitor`): live refresh while the child runs. It starts after readiness and stops at the top of `stop()`. The monitor's own generation and sequence guards live in `network/`. This class only owns the start and stop ordering.

Invariants:
- Port ownership is decided before spawning. If the port stays held by a foreign listener, the launch refuses (`PortInUseException`), and the password is never sent to it.
- The password only reaches the child env, never a log line. Each kind has its own password file (`runtime/ui-password`, `runtime/openchamber-ui-password`).
- Every line of the child's output goes to the bounded diagnostic tail and the kind's log (`logs/t3.log`, `logs/openchamber.log`). There is no notification protocol in either stream.
- The output pump clears `readyId` as soon as the child's output ends. `readyLaunchId()` only reads that id. Stop and child death update it under the server lock.

## Flow

Start (`ProotRuntimeServer.start`):
1. `stop()` first, so no previous child remains.
2. `prootPath` from the suite. `cleanBeforeStart(kind.port, prootPath)` → `killOwnedRuntime` → wait for the port. Failure throws `PortInUseException`.
3. `refreshDns(runtime)`, `suite()`, truncate the kind's log to 1 MB, mkdir logs, workspace and proot-tmp.
4. T3 kind only: detect the bundle identity. Cox runs `CoxHomeMigration.prepare` (rename `home/.t3` to `home/.cox` only when `.cox` is absent; never merge) and then `T3ProviderDefaults.apply(..., ".cox")`; a legacy bundle uses `".t3"`. OpenChamber only: `OpenChamberPolicy.write(runtime.rootfs)`.
5. `bindsForStart()` → `GuestBinds`. `ProotCommands.serverCommand(kind = kind)`.
6. `serverEnvironment(..., port = kind.port, kind = kind)`. `spawn(command, env, paths.root)`.
7. Store `process`, reset `recentOutput`, set `readyId = null`, `listenSignal = new`, `++launchId`.
8. `pumpOutput` thread: reads lines from the child's stdout, sends them to `BoundedLogWriter`, keeps the last 200 lines, and completes `listenSignal` on the kind's listen marker.
9. `waitUntilReady` (deadline 180 s, poll 500 ms): cancel check, child death check (`EADDRINUSE` maps to PortInUse), wait for the listen signal, then poll the kind's health document with stable identity and `Readiness.verdict`.
10. On failure: `stop()` and rethrow. On success: if still the current child, `readyId = id`, then `dnsMonitor.start(runtime.rootfs)` (failures are swallowed here; the monitor reports them), then `onStarted(binds)`. If `onStarted` throws, `stop()` runs and the error is rethrown, so the monitor is always released.

Stop (`stop`):
1. Clear `readyId` and detach the process under the lock. A pump from an older launch does not clear the replacement.
2. `dnsMonitor.stop()` (failures swallowed), so no live DNS write lands after teardown begins.
3. `ownedRoots(prootPath, rootfs)` → `terminateProcessTree`.
4. `onStopped()` (clears shared-storage `startedBinds`).

Command (argv, from `ProotCommands`):
- Cox: `proot --kill-on-exit --link2symlink -0 -r <rootfs> -b /dev -b /proc -b /sys -b /system -b <binds...> -w /root /usr/bin/node /usr/local/lib/node_modules/@doublepi123/cox/dist/bin.mjs`. Existing bundles use `/opt/t3/bin.mjs` until migrated.
- OpenChamber: `proot ... -w /root /usr/bin/node /usr/local/lib/node_modules/@openchamber/web/bin/cli.js serve --foreground --port 3000`

## Integration

- Consumers: `runtime/LocalRuntimeController.kt` (`start`, `stop`, `isAlive`, `killOwned`); `runtime/install/RuntimeEnvironmentInstaller.kt` (`ProotCommands`, `ProcessScanner`, `terminateProcessTree`); `data/WebSessionAuthenticatorImpl.kt` (`readyLaunchId(kind)` via `LocalReadiness`).
- Wiring: `di/AppModule.kt` builds a `RuntimeServer` per `ServerKind`.
- Storage: `ProotCommands.serverCommand` takes `storage/GuestBinds`.
- External contracts:
  - T3 protocol health (Cox and legacy T3 Code): `GET /.well-known/t3/environment` returns JSON with `environmentId` (stable identity) and `serverVersion`.
  - T3 protocol listen line: `Listening on http://127.0.0.1:<port>` (pino-style line in the child's output).
  - OpenChamber health: `GET /health` returns `status`, `serverId`, `isOpenCodeReady`, `openCodeRunning`, `lastOpenCodeError`.
  - OpenChamber listen line: `OpenChamber server listening on 127.0.0.1:<port>`.
  - OpenChamber policy: `rootfs/etc/openchamber/policy.json` pins `opencodeBinary`.
  - Exit marker: `EADDRINUSE` in output.
