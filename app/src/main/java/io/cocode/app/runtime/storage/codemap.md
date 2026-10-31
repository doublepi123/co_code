# app/src/main/java/io/cocode/app/runtime/storage/

## Responsibility

The optional shared-storage feature. Projects and the agent config folders can live in a public folder `/storage/emulated/0/CoCode` that is visible to a file manager or USB. This package decides which folders are bound into the guest for each server start, prepares the shared folders, migrates private config once per agent, and writes the git configuration that FUSE storage needs.

## Design

- `SharedStorageDecision` (pure object):
  - `status(enabled, permissionGranted, sharedRootUsable)`: Off unless enabled. NeedsPermission when enabled without access or a usable root. Active only when all hold.
  - `binds(status, home, privateWorkspace, hostRoot, privateExceptionsDir, fullDirectoryConsent)`:
    - Active + consent → `GuestBinds.shared`: shared projects, private workspace, whole shared OpenCode folder over `/root/.config/opencode`, whole shared Codex folder over `/root/.codex`, whole shared Claude folder over `/root/.claude`, then the OpenCode private exceptions.
    - Active without consent (legacy enabled user) → the same projects/OpenCode binds but no Codex/Claude binds. `SharedStorageState.needsConsent` is true.
    - Off and NeedsPermission → `GuestBinds.privateWithOpencodeExceptions`: the private workspace plus the same OpenCode private service/node_modules binds. The OpenCode secret files therefore stay canonical across toggles.
  - `prepareOpencodePrivate(...)`: creates `service.json`, `service.json.tmp` and, when needed, the canonical `node_modules` directory in `home-private/opencode`; adopts a non-empty private `service.json`, or a newer one when the user last ran with shared storage off. For `node_modules`, a non-empty canonical tree wins; otherwise an existing legacy `~/.config/opencode/node_modules` stays in place and becomes the bind source. It is never moved or rewritten, so internal absolute symlinks (including pnpm `.l2s`) keep resolving. Never overwrites existing content.
  - `restartRequired(started, current)`: true when a server is running with a different (status, bind list) pair.
- `GuestBinds` (data class): ordered `-b` specs. Fixed order: (1) private home over `/root`, (2) `projects` (shared or private), (3) `projects-private` (Active only), (4) shared config directories: opencode, codex, claude, (5) OpenCode private exceptions nested on top of the OpenCode directory.
- `SharedStorageLayout` (object): guest and host constants, `planMigration`/`applyMigration`/`recoverPending`, per-agent policies, `ensureOpencodeMinimal`/`ensureCodexMinimal`/`ensureClaudeMinimal`, `readme`, `mountPointsFor`, and the OpenCode private-exception builder. Migration is a pure plan with `Copy`/`Skip` steps. Rules: no symlinks, no overwrite, OpenCode skips its secrets; Codex/Claude migrate every regular file. Migration is recoverable, not an atomic-visibility claim: an app-private snapshot and a durable journal are written first, the public target is created with `CREATE_NEW` and copied from the snapshot through the same channel, then its SHA-256 is re-read and compared. Recovery runs before `target.exists` and before the agent marker: matching complete targets finalize, missing targets retry from the snapshot, partial/mismatching targets are explicit conflicts that keep source+snapshot+journal.
- `SharedStorageControllerImpl`: owns the state flow and `startedBinds` (status plus spec list of the running server). Persists a full-directory consent schema version (`SharedStorageLayout.FULL_DIRECTORY_CONSENT_VERSION`, prefs key `full_directory_consent_version`); only `setEnabledWithFullDirectoryConsent(true)` grants it. Without consent an enabled legacy user keeps projects/OpenCode and `state.needsConsent` is true. `prepareForStart()` prepares shared folders (Active only), prepares the OpenCode private placeholders in every mode, then returns the binds. `recordStarted`/`recordStopped` maintain `restartRequired`. `SharedStoragePrefs` and `SharedStoragePermission` are injected.
- `SharedStorageAndroid.kt`: `PrefsSharedStoragePrefs` (SharedPreferences, file `cocode_shared_storage`; `enabled` and `full_directory_consent_version`). `SharedStorageAccess` covers all-files access and the settings intents.
- `GitConfigBlock` (pure): the managed block between `# >>> CoCode (managed, do not edit) >>>` and `# <<< CoCode <<<`. `GitConfigWriter` writes the FUSE-friendly gitconfig and the managed `.gitconfig` in the home.

## Shared layout

