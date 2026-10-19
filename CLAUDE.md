# CLAUDE.md

Guidance for Claude Code when working in this repository.

## What this is

A native JetBrains plugin that is a client for T3 Code's server. Nothing is built yet; this repo holds
the design and the decisions so far. Read `README.md` first, then `contracts/README.md`.

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
- v0 scope: pair with a server, thread list, thread view, open file and diff. Nothing else.

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

## Style

Kotlin: 2-space indent, 100 columns, trailing commas, explicit types where not obvious, no wildcard
imports. Prose: short, casual, no em-dashes.
