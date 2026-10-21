# CLAUDE.md

Guidance for Claude Code when working in this repository.

## What this is

A native JetBrains plugin that is a client for T3 Code's server. Nothing is built yet; this repo holds
the design and the decisions so far. Read `GOALS.md` first (what the project is for and how to
settle a faithful-versus-native call), then `README.md`, then `contracts/README.md`. Work items are
GitHub issues; `ROADMAP.md` has the order. When asked to do an issue, its **Done when** is the
acceptance test.

## Decisions already made

Don't relitigate these. Change them only if the user asks.

- Plugin ID `io.github.xxfast.t3code`, Gradle group `io.github.xxfast`, Marketplace name "T3 Code".
- Native Kotlin client, UI in Jewel (Compose for the IntelliJ Platform). Not a JCEF wrapper, except as an
  escape hatch for views not worth owning.
- Two Gradle modules when the code lands: `:protocol` (plain Kotlin, no IDE dependency, `explicitApi()`)
  and `:plugin` (the IntelliJ Platform plugin, depends on `:protocol`).
- Contracts are generated from upstream's Effect schemas, pinned by `contracts/T3CODE_VERSION`. Never
  hand-port the whole surface.
- Target IntelliJ Platform 2025.3 and later (`sinceBuild = 253`, no `untilBuild`). Jewel is bundled from
  251.2, Android Studio is on 262.
- Phase 1 scope: pair with a server, thread list, thread view, open file and diff. Nothing else.
  Phases live in `ROADMAP.md`, not as GitHub milestones, and they aren't versions. No public
  release, and no version numbers, until it's ready for other people.
- Visual design happens in the **design harness**: the real plugin, in a sandbox IDE, fed by fixture
  data. Not in HTML mockups, not in Claude Design, not in a Compose Desktop standalone. Int UI is
  the design system and it does most of the design work; what's left is layout and states, and
  those are only honest inside the IDE. See "Design harness" below.

## Design harness

The plugin is design-limited by Int UI, so we prototype in the real material. The harness is the
plugin itself running in a sandbox IDE against canned server state instead of a live T3 server.
Nothing gets built twice: the composables designed in the harness are the composables that ship.

How it fits together:

