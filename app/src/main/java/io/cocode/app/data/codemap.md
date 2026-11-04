# app/src/main/java/io/cocode/app/data/

## Responsibility

Network and persistence adapters for remote and local Cox / T3 Code servers, used by the UI:

- `WebSessionAuthenticatorImpl.kt`: prepares a server for the WebView (health check, login, cookie hand-off). Implements `runtime.WebSessionAuthenticator`. Also defines `LocalReadiness`.
- `WebSessionOrigin.kt`, `WebSessionProfiles.kt`: per-target WebView isolation. `canonicalWebOrigin` normalizes the origin. `WebSessionProfiles` hands out a `WebProfileBinding` per (target id, canonical origin). A `WebSessionCookies` capability is bound to that profile's `CookieManager`. No code path falls back to the global `CookieManager`. Isolation applies to the page's own profile. It does not promise per-origin isolation for every sub-resource inside that profile.
- `WebProfileRemoval.kt`: per-identity write/open gates and ordered cleanup when a remote server is removed. Started cookie batches finish before the named profile's cookies and WebStorage are cleared; later writes and bindings are refused. Only a completed clear lets the UI delete the saved server.
- `HttpDownloads.kt`, `HttpDownloadPolicy.kt`, `HttpDownloadTransfer.kt`, `HttpDownloadDestinations.kt`: native http(s) downloads, started through `HttpDownloadStarter` (singleton `AndroidHttpDownloadStarter`). Cookies come from the bound profile, one hop at a time. `DownloadManager` is not used for downloads; it appears only in the notification tap intent.
  - Redirects are followed by hand (`HttpDownloadTransfer`, `NO_COOKIES`, no automatic redirect). The Cookie header is sent only while the chain is on the binding's origin. Once a hop leaves that origin the rest of the chain has no Cookie, and it does not come back. https is never downgraded to http. At most 5 hops.
  - Writes are streamed. Android 10+ uses a pending MediaStore item, published on success. API 26–28 writes a hidden `.part` file and renames it. A failure or cancel removes partial output. Cancel is rethrown.
  - Lifetime: the download runs on the starter's application scope, so leaving the page does not stop it. It is not resumed after the process dies, and it is not retried. Downloads are not idempotent. A reply after a lost response is not repeated.
- `EncryptedRemoteServerStore.kt`: persists the list of remote servers (`ServerTarget.Remote`, including the UI password) in EncryptedSharedPreferences. Implements `runtime.RemoteServerStore`.

## Design

### WebSessionAuthenticatorImpl (`DefaultWebSessionAuthenticator`)

- Constructor: `client`, `localPassword`, `localReady: LocalReadiness` (default denies everything), `remoteStoredPassword`. Cookies are not a constructor argument.
- One OkHttp client, `authClient`, derived from `client` with `CookieJar.NO_COOKIES`, `followRedirects(false)` and `followSslRedirects(false)`. Remote health, probe and login explicitly use the bound profile cookie header; local behavior is unchanged.
- `prepare(target, cookies: WebSessionCookies)`, in this order: (1) the cookie identity must match the target id and canonical origin, else `Unreachable` with no request and no secret read; (2) the target's URL must parse; (3) under a per-identity `Mutex`, `prepareLocked` runs. Cookies are stored only while the coroutine is still active, and the store itself is `NonCancellable`. `CancellationException` is never swallowed.
- Local targets are checked against `LocalReadiness` three times: after health, before the POST, and after the POST and before cookies are stored.
- Local targets are trusted only while `LocalReadiness.runningLaunchId()` returns the same non-null id before and right before the POST. A restart in between yields `Unreachable("...restarted; try again")`. Null id yields `Unreachable("not running")`.
- Transport rule: a remote target without `allowInsecureHttp` is refused unless the URL is https or loopback (`HttpUrl.isTransportSafe`). Refusal happens before any login request.
- Results include `NeedsGatewayLogin(base)` when Cloudflare Access gates health, probe or login; its pure detector recognizes Access redirects and challenge headers. This result never sends the UI password.
- Set-Cookie headers are written through the bound profile's `WebSessionCookies.storeCookies` and flushed there. The T3 protocol names the cookie `t3_session_<port>`; Cox keeps that name, which keeps two servers on one host apart.
- Login body: Cox uses `{"credential": ...}`. Successful HTTP responses must also contain `authenticated:true`; empty, invalid or unauthenticated bodies return `NeedsLogin` without storing cookies. 4xx and 3xx map to `NeedsLogin`; other non-2xx map to `Unreachable`.

