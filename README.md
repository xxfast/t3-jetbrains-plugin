# T3 Code for JetBrains

A native JetBrains plugin for [T3 Code](https://github.com/pingdotgg/t3code). Threads, tool calls and
permission prompts in a tool window; files, diffs and terminals in the IDE you already have.

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/screenshot-dark.png">
  <source media="(prefers-color-scheme: light)" srcset="docs/screenshot-light.png">
  <img alt="T3 Sidebar, a Chat View tab and T3 Thread Details in IntelliJ IDEA" src="docs/screenshot-light.png">
</picture>

Unofficial. Built for my own use, published in case it's useful to others.

## Status

0.1.0 is the first release. Built and tested on IntelliJ IDEA, against T3 Code 0.1.2. Android
Studio and Rider are checked by the plugin verifier on every build. Other IDEs on IntelliJ
Platform 2025.3 and later share IDEA's platform build and should work; report it if one doesn't.
What each release adds is in its [release notes](https://github.com/xxfast/t3-jetbrains-plugin/releases);
what's next is in the [milestones](https://github.com/xxfast/t3-jetbrains-plugin/milestones).

## Install

1. Download the zip from [Releases](https://github.com/xxfast/t3-jetbrains-plugin/releases).
2. Settings > Plugins > gear > Install Plugin from Disk, pick the zip.
3. In T3's desktop app, Settings > Connections, make a pairing code or link. Paste it into the
   banner in the T3 Sidebar tool window, or Settings > Tools > T3 Code > Pair.
4. The server URL defaults to the desktop app's `http://127.0.0.1:3773`. Change it in
   Settings > Tools > T3 Code for any other server.
5. Paired before and a control is locked, the branch picker say? The plugin asked for fewer scopes
   back then. Settings > Tools > T3 Code > Forget, then pair again.

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
- **Design in the harness.** Int UI is the design system, so we prototype in it: the plugin runs in a
  sandbox IDE against JSON fixtures in `harness/`. No mockups, nothing built twice.

## Layout

| Module | What |
| --- | --- |
| `:protocol` | Plain Kotlin client for the T3 server. No IDE dependency. Reusable for a CLI or anything else. |
| `:plugin` | The JetBrains plugin. Jewel UI on top of `:protocol`. |
| `contracts/` | Pinned upstream version and the schema generator. |
| `harness/` | Fixture scenarios for the design harness. `./gradlew runHarness` opens a sandbox IDE on them. |

Targets IntelliJ Platform 2025.3 and later, so IDEA, Android Studio, WebStorm, Rider and friends.

## Roadmap

Work items are [GitHub issues](https://github.com/xxfast/t3-jetbrains-plugin/issues).
A [milestone](https://github.com/xxfast/t3-jetbrains-plugin/milestones) per release holds them.

## Licence

MIT. Not affiliated with T3 Tools.
