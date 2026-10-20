# Roadmap

Work items are [GitHub issues](https://github.com/xxfast/t3-jetbrains-plugin/issues). This file
groups them into phases and holds what the issue list can't show: what each phase achieves and the
order to do things in. Phases are not versions. Versions come when something's ready to release,
see `Later`.

## Working an item

- Point an agent at an issue number. The issue body has **Goal**, **Done when** and **Notes**.
  **Done when** is the acceptance test. The agent should be able to finish from the issue,
  `README.md`, `CLAUDE.md` and `contracts/README.md` alone.
- Do items in the order listed here unless the issue says otherwise. One issue, one PR.
- Issues close as they land, with the commit in the close comment. Nothing gets ticked here.
- Issues name upstream RPC methods (`orchestration.subscribeThread`) and HTTP routes
  (`/api/auth/session`). Those come from `packages/contracts/src/rpc.ts` and `environmentHttp.ts`
  at the tag in `contracts/T3CODE_VERSION`. Verify against that tag, not `main`.
- Scope creep goes to a new issue or to `Later` below, not into the item you're on.
- Principles for every item live in `CLAUDE.md`. Short version: mirror server state, decode with
  `ignoreUnknownKeys`, render unknown things generically, let the IDE do IDE things.
- UI items (#3 to #6 and most of phase 2) start in the design harness. Add or extend a scenario in
  `harness/scenarios`, design against it, then wire the real client. The PR carries a screenshot
  from the harness. See "Design harness" in `CLAUDE.md`.

## Phase 1: it connects, it shows threads

Open the tool window, pair with the local desktop server, see your threads, click a file or diff and
land in the editor. Nothing else.

1. #1 Gradle scaffold with `:protocol` and `:plugin`
2. #23 Design harness: fixture client, `harness/scenarios`, scenario picker, `runHarness` task.
   Every UI item below is designed in it first, so it comes before them.
3. #2 Hand-written `:protocol` client: handshake, auth, WebSocket. Can run in parallel with the
   harness; they meet at the client interface.
4. #3 Pairing flow in the IDE
5. #4 Thread list
6. #5 Thread view
7. #6 Open file and diff from a thread
8. #7 Generated contracts and nightly contract check
9. #24 Two tool windows: the Sidebar and the ChatView

## Phase 2: you can work from the IDE

Everything you'd do in the desktop app for a thread that's already running, you can do from the tool
window. Starting new work still happens in the desktop app.

1. #9 Send a message
2. #10 Respond to approvals and user-input questions
3. #11 Stop, interrupt, archive, pin
4. #12 Terminal in the IDE
5. #13 Project and worktree awareness
6. #14 Attachments and context
7. #15 Diff review with inline comments
8. #16 Settings sync and keybindings

## Phase 3: you can start work from the IDE

The desktop app becomes optional for day-to-day use. It still owns provider setup, server lifecycle
and anything administrative.

1. #17 New thread
2. #18 Pull requests
3. #19 Subagents and delegated tasks
4. #20 Multiple servers
5. #21 Scheduled tasks, read-only
6. #22 JCEF escape hatch

## Sidebar parity

The Sidebar does everything upstream's `Sidebar.tsx` does, not just show threads. Most of the
commands are already generated in `Command.kt`. Independent of phase 3 except where an issue says.

1. #25 Settle and un-settle threads
2. #26 Snooze and wake threads
3. #27 Rename, regenerate title and delete threads
4. #28 Thread context menu parity
5. #31 Undo archive and delete
6. #32 Sidebar row details: hover card, diff stat, indicators
7. #29 Drag to reorder and move threads between sections
8. #30 Multi-select and bulk actions
9. #33 Sidebar search parity
10. #34 Sidebar keyboard navigation
11. #35 Drag files onto threads and threads into the composer
12. #36 Sidebar empty state and project picker
13. #38 Project settings from the thread menu

## Later

Not scheduled and not fleshed out. When one gets picked up it becomes an issue.

- Public release (#8). Signed zip on a GitHub release, then the Marketplace. Not until the
  plugin's ready for people other than me, whichever phase that lands in.
- Android Studio and Rider specific testing. Same platform, different bundled modules, worth a
  pass before saying "supported".
- Usage and cost summary from `server.getUsageSummary` on the thread header.
- Device and preview panes (`device.*`, `preview.*`). Probably JCEF forever.
- Notifications routed through the IDE's event log with per-thread mute.
- A CLI that reuses `:protocol`. It's why `:protocol` has no IDE dependency.
- Offline mode: cached last-known snapshot when the server's unreachable.
- Publish `:protocol` to Maven Central separately.
