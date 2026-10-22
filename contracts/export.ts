// Dumps upstream's Effect schemas to JSON Schema, one file per curated schema.
//
// Usage: node contracts/export.ts [--upstream <dir>] [--out <dir>] [--tag <tag>]
//
// Upstream defaults to contracts/.upstream, shallow-cloned at the tag in T3CODE_VERSION when
// absent. Out defaults to contracts/schema. --tag overrides T3CODE_VERSION (the nightly uses it).
//
// Effect inlines nearly everything, so the exporter puts the names back: every upstream export
// that is an object or a union of objects is fingerprinted, and any subtree of a curated schema
// that matches one becomes a `$ref` into `$defs`. That keeps TurnItem one class, not a copy in
// every file that embeds it.

import { execFileSync } from "node:child_process";
import { createHash } from "node:crypto";
import { existsSync, mkdirSync, readdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { registerHooks } from "node:module";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

// Bump-sensitive: the curated list. Module paths are relative to packages/contracts/src.
export const CURATED: ReadonlyArray<readonly [name: string, module: string]> = [
  ["AuthSessionState", "auth.ts"],
  ["AuthAccessTokenResult", "auth.ts"],
  ["OrchestrationProjectShell", "orchestrationProject.ts"],
  ["OrchestrationV2ThreadShell", "orchestrationV2.ts"],
  ["OrchestrationV2ThreadProjection", "orchestrationV2.ts"],
  ["OrchestrationV2TurnItem", "orchestrationV2.ts"],
  ["OrchestrationV2Run", "orchestrationV2.ts"],
  ["OrchestrationV2RuntimeRequest", "orchestrationV2.ts"],
  ["OrchestrationV2DomainEvent", "orchestrationV2.ts"],
  ["OrchestrationV2SubscribeShellInput", "orchestrationV2.ts"],
  ["OrchestrationV2ShellStreamItem", "orchestrationV2.ts"],
  ["OrchestrationV2SubscribeThreadInput", "orchestrationV2.ts"],
  ["OrchestrationV2ThreadStreamItem", "orchestrationV2.ts"],
  ["OrchestrationGetTurnDiffInput", "checkpointDiff.ts"],
  ["OrchestrationGetTurnDiffResult", "checkpointDiff.ts"],
  ["OrchestrationGetFullThreadDiffInput", "checkpointDiff.ts"],
  ["OrchestrationGetFullThreadDiffResult", "checkpointDiff.ts"],
  ["ProjectReadFileInput", "project.ts"],
  ["ProjectReadFileResult", "project.ts"],
  ["ProjectSearchEntriesInput", "project.ts"],
  ["ProjectSearchEntriesResult", "project.ts"],
  ["PullRequestListInput", "pullRequest.ts"],
  ["PullRequestListResult", "pullRequest.ts"],
  ["PullRequestRef", "pullRequest.ts"],
  ["PullRequestDetail", "pullRequest.ts"],
  ["OrchestrationV2Command", "orchestrationV2.ts"],
  ["OrchestrationV2DispatchCommandResult", "orchestrationV2.ts"],
  ["TerminalAttachInput", "terminal.ts"],
  ["TerminalWriteInput", "terminal.ts"],
  ["TerminalResizeInput", "terminal.ts"],
  ["TerminalCloseInput", "terminal.ts"],
  ["TerminalSessionSnapshot", "terminal.ts"],
  ["TerminalAttachStreamEvent", "terminal.ts"],
  ["TerminalMetadataStreamEvent", "terminal.ts"],
  ["VcsStatusSubscriptionInput", "git.ts"],
  ["VcsStatusStreamEvent", "git.ts"],
  ["AttachmentCreateUploadUrlInput", "assets.ts"],
  ["AttachmentCreateUploadUrlResult", "assets.ts"],
  ["PersistChatAttachmentsInput", "chatAttachment.ts"],
  ["PersistChatAttachmentsResult", "chatAttachment.ts"],
  ["ServerProvider", "server.ts"],
  ["OrchestrationMessageContext", "composerContext.ts"],
  ["OrchestrationV2ThreadLaunchInput", "orchestrationV2.ts"],
  ["OrchestrationV2ThreadLaunchResult", "orchestrationV2.ts"],
  ["WorktreeSetupSubscribeInput", "worktreeSetup.ts"],
  ["WorktreeSetupSnapshot", "worktreeSetup.ts"],
  ["WorktreeSetupCancelInput", "worktreeSetup.ts"],
  ["WorktreeSetupCancelResult", "worktreeSetup.ts"],
  ["VcsListRefsInput", "git.ts"],
  ["VcsListRefsResult", "git.ts"],
  ["VcsSwitchRefInput", "git.ts"],
  ["VcsSwitchRefResult", "git.ts"],
  ["VcsCreateRefInput", "git.ts"],
  ["VcsCreateRefResult", "git.ts"],
];

const UPSTREAM_REPO = "https://github.com/pingdotgg/t3code.git";
const HERE: string = dirname(fileURLToPath(import.meta.url));

type Json = null | boolean | number | string | Json[] | { [key: string]: Json };
type JsonObject = { [key: string]: Json };

function arg(name: string): string | undefined {
  const index = process.argv.indexOf(`--${name}`);
  return index === -1 ? undefined : process.argv[index + 1];
}

function warn(message: string): void {
  process.stderr.write(`warning: ${message}\n`);
}

function prepareUpstream(dir: string, tag: string): void {
  if (!existsSync(dir)) {
    console.log(`Cloning ${UPSTREAM_REPO} at ${tag} into ${dir}`);
    const clone: string[] =
      ["clone", "--quiet", "--depth", "1", "--branch", tag, UPSTREAM_REPO, dir];
    execFileSync("git", ["-c", "advice.detachedHead=false", ...clone], { stdio: "inherit" });
    return;
  }
  const head = execFileSync("git", ["-C", dir, "rev-parse", "HEAD"]).toString().trim();
  let tagged: string;
  try {
    tagged = execFileSync("git", ["-C", dir, "rev-parse", `${tag}^{commit}`]).toString().trim();
  } catch {
    throw new Error(`${dir} has no tag ${tag}. Delete it or point --upstream at a checkout of it.`);
  }
  if (head !== tagged) throw new Error(`${dir} is at ${head}, not ${tag} (${tagged}).`);
}

// Upstream imports `effect` as a bare specifier and has no node_modules. Resolve it from here.
function resolveEffectFromContracts(): void {
  const parentURL: string = pathToFileURL(join(HERE, "export.ts")).href;
  registerHooks({
    resolve(specifier, context, next) {
      if (specifier === "effect" || specifier.startsWith("effect/")) {
        return next(specifier, { ...context, parentURL });
      }
      return next(specifier, context);
    },
  });
}

const IGNORED_KEYS: ReadonlySet<string> = new Set([
  "description",
  "title",
  "examples",
  "default",
  "$comment",
]);

function canonical(node: Json): string {
  if (Array.isArray(node)) return `[${node.map(canonical).join(",")}]`;
  if (node === null || typeof node !== "object") return JSON.stringify(node);
  const keys: string[] = Object.keys(node).filter((key) => !IGNORED_KEYS.has(key)).sort();
  return `{${keys.map((key) => `${JSON.stringify(key)}:${canonical(node[key])}`).join(",")}}`;
}

// Upstream's ForwardCompatibleUnion adds a catch-all member, `{ <tag>: string }`, after the known
// ones. The generated code has its own Unknown branch, so it doesn't change what a union is.
function isFallbackMember(node: Json): boolean {
  if (!isObject(node) || node.type !== "object" || !isObject(node.properties)) return false;
  const keys: string[] = Object.keys(node.properties);
  if (keys.length !== 1) return false;
  const tag: Json = node.properties[keys[0]];
  return isObject(tag) && tag.type === "string" && tag.enum === undefined;
}

function withoutFallback(node: Json): Json {
  if (!isObject(node)) return node;
  for (const key of ["anyOf", "oneOf"]) {
    const members: Json = node[key];
    if (Array.isArray(members) && members.length > 2 && isFallbackMember(members.at(-1)!)) {
      return { ...node, [key]: members.slice(0, -1) };
    }
  }
  return node;
}

function fingerprint(node: Json): string {
  return createHash("sha256").update(canonical(withoutFallback(node))).digest("hex");
}

function isObject(node: Json): node is JsonObject {
  return node !== null && typeof node === "object" && !Array.isArray(node);
}

function isObjectSchema(node: Json): boolean {
  return isObject(node) && node.type === "object" && isObject(node.properties) &&
    Object.keys(node.properties).length > 0;
}

// Only objects and unions of objects get names. Strings, enums and nullable wrappers stay inline.
function isNameable(node: Json): boolean {
  if (isObjectSchema(node)) return true;
  if (!isObject(node)) return false;
  const members: Json = node.anyOf ?? node.oneOf;
  return Array.isArray(members) && members.length > 1 && members.every(isObjectSchema);
}

function renameRefs(node: Json, from: string, to: string): Json {
  if (Array.isArray(node)) return node.map((child) => renameRefs(child, from, to));
  if (!isObject(node)) return node;
  const out: JsonObject = {};
  for (const [key, value] of Object.entries(node)) {
    out[key] = key === "$ref" && value === `#/$defs/${from}`
      ? `#/$defs/${to}`
      : renameRefs(value, from, to);
  }
  return out;
}

const SELF = "__self__";

interface Export {
  readonly name: string;
  readonly value: unknown;
}

interface Named {
  /** Inline schema of the export, Effect's synthetic definitions renamed after their export. */
  readonly schema: Json;
}

interface Document {
  readonly schema: Json;
  readonly definitions: Record<string, Json>;
}

function isBareRef(node: Json): boolean {
  return isObject(node) && typeof node.$ref === "string" && Object.keys(node).length === 1;
}

function refName(node: Json): string {
  return String((node as JsonObject).$ref).replace("#/$defs/", "");
}

async function main(): Promise<void> {
  const tag: string = arg("tag") ??
    readFileSync(join(HERE, "T3CODE_VERSION"), "utf8").trim();
  const upstream: string = resolve(arg("upstream") ?? join(HERE, ".upstream"));
  const out: string = resolve(arg("out") ?? join(HERE, "schema"));

  prepareUpstream(upstream, tag);
  resolveEffectFromContracts();

  const { Schema } = await import("effect");
  const src: string = join(upstream, "packages", "contracts", "src");
  const toDocument = (value: unknown): Document =>
    Schema.toJsonSchemaDocument(value as never) as unknown as Document;

  // Every exported schema upstream, in a stable order.
  const modules: Record<string, Record<string, unknown>> = {};
  for (const file of readdirSync(src).filter((f) => f.endsWith(".ts") && !f.endsWith(".test.ts"))
    .sort()) {
    try {
      modules[file] = await import(pathToFileURL(join(src, file)).href);
    } catch (error) {
      warn(`skipped upstream module ${file}: ${String(error).split("\n")[0]}`);
    }
  }

  const exports: Export[] = [];
  for (const [, module] of Object.entries(modules)) {
    for (const [name, value] of Object.entries(module).sort(([a], [b]) => a.localeCompare(b))) {
      if (Schema.isSchema(value)) exports.push({ name, value });
    }
  }

  // Aliases (`export const A = B`) share one object. The shortest name wins.
  const curatedNames: ReadonlySet<string> = new Set(CURATED.map(([name]) => name));
  const rank = (name: string): [number, number, string] =>
    [curatedNames.has(name) ? 0 : 1, name.length, name];
  const better = (a: string, b: string): boolean => {
    const [ra, rb] = [rank(a), rank(b)];
    return ra[0] !== rb[0] ? ra[0] < rb[0] : ra[1] !== rb[1] ? ra[1] < rb[1] : ra[2] < rb[2];
  };
  const aliasOf = new Map<unknown, string>();
  for (const { name, value } of exports) {
    const current: string | undefined = aliasOf.get(value);
    const shorter = (a: string, b: string): boolean =>
      a.length !== b.length ? a.length < b.length : a < b;
    if (current === undefined || shorter(name, current)) aliasOf.set(value, name);
  }

  // Fingerprint every nameable export. Recursive ones first: other exports embed them through
  // Effect's synthetic definition names, which get renamed after them before fingerprinting.
  const byFingerprint = new Map<string, string>();
  const named = new Map<string, Named>();
  const register = (name: string, schema: Json, print: string): void => {
    const existing: string | undefined = byFingerprint.get(print);
    if (existing === undefined || better(name, existing)) byFingerprint.set(print, name);
    named.set(name, { schema });
  };

  const documents: Array<[name: string, document: Document]> = [];
  for (const [value, name] of aliasOf) {
    try {
      documents.push([name, toDocument(value)]);
    } catch {
      // Not every upstream export converts. The curated ones report their own errors below.
    }
  }

  const recursiveDefs = new Map<string, Json>();
  for (const [name, { schema, definitions }] of documents) {
    if (!isBareRef(schema)) continue;
    const self: string = refName(schema);
    if (definitions[self] === undefined) continue;
    const body: Json = renameRefs(definitions[self], self, SELF);
    register(name, renameRefs(body, SELF, name), fingerprint(body));
  }
  for (const [name, { schema, definitions }] of documents) {
    if (isBareRef(schema)) continue;
    const { root } = normalize(schema, definitions, recursiveDefs);
    if (isNameable(root)) register(name, root, fingerprint(root));
  }
  const canonicalNames: ReadonlySet<string> = new Set(byFingerprint.values());

  // Renames Effect's synthetic definitions after the export they match, and collects them.
  function normalize(
    schema: Json,
    definitions: Record<string, Json>,
    collected: Map<string, Json>,
  ): { root: Json } {
    const renames = new Map<string, string>();
    for (const [key, body] of Object.entries(definitions)) {
      const match: string | undefined = byFingerprint.get(fingerprint(renameRefs(body, key, SELF)));
      renames.set(key, match ?? key);
    }
    const rename = (node: Json): Json => {
      let out: Json = node;
      for (const [from, to] of renames) if (from !== to) out = renameRefs(out, from, to);
      return out;
    };
    for (const [key, body] of Object.entries(definitions)) {
      const target: string = renames.get(key)!;
      if (!collected.has(target)) collected.set(target, rename(body));
    }
    return { root: rename(schema) };
  }

  // Replace matching subtrees with refs, top-down so the biggest match wins.
  function extract(node: Json, defs: Map<string, Json>, root: boolean): Json {
    if (Array.isArray(node)) return node.map((child) => extract(child, defs, false));
    if (!isObject(node)) return node;
    if (!root && isNameable(node)) {
      const match: string | undefined = byFingerprint.get(fingerprint(node));
      if (match !== undefined) {
        define(match, defs);
        return { $ref: `#/$defs/${match}` };
      }
    }
    const out: JsonObject = {};
    for (const [key, value] of Object.entries(node)) out[key] = extract(value, defs, false);
    return out;
  }

  function define(name: string, defs: Map<string, Json>): void {
    if (defs.has(name)) return;
    const entry: Named | undefined = named.get(name);
    const schema: Json | undefined = entry?.schema ?? recursiveDefs.get(name);
    if (schema === undefined) throw new Error(`no schema for ${name}`);
    defs.set(name, {}); // reserve it so recursion lands on the ref
    defs.set(name, extract(schema, defs, true));
  }

  rmSync(out, { recursive: true, force: true });
  mkdirSync(out, { recursive: true });

  const exported: string[] = [];
  const failed: string[] = [];
  for (const [name, file] of CURATED) {
    const value: unknown = modules[file]?.[name];
    try {
      if (value === undefined) throw new Error(`${file} does not export ${name}`);
      if (!Schema.isSchema(value)) throw new Error(`${file}#${name} is not a Schema`);
      const document: Document = toDocument(value);
      const defs = new Map<string, Json>();
      const original: string | undefined = aliasOf.get(value);
      let schema: Json;
      if (original !== undefined && original !== name && canonicalNames.has(original)) {
        // `export const OrchestrationGetTurnDiffResult = ThreadTurnDiff`: point at the original.
        define(original, defs);
        schema = { $ref: `#/$defs/${original}` };
      } else {
        // A root with checks or recursion comes back as a bare `$ref`. Unwrap it so the file's
        // own schema is the shape, and self references point at the file's `$id`.
        let top: Json = document.schema;
        let definitions: Record<string, Json> = document.definitions;
        if (isBareRef(top) && definitions[refName(top)] !== undefined) {
          const self: string = refName(top);
          const { [self]: body, ...rest } = definitions;
          top = renameRefs(body, self, name);
          definitions = Object.fromEntries(
            Object.entries(rest).map(([key, def]) => [key, renameRefs(def, self, name)]),
          );
        }
        const local = new Map<string, Json>();
        const { root } = normalize(top, definitions, local);
        for (const [key, body] of local) {
          if (!defs.has(key)) {
            defs.set(key, {});
            defs.set(key, extract(body, defs, true));
          }
        }
        schema = extract(root, defs, true);
        defs.delete(name);
      }
      const sorted: JsonObject = {};
      for (const key of [...defs.keys()].sort()) sorted[key] = defs.get(key)!;
      const output: JsonObject = {
        $schema: "https://json-schema.org/draft/2020-12/schema",
        $id: name,
        title: name,
        ...(schema as JsonObject),
        ...(defs.size > 0 ? { $defs: sorted } : {}),
      };
      writeFileSync(join(out, `${name}.json`), `${JSON.stringify(output, null, 2)}\n`);
      exported.push(name);
    } catch (error) {
      const message: string = error instanceof Error
        ? (error.stack ?? error.message)
        : String(error);
      writeFileSync(join(out, `${name}.error.txt`), `${message}\n`);
      warn(`${name} did not export: ${message.split("\n")[0]}`);
      failed.push(name);
    }
  }

  console.log(`Exported ${exported.length} schemas from ${tag} into ${out}`);
  if (failed.length > 0) console.log(`Failed: ${failed.join(", ")} (see *.error.txt)`);
}

await main();
