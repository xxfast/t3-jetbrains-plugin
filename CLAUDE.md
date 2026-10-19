# CLAUDE.md

Guidance for Claude Code when working in this repository.

## What this is

A native JetBrains plugin that is a client for T3 Code's server. Nothing is built yet; this repo holds
the design and the decisions so far. Read `README.md` first, then `contracts/README.md`. Work items are
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
- 0.1 scope: pair with a server, thread list, thread view, open file and diff. Nothing else.
  Milestones on GitHub are product versions (`0.1`, `0.2`, `0.3`), see `ROADMAP.md`.
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
- Scenarios live in `harness/scenarios/*.json`, one file per state worth looking at:
  disconnected, pairing, auth error, empty server, connected list, running thread, waiting on
  approval, unknown item types, a few hundred items. Shapes are the same hand-written `:protocol`
  types the real client decodes, so scenarios double as test fixtures.
- Items may carry an `after` delay in milliseconds. The fixture client replays them in order so a
  "running" thread visibly grows, which is the only way to design streaming.
- The fixture client watches its directory. Edit a JSON file, the tool window updates. No rebuild
  for data changes; a rebuild for Kotlin changes.
- In fixture mode the tool window header shows a scenario picker. In real mode it doesn't exist.
- A Gradle task `runHarness` (an `intellijPlatformTesting.runIde` registration) launches the
  sandbox with the fixtures property set and its own sandbox dir, so harness state never mixes
  with `runIde` against a real server.
- Point the Gradle plugin at a locally installed IDE with `local(...)` when one exists (via a
  Gradle property, see "Things learned"). That skips the 1 GB platform download for the harness.
  The release build still targets `intellijIdea("2025.3")`.

The loop: pick a scenario, look, change Kotlin or JSON, look again. When a screen settles, its
scenario becomes the test fixture and the PR gets a screenshot from the harness.

## Principles

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
- `gradle` isn't installed globally on these machines. Commit the wrapper.
- Claude Design was tried for this project and dropped. It only produces HTML and CSS, doesn't
  know Int UI or Jewel, and the standalone site closes 14 December 2026 (folded into claude.ai
  artifacts). Don't bring it back; the harness replaces it.

## Style

Kotlin: 2-space indent, 100 columns, trailing commas, explicit types where not obvious, no wildcard
imports. Prose: short, casual, no em-dashes.
