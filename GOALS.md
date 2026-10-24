# Goals

What this project is for. Everything else (the milestones, the issues, `CLAUDE.md`) serves these.
When two of them pull against each other, the order below is the tie-break.

## 1. A faithful T3 Code client

The plugin is a client for T3 Code's server, not a reimagining of it. Anyone who uses the
desktop app should land in the plugin and already know where everything is.

- Names align. Screens, components and tool windows carry upstream's names: the thread list is
  the **Sidebar**, the conversation is the **ChatView**, the input is the **ChatComposer**. No
  friendlier names.
- Behaviour aligns. Sorting, grouping, labels, badges, what a row shows, what a search ranks:
  ported one-to-one from `apps/web`, same function names, same edge cases, with a comment saying
  which upstream file it mirrors. Upstream drifts; a port is a diff away from catching up, a
  reinterpretation isn't.
- State is the server's. The plugin mirrors it and never reimplements T3 logic. Unknown things
  render generically rather than breaking.

## 2. A native IDEA plugin

It should feel like it shipped with the IDE. An IDEA user shouldn't have to learn a second set of
controls to use it.

- The IDE's own surfaces first: tool windows, editor tabs, the diff viewer, the terminal,
  notifications, `JBPopup` choosers, Find Action, the keymap. Files, diffs and terminals open in
  the IDE, they aren't rebuilt inside the plugin.
- Int UI is the design system. Jewel for the Compose parts, Swing for the rest, whichever the
  IDE already uses for that kind of control. No web-styled chrome, no custom popovers where a
  standard popup exists.
- IDE conventions over web conventions when they differ: speed search, Tab to switch tabs in a
  chooser, Esc to close, the IDE's icons and spacing.

## When they conflict

Faithful says "copy the layout". Native says "use the IDE's control". Resolve it like this:

- **Logic is faithful, chrome is native.** Port the rules (what's listed, how it's sorted, what's
  disabled and why) exactly. Render them with the IDE's components, laid out the way the IDE
  lays out that kind of thing. The model picker is the worked example: upstream's ranking and
  grouping, inside a Search Everywhere-shaped `JBPopup`.
- Names never bend. A native-looking control still carries the upstream name.
- When a native control can't express an upstream rule, keep the rule and note what the control
  dropped, so it's visible when the IDE grows the ability.
