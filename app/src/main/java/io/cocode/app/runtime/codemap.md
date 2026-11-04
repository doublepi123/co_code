# app/src/main/java/io/cocode/app/runtime/

## Responsibility

Runs Cox and its selected agents inside a shared Alpine/Node environment under PRoot. Owns installation, repair, updates, crash recovery, process ownership, DNS, shared storage and lifecycle contracts. Cox retains `ServerKind.T3`, the saved `t3` identity, port 3773 and T3 Code HTTP authentication protocol.

## Design

- `RuntimeContract.kt` defines runtime states, failure phases, operations, messages, server targets, updates and profile-scoped web session capabilities. `RemoteServerOrder.kt` supplies stable list operations.
- `LocalRuntimeController.kt` serializes operations and recovery under one mutex. Its work counter includes queued operations; cancellation generation tokens reject stale starts. Watchdog recovery respects explicit running intent and the restart budget.
- `AgentSelection.kt` separates available specs, requested selection and verified installed versions. Empty/unknown selections are ignored. Default agent is OpenCode; Cox requires no implicit CLI dependency.
- `ServerKind.kt` contains only `T3` (Cox). `ServersState`, selection stores and controller selection APIs remain for compatibility. Saved unknown/retired server choices sanitize to Cox, never to an unsupported enum.
- `RuntimePaths.kt` keeps `environment/rootfs` and metadata together for atomic activation. `environment.staging` and `environment.rollback` implement the swap protocol. `home`, `workspace`, logs, cache, secrets and command-suite directories live outside the replaceable environment.
- `RuntimeSecrets.kt` preserves the existing app-private `ui-password`. The Cox log retains the historical `t3.log` name.
- `RestartPolicy.kt` and `WorkCounter.kt` are pure helpers. Terminal state publication clears running intent where required; DNS has separate generation guards.

## Flow

1. Detect supported ABI, recover an interrupted environment swap, normalize symlinks and inspect verified installed assets.
2. If no Cox/legacy T3 assets are usable, publish `NotInstalled`. The old environment and user directories are not discarded. A retired-only installation can therefore install Cox through the regular staged flow.
3. Install/repair downloads the pinned Alpine rootfs, installs Cox and the selected agents, verifies the server, native modules and offline Cursor SDK, writes metadata, then atomically swaps environments.
4. Update/apply operations copy the active environment, mutate only staging, verify survivors, write metadata and use the same activation protocol. Retired server program files are removed only from staging.
5. Start protects port ownership, prepares Cox home, merges missing provider defaults, launches the owned PRoot child, and trusts health only after its private listen marker and stable identity.
6. Stop cancels recovery and terminates only owned processes. Repair preserves home/workspace; explicit full uninstall retains its existing separate destructive contract.

## Integration

- `manifest/`: npm CLI specs plus explicitly bundled Cursor SDK; no fake npm packages for other Cox drivers.
- `install/`: staged package selection, asset checks, metadata and atomic rollback.
- `process/`: Cox commands, data migration, provider-instance defaults, readiness and ownership.
- `storage/`, `network/`, `fs/`: persistent user data bindings, DNS and no-follow filesystem helpers.
- UI, service and DI consume these runtime contracts; provider authentication and non-npm tool installation remain in Cox's own web settings.
