# T3 Code server wire protocol: notes for a Kotlin client (v0.1)

Source: `/tmp/t3up/t3code` @ tag `v0.1.1-preview.20261019.3339`. All paths below are relative to that root unless absolute.
Effect version: `effect: 4.0.5` (`pnpm-lock.yaml:134`). There is **no separate `@effect/rpc` package**; RPC lives inside `effect` as `effect/rpc` (`apps/server/src/ws.ts:114`, `packages/client-runtime/src/rpc/protocol.ts:3`).
`node_modules` is absent in the checkout. Framing was checked against Effect sources at `/Users/xxfast/.config/opencode/node_modules/effect` (version `4.0.0-beta.83`, `src/unstable/rpc/*`). Where 4.0.5 might differ it is marked UNVERIFIED.

---

## 1. HTTP handshake

### 1.1 `GET /api/auth/session`
Declared at `packages/contracts/src/environmentHttp.ts:452-456`. Optional headers `authorization` and `dpop` (`environmentHttp.ts:76-79`). Never answers 401; a bad or missing credential gives `authenticated:false` (`apps/server/src/auth/EnvironmentAuth.ts:823-842`).

```ts
// packages/contracts/src/auth.ts:384-391
AuthSessionState = Struct({
  authenticated: Boolean,
  auth: ServerAuthDescriptor,
  scopes: optionalKey(Array(AuthEnvironmentScope)),       // legacy-filtered subset
  permissions: optionalKey(ForwardCompatibleArray(AuthEnvironmentScope)), // exact grant
  sessionMethod: optionalKey(ServerAuthSessionMethod),
  expiresAt: optionalKey(DateTimeUtc),                     // ISO-8601 string on the wire
})
// auth.ts:177-184
ServerAuthDescriptor = Struct({
  policy: ServerAuthPolicy,
  bootstrapMethods: Array(ServerAuthBootstrapMethod),
  sessionMethods: Array(ServerAuthSessionMethod),
  sessionCookieName: TrimmedNonEmptyString,
  serverUpdateScope: optionalKey(Literal("environment:maintain")), // "Older servers omit this"
})
```
Enums:
- `ServerAuthPolicy` = `"desktop-managed-local" | "loopback-browser" | "remote-reachable" | "unsafe-no-auth"` (`auth.ts:12-17`).
- `ServerAuthBootstrapMethod` = `"desktop-bootstrap" | "one-time-token"` (`auth.ts:20`)
- `ServerAuthSessionMethod` = `"browser-session-cookie" | "bearer-access-token" | "dpop-access-token"` (`auth.ts:23-27`)

When authenticated, the server fills `scopes`, `permissions`, `sessionMethod` and `expiresAt` (`EnvironmentAuth.ts:826-833`). `scopes` holds only legacy names (`orchestration:read`, `orchestration:operate`, `terminal:operate`, `review:write`, `access:read`, `access:write`, `relay:read`, `relay:write`, `auth.ts:78-87`); `permissions` holds all. **Use `permissions` whenever present.**

The locally observed response has no `serverUpdateScope`; this tag always sets it (`EnvironmentAuthPolicy.ts:43`), so the running desktop server is older than this tag. Treat `serverUpdateScope` as optional.

### 1.2 Why `bootstrapMethods` is only `["desktop-bootstrap"]`
```ts
// apps/server/src/auth/EnvironmentAuthPolicy.ts:23-37
policy = config.mode === "desktop"
  ? (isRemoteReachable ? "remote-reachable" : "desktop-managed-local")
  : (isRemoteReachable ? "remote-reachable" : "loopback-browser");
bootstrapMethods = policy === "desktop-managed-local" ? ["desktop-bootstrap"]
  : config.mode === "desktop" && policy === "remote-reachable" ? ["desktop-bootstrap","one-time-token"]
  : ["one-time-token"];
```
- Loopback-bound desktop server is `desktop-managed-local`. Turning on "Network access" in desktop Settings > Connections rebinds it to `remote-reachable` with both methods (`apps/web/src/components/settings/ConnectionsSettings.tsx:2240-2242`).
- Desktop cookie name: `${SESSION_COOKIE_NAME}_${port}`, e.g. `t3_session_3773` (`utils.ts:28-30`).
- **`bootstrapMethods` is only advertised, not enforced.** `POST /oauth/token` -> `exchangeBootstrapCredentialForAccessToken` -> `PairingGrantStore.consume`; neither reads `descriptor.bootstrapMethods` (`EnvironmentAuth.ts:912-1006`, `PairingGrantStore.ts:430-560`). A valid one-time pairing credential is accepted on a `desktop-managed-local` server.
- `desktop-bootstrap` credentials stay inside the Electron process; a third-party client cannot get one.

How a non-desktop client bootstraps (creator needs `access:write`):
1. Create a pairing credential via desktop UI Settings > Connections (`apps/web/src/environments/primary/auth.ts:356-381`), or HTTP `POST /api/auth/pairing-token` (route `pairingCredential`, `environmentHttp.ts:480-486`; there is **no `/pairing-link` POST route**), or CLI `t3 auth pairing create [--scopes ...] [--ttl ...] [--label ...] [--base-url ...]` (`apps/server/src/cli/auth.ts:81-112`). `t3 auth session issue` mints a bearer token directly, skipping pairing (`cli/auth.ts:160-193`).
2. Request body: `AuthCreatePairingCredentialInput = { label?: string, scopes?: AuthGrantScope[] }` (`auth.ts:378-381`). Default scopes are `AuthStandardClientScopes`.
3. Response: `AuthPairingCredentialResult = { id, credential, label?, expiresAt }` (`auth.ts:249-254`). Default TTL **5 minutes** (`PairingGrantStore.ts:249`). Single use.
4. Format: 12 chars from `"23456789ABCDEFGHJKLMNPQRSTUVWXYZ"` (`PairingGrantStore.ts:252-253`).
5. Pairing link: `<baseUrl>/pair#token=<credential>`, token in the URL **fragment** (`EnvironmentAuth.ts:1194-1204`; `packages/shared/src/remote.ts:3,165-170`). Parsers also accept `?token=` (`remote.ts:144-152`). Hosted form: `?host=...` plus a token (`remote.ts:172-186`).
6. **Pasting a raw code works.** `resolveRemotePairingTarget({host, pairingCode})` uses the bare code (`remote.ts:230-246`). Web wizard placeholder: `https://your-server:5230/pair#token=...`.
7. List: `GET /api/auth/pairing-links` (needs `access:read`) -> `AuthPairingLink[] = {id, scopes, permissions?, subject, label?, createdAt, expiresAt}`; **never includes the secret**. Revoke: `POST /api/auth/pairing-links/revoke {id}` -> `{revoked}`.

