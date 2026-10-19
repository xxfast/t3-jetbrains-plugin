# T3 Code for JetBrains

A native JetBrains plugin for [T3 Code](https://github.com/pingdotgg/t3code). Threads, tool calls and
permission prompts in a tool window; files, diffs and terminals in the IDE you already have.

Unofficial, built for my own use. Will publish if it turns out useful to others.

## Status

Design only. No code yet.

## Why

T3 Code is great at running agents and bad at being an editor. The IDE is the other way round.
T3's desktop app is a thin shell over a local server with an HTTP and WebSocket API, so a second
client is a supported shape, not a hack.

## Design

- **Mirror, don't reimplement.** The server projects state; the plugin displays it. Logic stays upstream.
- **Generate the contracts.** Wire types come from T3's Effect schemas, pinned to a tag in `contracts/`.
- **Render generically, specialise lazily.** Unknown events show as plain cards. Nothing crashes on a
  newer server. Custom renderers only for things worth looking at every day.
- **The IDE does the IDE things.** Diff viewer, editor, terminal, git, run configs. We only open them.
- **JCEF is the escape hatch.** Anything not worth owning is a web view onto the server.

## Planned layout

| Module | What |
| --- | --- |
| `:protocol` | Plain Kotlin client for the T3 server. No IDE dependency. Reusable for a CLI or anything else. |
| `:plugin` | The JetBrains plugin. Jewel UI on top of `:protocol`. |
| `contracts/` | Pinned upstream version and the schema generator. |

Targets IntelliJ Platform 2025.3 and later, so IDEA, Android Studio, WebStorm, Rider and friends.

## Roadmap for v0

1. Pair with a local or remote server, bearer token auth.
2. Thread list and thread view.
3. Open file and diff from a thread.
4. Generated contracts and a nightly contract check against the latest upstream release.

## Licence

MIT. Not affiliated with T3 Tools.
