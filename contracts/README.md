# Contracts

T3 Code's wire types live in [`packages/contracts`](https://github.com/pingdotgg/t3code/tree/main/packages/contracts)
of the upstream repo as Effect schemas. We don't port them by hand. The plan:

1. `T3CODE_VERSION` pins the upstream tag we build against. Bump it, regenerate, fix what broke.
2. A script checks out that tag, loads the schemas, and exports JSON Schema via `effect`'s `JSONSchema.make`.
3. A generator turns that into `kotlinx.serialization` classes in `:protocol`, every class tolerant of
   unknown fields and every tagged union carrying a fallback case.

Until step 2 and 3 exist, `:protocol` hand-writes the handful of shapes it needs and keeps the rest as
`JsonElement`.

The nightly CI job will run the export against the latest upstream release and fail if our pinned
schemas no longer decode its fixtures. That's how we find out about breaking changes before a session does.