### 1.3 `POST /oauth/token`
Body is **`application/x-www-form-urlencoded`** (`auth.ts:222-231`). Optional `dpop` header.

```
grant_type:          "urn:ietf:params:oauth:grant-type:token-exchange"
subject_token:       <pairing credential>
subject_token_type:  "urn:t3:params:oauth:token-type:environment-bootstrap"
requested_token_type:"urn:ietf:params:oauth:token-type:access_token"
scope?:              space-separated scopes ("a b c")
client_label?:       string
client_device_type?: "desktop"|"mobile"|"tablet"|"bot"|"unknown"
client_os?:          string
```
Example:
```
POST /oauth/token
Content-Type: application/x-www-form-urlencoded

grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Atoken-exchange&subject_token=ABCD2345EFGH&subject_token_type=urn%3At3%3Aparams%3Aoauth%3Atoken-type%3Aenvironment-bootstrap&requested_token_type=urn%3Aietf%3Aparams%3Aoauth%3Atoken-type%3Aaccess_token&scope=orchestration%3Aread%20filesystem%3Aread&client_label=JetBrains&client_device_type=desktop
```
Rules:
- Unknown scope names are dropped; none left or unparsable -> 400 `invalid_scope` (`http.ts:357-363`).
- Granted = requested intersected with the link's scopes; empty -> `scope_not_granted` (`EnvironmentAuth.ts:954-960`).
- `scope` omitted -> token gets every scope on the link.

`AuthStandardClientScopes` (`auth.ts:152-169`): `orchestration:read`, `orchestration:operate`, `settings:write`, `providers:manage`, `environment:maintain`, `preview:operate`, `diagnostics:read`, `terminal:read`, `terminal:operate`, `source-control:write`, `filesystem:read`, `filesystem:write`, `relay:read`, `access:read`, `access:write`, `relay:write`.
**Minimum for v0.1 read-only:** `orchestration:read filesystem:read`. Add `diagnostics:read` for `server.getUsageSummary`.

Response (`auth.ts:234-240`):
```
{ access_token: string, issued_token_type: "urn:ietf:params:oauth:token-type:access_token",
  token_type: "Bearer" | "DPoP", expires_in: number /*seconds*/, scope: string /*space-separated, FULL list*/ }
```
- **No `permissions`, no refresh token.** Bearer TTL `DEFAULT_SESSION_TTL = 30 days` (`SessionStore.ts:428`). DPoP tokens last 1 h.
- Response headers `cache-control: no-store`, `pragma: no-cache`.
- To renew, pair again.

### 1.4 Bearer headers
`Authorization: Bearer <access_token>` (`EnvironmentAuth.ts:574, 622-629`). Credential precedence: cookie, then Bearer, then DPoP (`EnvironmentAuth.ts:640-666`). **Do not send a stale `t3_session_*` cookie.**
`/api/orchestration/*` HTTP routes also **require** `x-t3-orchestration-protocol: 2` (`environmentHttp.ts:81-85`; `environment.ts:12-15`).

### 1.5 Error bodies (HTTP)
All `Schema.TaggedError`s serialized as JSON **with `_tag`** (`environmentHttp.ts:132-243`):
```json
// 401 (EnvironmentAuthInvalidError, environmentHttp.ts:150-167)
{"_tag":"EnvironmentAuthInvalidError","code":"auth_invalid","reason":"missing_credential"|"invalid_credential",
 "dpopFailureReason":"time_window"|"key_mismatch"|"request_mismatch"|"token_mismatch"|"replay"|"invalid_proof" /*optional*/,
 "traceId":"<hex or 'unavailable'>"}
// 400 EnvironmentRequestInvalidError: code "invalid_request", reason in invalid_scope|scope_not_granted|invalid_command|invalid_history_cursor|thread_transcript_too_large, traceId
// 403 EnvironmentScopeRequiredError: code "insufficient_scope", requiredScope (legacy parent), requiredPermission? (exact), traceId
// 403 EnvironmentOperationForbiddenError: code "operation_forbidden", reason "current_session_revoke_not_allowed"
// 404 EnvironmentResourceNotFoundError: code "not_found", reason "thread_not_found"
// 500 EnvironmentInternalError: code "internal_error", reason in (environmentHttp.ts:111-129)
```
An expired or consumed pairing code returns 401 `invalid_credential` (`EnvironmentAuth.ts:590-600`).

---

## 2. WebSocket upgrade

- **Route: `GET /ws`** (`apps/server/src/ws.ts:3132-3135`). No `/rpc`.
- **Required query param: `orchestrationProtocol=2`.** Without it: HTTP **426** `{"code":"orchestration_protocol_incompatible","message":"Update this client...","orchestrationProtocolVersion":2}` **before** auth (`ws.ts:608-613, 3138-3147`).
- Auth in `authenticateWebSocketUpgrade` (`EnvironmentAuth.ts:1224-1245`):
  1. Query param **`wsTicket=<ticket>`** (`EnvironmentAuth.ts:576, 1228`), or
  2. the same credentials as HTTP. **`Authorization: Bearer ...` on the upgrade request works.** No subprotocol.