Host `/storage/emulated/0/CoCode`:

| Host | Guest | Content |
| --- | --- | --- |
| `projects/` | `~/projects` | projects; `~/projects-private` is the private workspace |
| `opencode/` | `~/.config/opencode` | whole OpenCode config folder; `service.json`, its `.tmp`, and `node_modules` are nested private binds |
| `codex/` | `~/.codex` | **whole** Codex folder, including `config.toml`, `auth.json` login tokens, `sessions/`, `archived_sessions/`, `history.jsonl`, `log/`, `sqlite/`, `shell_snapshots/`, `memories/`, `installation_id` and `session_index.jsonl` |
| `claude/` | `~/.claude` | **whole** Claude folder, including `settings.json`, `CLAUDE.md`, `.credentials.json` login tokens, `projects/` transcripts, `history.jsonl`, `sessions/`, `file-history/`, `todos/`, `shell-snapshots/`, `debug/`, `paste-cache/`, `session-env/`, `stats-cache.json`, `backups/`, `telemetry/` and the other runtime-state folders |

`~/.claude.json` is outside `~/.claude` and is **not** mapped; it stays private.

The Codex and Claude folders are shared in full only after the user confirms the current full-directory consent. Their writers use temporary-file + rename, so a single-file bind or nested file bind would break atomic saves. The accepted tradeoff is that shared storage contains live login tokens and session history; Android/FUSE permissions are not a secret boundary.

## Consent

- Persisted as `full_directory_consent_version` in `cocode_shared_storage`, default `0`; current schema version is `SharedStorageLayout.FULL_DIRECTORY_CONSENT_VERSION` (`1`).
- `setEnabled(true)` never grants consent. Only the UI confirm flow calls `setEnabledWithFullDirectoryConsent(true)`, which stores the current version and enables.
- An existing app user with `enabled=true` and no current consent keeps the legacy projects/OpenCode mapping, gets no Codex/Claude migration or bind, and sees `SharedStorageState.needsConsent = true`.
- Disabling does not clear the stored consent. Re-enabling through `setEnabled(true)` after an earlier explicit consent is full-directory.

## Migration

- OpenCode: one-time copy from `~/.config/opencode` to `opencode/`, skipping `service.json`, `service.json.tmp`, `node_modules`, symlinks, empty placeholders and existing shared files. Marker `home-private/opencode/.migrated-to-shared`, version `1`.
- Codex: copies **all regular files**, including auth/history/sessions/sqlite, from `~/.codex` to `codex/` (symlinks skipped, existing shared files not replaced). Marker `home-private/codex/.migrated-to-shared`, version `2`. A version-1 marker from the earlier config-only policy is upgraded once.
- Claude: copies **all regular files**, including `.credentials.json`/settings/transcripts, from `~/.claude` to `claude/` (symlinks skipped, existing shared files not replaced). Marker `home-private/claude/.migrated-to-shared`, version `2`.
- Recoverable publication (not an atomic-visibility claim): an app-private snapshot + durable journal (`target` relative path, length, SHA-256) are written first; the public target is created with `CREATE_NEW` and copied from that snapshot through the same channel, then its actual SHA-256 is re-read and compared. `EEXIST` preserves the public file and revokes this pending snapshot/journal. `recoverPending` runs before `target.exists` and before the agent marker: matching complete targets finalize, missing targets retry, partial/mismatching targets are explicit conflicts that keep source+snapshot+journal. Journal directories are `home-private/<agent>/.migration-journal`; journal/logs never contain credential text.
- Originals are never deleted. Markers are idempotent; a newer policy version copies only files still missing in shared.

## On/off behavior

- Full Active: the agents read and write the public trees. New files, including auth token refreshes and session logs, land in `/sdcard/CoCode/{codex,claude}`.
- Legacy Active without consent: Codex and Claude remain private; projects/OpenCode stay shared as before.
- Off / permission lost: the guest sees the private `~/.codex` and `~/.claude` again. These are the pre-Active copies and may be stale.
- The app **does not auto copy public changes back** into the private home, because a full merge needs conflict handling and a UI decision. The public data remains in `CoCode`; enabling shared storage again makes it live. Disabling must be presented in the UI as a switch between two diverged copies, not as a lossless merge.
- OpenCode is the exception: `service.json`/`service.json.tmp` stay in `home-private/opencode`; `node_modules` is bound from the non-empty canonical tree, or from the stable legacy `~/.config/opencode/node_modules` when the canonical tree is empty. Internal absolute symlinks are never rewritten. A non-empty canonical target is never overwritten.