### Named profile removal

- Removing a remote retires its (target id, current canonical origin) gate before discarding the pooled view. The gate refuses new opens and cookie writes while an already-started `NonCancellable` batch drains.
- Only the exact derived profile is addressed; `Default` and other targets are never enumerated or cleared. Older origins from address edits are not claimed.
- Cookie callback completion and WebStorage deletion must finish before the root commits `remotes.remove` and forgets the page. Failure, timeout or cancellation reopens the gate and keeps the server; success keeps the removed identity frozen against stale pages.

### EncryptedRemoteServerStore

- File `cocode_remote_servers`, key `servers`. Master key AES256_GCM; keys AES256_SIV; values AES256_GCM.
- Persisted format: JSON list of `StoredServer(id, displayName, baseUrl, uiPassword?, allowInsecureHttp=false)`, `ignoreUnknownKeys = true`.
- `servers: StateFlow<List<ServerTarget.Remote>>` is the source of truth in memory. `upsert`, `remove` and `move` are `@Synchronized`, write-through (one write each), then update the flow. The list order is the user's: `upsert` appends a new id and replaces an existing one in place; `move(id, toIndex)` clamps the index, ignores unknown ids and no-op moves (no write). Both use the pure helpers in `runtime/RemoteServerOrder.kt`. A decode failure yields an empty list.
- Created in DI via `EncryptedRemoteServerStore.create(context)`.

## Flow

WebView login (`WebSessionAuthenticator.prepare(target, cookies)`):
1. Parse base URL; reject if invalid.
2. `GET {base}/.well-known/t3/environment` must return an `environmentId`, else `Unreachable`.
3. Local only: `launchId = localReady.runningLaunchId()`; null → `Unreachable`.
4. Pick password (local: `localPassword()`; remote: stored `uiPassword`).
5. No password → `GET /api/auth/session`: 200 with `authenticated:true` → `Ready`, else `NeedsLogin`.
6. Local: re-check the launch id is unchanged, else `Unreachable`.
7. Remote transport check (see above).
8. `POST /api/auth/browser-session` with `{"credential":...}` (no redirects) → store cookies → `Ready`, or `NeedsLogin` (4xx/3xx), or `Unreachable`.

Remote server CRUD: UI calls `upsert`/`remove`/`move` → JSON re-encoded → EncryptedSharedPreferences → `servers` flow emits.

## Integration

- `di/AppModule.kt` constructs: `DefaultWebSessionAuthenticator` (with `LocalReadiness { get<T3Server>().readyLaunchId() }`), `EncryptedRemoteServerStore.create`, `WebSessionProfiles`, and the `AndroidHttpDownloadStarter` singleton.
- Interfaces implemented from `runtime/`: `WebSessionAuthenticator`, `RemoteServerStore`. Types used: `ServerTarget` (Local/Remote), `WebSessionResult`, `RuntimeSecrets`, `RuntimePaths`, `RuntimeState`.
- Consumers: `MainActivity` and `ui/` (through `WebSessionAuthenticator` and `RemoteServerStore`); `ui/web` for the bound profile and download cookie hand-off.
- T3 protocol HTTP endpoints (Cox and legacy T3 Code):
  - `GET /.well-known/t3/environment` (`WebSessionAuthenticatorImpl`).
  - `GET /api/auth/session` (session probe).
  - `POST /api/auth/browser-session` (login with `{"credential":...}`).
- Networking: `app/src/main/res/xml/network_security_config.xml` governs cleartext; the code-level plain-HTTP guard is in `WebSessionAuthenticatorImpl`.

- Saved remote entries with a retired or unknown protocol are not loaded as Cox. Their stored records are left untouched during loading.