- `POST /api/auth/websocket-ticket` (`environmentHttp.ts:473-479`): no body, Bearer header. Response `{ "ticket": string, "expiresAt": ISO }` (`auth.ts:243-246`). Ticket is HMAC-signed, 5 min (`SessionStore.ts:429, 900-930`). Appears reusable until expiry (UNVERIFIED).
- Optional analytics query params (`packages/client-runtime/src/authorization/remote.ts:40-81`): `clientSurface`, `clientAppVersion`, `clientDeviceType` (`phone|desktop|tablet|unknown`), `clientOs`, `connectionMethod`.
- Example: `ws://127.0.0.1:3773/ws?orchestrationProtocol=2&wsTicket=eyJ....abc&clientSurface=desktop`
- Origin check applies to **browser-cookie credentials only**. Bearer, DPoP and ticket skip it. No CSRF token.
- On auth failure the server returns HTTP 401 JSON (`EnvironmentAuthInvalidError`) instead of upgrading (`ws.ts:3151-3161`).
- After the socket opens nothing is required first. The web client's first call is `subscribeServerConfig` and treats the connection ready once the first config event arrives (`packages/client-runtime/src/rpc/session.ts:217-219`).

---

## 3. RPC framing

**Not JSON-RPC 2.0, not ndjson.** Effect's native RPC envelope serialized with `RpcSerialization.layerJson` (`ws.ts:3186`; `session.ts:209`). **Each WebSocket text frame is one `JSON.stringify(message)`**; decoder also accepts a JSON array of messages. Server options `{ disableTracing: true, disableFatalDefects: true }` (`ws.ts:3117-3120`): request defects arrive as per-request `Exit` with `Die`.

### 3.1 Client to server (`RpcMessage.ts:34`)
```jsonc
// Request (RpcMessage.ts:61-70). id = decimal string of a bigint (server does BigInt(id); non-numeric -> defect)
{"_tag":"Request","id":"0","tag":"orchestration.subscribeShell","payload":{},"headers":[]}
// optional extra keys "traceId","spanId","sampled": omit them
{"_tag":"Ack","requestId":"0"}          // MUST send after every Chunk (see 3.3)
{"_tag":"Interrupt","requestId":"0"}    // cancel a request/stream
{"_tag":"Ping"}                         // keepalive
{"_tag":"Eof"}                          // client done (optional)
```
- `headers` is an array of `[name, value]` pairs.
- `payload` is the JSON encoding of the RPC's payload schema. Use `{}` for an empty struct.
- Ids count from `"0"` upward in the Effect client; any unique numeric string works.

### 3.2 Server to client (`RpcMessage.ts:192-197`)
```jsonc
// Unary success
{"_tag":"Exit","requestId":"1","exit":{"_tag":"Success","value":{ /* success schema JSON */ }}}
// Typed failure
{"_tag":"Exit","requestId":"1","exit":{"_tag":"Failure","cause":[{"_tag":"Fail","error":{"_tag":"EnvironmentAuthorizationError","message":"The authenticated token is missing required scope: filesystem:read.","requiredScope":"orchestration:read","requiredPermission":"filesystem:read"}}]}}
// Defect (payload failed to decode, or handler crash)
{"_tag":"Exit","requestId":"1","exit":{"_tag":"Failure","cause":[{"_tag":"Die","defect":{"name":"Error","message":"..."}}]}}
// Interrupted
{"_tag":"Exit","requestId":"1","exit":{"_tag":"Failure","cause":[{"_tag":"Interrupt","fiberId":123}]}}
// Stream chunk: values is a NON-EMPTY array of stream elements
{"_tag":"Chunk","requestId":"0","values":[{"kind":"snapshot", ...},{"kind":"synchronized"}]}
// Stream end: Exit Success; value likely omitted/null (UNVERIFIED)
{"_tag":"Exit","requestId":"0","exit":{"_tag":"Success"}}
// Keepalive reply
{"_tag":"Pong"}
// Socket-level defect (no requestId; e.g. unknown tag)
{"_tag":"Defect","defect":"Unknown request tag: projects.list"}
```
- `cause` may hold several entries. The server never sends `Ack`.
- Defect encoding: `Error` values become `{name, message, cause?}`, other values pass through.

### 3.3 Backpressure: Ack is mandatory for streams
Server socket protocol has `supportsAck: true`. After each `Chunk` the server **waits for `{"_tag":"Ack","requestId":...}` before sending the next chunk** (`RpcServer.ts:395-434`). **A client that never Acks receives only the first chunk of every subscription.**

### 3.4 Keepalive
Client sends `{"_tag":"Ping"}` about every 5 s, server replies `{"_tag":"Pong"}`. T3 sets `pingTimeout: "15 seconds"` (`packages/client-runtime/src/rpc/protocol.ts:10-12`). No application-level heartbeat RPC. `server.probe` (`{}` -> `{}`, `orchestration:read`) exists for liveness.

### 3.5 Literal session example
```
-> {"_tag":"Request","id":"0","tag":"subscribeServerConfig","payload":{},"headers":[]}
<- {"_tag":"Chunk","requestId":"0","values":[{"type":"snapshot", ...}]}
-> {"_tag":"Ack","requestId":"0"}
-> {"_tag":"Request","id":"1","tag":"orchestration.subscribeShell","payload":{"requestCompletionMarker":true},"headers":[]}
<- {"_tag":"Chunk","requestId":"1","values":[{"kind":"snapshot","snapshot":{...}},{"kind":"synchronized"}]}
-> {"_tag":"Ack","requestId":"1"}
-> {"_tag":"Request","id":"2","tag":"projects.readFile","payload":{"cwd":"/Users/me/repo","relativePath":"src/a.kt"},"headers":[]}
<- {"_tag":"Exit","requestId":"2","exit":{"_tag":"Success","value":{"relativePath":"src/a.kt","contents":"...","byteLength":123,"truncated":false}}}
-> {"_tag":"Ping"}
<- {"_tag":"Pong"}
```