- `:protocol` exposes a client interface (`T3Client` or whatever #2 names it) with `StateFlow`s
  for connection, projects, threads and thread items. The real client implements it over the
  WebSocket. A `FixtureClient` in `:plugin` implements it from JSON files on disk.
- Fixture mode is switched on by a system property (`-Dt3code.fixtures=<dir>`), never by a build
  flavour. The code ships, inert. The plugin's container picks the client at startup from that
  property and nothing else in the plugin knows which one it got.
- Composables take state, not the client. That's what makes fixture and real interchangeable, and
  it's what makes screenshot tests possible later.
- A scenario is a folder in `harness/scenarios/`; the fixture client merges every `*.json` in it.
  `default/` is the thread list plus one demo thread per thread-level state (`running.json`,
  `waiting-approval.json`, `waiting-question.json`, `unknown-items.json`, `many-items.json`,
  `unread.json`, `snoozed.json` with one sleeping and one woken, `worktree-setup.json` with a
  first turn whose worktree is still being prepared), and its `connection.json` grants the
  operate and terminal scopes so every action is live. `disconnected/`, `pairing/`, `auth-error/`, `read-only/` (no operate) and
  `empty-server/` cover connection-level states; the ones with threads carry a byte-for-byte copy
  of `default/list.json`. Shapes are the same `:protocol` types the real client decodes, so
  scenarios double as test fixtures.
- Items may carry an `after` delay in milliseconds. The fixture client replays them in order so a
  "running" thread visibly grows, which is the only way to design streaming.
- The fixture client watches its directory. Edit a JSON file, the tool window updates. No rebuild
  for data changes; a rebuild for Kotlin changes.
- A Gradle task `runHarness` (an `intellijPlatformTesting.runIde` registration) launches the
  sandbox on `harness/scenarios/default` (`-Pt3.scenario=pairing` for another folder) with its own
  sandbox dir, so harness state never mixes with `runIde` against a real server.
- Point the Gradle plugin at a locally installed IDE with `local(...)` when one exists (via a
  Gradle property, see "Things learned"). That skips the 1 GB platform download for the harness.
  The release build still targets `intellijIdea("2025.3")`.

The loop: pick a scenario, look, change Kotlin or JSON, look again. When a screen settles, its
scenario becomes the test fixture and the PR gets a screenshot from the harness.

## Principles

Match T3 Code's own implementation as closely as possible, not just its behaviour. When the
plugin needs a rule the desktop app has (how the sidebar sorts, what a row shows, how a monogram
is derived), port the upstream function from `~/Developer/XXFAST/TS/T3Code` with the same name,
the same precedence and the same edge cases, and say in a comment which upstream file it mirrors.
Upstream drifts; a one-to-one port is a diff away from catching up, a reinterpretation isn't.
Names follow upstream too, down to the tool windows: the thread list is the **Sidebar**
(`Sidebar.tsx`, tool window "T3 Sidebar"), the conversation is the **ChatView** (`ChatView.tsx`,
tool window "T3 Chat View"). Don't invent friendlier names.

The plugin mirrors server state and never reimplements T3 logic. Decode with `ignoreUnknownKeys`, fall
back to a generic renderer for anything unrecognised, and open files, diffs and terminals in the IDE
rather than rebuilding them. Every line of copied T3 logic is a line we maintain when T3 ships a feature.

## Things learned while scaffolding

Hard-won, so recorded here rather than rediscovered.

- `intellijIdeaCommunity(...)` is gone from 2025.3. Use `intellijIdea("2025.3")` in the IntelliJ
  Platform Gradle Plugin (2.19.0 at the time of writing).
- Version catalog: don't name a version `intellij-platform` when a plugin alias `intellij-platform`
  exists. `libs.versions.intellij.platform` becomes ambiguous and the build script fails to compile.
- Jewel comes from the IDE via `bundledModule(...)` for `intellij.platform.jewel.{foundation,ui,
  ideLafBridge,markdown.core,markdown.ideLafBridgeStyling}`, `intellij.libraries.compose.foundation.desktop`
  and `intellij.libraries.skiko`, mirrored as `<module name=.../>` in `plugin.xml`. No Jewel Maven deps.
- Kotlin version tracks what the target IDE bundles, not the newest release. The Compose compiler plugin
  must match the Compose runtime the IDE ships. Check the IDE's `lib/` before bumping.
- Never bundle stdlib or coroutines in the plugin: `kotlin.stdlib.default.dependency=false` plus
  excludes on the plugin's configurations. `:protocol` then needs stdlib as `compileOnly`.
  kotlinx-serialization is the exception: the IDE's `intellij.libraries.kotlinx.serialization.json`
  is an internal module from 2026.3 and the plugin silently fails to load if it depends on it.
  Bundle serialization-json in the plugin instead.
- First plugin build downloads the platform, about 1 GB. On this network that took over 30 minutes and
  was killed. Run it in a terminal, not from an agent with a timeout.
- Local T3 desktop server listens on `127.0.0.1:3773`. `GET /api/auth/session` works unauthenticated
  and is the handshake; it advertises `bearer-access-token` as a session method. `/api/orchestration/
  snapshot` and the rest return 401 `EnvironmentAuthInvalidError` without one. Transport is Effect RPC
  over WebSocket (JSON-RPC and ndjson) plus HTTP routes.
- Auth, from upstream `docs/internals/environment-auth.md`: a non-desktop client pairs with a
  one-time token or pairing link minted in the desktop app, exchanged at `/oauth/token`. Bearer
  clients fetch a short-lived ticket from `/api/auth/websocket-ticket` for the socket upgrade; the
  token never goes in the URL. Every RPC declares a required scope and the server checks it per call.
- Upstream tags are `v0.1.1-preview.*` and `v0.1.1-nightly.*` as of Oct 2026. `T3CODE_VERSION` still
  says `v0.1.0`. Bump before generating contracts.
- The IntelliJ Platform Gradle Plugin can build against an installed IDE: `local(path)` instead of
  `intellijIdea(version)`. Read the path from a Gradle property (`t3.localIde`) or
  `local.properties`, fall back to the download when unset. An installed 2026.3 is fine for the
  harness even though the plugin targets 2025.3; `sinceBuild` is about what we ship, not what we
  run locally.
- The IDE bundles Jewel's Swing bridge as `org.jetbrains.jewel.bridge.JewelComposePanelWrapper`
  in `intellij.platform.jewel.ideLafBridge.jar`, and `com.intellij.platform.compose.JBComposePanel`
  in `intellij.platform.compose.jar`. The tool window content is one of those.
- Ctrl+V never reaches a Compose key handler inside the IDE. The IDE's `$Paste` action takes
  it first: Jewel's `RootDataProviderNode` sets `PASTE_PROVIDER` to its `ComposePasteProvider`
  for every Compose text field, and a `DataSink` keeps the first value for a key, so a node's
  `provideData` can't offer another provider. Seen in the harness on 2026-10-20: an
  `onPreviewKeyEvent` for Ctrl+V was never called while the text field pasted text fine. The
  way in is an action with `use-shortcut-of="$Paste"` plus an `actionPromoter` that puts it
  first when the composer's data key is in the context (`PasteAttachmentAction`). Expect the
  same for any other shortcut the IDE keymap owns.
- `gradle` isn't installed globally on these machines. Commit the wrapper.
- A Compose library goes in non-transitive (`isTransitive = false`) and must be built against
  the Compose the IDE bundles: `javap -constants` on `androidx.compose.runtime.ComposeVersion` in
  `intellij.libraries.compose.runtime.desktop.jar` says which (13000, Compose 1.10, on 2025.3
  and 2026.3). Haze was tried for the composer's glass on 2026-10-20 and dropped; 1.7.2 was
  the last version on Compose 1.10, 1.7.3 and 2.x need Compose 1.12 and newer Kotlin metadata.
- Upstream is cloned at `~/Developer/XXFAST/TS/T3Code`, checked out at the tag in
  `contracts/T3CODE_VERSION`. When a question is "how does T3 decide X", read the source there
  (`apps/web` for the UI rules, `apps/server` for the wire) instead of inferring from screenshots
  or snapshots. Mirror what it does.
- Wire facts from upstream at `v0.1.1-preview.20261019.3339` (full notes were extracted into
  `docs/wire-notes.md` on 2026-10-19):
  - Transport is Effect's RPC envelope over `GET /ws?orchestrationProtocol=2`, one JSON object
    per text frame. Not JSON-RPC, not ndjson. Request: `{"_tag":"Request","id":"0","tag":...,
    "payload":...,"headers":[]}`. Replies: `Chunk`, `Exit`, `Defect`, `Pong`. The client must
    send `{"_tag":"Ack","requestId":...}` after every `Chunk` or the stream stalls. Ping every 5 s.
  - `Authorization: Bearer` on the upgrade request works; the `wsTicket` dance is for browsers.
  - `bootstrapMethods` is advertised, not enforced. A one-time pairing code works on a
    `desktop-managed-local` server. Codes are 12 chars, last 5 minutes, minted by
    `POST /api/auth/pairing-token` (needs `access:write`) or the desktop app's Settings >
    Connections. Links are `<base>/pair#token=CODE`. `/oauth/token` is form-encoded token
    exchange; the answer has no refresh token and no `permissions`, tokens last 30 days.
  - `projects.list` is a constant upstream but not a registered RPC. Projects and the thread
    list both come from the `orchestration.subscribeShell` snapshot. One thread comes from
    `orchestration.subscribeThread`; render `projection.visibleTurnItems[].item`.
  - `subscribeShell` sends the full snapshot first, then partial snapshots (same sequence,
    `resolvedRepositoryIdentityRoots` set, projects only, zero threads). Upsert those; replacing
    state with them empties the thread list. Seen live on 2026-10-19 with `schemaVersion: 2`.
  - Sidebar rules (`apps/web/src/components/Sidebar.tsx`, `client-runtime/state/threadShell.ts`,
    `threadSort.ts`): settled is `settledOverride == "settled"`, not `settledAt`. Hidden rows are
    `lineage.relationshipToParent == "subagent"` and delegated tasks whose `delegatedFrom.threadId`
    is listed. Pinned sort by `pinOrderKey`; active by `activeOrderKey`, keyless first by
    `max(createdAt, unsettledAt)`; settled by `settledAt` then last activity. Inbox row time is
    `latestUserMessageAt ?: updatedAt`.
  - Row icons (`apps/web/src/components/ProjectFavicon.tsx`): `projectIcon` override first
    (monogram, emoji, lucide), else the favicon via `assets.createUrl` with
    `{_tag:"project-favicon", cwd}` (scope `orchestration:read`) and a GET of the signed
    `relativeUrl` (no auth needed; a filename of `project-favicon-missing` means none), else a
    monogram derived from the title (`projectIdentity.ts`). Favicons are often SVG;
    `Image.makeFromEncoded` can't decode those. `com.intellij.ui.svg.loadSvg` can but it's
    `@ApiStatus.Internal` and `verifyPlugin` fails the build on it. Use skiko's
    `org.jetbrains.skia.svg.SVGDOM` (what Compose Desktop uses for SVG resources) instead.
  - No delta events. Streaming text is repeated `turn-item.updated` events carrying the whole
    item with `streaming: true`; upsert by id. A "turn" is a `Run`; `getTurnDiff` counts run
    ordinals.
  - Dates are ISO strings. Branded ids are plain strings. Optional means absent, `NullOr` means
    present and null.
- Dev loop (full notes in `docs/platform-notes.md`): the Gradle plugin auto-reloads a dynamic
  plugin into a running sandbox after `buildPlugin`, but not under the debugger, and it wants
  `buildSearchableOptions = false`. `runHarness` has its own `prepareSandbox_runHarness`. Sandbox
  log is `plugin/build/harness-sandbox/log_runHarness/idea.log` for the harness; `runIde` prints its own
  `IDE logs:` line. Under the debugger, "Reload Changed Classes" works and JBR 25 allows more
  than method-body swaps; changed composables redraw only when state changes. Compose Hot Reload
  can't run inside the IDE. Installing the zip from disk into a running IDE needs no restart
  while the plugin stays dynamic (no components, only `toolWindow` EP, light services).
  Why Gradle auto-reload fails here (2026-10-19, Windows): `buildPlugin` writes to the sandbox's
  `plugins/`, but a running `runIde` or `runHarness` loads from `plugins_runIde/` or
  `plugins_runHarness/`. The matching `prepareSandbox_<task>` targets the right dir but fails on
  Windows with "user-mapped section open": the sandbox has the jars memory-mapped and the IDE
  only unloads after it sees the file change. Kotlin changes mean restarting the sandbox or
  installing the zip from disk; JSON scenario edits still apply live.
- The JDK `HttpClient` must be built with `version(HTTP_1_1)`. On plain http it otherwise sends
  an h2c `Upgrade` on the first request and the T3 server's WebSocket upgrade handler answers
  with an empty 400. `curl --http2` reproduces it; `curl --http1.1` gets the real answer.
- Building against the local 263 can link 263-only APIs (Jewel `MenuComboBox`, `Icon(iconDescriptor=)`,
  `AnActionEvent.coroutineScope`) that don't exist on 253. CI compiling against `intellijIdea("2025.3")`
  plus `verifyPlugin` with a `pluginVerification { ides { } }` block is the guard.
- Testing tiers: plain JUnit for everything IDE-free (most of it); `com.intellij.testFramework.junit5`
  (`@TestApplication`, `projectFixture()`) for services and `PersistentStateComponent`, which needs
  `opentest4j` and `junit:junit:4.13.2` on the test runtime; Starter + Driver (`testIdeUi`) is a
  full IDE launch, minutes per run, can't click inside a Compose panel, so a smoke test asserts on
  service state via `@Remote` stubs, Linux with `xvfb-run`, nightly at most.
- Claude Design was tried for this project and dropped. It only produces HTML and CSS, doesn't
  know Int UI or Jewel, and the standalone site closes 14 December 2026 (folded into claude.ai
  artifacts). Don't bring it back; the harness replaces it.
- Run `runHarness` from a worktree, not the main checkout (2026-10-20). The main checkout has the
  gitignored `contracts/.upstream` clone, and the sandbox indexes it for 25+ minutes. Worktrees
  need `local.properties` copied in.
- The Compose tool window takes a few seconds to first render in the sandbox. A screenshot right
  after activating it shows nothing. Not a bug.
- `git worktree remove` fails on long paths once a sandbox has run inside the worktree. Use
  `cmd /c rd /s /q <path>`, then `git worktree prune`.
- Pairing grants the requested scopes intersected with the code's grant, and only fails when
  that's empty. `T3Auth.PLUGIN_SCOPES` is what the plugin asks for, so a read-only code still
  pairs. The "IntelliJ IDEA" client under the desktop's Settings > Connections shows how many
  scopes it got.
- Generated union discriminators carry `@EncodeDefault(ALWAYS)` because `T3Json` drops defaults,
  and the server rejects a command without `type`.
- `thread.stop` is in upstream's internal command union. Clients detach provider sessions
  instead, upstream's `stopThreadSession`.
- Streams only wait for a reconnect when `RpcConnection.closed` is complete. A server `Die` or
  `Fail` ends the flow, so the UI hosts wrap `thread()` in `threadLoad()` to show it with Retry.
- Upstream's model pick is a composer draft applied on the next turn. Mid-run the plugin keeps it
  as a draft too and sends it with the next message.
- A new thread's workspace (current checkout or a new worktree) is picked in the composer, not
  on creation, as upstream's `BranchToolbar` does: `canOverrideServerThreadEnvMode`
  (`ChatView.tsx:4611`) lets a server thread with no message and no worktree choose until its
  first send. That send goes through `orchestration.launchThread` with `reuseExistingThread`
  and a `worktree` strategy (`commands.ts:634`), not `message.dispatch`. The server claims the
  thread with `thread.metadata.update { expectedEmpty: true }` and refuses one that has
  messages. The answer has no command sequence, so the optimistic message only retires once
  the thread stream acknowledges it. `subscribeWorktreeSetup` streams whole snapshots and
  answers `null` once a finished setup's grace period passes; hold the last one, as upstream's
  `useWorktreeSetup` does. `vcs.listRefs` lists the base branches; the fixture client answers
  a canned set and plays a fake setup over a few seconds.
- `LiveServerTest` (skipped unless `T3_LIVE_TOKEN` and `T3_LIVE_THREAD` are set) is how wire
  changes get checked against the real server. Last passed 2026-10-20 against the local desktop.
- A Search Everywhere-shaped chooser is `com.intellij.ide.actions.BigPopupUI` (public, what
  `SearchEverywhereUI` and `RunAnythingPopupUI` extend), shown with `createComponentPopupBuilder`
  and `showCenteredInCurrentWindow`. Learned on the model picker, 2026-10-21:
  - It measures itself without its list (`ViewType.SHORT`) until a subclass calls
    `updateViewType(FULL)`; Search Everywhere does so when its list fills. Setting the popup's
    size from outside loses to that, and a `setDimensionServiceKey` then remembers the short size.
  - Its list pane asks for `BigPopup.maxListHeight()`; override `createListPane()` to cap it.
  - The new-UI header is UI DSL's `tabbedPaneHeader` on a `JBTabbedPane` with
    `ComplexPopup.HEADER_BACKGROUND`, `BigPopup.headerBorder()` and a bottom line in
    `CustomFrameDecorations.separatorForeground()`. `JBTabbedPane` builds its own label per tab
    and drops the icon; an icon-only tab is `setTabComponentAt(index, JBLabel(icon))`.
  - `JBTextField.emptyText` hides while the field has focus unless
    `TextComponentEmptyText.STATUS_VISIBLE_FUNCTION` says otherwise. `TextIcon` is 0x0 (and logs
    SEVERE) until it has a font.
  - `org.jetbrains.jewel.foundation.LocalComponent` (experimental, not internal) is the Swing
    component hosting a composable, for anchoring a `JBPopup` to Compose content.

## Style

Kotlin: 2-space indent, 100 columns, trailing commas, explicit types where not obvious, no wildcard
imports. Prose: short, casual, no em-dashes.
