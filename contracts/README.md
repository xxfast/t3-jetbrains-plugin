# Contracts

T3 Code's wire types live in [`packages/contracts`](https://github.com/pingdotgg/t3code/tree/main/packages/contracts)
of the upstream repo as Effect schemas. We don't port them by hand:

- `T3CODE_VERSION` pins the upstream tag.
- `export.ts` loads upstream's schemas at that tag and writes JSON Schema into `schema/`, one file per
  schema in its `CURATED` list.
- `generate.ts` turns `schema/` into `kotlinx.serialization` classes in
  `:protocol`'s `io.github.xxfast.t3code.protocol.generated`.

Both outputs are checked in. The plugin reads the generated classes through `:protocol`'s
`Models.kt`, which is only aliases (`Project = ProjectShell`, `Thread = ThreadShell`,
`ServerSession = AuthSessionState`, ...) plus the few things that aren't wire shapes: `Credential`,
`ThreadView`, and extension properties like `Thread.archived`. A regenerated class name moves one
alias, not the plugin. Typealiases don't reach nested classes, so code that names a branch
(`TurnItem.FileChange`) imports `generated.TurnItem` directly.

## Bump the tag and regenerate

Needs Node 24 or later. Set `T3CODE_VERSION` to the new tag, then from the repo root:

```sh
npm ci --prefix contracts
node contracts/export.ts
node contracts/generate.ts
```

`export.ts` shallow-clones upstream into `contracts/.upstream` at the pinned tag (or checks an existing
clone is at it; delete it to re-clone). `--upstream <dir>` points it at another checkout, `--out <dir>`
somewhere other than `schema/`, `--tag <tag>` overrides `T3CODE_VERSION`. Then
`./gradlew :protocol:build` and fix what broke.

To export another schema, add its exported name and module to `CURATED` in `export.ts`.

## What the exporter does

Effect 4 has no `JSONSchema.make`. The exporter uses `Schema.toJsonSchemaDocument`, which describes
the JSON the server actually sends (`Schema.toCodecJson`). Effect inlines almost everything, so the
exporter puts the names back: every upstream export that's an object or a union of objects is
fingerprinted, and any matching subtree becomes a `$ref` into `$defs`. Output is deterministic,
so CI can diff it.

If a curated schema fails to export, it writes `<name>.error.txt` next to the others, warns, and
keeps going.

## What the generator does with shapes it can't handle

- Objects become `@Serializable public data class`es. Required fields are plain. Optional fields
  are nullable and default to `null`; optional arrays and maps default to empty. `NullOr` fields
  are nullable with no default.
- Literal string enums stay `String`, so unknown values still decode.
- Unions of objects with a literal discriminator (`type`, `_tag`, `kind`, ...) become a sealed
  interface. Each branch gets its own class, and an `Unknown` branch carries the raw `JsonObject`.
  Upstream's catch-all members are left to `Unknown`.
- Union decoding is tolerant: the serializer picks the branch by discriminator, and if that branch
  fails to decode (a required field missing, a field of the wrong shape) the item lands as
  `Unknown` with its raw JSON instead of failing the whole payload. Everything else stays strict,
  so a project, thread or run missing a required field still fails to decode.
- Anything else (`Schema.Unknown`, objects with no declared fields, unions with no discriminator)
  becomes `JsonElement` with a `// TODO(contracts): <why>` comment, plus a warning on stderr.
- A `$ref` to nothing, or a cycle it can't break with a class, fails the run with the path.

## Nightly

The `nightly` job in `.github/workflows/contracts.yml` resolves the newest upstream `v*-preview.*`
tag. It exports that tag into a temp dir and diffs the result against `schema/`. On any drift it
opens one issue, "Upstream contracts drifted", labelled `contracts`. Later runs comment on that
issue only when the drift changes. The `check` job, on every PR and push to `main`, regenerates at
the pinned tag and fails if the checked-in output is out of sync.