---

## 4. RPC methods for v0.1

Every RPC error union also contains `EnvironmentAuthorizationError` (`{_tag, message, requiredScope, requiredPermission?}`, `auth.ts:332-339`), raised by middleware before any handler (`rpc.ts:1887-1890`; `RpcAuthorization.ts:222-226`). Scopes from `RPC_REQUIRED_SCOPES` (`RpcAuthorization.ts:40-209`).

| Tag (exact) | Payload | Success | Error `_tag`s | Stream | Scope |
|---|---|---|---|---|---|
| `orchestration.subscribeShell` | `{afterSequence?: int>=0, requestCompletionMarker?: bool}` (`orchestrationV2.ts:3366-3369`) | `OrchestrationV2ShellStreamItem` (`:1980-2011`) | `OrchestrationV2GetShellSnapshotError` | yes | `orchestration:read` |
| `orchestration.subscribeThread` | `{threadId, afterSequence?, requestCompletionMarker?, acceptBoundedSnapshot?, acceptCompactTurnItems?}` (`:3372-3378`) | `OrchestrationV2ThreadStreamItem` (`:3477-3498`) | `OrchestrationV2GetThreadProjectionError` | yes | `orchestration:read` |
| `orchestration.getThreadProjection` | `{threadId}` | `OrchestrationV2ThreadProjection` (`:1830-1855`) | same | no | `orchestration:read` |
| `orchestration.searchThreads` | `{query: string 2..200, limit?: int 1..50}` | `{matches: [{threadId, projectId, source:"user"|"assistant", snippet, messageCreatedAt: string|null}]}` | `OrchestrationSearchThreadsError` | no | `orchestration:read` |
| `orchestration.getTurnItem` | `{threadId, itemId, revision?: string}` (`:3625-3629`) | `{item: TurnItem | null}` | `OrchestrationV2GetThreadProjectionError` | no | `orchestration:read` |
| `orchestration.getTurnDiff` | `{threadId, fromTurnCount: int>=0, toTurnCount: int>=0, ignoreWhitespace?}` from<=to (`checkpointDiff.ts:29-35`) | `{threadId, fromTurnCount, toTurnCount, diff: string}` | `OrchestrationGetTurnDiffError{message,cause?}` | no | `orchestration:read` |
| `orchestration.getFullThreadDiff` | `{threadId, toTurnCount, ignoreWhitespace?}` | same `ThreadTurnDiff` | `OrchestrationGetFullThreadDiffError` | no | `orchestration:read` |
| `orchestration.getArchivedShellSnapshot` | `{}` | `{schemaVersion, snapshotSequence, projects, threads}` | `OrchestrationV2GetShellSnapshotError` | no | `orchestration:read` |
| `orchestration.subscribeCommandOutput` | `{threadId, itemId}` | `{kind:"replace"|"append", text, truncated, running}` | `OrchestrationV2CommandOutputError` | yes | `orchestration:read` |
| `projects.readFile` | `{cwd, relativePath}` (`project.ts:416-421`) | `{relativePath, contents, byteLength, truncated}` | `ProjectReadFileError` (`project.ts:464-477`) | no | `filesystem:read` |
| `review.getDiffFileContents` | `{cwd, sourceKind:"working-tree"|"branch-range", changeType:"change"|"rename-pure"|"rename-changed"|"new"|"deleted", baseRef: string|null, headRef: string|null, oldPath, newPath}` (`review.ts:44-52`) | `{oldContents, newContents}` | `VcsError` | `GitCommandError` | no | `filesystem:read` |
| `server.getUsageSummary` | `{sinceDay, untilDay, timeZone, resolution?}` (`usage.ts:132-145`) | `UsageSummary` | `UsageReadError` | no | `diagnostics:read` |
| `subscribeServerConfig` | `{environmentThemes?, usageLimitSources?, usageLimitsCommand?}` (`rpc.ts:1769-1788`) | `ServerConfigStreamEvent` (`server.ts:747-754`) | `KeybindingsConfigError`, `ServerSettingsError` | yes | `orchestration:read` |
| `server.probe` | `{}` -> `{}` | | | no | `orchestration:read` |

**`projects.list` does not exist as an RPC.** The constant is in `WS_METHODS` (`rpc.ts:385`) but nothing registers it; calling it yields `{"_tag":"Defect","defect":"Unknown request tag: projects.list"}`. Get projects from the `projects` array in the `subscribeShell` snapshot, or HTTP `GET /api/projects` -> `ProjectSnapshot {projects: Project[], updatedAt}` (`environmentHttp.ts:637-643`; `project.ts:151-172`).

What the web app does on connect (`packages/client-runtime/src/state/shell.ts:318-360`, `threads.ts:767-800`):
1. Open `/ws`, start `subscribeServerConfig`.
2. `GET /api/orchestration/shell` with `x-t3-orchestration-protocol: 2` -> `OrchestrationV2ShellSnapshot`.
3. `orchestration.subscribeShell` with `afterSequence = snapshot.snapshotSequence`.
4. Per opened thread, `orchestration.subscribeThread`.

Simplest path for a new client: call `subscribeShell` **without** `afterSequence`; the server emits a `snapshot` item itself (`ws.ts:1100-1106`).

---

## 5. Orchestration state model (v2)

### 5.1 Project (shell form)
```ts
// packages/contracts/src/orchestrationProject.ts:8-22
OrchestrationProjectShell = { id: ProjectId, title, workspaceRoot /*absolute path*/, repositoryIdentity?: RepositoryIdentity|null,
  defaultModelSelection: ModelSelection|null, defaultThreadEnvMode?: ...|null, autoPull?: bool,
  faviconPath?: string|null, projectIcon?: ...|null, scripts: ProjectScript[], createdAt: IsoDateTime, updatedAt: IsoDateTime }
```
`Project` (HTTP) is the same plus `deletedAt: string|null`. `ModelSelection` = `{instanceId, model, options?}` (legacy `provider` in place of `instanceId` also accepted).

