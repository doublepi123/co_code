# app/src/main/java/io/cocode/app/runtime/process/

## Responsibility

Launch, probe and stop the owned Cox process inside PRoot. Preserve legacy T3 bundle/data compatibility, protect loopback identity and secrets, and expose installed agent providers through Cox settings.

## Design

- `RuntimeServer` / `ProotRuntimeServer`: single owned process and monotonic launch/readiness ids under a lock. Clean stale owned children before spawning; refuse a foreign listener without sending credentials. Port 3773 comes from `ServerKind.T3`, not untrusted metadata.
- `ProotCommands`: builds PRoot argv/binds and sanitized guest environments. Npm Cox launches `dist/bin.mjs`; existing legacy bundles can still launch `/opt/t3/bin.mjs` until repaired/updated. Cox receives `COX_HOME`, `COX_AUTH_PASSWORD` and matching historical T3 password; legacy T3 receives only historical variables. Installer environments receive no server secrets.
- `CoxHomeMigration`: one-time `.t3` to `.cox` directory move; never merge or overwrite an existing Cox home.
- `T3ProviderDefaults`: defaults to `.cox/userdata/settings.json`. Installed Codex, Claude, OpenCode, Grok, Pi and bundled Cursor get missing modern `{driver, enabled, config}` provider instances. Antigravity, ACP Registry (`acpRegistry`), Fx and Muse get opt-in disabled entries reachable from Cox settings; no npm packages are invented for them. Explicit disabled/configured or renamed instances are preserved, and malformed settings are untouched. Legacy T3 home receives only its historical OpenCode default, never unsupported Cox drivers.
- `HealthProbe`: unauthenticated `/.well-known/t3/environment` on loopback, bounded timeouts, no redirects, `environmentId` and optional `serverVersion` parsing.
- `Readiness`: trust only the child's private listen marker followed by ready health and stable identity. Foreign/stale responses never establish an owned launch.
- `ProcessTree` / `StaleServerCleanup`: prove uid, launcher path, rootfs argv and start time before signalling; terminate descendants before parents. Foreign processes are never cleanup targets.
- `EmbeddedCommandSuite`: native PRoot/loader paths and command-suite home/temp preparation.
- Runtime output uses bounded diagnostic tails and historical `logs/t3.log`; secrets are not command arguments or log output.
- DNS refresh is injected before start; live monitors begin only after readiness and stop with the owned child.

## Provider Limits

Cox owns authentication and provider-specific maintenance. Cursor SDK needs no separate CLI. Antigravity uses Cox's archive/harness installer; ACP Registry can use binary, npm or uv distributions. Fx uses its own installer and Muse its launcher/manual installation, not generic npm. Their upstream native dependencies may be incompatible with Android/Alpine/PRoot: exposing a provider entry does not certify installation or paid inference. Fresh/staged Cox verification performs only offline SDK and native smoke checks.