## Invariants

- Shared storage is a convenience. If preparing it fails, the start falls back to private storage (`NeedsPermission`).
- Codex/Claude are never migrated or bound without the current full-directory consent version.
- `service.json`, `service.json.tmp`, `node_modules`, `~/.local/share/opencode` and `~/.claude.json` are never placed in shared storage.
- The config-directory binds come after the parent home bind and before nested OpenCode exceptions.
- `README.txt` prominently states that Codex/Claude auth tokens and histories are public in this folder and that FUSE permissions are not a secret boundary (full mode). Legacy mode states that Codex/Claude remain private until consent.

## Flow

`SharedStorageControllerImpl.prepareForStart()` (every server start, before spawn):
1. `decideStatus()`: `prefs.enabled()`, `permission.granted()`, and `sharedRootUsable()`.
2. Read `fullDirectoryConsent = prefs.fullDirectoryConsentVersion() >= FULL_DIRECTORY_CONSENT_VERSION`.
3. If Active: `prepareShared(fullDirectoryConsent)`:
   - mkdirs `hostRoot/projects`.
   - Always prepare/migrate OpenCode.
   - Only with consent: prepare/migrate Codex and Claude (marker per agent, journaled recoverable copy).
   - Write `README.txt` in full or legacy form.
   - On failure, log, then fall back to `NeedsPermission`.
4. `prepareOpencodePrivate(...)` in every mode.
5. `bindsFor(status, fullDirectoryConsent)` → `SharedStorageDecision.binds`.
6. `GitConfigWriter.apply(home)`; failures are logged only.
7. `ensureMountPoints(binds)`: mkdirs `home/projects`, plus the config dirs the binds target.
8. Return `GuestBinds`. The server calls `onStarted(binds)` → `recordStarted`, which stores `(status, specs)` and refreshes the state flow. `onStopped` → `recordStopped`.
9. `state.needsConsent` is true only when Active without current consent (legacy mode).

## Integration

- Consumers: `runtime/process/ProotCommands.kt` (`GuestBinds.privateOnly` default and `serverCommand(binds)`), the runtime server's `bindsForStart`/`onStarted`/`onStopped`, and `runtime/SharedStorageContract.kt`.
- Wiring: `di/AppModule.kt` constructs `SharedStorageControllerImpl` with `PrefsSharedStoragePrefs.open(context)` and `hostRoot` (`Environment.getExternalStorageDirectory()/CoCode`).
- Git: `GitConfigWriter` writes into the persistent guest home (`runtime/home` = guest `/root`).
- External contracts:
  - Host layout: `/storage/emulated/0/CoCode/{projects/, opencode/, codex/, claude/, README.txt}`.
  - Guest paths: `/root/projects`, `/root/projects-private`, `/root/.config/opencode`, `/root/.codex`, `/root/.claude`.
  - Git: `/root/.config/cocode/git/shared-storage.gitconfig` and the managed block in `/root/.gitconfig`.
  - Android: `Environment.isExternalStorageManager()` and `ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION` on API 30+, runtime `READ/WRITE_EXTERNAL_STORAGE` on API 26–29.

## Tests

- JVM: `app/src/test/java/io/cocode/app/runtime/storage/SharedStorageTest.kt` covers bind order/paths, full-directory Codex/Claude binds only with consent, legacy no-consent mode and `needsConsent`, all-content migration including synthetic auth/history/transcript files, failure/abandoned temp handling, true no-replace raced-target publication (host hard-link publisher injection), no overwrite, symlink skip, marker version upgrade, OpenCode node_modules stable-source selection with internal absolute symlinks and no valid-target overwrite, on/off declared divergence, and README warnings.
- Device: `SharedStorageGuestBindTest.kt` runs the real PRoot with the installed rootfs against a fresh `.storage-test-*` directory directly under `/sdcard/CoCode`, and checks `config.toml` + `auth.json` and `settings.json` + randomly named `.credentials.json.<hex>.tmp` all survive their agents' atomic temp+rename in the real shared directories. It requires all-files access and the runtime/rootfs; it fails rather than skips.
- Device migration: `SharedStorageMigrationDeviceTest.kt` validates the journaled recovery on real FUSE: complete copy, existing final wins, raced target not overwritten, partial target conflicts instead of being accepted, completed pending finalizes, missing target retries, and a non-durable journal fails before the public target. It needs all-files access and fails rather than skips.