### 5.2 Thread shell, as delivered by subscribeShell (`orchestrationV2.ts:1889-1963`)
Required:
- `createdBy`: `"user"|"agent"|"system"`; `creationSource`: `"web"|"mobile"|"mcp"|"provider"|"server"`
- `id`, `projectId`, `title`, `providerInstanceId`, `modelSelection`
- `runtimeMode`: `"approval-required"|"auto-accept-edits"|"auto"|"full-access"`; `interactionMode`: `"default"|"plan"`
- `branch: string|null`, `worktreePath: string|null`
- `lineage: {parentThreadId|null, relationshipToParent: "fork"|"subagent"|null, rootThreadId}`, `forkedFrom: ...|null`
- `activeProviderThreadId|null`, `latestRunId|null`, `activeRunId|null`
- **`status`**: `"idle"` or RunStatus = `"preparing"|"queued"|"starting"|"running"|"waiting"|"completed"|"interrupted"|"failed"|"cancelled"|"rolled_back"` (`:1866-1869`, `:463-474`)
- `pendingRuntimeRequest: {id, kind, createdAt}|null`
- `latestVisibleMessage: {id, role, text, updatedAt}|null`
- `latestUserMessageAt|null`, `hasActionableProposedPlan: bool`
- `itemCount`, `visibleItemCount`, `createdAt`, `updatedAt`
- **`archivedAt: ISO|null`**, `settledOverride: "settled"|"active"|null`, `settledAt|null`, `deletedAt|null`

Optional (key may be missing): `linkedPullRequest`, `pullRequests`, `branchPullRequest`, `historyOrigin` (`"native"|"v1_import"`), `linkOrigin`, `delegatedFrom`, `latestRun{Requested,Started,Completed}At`, `activityRunStartedAt`, `activityRunStatus`, `lastError`, `lastErrorClass`, `usageLimitResetAt`, `latestUserAuthoredMessageAt`, `pendingBackgroundTasks` (default `[]`), `providerInstanceHistory` (default `[]`), `goal`, `unsettledAt`, `snoozedUntil`, `snoozedAt`, `limitRecovery`, **`pinnedAt`** (pinned iff non-null), `autoSettleDisabledAt`, `pinOrderKey`, `activeOrderKey`, `lastVisitedAt`, `titleRegeneration`.
No `archived`/`pinned` booleans; derive from `archivedAt`/`pinnedAt`.

`OrchestrationV2AppThread` (full thread inside a projection or `thread.*` event, `:381-450`) has almost the same fields minus the shell summaries (`status`, counts, latest*), plus `rollbackRequestId?`, `rollbackFailure?`, `lastSnoozeWakeAt?`.

### 5.3 Shell stream items (`:1980-2011`)
```jsonc
{"kind":"snapshot","snapshot":{"schemaVersion":1,"snapshotSequence":42,"threads":[ThreadShell...],"archivedThreads":[ThreadShell...],"projects":[ProjectShell...]},"resolvedRepositoryIdentityRoots":["..."] /*optional*/}
{"kind":"synchronized"}                                          // only if requestCompletionMarker:true
// Observed live 2026-10-19 (desktop 0.1.0, schemaVersion 2): the first snapshot is the full state.
// It is followed by one snapshot per resolved repository root group, carrying
// resolvedRepositoryIdentityRoots, the SAME snapshotSequence, only the projects whose
// repositoryIdentity just resolved, and NO threads. Treat those as project upserts, never as
// a replacement, or the thread list empties.
{"kind":"project.updated","sequence":43,"project":ProjectShell}
{"kind":"project.removed","sequence":44,"projectId":"..."}
{"kind":"thread.updated","sequence":45,"location":"active"|"archive","thread":ThreadShell}
{"kind":"thread.removed","sequence":46,"location":"active"|"archive","threadId":"..."}
```
No `thread.created`/`thread.archive` items on the shell stream. An archive appears as `thread.removed{location:"active"}` then `thread.updated{location:"archive"}` (ordering UNVERIFIED).

### 5.4 Thread stream items (`:3477-3498`)
```jsonc
{"kind":"snapshot","snapshotSequence":100,"projection":ThreadProjection,
 "historyCursor":...?, "hasMoreHistory":...?, "latestLocalTurnOrdinal":...?, "payloadBudgetExceeded":...?, "turnItemsOmitLocalVisible":true? }
{"kind":"synchronized"}
{"kind":"event","sequence":101,"event":DomainEvent}
```
- Without `afterSequence`: `snapshot`, then `synchronized` if requested, then live events (`ws.ts:822-857`).
- Leave `acceptBoundedSnapshot` and `acceptCompactTurnItems` unset for the full projection.
- `command_execution.output` and `dynamic_tool.output` can be withheld; `outputOmitted: true` marks this and `orchestration.getTurnItem` returns the full item.

`ThreadProjection` (`:1830-1855`): `thread` (AppThread), `runs`, `attempts`, `nodes`, `subagents`, `providerSessions`, `providerThreads`, `providerTurns`, `runtimeRequests`, `messages`, `plans`, **`turnItems`**, `checkpointScopes`, `checkpoints`, `contextHandoffs`, `contextTransfers`, **`visibleTurnItems`**, `updatedAt`.
- `visibleTurnItems` = `[{position, visibility:"local"|"inherited"|"synthetic", sourceThreadId, sourceItemId, item: TurnItem}]` (`:1650-1656`). **Render from `visibleTurnItems`.**

