# app/src/main/java/io/cocode/app/runtime/process/

## Responsibility

Runs, probes and stops the T3 Code server inside PRoot. Covers the PRoot command lines and guest environment, readiness and identity checks, and process ownership. A process is only ever signalled when it can be proven to belong to this app's runtime.

## Design

- `T3Server` (interface): `start(runtime, uiPassword, onLog, isCancelled): Long` (launch id), `stop()`, `isAlive()`, `readyLaunchId(): Long?`.
- `ProotT3Server`: the implementation. It has a single `lock` guarding `process`, `launchId`, `readyId`, `listenSignal` and `recentOutput`. Launch ids are monotonic. A launch is trusted only while `readyId` matches.
- `Readiness` (pure object): trust order is (1) the child printed `Listening on http://127.0.0.1:<port>`, (2) the `/.well-known/t3/environment` document answered with an `environmentId`, and `identityStable` requires that id to stay the same across polls. Anything else keeps waiting until the 180 s deadline.
- `HealthProbe` (`fun interface`): `read(port): HealthReport?`. `Loopback` does GET `http://127.0.0.1:<port>/.well-known/t3/environment` (connect 2 s, read 5 s). `parse` is pure. `HealthReport.serverOk` requires a non-blank `environmentId`. `isPortFree` tests for a refused connection.
- `ProcessScanner` and `OwnedProcess` (`ProcessTree.kt`): the only place that scans `/proc`. Ownership needs all of: real uid equals the app uid, `argv[0]` equals the PRoot launcher path, and argv contains the rootfs path. Kills recheck the start time (`/proc/<pid>/stat` field 22) right before signalling, so a recycled pid is never signalled. `treePostOrder` lists children first. Only nodes that pass the uid check are included.
- `terminateProcessTree(process, scanner, roots, signal)`: `destroy()`, wait 750 ms, kill owned tree, then `destroyForcibly()` if still alive.
- `StaleServerCleanup.kt`: `ProotStaleServerCleaner` kills owned runtime processes, then waits up to 5 s for the port to be free. T3 Code does not use a pid file this app can trust, so cleanup is owned-process-only; a foreign listener is never touched and the start refuses with `PortInUseException`. `killOwnedRuntime` is shared with Stop, Repair and Uninstall.
- `ProotCommands` (pure builders): `prootArguments`, `guestShellCommand` (installer, `/bin/sh -lc`), `serverCommand` (server, `-w /root`), `serverEnvironment`, `installEnvironment`.
- `EmbeddedCommandSuite`: resolves the PRoot binaries from `nativeLibraryDirectory`. Names: `libcocode_android_proot.so`, `libcocode_android_proot_loader.so`, `libcocode_android_proot_loader32.so`. Also creates `command-suite/{home,tmp}`.
- `truncateLogFile`: keeps the newest half of an oversized log, preferring a line boundary, using a bounded tail read rather than loading the whole file. The output pump also uses `BoundedLogWriter` to cap bytes on every append.
- DNS in this package has two hooks, both injected:
  - `refreshDns(runtime)`: a synchronous rewrite of the guest `resolv.conf` before each launch. It is the only DNS write before readiness.
  - `dnsMonitor: RuntimeDnsMonitor` (default `None`; production passes `DnsRefresherMonitor`): live refresh while the child runs. It starts after readiness and stops at the top of `stop()`. The monitor's own generation and sequence guards live in `network/`. This class only owns the start and stop ordering.

Invariants:
- Port ownership is decided before spawning. If the port stays held by a foreign listener, the launch refuses (`PortInUseException`), and the password is never sent to it.
- The password only reaches the child env, never a log line.
- Every line of the child's output goes to the bounded diagnostic tail and the log. There is no notification protocol in the T3 stream.
- The output pump clears `readyId` as soon as the child's output ends. `readyLaunchId()` only reads that id. Stop and child death update it under the server lock.

## Flow

Start (`ProotT3Server.start`):
1. `stop()` first, so no previous child remains.
2. `prootPath` from the suite. `cleanBeforeStart(port, prootPath)` → `killOwnedRuntime` → wait for the port. Failure throws `PortInUseException`.
3. `refreshDns(runtime)`, `suite()`, truncate `t3.log` to 1 MB, mkdir logs, workspace and proot-tmp.
4. `bindsForStart()` → `GuestBinds`. `ProotCommands.serverCommand(proot, rootfs, binds, [])`.
5. `serverEnvironment(suite.env, prootTmp, port, uiPassword)`. `spawn(command, env, paths.root)`.
6. Store `process`, reset `recentOutput`, set `readyId = null`, `listenSignal = new`, `++launchId`.
7. `pumpOutput` thread: reads lines from the child's stdout, sends them to `BoundedLogWriter`, keeps the last 200 lines, and completes `listenSignal` on the listen marker. `truncateLogFile` retains the newest half of an oversized log.
8. `waitUntilReady` (deadline 180 s, poll 500 ms): cancel check, child death check (`EADDRINUSE` maps to PortInUse), wait for the listen signal, then poll the environment document with `identityStable` and `Readiness.verdict`.
9. On failure: `stop()` and rethrow. On success: if still the current child, `readyId = id`, then `dnsMonitor.start(runtime.rootfs)` (failures are swallowed here; the monitor reports them), then `onStarted(binds)`. If `onStarted` throws, `stop()` runs and the error is rethrown, so the monitor is always released.

Stop (`stop`):
1. Clear `readyId` and detach the process under the lock. A pump from an older launch does not clear the replacement.
2. `dnsMonitor.stop()` (failures swallowed), so no live DNS write lands after teardown begins.
3. `ownedRoots(prootPath, rootfs)` → `terminateProcessTree`.
4. `onStopped()` (clears shared-storage `startedBinds`).

Command (argv, from `ProotCommands`):
`proot --kill-on-exit --link2symlink -0 -r <rootfs> -b /dev -b /proc -b /sys -b /system -b <binds...> -w /root /usr/bin/node /opt/t3/bin.mjs`

Environment (cleared, then set): `HOME=/root`, `PATH` (guest), `LANG=C.UTF-8`, XDG_* under `/root`, `PROOT_TMP_DIR`, `PROOT_LOADER`, `PROOT_LOADER_32`, `LD_LIBRARY_PATH`, `T3CODE_HOST=127.0.0.1`, `T3CODE_PORT=<port>`, `T3CODE_NO_BROWSER=true`, `T3CODE_HOME=/root/.t3`, `T3CODE_AUTH_PASSWORD=<ui password>`, `OPENCODE_DISABLE_AUTOUPDATE=true`, `NODE_OPTIONS=--require /opt/cocode/android-shim.cjs`.

## Integration

- Consumers: `runtime/LocalRuntimeController.kt` (`start`, `stop`, `isAlive`, `killOwned`); `runtime/install/RuntimeEnvironmentInstaller.kt` (`ProotCommands`, `ProcessScanner`, `terminateProcessTree`); `data/WebSessionAuthenticatorImpl.kt` (`readyLaunchId` via `LocalReadiness`).
- Wiring: `di/AppModule.kt` builds `ProotT3Server`, `ProotStaleServerCleaner`, and the `killOwnedRuntime` kill hook.
- Storage: `ProotCommands.serverCommand` takes `storage/GuestBinds`.
- External contracts:
  - Health: `GET /.well-known/t3/environment` returns JSON with `environmentId` (stable identity) and `serverVersion`.
  - Listen line: `Listening on http://127.0.0.1:<port>` (pino-style line in the child's output).
  - Exit marker: `EADDRINUSE` in output.