### 5.5 Domain event envelope (`:1674-1827`)
Base: `{ id: EventId, threadId, runId?, nodeId?, driver?, providerInstanceId?, rawEventId?, occurredAt: ISO, type, payload }`.
`type` literals:
- `thread.created` -> AppThread
- `thread.archived` | `thread.unarchived` | `thread.deleted` | `thread.settled` | `thread.unsettled` | `thread.snoozed` | `thread.unsnoozed` | `thread.pinned` | `thread.auto-settle-set` | `thread.unpinned` | `thread.pin-reordered` | `thread.active-reordered` | `thread.visited` | `thread.marked-unread` | `thread.metadata-updated` | `thread.pull-request-synced` | `thread.runtime-mode-updated` | `thread.interaction-mode-updated` | `thread.model-selection-updated` | `thread.provider-switched` -> AppThread (full new state)
- `thread.pull-request-link-updated` -> ThreadPullRequestLink
- `run.created` | `run.updated` -> Run (`:547-579`); `run.background-work-cancelled`
- `run-attempt.created` | `run-attempt.updated`; `node.updated`; `subagent.updated`
- `provider-session.attached` | `provider-session.updated` | `provider-session.detached`; `provider-thread.updated`; `provider-turn.updated`
- `runtime-request.updated` -> RuntimeRequest
- `message.updated` -> ConversationMessage `{id, threadId, runId|null, nodeId|null, role:"user"|"assistant"|"system", text, attachments, streaming, createdAt, updatedAt, ...}` (`:1141-1164`)
- **`turn-item.updated` -> TurnItem**
- `plan.updated`; `checkpoint-scope.created`; `checkpoint.captured`; `checkpoint.rollback-requested`; `context-handoff.updated`; `context-transfer.created` | `context-transfer.updated`

**No `item.updated` or `*.delta` events.** Streaming text arrives as repeated `turn-item.updated` (or `message.updated`) events, each carrying the whole item with `streaming: true` until the final one. Upsert by `id` (`packages/client-runtime/src/state/orchestrationV2Projection.ts:295-310`). Unknown event types: skip but advance `sequence`.

### 5.6 TurnItem (`:1331-1350`, `:1419-1624`)
Base fields on every item:
- `id`, `threadId`, `runId|null`, `nodeId|null`, `providerThreadId|null`, `providerTurnId|null`, `nativeItemRef|null`, `parentItemId|null`
- `ordinal: int`
- `status`: `"idle"|"pending"|"running"|"waiting"|"completed"|"failed"|"cancelled"|"interrupted"`
- `title: string|null`, `startedAt|null`, `completedAt|null`, `updatedAt`
- optional `toolNonExecutionKind`, `toolSurface`, `toolIcon`, `toolSource`

Discriminated on `type`:
- `notification`: `source`, `outcome: "completed"|"failed"|"cancelled"|"updated"|"unknown"`, `summary`, `detail?`
- `user_message`: `createdBy`, `creationSource`, `messageId`, `scheduledTaskId?`, `senderThreadId?`, `inputIntent: "turn_start"|"queued_turn"|"steer"|"promoted_queued_to_steer"`, `text`, `context?`, `attachments[]`
- `assistant_message`: `messageId`, `text`, `attachments?`, `streaming: bool`
- `reasoning`: `text`, `streaming`
- `proposed_plan`: `planId`, `markdown`, `streaming`
- `todo_list`: `planId`, `steps[]`, `explanation?`
- `user_input_request`: `requestId`, `questions[]`, `questionAnswer?`, `responseMode?: "message"`
- **`file_change`**: `fileName`, `additions?`, `deletions?`, `diffStr?`, `oldStr?`, `newStr?`, `changes?: [{operation, path, oldPath?, fileType?, mimeType?}]` (`:1275-1281, 1474-1483`)
- **`command_execution`**: `input`, `output?`, `outputOmitted?`, `outputIndicatesFailure?`, `exitCode?: int`
- `file_search`: `pattern?`, `results?: [{fileName, line?, column?, preview?}]`
- `web_search`: `patterns?`, `results?: [{title?, url?, snippet?}]`
- **`approval_request`**: `requestId`, `requestKind: "command"|"file-read"|"file-change"|"mcp-elicitation"|"permission"`, `prompt?`, `appName?`, `options?: [{decision: "accept"|"acceptForSession"|"acceptAlways"|"decline"|"cancel", label, warning?}]`
- `checkpoint`: `checkpointId`, `scopeId`, `files: [{path, kind, additions, deletions}]`
- `run_interrupt_request` / `run_interrupt_result`: `message`
- **`system_notice`**: `message`
- **`error`**: `failure: {class: "usage_limit"|"provider_error"|"transport_error"|"permission_error"|"validation_error"|"unknown", message, code: string|null, retryable: bool|null, resetAt?: string|null}`, `retry?: {attempt, maxAttempts|null, retryDelayMs|null}`
- `compaction`, `handoff`, `fork`, `thread_created`, `secret_request`, `link_request`, `subagent`, `dynamic_tool`: lines 1543-1623.

**No `turn` type** in v2. A "turn" is a `Run` (`ordinal: int>=1`, `status: RunStatus`, `userMessageId`, `requestedAt`, `startedAt|null`, `completedAt|null`, `checkpointId|null`; `:547-579`).

`RuntimeRequest` = `{id, nodeId, providerTurnId|null, nativeRequestRef|null, kind: ProviderRequestKind | "dynamic_tool_call" | "user_input" | "auth_refresh", status: "pending"|"resolved"|"expired"|"cancelled", responseCapability: {...}, createdAt, resolvedAt|null, decision?, answers?}` (`:1062-1081`).

---

## 6. Diffs and file contents

- `getTurnDiff` / `getFullThreadDiff` return `{threadId, fromTurnCount, toTurnCount, diff}`; `diff` is a **unified git patch string**.
  - "Turn count" = `checkpoint.appRunOrdinal`, the run ordinal (`CheckpointDiffQuery.ts:125-143, 165-182`).
  - Turn N = `{fromTurnCount: N-1, toTurnCount: N}`. `fromTurnCount: 0` diffs from the scope's base. `from == to` gives `""`.
  - `getFullThreadDiff` is 0 -> `toTurnCount`.
  - Patch comes from `git diff` between checkpoint refs in the scope's `cwd`; paths should be repo-relative with `a/`/`b/` prefixes (UNVERIFIED).
  - `toTurnCount` above the max ready checkpoint -> `OrchestrationGetTurnDiffError`.
- `projects.readFile`: `cwd` is the root; `relativePath` is workspace-relative **or an absolute host path** (`project.ts:418-419`). Result echoes `relativePath`; `truncated: true` means cut at a server limit; `binary_file` is an error. Use `thread.worktreePath ?: project.workspaceRoot` as `cwd` (inference).
- `review.getDiffFileContents`: `cwd` plus repo-relative `oldPath`/`newPath` (UNVERIFIED). `baseRef`/`headRef` git refs or `null` (working tree). Returns `{oldContents, newContents}`.
- `file_change` items carry `fileName` plus `changes[].path`; absolute vs relative depends on the provider (UNVERIFIED), handle both.

---

## 7. Gotchas

1. **Prefer V2.** All orchestration goes through `ORCHESTRATION_V2_WS_METHODS` (`orchestrationV2.ts:3244-3261`); the socket must declare `orchestrationProtocol=2`.
2. Wire uses the plain schemas encoded with `Schema.toCodecJson` (`RpcServer.ts:599-606`).
3. `scopes` is legacy-filtered, `permissions` exact (`auth.ts:89-96`). Denials carry `requiredScope` (legacy parent) and `requiredPermission` (exact).
4. Branded IDs are **plain trimmed non-empty strings** on the wire (`baseSchemas.ts:258-323`). Never send `""`.
5. Optional encoding: `optional`/`optionalKey` means key may be **absent**; `NullOr(X)` means always present, may be `null`. `Schema.Option` (`{"_tag":"Some","value":...}`) is not used by any v0.1 shape. `withDecodingDefault` fields may be missing.
6. Dates are **ISO-8601 strings**. Nothing on the v0.1 wire is epoch millis. `expires_in` is seconds.
7. Errors are tagged by `_tag` (the class name). HTTP errors also carry `code` and `reason`.
8. Forward compatibility: arrays of turn items silently drop unknown `type`s upstream; `permissions` drops unknown scopes. Mirror with lenient decoding and unknown-type fallbacks.
9. Do not put long-lived tokens in the socket URL. Use `wsTicket` or the `Authorization` header on the upgrade.
10. `/api/orchestration/*` HTTP fails without `x-t3-orchestration-protocol: 2`.
11. The desktop server may be older than this tag. Gate on `/.well-known/t3/environment` (`{environmentId, label, platform, serverVersion, orchestrationProtocolVersion?, capabilities}`, `environment.ts:167-174`) and on `subscribeServerConfig` capabilities.

---

## 8. Phase 2 additions

What the plugin calls since phase 2, each with what it took to get right. Learned against `v0.1.1-preview.20261019.3339`, checked live with `LiveServerTest` on 2026-10-20.

- **`orchestration.dispatchCommand`**, scope `orchestration:operate` for every command (`RpcAuthorization.ts:42`). Answers the server's sequence. Types the plugin sends: `message.dispatch`, `runtime-request.respond`, `thread.user-input.dismiss`, `run.interrupt`, `provider-session.detach`, `thread.archive`, `thread.unarchive`, `thread.pin`, `thread.unpin`, `thread.mark-unread`, `thread.visit`, `thread.runtime-mode.set`, `thread.interaction-mode.set`, `thread.model-selection.set` (idle threads; mid-run the pick rides on the next `message.dispatch` as `modelSelection`), `provider.switch` (a change of instance when the server does not resolve command context) and `checkpoint.rollback` (scope and checkpoint, no `restoreFiles`, the way `revertThreadCheckpoint` sends it; the thread stream answers with `checkpoint.rollback-requested`).
  - Every command needs its `type` on the wire; the generated discriminators are `@EncodeDefault(ALWAYS)` because `T3Json` drops defaults.
  - A sent message is reconciled by sequence: the optimistic row goes when its `messageId` shows up, or when the thread's sequence passes the one the dispatch answered.
  - `thread.stop` is internal and rejected from a client. Stop is one `provider-session.detach` per attached session, `reason: "client-requested"`, command id `<base>:detach:<sessionId>` (`stopThreadSession`).
  - `run.interrupt` goes to the newest run that's preparing, starting, running or waiting, with `holdQueue: true` (`interruptThreadTurn`).
  - `thread.mark-unread` fails unless the latest run completed (`Orchestrator.ts:2815`). Archive is refused while a run is attached.
  - `thread.visit` with `visitedAt` = the row's `updatedAt`, deduped per watermark, throttled to one per 10 s unless a completion is unseen (`ThreadVisitWatermark`).
- **`runtime-request.respond`** only works on a request whose `responseCapability.type` is `live`; a user-input request answers unless it's `not_resumable`. Message-mode prompts are dismissed with `thread.user-input.dismiss` instead.
- **`terminal.attach`** (`terminal:operate`): a snapshot first, then events. A snapshot or restart resets the screen and replays `history`; resend the size after every snapshot, the way upstream's `resendSize` does. Terminal id `term-1`, cwd is the worktree else the project root. Cancelling detaches, it never kills.
- **`terminal.observe`** (`terminal:read`): the same events for a read-only session, never starts a terminal. Used when attach is refused.
- **`terminal.write`** and **`terminal.resize`**: writes go out in order. **`terminal.close`** once the shell has exited, like the desktop, or the next attach gets the dead one back.
- **`subscribeVcsStatus`** `{cwd, includeRemote: true}`: `snapshot`, then `localUpdated` and `remoteUpdated` that each replace their half (`applyGitStatusStreamEvent`, `packages/shared/src/git.ts:351`). Each subscription polls, so share one per cwd.
- **`subscribeTerminalMetadata`** `{}` (`terminal:read`): every terminal on the server, `snapshot` then `upsert` and `remove` by thread and terminal id. `hasRunningSubprocess` is what the Sidebar's terminal icon shows (`selectRunningSubprocessTerminalIds`). One subscription for the whole Sidebar.
- **`attachments.createUploadUrl`**, then an HTTP `POST` of the bytes to the signed `relativeUrl` with `Content-Type` set (the web app's `uploadBytes`), then the minted `attachmentId` goes straight into `message.dispatch.attachments`. The desktop never calls `assets.persistChatAttachments` after an upload; that RPC takes data-URL images and the plugin only keeps it for completeness. Removing a chip before send cancels the transfer and calls **`attachments.delete`** on a minted id.
- **`message.dispatch.context`**: review comments travel as `review-comment` context records with the inline `t3-context://v1/review-comment/<id>` reference appended to the text (`ensureInlineContextReferences`); the provider only sees records the text cites.
- **`server.getConfig`**: the provider catalog for the model picker, and `environment.capabilities.serverResolvedCommandContext` (the server picks queue or steer itself). Cached per connection. Also `environment.label` and the machine kind (`settings.environmentIcon`, else `environment.platform.machine`, else `server`) for the Sidebar's hover card.
- **Pairing scopes**: `/oauth/token` grants the requested scopes intersected with the code's grant and only fails when that's empty. The plugin asks for `orchestration:read`, `orchestration:operate`, `terminal:read`, `terminal:operate`, `filesystem:read`.
- **Stream ends**: a stream waits for a reconnect only when the socket really closed. A server `Die` or `Fail` ends the flow.

## 9. Phase 3 additions

What a new thread from the IDE takes, learned against the same tag on 2026-10-21 (fixture and unit tests; the live check is still to do with `LiveServerTest`).

- **A new thread is a local draft** until its first send, as upstream's `useNewThreadHandler`: a client-minted thread id, the project's `defaultModelSelection` else the first ready provider's default model (`getDefaultServerModel`), `runtimeMode: "full-access"`, `interactionMode: "default"`. The server hears nothing until the first message goes out as `orchestration.launchThread` without `reuseExistingThread` (`bootstrap.createThread`), `workspaceStrategy` `root { branch? }`, `existing_worktree { worktreePath, branch? }` or `worktree { baseRef }`. `thread.create` (`createThread`, `commands.ts:386`) is what "implement plan in new thread" uses upstream; the plugin doesn't send it any more. (Changed 2026-10-24; until then the plugin created the row at once.)
- **`orchestration.launchThread`** (`orchestration:operate`) is how a first message prepares a new worktree: `reuseExistingThread: true` with the thread's id, `workspaceStrategy: { type: "worktree", baseRef, startFromOrigin? }`, `title` the seed and `generateTitle: true`, and the message as `initialMessage { messageId, text, context?, attachments }`. That is what `startThreadTurn` does when a send carries `bootstrap.prepareWorktree` (`commands.ts:634` to `686`). The server claims the thread with `thread.metadata.update { expectedEmpty: true }` (`ThreadLaunchService.ts:908`) and refuses one that already has messages; `validateReusableThread` runs when no receipt for the command exists. The answer is `{ threadId, projection, resumed }`, no sequence: the optimistic row only retires when the thread stream carries its `messageId`. Clients cannot pick the folder; worktrees go in the server's managed folder (`rpc.ts:1421`).
- **`subscribeWorktreeSetup`** `{threadId}`: `NullOr(WorktreeSetupSnapshot)`, a whole snapshot per change with a rising `sequence`, stages `fetch`, `checkout` (with `percent`), `submodules`, `setup-script` (with a `tail` of output lines, 400 chars each at most) and `agent`. `null` when the server has nothing for the thread, and again after a finished snapshot's grace period; hold the last non-null one and only replace it with a higher sequence (`useWorktreeSetup.ts:95`). A send waits for the `agent` stage to be `done`, not for the script (`blocksSend`). Upstream also records the finished setup as a `worktree-setup` activity on the thread; the plugin reads nothing from that.
- **`worktreeSetup.cancel`** `{threadId}` answers `{cancelled}`. Only a running setup cancels.
- **`vcs.listRefs`** `{cwd, query?, cursor?, limit? (max `GIT_LIST_BRANCHES_MAX_LIMIT`), refKind?, includeMatchingRemoteRefs?}` answers `{refs, isRepo, hasPrimaryRemote, nextCursor, totalCount}`; each ref has `name`, `current`, `isDefault`, `worktreePath` and optional `isRemote` and `remoteName`. The server filters by substring, so query with the sanitised name (`sanitizeNewRefName`). The plugin asks for one page of 100 and doesn't page.
- **`vcs.switchRef`** `{cwd, refName}` and **`vcs.createRef`** `{cwd, refName, switchRef?}` need `source-control:write` (`clientRpcPermissions.ts:41`). A switch to a remote ref makes a local branch and answers its name (`refName`, nullable). The composer's branch picker in local mode calls them on a pick or a typed name (`selectRef`, `createRef` in `BranchToolbarBranchSelector.tsx`), then the next send carries `thread.metadata.update { branch, worktreePath: null }` first when the pick differs from the row. On a server thread, a ref that already has a worktree only records the branch (`setThreadBranch`, `hasServerThread`); the worktree reuse is a draft-only path upstream.
- **When a thread can still choose**: `canOverrideServerThreadEnvMode` (`ChatView.tsx:4611`) is a server thread with `messages.length == 0`, `worktreePath == null` and nothing running. The base branch defaults to the thread's `branch` else the checkout's `refName` from `subscribeVcsStatus` (`resolveBranchToolbarValue`). Outside a git repo everything runs locally (`resolveSendEnvMode`).

