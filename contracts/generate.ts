// Turns contracts/schema/*.json into kotlinx.serialization classes in :protocol.
//
// Usage: node contracts/generate.ts [--schema <dir>] [--out <dir>]
//
// One Kotlin file per schema file, shared `$defs` once in Common.kt. Shapes it can't map become a
// `JsonElement` with a `// TODO(contracts)` comment and a warning on stderr. A `$ref` it can't
// resolve, or a cycle it would have to inline, fails the run.

import { existsSync, mkdirSync, readdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { basename, dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const HERE: string = dirname(fileURLToPath(import.meta.url));
const PACKAGE = "io.github.xxfast.t3code.protocol.generated";
const MAX_COLUMNS = 100;

type Json = null | boolean | number | string | Json[] | { [key: string]: Json };
type JsonObject = { [key: string]: Json };

function arg(name: string): string | undefined {
  const index: number = process.argv.indexOf(`--${name}`);
  return index === -1 ? undefined : process.argv[index + 1];
}

function isObject(node: Json | undefined): node is JsonObject {
  return node !== null && typeof node === "object" && !Array.isArray(node);
}

function fail(message: string): never {
  process.stderr.write(`error: ${message}\n`);
  process.exit(1);
}

let warnings = 0;
function warn(message: string): void {
  warnings++;
  process.stderr.write(`warning: ${message}\n`);
}

// Naming

const KEYWORDS: ReadonlySet<string> = new Set([
  "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in",
  "interface", "is", "null", "object", "package", "return", "super", "this", "throw", "true",
  "try", "typealias", "typeof", "val", "var", "when", "while",
]);

// Simple names generated code refers to. A nested class with one of these names would shadow it.
const RESERVED: ReadonlySet<string> = new Set([
  "Any", "Boolean", "Companion", "Double", "Int", "List", "Long", "Map", "Nothing", "String",
  "Unit", "DeserializationStrategy", "KSerializer", "SerialName", "Serializable",
  "SerialDescriptor", "Decoder", "Encoder", "JsonContentPolymorphicSerializer", "JsonDecoder",
  "JsonElement", "JsonEncoder", "JsonObject",
]);

function words(text: string): string[] {
  return text
    .replace(/([a-z0-9])([A-Z])/g, "$1 $2")
    .split(/[^A-Za-z0-9]+/)
    .filter((word) => word.length > 0);
}

function pascal(text: string): string {
  const name: string = words(text).map((w) => w[0].toUpperCase() + w.slice(1)).join("");
  if (name.length === 0) return "Value";
  return /^[0-9]/.test(name) ? `N${name}` : name;
}

function camel(text: string): string {
  const name: string = pascal(text);
  return name[0].toLowerCase() + name.slice(1);
}

function singular(name: string): string {
  if (name.endsWith("ies") && name.length > 4) return `${name.slice(0, -3)}y`;
  if (name.endsWith("ses") || name.endsWith("xes")) return name.slice(0, -2);
  if (name.endsWith("s") && !name.endsWith("ss") && name.length > 3) return name.slice(0, -1);
  return `${name}Item`;
}

/** Kotlin property name and whether it needs `@SerialName`. */
function propertyName(json: string): { name: string; serialName: boolean } {
  if (/^[a-z][A-Za-z0-9]*$/.test(json)) {
    return { name: KEYWORDS.has(json) ? `\`${json}\`` : json, serialName: false };
  }
  const name: string = camel(json);
  return { name: KEYWORDS.has(name) ? `\`${name}\`` : name, serialName: true };
}

function topLevelName(schemaName: string): string {
  return schemaName.replace(/^OrchestrationV2(?=[A-Z])/, "").replace(/^Orchestration(?=[A-Z])/, "");
}

// Model

interface TypeRef {
  readonly kotlin: string;
  readonly nullable: boolean;
  readonly kind: "primitive" | "list" | "map" | "class" | "json";
  readonly todos: readonly string[];
}

interface Field {
  readonly json: string;
  readonly name: string;
  readonly serialName: boolean;
  readonly type: TypeRef;
  readonly required: boolean;
  /** Single literal value, used as the default for a union discriminator. */
  readonly literal?: string;
}

interface Scope {
  /** Qualified path from the top level, e.g. ["ThreadProjection", "VisibleTurnItem"]. */
  readonly path: readonly string[];
  readonly nested: Decl[];
  readonly usedNames: Set<string>;
}

interface DataClass extends Scope {
  readonly kind: "data";
  readonly fields: Field[];
  readonly supers: UnionDecl[];
}

interface UnionDecl extends Scope {
  readonly kind: "union";
  readonly discriminator: string;
  /** [absent] marks the one branch an object without the discriminator key decodes as. */
  readonly branches: Array<{ values: string[]; target: DataClass; absent: boolean }>;
  readonly topLevel: boolean;
}

interface Alias {
  readonly kind: "alias";
  readonly path: readonly string[];
  readonly target: TypeRef;
}

interface Opaque {
  readonly kind: "opaque";
  readonly path: readonly string[];
  readonly target: TypeRef;
}

type Decl = DataClass | UnionDecl | Alias | Opaque;

const PRIMITIVES: ReadonlySet<string> = new Set(["String", "Long", "Double", "Boolean"]);

function primitive(kotlin: string, nullable = false): TypeRef {
  return { kotlin, nullable, kind: "primitive", todos: [] };
}

function opaque(why: string, kotlin = "JsonElement", nullable = false): TypeRef {
  return { kotlin, nullable, kind: "json", todos: [why] };
}

function nullable(type: TypeRef, value: boolean): TypeRef {
  return value === type.nullable ? type : { ...type, nullable: type.nullable || value };
}

function render(type: TypeRef): string {
  return type.nullable ? `${type.kotlin}?` : type.kotlin;
}

// Generator

class Generator {
  readonly defs = new Map<string, Json>();
  readonly defOrigin = new Map<string, string>();
  readonly roots = new Map<string, Json>();
  readonly topNames = new Map<string, string>();
  readonly topDecls = new Map<string, Decl>();
  readonly building = new Set<string>();
  readonly inlining: string[] = [];

  constructor(files: ReadonlyArray<[name: string, document: JsonObject]>) {
    for (const [name, document] of files) {
      const { $defs, $schema: _schema, $id: _id, title: _title, ...schema } = document;
      this.roots.set(name, schema);
      if (!isObject($defs)) continue;
      for (const [defName, def] of Object.entries($defs)) {
        const existing: Json | undefined = this.defs.get(defName);
        if (existing === undefined) {
          this.defs.set(defName, def);
          this.defOrigin.set(defName, name);
        } else if (JSON.stringify(existing) !== JSON.stringify(def)) {
          warn(`$defs/${defName} differs between ${this.defOrigin.get(defName)} and ${name}, ` +
            `using the first`);
        }
      }
    }
    // A root is also a def when another file refers to it. The root wins.
    for (const [name, schema] of this.roots) {
      if (!isBareRef(schema)) this.defs.set(name, schema);
    }
    const all: string[] = [...new Set([...this.roots.keys(), ...this.defs.keys()])].sort();
    const stripped = new Map<string, string[]>();
    for (const name of all) {
      const short: string = topLevelName(name);
      stripped.set(short, [...(stripped.get(short) ?? []), name]);
    }
    for (const name of all) {
      const short: string = topLevelName(name);
      const clash: boolean = stripped.get(short)!.length > 1 ||
        (short !== name && all.includes(short));
      this.topNames.set(name, clash ? pascal(name) : pascal(short));
    }
  }

  isClassLike(schema: Json): boolean {
    if (!isObject(schema)) return false;
    if (schema.type === "object" && isObject(schema.properties)) {
      return Object.keys(schema.properties).length > 0;
    }
    const members: Json[] | undefined = unionMembers(schema);
    if (members === undefined) return false;
    const objects: Json[] = members.filter((m) => !isNull(m));
    return objects.length > 1 && objects.every((m) => this.isObjectLike(m));
  }

  isObjectLike(schema: Json): boolean {
    const resolved: Json = this.deref(schema);
    return isObject(resolved) && resolved.type === "object" && isObject(resolved.properties) &&
      Object.keys(resolved.properties).length > 0;
  }

  deref(schema: Json): Json {
    let current: Json = schema;
    const seen = new Set<string>();
    while (isBareRef(current)) {
      const name: string = refName(current);
      if (seen.has(name)) return current;
      seen.add(name);
      const next: Json | undefined = this.defs.get(name);
      if (next === undefined) return current;
      current = next;
    }
    return current;
  }

  /** Builds (once) the top-level declaration for a def or root. */
  top(name: string): Decl {
    const existing: Decl | undefined = this.topDecls.get(name);
    if (existing !== undefined) return existing;
    const schema: Json | undefined = this.defs.get(name) ?? this.roots.get(name);
    if (schema === undefined) fail(`$ref to ${name}, which no schema file defines`);
    const kotlinName: string = this.topNames.get(name)!;
    const scope: Scope = { path: [kotlinName], nested: [], usedNames: new Set([kotlinName]) };
    if (isBareRef(schema)) {
      const decl: Alias = {
        kind: "alias",
        path: [kotlinName],
        target: this.typeOf(schema, scope, kotlinName, name),
      };
      this.topDecls.set(name, decl);
      return decl;
    }
    if (this.isClassLike(schema)) {
      const members: Json[] | undefined = unionMembers(schema);
      if (members !== undefined) {
        const union: UnionDecl | TypeRef = this.union(schema, [], kotlinName, name, true, (decl) =>
          this.topDecls.set(name, decl));
        if ("kind" in union && union.kind === "union") return union;
        const decl: Opaque = { kind: "opaque", path: [kotlinName], target: union as TypeRef };
        this.topDecls.set(name, decl);
        return decl;
      }
      const decl: DataClass = this.dataClass(schema as JsonObject, [], kotlinName, name, (made) =>
        this.topDecls.set(name, made));
      return decl;
    }
    const decl: Opaque = {
      kind: "opaque",
      path: [kotlinName],
      target: this.typeOf(schema, scope, kotlinName, name),
    };
    this.topDecls.set(name, decl);
    return decl;
  }

  /** Reserves a nested name under `parent` that can't shadow anything. */
  nestedName(parent: Scope, wanted: string): string {
    let name: string = pascal(wanted);
    const taken = (candidate: string): boolean =>
      parent.usedNames.has(candidate) || parent.path.includes(candidate) ||
      RESERVED.has(candidate) || [...this.topNames.values()].includes(candidate);
    if (taken(name)) name = `${parent.path.at(-1)}${name}`;
    let candidate: string = name;
    for (let i = 2; taken(candidate); i++) candidate = `${name}${i}`;
    parent.usedNames.add(candidate);
    return candidate;
  }

  dataClass(
    schema: JsonObject,
    parentPath: readonly string[],
    name: string,
    where: string,
    register?: (decl: DataClass) => void,
  ): DataClass {
    const decl: DataClass = {
      kind: "data",
      path: [...parentPath, name],
      nested: [],
      usedNames: new Set(),
      fields: [],
      supers: [],
    };
    register?.(decl);
    const properties: JsonObject = schema.properties as JsonObject;
    const required: Set<string> = new Set(
      Array.isArray(schema.required) ? (schema.required as string[]) : [],
    );
    for (const [json, property] of Object.entries(properties)) {
      const { name: fieldName, serialName } = propertyName(json);
      const type: TypeRef = this.typeOf(property, decl, json, `${where}.${json}`);
      const resolved: Json = this.deref(property);
      const literal: string | undefined =
        isObject(resolved) && Array.isArray(resolved.enum) && resolved.enum.length === 1 &&
        typeof resolved.enum[0] === "string" ? resolved.enum[0] : undefined;
      decl.fields.push({
        json,
        name: fieldName,
        serialName,
        type,
        required: required.has(json),
        ...(literal !== undefined ? { literal } : {}),
      });
    }
    return decl;
  }

  /** A discriminated union of objects, or an opaque type when there is no discriminator. */
  union(
    schema: JsonObject,
    parentPath: readonly string[],
    name: string,
    where: string,
    topLevel: boolean,
    register?: (decl: UnionDecl) => void,
  ): UnionDecl | TypeRef {
    const found = this.discriminator(unionMembers(schema)!.filter((m) => !isNull(m)));
    if (found === undefined) {
      const why = "union of objects without a literal discriminator";
      warn(`${where}: ${why}`);
      return opaque(why);
    }
    const { key: discriminator, kept: members, absent } = found;
    const decl: UnionDecl = {
      kind: "union",
      path: [...parentPath, name],
      nested: [],
      usedNames: new Set(["Unknown"]),
      discriminator,
      branches: [],
      topLevel,
    };
    register?.(decl);
    for (const member of members) {
      const values: string[] = literalValues(this.deref(member), discriminator)!;
      let target: DataClass;
      if (isBareRef(member) && this.defs.has(refName(member)) &&
        this.isObjectLike(this.defs.get(refName(member))!)) {
        const top: Decl = this.top(refName(member));
        if (top.kind !== "data") fail(`${where}: branch ${refName(member)} is not an object`);
        target = top;
      } else {
        const branchName: string = this.nestedName(decl, branchNameFor(values));
        target = this.dataClass(this.deref(member) as JsonObject, decl.path, branchName,
          `${where}[${values.join("|")}]`);
        decl.nested.push(target);
      }
      if (!target.supers.includes(decl)) target.supers.push(decl);
      decl.branches.push({ values, target, absent: member === absent });
    }
    return decl;
  }

  /**
   * The discriminator key and the members that get a branch. Upstream's catch-all members (a
   * trailing member whose discriminator isn't a literal, or a later member repeating values a
   * known one already claims) don't: the Unknown branch is what they are for. One member may
   * have the literal as an optional key (`type: optionalKey(Literal("image"))`); an object
   * without the key is that member.
   */
  discriminator(members: Json[]): { key: string; kept: Json[]; absent?: Json } | undefined {
    const resolved: JsonObject[] = members.map((m) => this.deref(m) as JsonObject);
    const candidates = new Set<string>();
    for (const member of resolved) {
      for (const key of Object.keys((member.properties as JsonObject) ?? {})) candidates.add(key);
    }
    const preferred: string[] = ["type", "_tag", "kind"];
    const ordered: string[] = [
      ...preferred.filter((k) => candidates.has(k)),
      ...[...candidates].filter((k) => !preferred.includes(k)).sort(),
    ];
    for (const key of ordered) {
      const seen = new Set<string>();
      const kept: Json[] = [];
      let absent: Json | undefined;
      let ok = true;
      resolved.forEach((member, index) => {
        if (!ok) return;
        const required: Json[] = Array.isArray(member.required) ? member.required : [];
        const values: string[] | undefined = literalValues(member, key);
        if (values === undefined) {
          ok = index === resolved.length - 1 && isObject((member.properties as JsonObject)?.[key]);
          return;
        }
        if (values.every((v) => seen.has(v))) return;
        if (values.some((v) => seen.has(v))) {
          ok = false;
          return;
        }
        if (!required.includes(key)) {
          if (absent !== undefined) {
            ok = false;
            return;
          }
          absent = members[index];
        }
        for (const value of values) seen.add(value);
        kept.push(members[index]);
      });
      if (ok && kept.length > 0 && kept.length > (absent === undefined ? 0 : 1)) {
        return { key, kept, absent };
      }
    }
    return undefined;
  }

  typeOf(schema: Json, owner: Scope, hint: string, where: string): TypeRef {
    if (!isObject(schema)) return opaque("schema is not an object");
    if (isBareRef(schema)) return this.ref(refName(schema), owner, hint, where);

    const members: Json[] | undefined = unionMembers(schema);
    if (members !== undefined) return this.anyOf(schema, members, owner, hint, where);

    if (Array.isArray(schema.type)) {
      const types: Json[] = schema.type;
      return this.anyOf(schema, types.map((type) => ({ ...schema, type })), owner, hint, where);
    }

    if (Array.isArray(schema.enum)) {
      const values: Json[] = schema.enum;
      if (values.every((v) => typeof v === "string")) return primitive("String");
      if (values.every((v) => typeof v === "boolean")) return primitive("Boolean");
      if (values.every((v) => typeof v === "number")) {
        return primitive(values.every((v) => Number.isInteger(v)) ? "Long" : "Double");
      }
      return this.todo(where, "enum mixes value types");
    }

    switch (schema.type) {
      case "string":
        return primitive("String");
      case "integer":
        return primitive("Long");
      case "number":
        return primitive("Double");
      case "boolean":
        return primitive("Boolean");
      case "null":
        return this.todo(where, "always null", "JsonElement", true);
      case "array": {
        if (!isObject(schema.items)) {
          const why = "array without an item schema";
          warn(`${where}: ${why}`);
          return { kotlin: "List<JsonElement>", nullable: false, kind: "list", todos: [why] };
        }
        const item: TypeRef = this.typeOf(schema.items, owner, singular(hint), `${where}[]`);
        return {
          kotlin: `List<${render(item)}>`,
          nullable: false,
          kind: "list",
          todos: item.todos,
        };
      }
      case "object": {
        if (isObject(schema.properties) && Object.keys(schema.properties).length > 0) {
          const name: string = this.nestedName(owner, hint);
          const nested: DataClass = this.dataClass(schema, owner.path, name, where);
          owner.nested.push(nested);
          return { kotlin: nested.path.join("."), nullable: false, kind: "class", todos: [] };
        }
        if (isObject(schema.additionalProperties)) {
          const value: TypeRef = this.typeOf(schema.additionalProperties, owner, hint,
            `${where}{}`);
          return {
            kotlin: `Map<String, ${render(value)}>`,
            nullable: false,
            kind: "map",
            todos: value.todos,
          };
        }
        return this.todo(where, "object with no declared properties", "JsonObject");
      }
      case undefined:
        if (Object.keys(schema).length === 0) {
          return this.todo(where, "unconstrained upstream (Schema.Unknown or an opaque type)");
        }
    }
    return this.todo(where, `unsupported schema ${JSON.stringify(schema).slice(0, 60)}`);
  }

  todo(where: string, why: string, kotlin = "JsonElement", isNullable = false): TypeRef {
    warn(`${where}: ${why}`);
    return opaque(why, kotlin, isNullable);
  }

  ref(name: string, owner: Scope, hint: string, where: string): TypeRef {
    const root: Json | undefined = this.roots.get(name);
    const schema: Json | undefined = this.defs.get(name) ??
      (root !== undefined && !isBareRef(root) ? root : undefined);
    if (schema === undefined) fail(`${where}: $ref to ${name}, which no schema file defines`);
    if (this.isClassLike(schema)) {
      const decl: Decl = this.top(name);
      if (decl.kind === "opaque") return decl.target;
      return { kotlin: decl.path.join("."), nullable: false, kind: "class", todos: [] };
    }
    // Not a class: inline it. Recursion through an inlined shape can't terminate.
    if (this.inlining.includes(name)) {
      fail(`${where}: recursive $ref through ${[...this.inlining, name].join(" -> ")} ` +
        `has no class to break the cycle`);
    }
    this.inlining.push(name);
    try {
      return this.typeOf(schema, owner, hint, where);
    } finally {
      this.inlining.pop();
    }
  }

  anyOf(schema: JsonObject, raw: Json[], owner: Scope, hint: string, where: string): TypeRef {
    const members: Json[] = flatten(raw);
    const isNullable: boolean = members.some(isNull);
    const rest: Json[] = members.filter((m) => !isNull(m));
    if (rest.length === 0) return this.todo(where, "always null", "JsonElement", true);
    if (rest.length === 1) return nullable(this.typeOf(rest[0], owner, hint, where), isNullable);
    if (isEffectNumber(rest)) return primitive("Double", isNullable);
    const resolved: Json[] = rest.map((m) => this.deref(m));
    if (resolved.every((m) => isObject(m) && m.type === "string")) {
      return primitive("String", isNullable);
    }
    if (resolved.every((m) => isObject(m) && m.type === "boolean")) {
      return primitive("Boolean", isNullable);
    }
    if (rest.every((m) => this.isObjectLike(m))) {
      const name: string = this.nestedName(owner, hint);
      const union: UnionDecl | TypeRef = this.union({ ...schema, anyOf: rest }, owner.path, name,
        where, false);
      if ("kind" in union && union.kind === "union") {
        owner.nested.push(union);
        return { kotlin: union.path.join("."), nullable: isNullable, kind: "class", todos: [] };
      }
      owner.usedNames.delete(name);
      return nullable(union as TypeRef, isNullable);
    }
    return this.todo(where, "union of unrelated shapes", "JsonElement", isNullable);
  }
}

function isBareRef(node: Json | undefined): node is JsonObject {
  return isObject(node) && typeof node.$ref === "string" && Object.keys(node).length === 1;
}

function refName(node: JsonObject): string {
  return String(node.$ref).replace("#/$defs/", "");
}

function isNull(node: Json): boolean {
  return isObject(node) && node.type === "null";
}

function unionMembers(schema: Json): Json[] | undefined {
  if (!isObject(schema)) return undefined;
  const members: Json | undefined = schema.anyOf ?? schema.oneOf;
  return Array.isArray(members) ? members : undefined;
}

function flatten(members: Json[]): Json[] {
  return members.flatMap((m) => {
    const inner: Json[] | undefined = unionMembers(m);
    return inner !== undefined && isObject(m) && Object.keys(m).length === 1 ? flatten(inner) : [m];
  });
}

// Effect encodes Schema.Number as `number | "Infinity" | "-Infinity" | "NaN"`.
function isEffectNumber(members: Json[]): boolean {
  if (members.length !== 2) return false;
  const [a, b] = members;
  return isObject(a) && a.type === "number" && isObject(b) && b.type === "string" &&
    Array.isArray(b.enum) &&
    b.enum.every((v) => ["Infinity", "-Infinity", "NaN"].includes(v as string));
}

function literalValues(member: Json, key: string): string[] | undefined {
  if (!isObject(member) || !isObject(member.properties)) return undefined;
  const property: Json | undefined = member.properties[key];
  if (!isObject(property) || !Array.isArray(property.enum)) return undefined;
  if (!property.enum.every((v) => typeof v === "string")) return undefined;
  return property.enum as string[];
}

function branchNameFor(values: string[]): string {
  if (values.length === 1) return pascal(values[0]);
  // `thread.archived`, `thread.pinned`, ... share a `thread` prefix.
  const segments: string[][] = values.map((v) => v.split("."));
  const prefix: string[] = [];
  for (let i = 0; segments.every((s) => s.length > i + 1 && s[i] === segments[0][i]); i++) {
    prefix.push(segments[0][i]);
  }
  return prefix.length > 0 ? pascal(prefix.join(" ")) : pascal(values[0]);
}

// Rendering

class Writer {
  readonly lines: string[] = [];
  readonly imports = new Set<string>();
  longLines = 0;

  line(indent: number, text: string): void {
    const line: string = text.length === 0 ? "" : `${"  ".repeat(indent)}${text}`;
    if (line.length > MAX_COLUMNS) this.longLines++;
    this.lines.push(line);
  }

  use(...names: string[]): void {
    for (const name of names) this.imports.add(name);
  }

  /** Writes the first layout whose lines all fit, else the last one. */
  pick(indent: number, layouts: Array<Array<[number, string]>>): void {
    const fits = (layout: Array<[number, string]>): boolean =>
      layout.every(([depth, text]) =>
        "  ".repeat(indent + depth).length + text.length <= MAX_COLUMNS);
    const layout: Array<[number, string]> = layouts.find(fits) ?? layouts.at(-1)!;
    for (const [depth, text] of layout) this.line(indent + depth, text);
  }
}

const IMPORTS: Record<string, string> = {
  DeserializationStrategy: "kotlinx.serialization.DeserializationStrategy",
  EncodeDefault: "kotlinx.serialization.EncodeDefault",
  ExperimentalSerializationApi: "kotlinx.serialization.ExperimentalSerializationApi",
  KSerializer: "kotlinx.serialization.KSerializer",
  SerialName: "kotlinx.serialization.SerialName",
  Serializable: "kotlinx.serialization.Serializable",
  SerialDescriptor: "kotlinx.serialization.descriptors.SerialDescriptor",
  Decoder: "kotlinx.serialization.encoding.Decoder",
  Encoder: "kotlinx.serialization.encoding.Encoder",
  JsonDecoder: "kotlinx.serialization.json.JsonDecoder",
  JsonElement: "kotlinx.serialization.json.JsonElement",
  JsonEncoder: "kotlinx.serialization.json.JsonEncoder",
  JsonObject: "kotlinx.serialization.json.JsonObject",
  booleanOrNull: "kotlinx.serialization.json.booleanOrNull",
  contentOrNull: "kotlinx.serialization.json.contentOrNull",
  doubleOrNull: "kotlinx.serialization.json.doubleOrNull",
  jsonObject: "kotlinx.serialization.json.jsonObject",
  jsonPrimitive: "kotlinx.serialization.json.jsonPrimitive",
  longOrNull: "kotlinx.serialization.json.longOrNull",
};

interface CommonField {
  readonly name: string;
  readonly json: string;
  readonly base: string;
  readonly nullable: boolean;
}

function commonFields(union: UnionDecl): CommonField[] {
  const branches: DataClass[] = union.branches.map((b) => b.target);
  if (branches.length === 0) return [];
  const out: CommonField[] = [];
  for (const field of branches[0].fields) {
    const all: Array<Field | undefined> =
      branches.map((b) => b.fields.find((f) => f.json === field.json));
    if (all.some((f) => f === undefined)) continue;
    const present: Field[] = all as Field[];
    const isDiscriminator: boolean = field.json === union.discriminator;
    if (!isDiscriminator && !present.every((f) => PRIMITIVES.has(f.type.kotlin) &&
      f.type.kotlin === field.type.kotlin)) continue;
    out.push({
      name: field.name,
      json: field.json,
      base: isDiscriminator ? "String" : field.type.kotlin,
      nullable: !isDiscriminator && present.some((f) => f.type.nullable || !f.required),
    });
  }
  return out;
}

function overrides(decl: DataClass): Set<string> {
  const names = new Set<string>();
  for (const union of decl.supers) for (const field of commonFields(union)) names.add(field.json);
  return names;
}

function discriminatorDefault(decl: DataClass, field: Field): string | undefined {
  if (field.literal === undefined) return undefined;
  return decl.supers.some((u) => u.discriminator === field.json) ? field.literal : undefined;
}

function writeField(out: Writer, indent: number, decl: DataClass, field: Field): void {
  const isOverride: boolean = overrides(decl).has(field.json);
  for (const todo of field.type.todos) out.line(indent, `// TODO(contracts): ${todo}`);
  if (field.type.kind === "json") out.use(field.type.kotlin);
  let type: string = render(field.type);
  let value = "";
  const literal: string | undefined = discriminatorDefault(decl, field);
  // A discriminator is a default, so a Json with encodeDefaults = false would drop it. Always
  // encode it: the server picks the branch by it.
  const discriminator: boolean = literal !== undefined && !field.type.nullable;
  if (discriminator) {
    value = ` = ${JSON.stringify(literal)}`;
  } else if (!field.required) {
    if (field.type.kind === "list" && !field.type.nullable) value = " = emptyList()";
    else if (field.type.kind === "map" && !field.type.nullable) value = " = emptyMap()";
    else {
      type = field.type.nullable ? type : `${type}?`;
      value = " = null";
    }
  }
  const head: string = `${isOverride ? "override " : ""}val ${field.name}:`;
  let annotation = "";
  if (field.serialName) {
    out.use("SerialName");
    annotation = `@SerialName(${JSON.stringify(field.json)}) `;
  }
  if (discriminator) annotation += "@EncodeDefault(EncodeDefault.Mode.ALWAYS) ";
  const single = `${annotation}${head} ${type}${value},`;
  if (`${"  ".repeat(indent)}${single}`.length <= MAX_COLUMNS) {
    out.line(indent, single);
    return;
  }
  if (annotation.length > 0) out.line(indent, annotation.trim());
  const withoutAnnotation = `${head} ${type}${value},`;
  if (`${"  ".repeat(indent)}${withoutAnnotation}`.length <= MAX_COLUMNS) {
    out.line(indent, withoutAnnotation);
    return;
  }
  out.line(indent, head);
  out.line(indent + 2, `${type}${value},`);
}

function writeDecl(out: Writer, indent: number, decl: Decl): void {
  switch (decl.kind) {
    case "alias":
      out.line(indent, `public typealias ${decl.path.at(-1)} = ${render(decl.target)}`);
      return;
    case "opaque":
      for (const todo of decl.target.todos) out.line(indent, `// TODO(contracts): ${todo}`);
      if (decl.target.kind === "json") out.use(decl.target.kotlin);
      out.line(indent, `public typealias ${decl.path.at(-1)} = ${render(decl.target)}`);
      return;
    case "data":
      return writeDataClass(out, indent, decl);
    case "union":
      return writeUnion(out, indent, decl);
  }
}

function writeDataClass(out: Writer, indent: number, decl: DataClass): void {
  out.use("Serializable");
  out.line(indent, "@Serializable");
  out.line(indent, `public data class ${decl.path.at(-1)}(`);
  for (const field of decl.fields) writeField(out, indent + 1, decl, field);
  const names: string[] = decl.supers.map((s) => s.path.join("."));
  const open: string = decl.nested.length === 0 ? "" : " {";
  if (names.length === 0) {
    out.line(indent, `)${open}`);
  } else {
    // A branch shared by several unions can list more supertypes than fit, one per line then.
    const wrapped: Array<[number, string]> = names.map((name, index) => [
      index === 0 ? 0 : 1,
      `${index === 0 ? ") : " : ""}${name}${index < names.length - 1 ? "," : open}`,
    ]);
    out.pick(indent, [[[0, `) : ${names.join(", ")}${open}`]], wrapped]);
  }
  if (decl.nested.length === 0) return;
  decl.nested.forEach((nested, index) => {
    if (index > 0) out.line(0, "");
    writeDecl(out, indent + 1, nested);
  });
  out.line(indent, "}");
}

// A nested union's serializers live inside it. Its simple name is unique in its parent and never a
// top-level name, so it resolves from the annotation and from inside its own body.
function serializerNames(union: UnionDecl): { select: string; unknown: string } {
  const simple: string = union.path.at(-1)!;
  const prefix: string = union.topLevel ? "" : `${simple}.`;
  return { select: `${prefix}${simple}Serializer`, unknown: `${prefix}Unknown${simple}Serializer` };
}

/** How code inside a union (or its serializers) refers to a class. */
function local(union: UnionDecl, path: readonly string[]): string {
  const inside: boolean = union.path.every((segment, i) => path[i] === segment);
  return inside ? path.slice(union.path.length - 1).join(".") : path.join(".");
}

function writeUnion(out: Writer, indent: number, union: UnionDecl): void {
  const name: string = local(union, union.path);
  const simple: string = union.path.at(-1)!;
  const common: CommonField[] = commonFields(union);
  const { select, unknown } = serializerNames(union);
  out.use("Serializable", "JsonObject");
  annotate(out, indent, select);
  out.line(indent, `public sealed interface ${simple} {`);
  for (const field of common) {
    out.line(indent + 1, `public val ${field.name}: ${field.base}${field.nullable ? "?" : ""}`);
  }
  for (const nested of union.nested) {
    out.line(0, "");
    writeDecl(out, indent + 1, nested);
  }
  out.line(0, "");
  out.line(indent + 1,
    "/** A variant this build doesn't know. [raw] is the whole object as received. */");
  annotate(out, indent + 1, unknown);
  out.line(indent + 1, "public data class Unknown(");
  for (const field of common) {
    out.line(indent + 2, `override val ${field.name}: ${field.base}${field.nullable ? "?" : ""},`);
  }
  out.line(indent + 2, "val raw: JsonObject,");
  out.line(indent + 1, `) : ${name}`);
  if (!union.topLevel) {
    out.line(0, "");
    writeSerializers(out, indent + 1, union, common, `${simple}Serializer`,
      `Unknown${simple}Serializer`);
  }
  out.line(indent, "}");
  if (union.topLevel) {
    out.line(0, "");
    writeSerializers(out, indent, union, common, `${simple}Serializer`,
      `Unknown${simple}Serializer`);
  }
}

function annotate(out: Writer, indent: number, serializer: string): void {
  out.pick(indent, [
    [[0, `@Serializable(with = ${serializer}::class)`]],
    [[0, "@Serializable("], [1, `with = ${serializer}::class,`], [0, ")"]],
  ]);
}

function objectHeader(
  out: Writer,
  indent: number,
  name: string,
  supertype: string,
  argument?: string,
): void {
  const call: string = argument === undefined ? supertype : `${supertype}(${argument})`;
  out.pick(indent, [
    [[0, `public object ${name} : ${call} {`]],
    [[0, `public object ${name} :`], [1, `${call} {`]],
    [[0, `public object ${name} :`], [1, `${supertype}(`], [2, `${argument},`], [1, ") {"]],
  ]);
}

function writeSerializers(
  out: Writer,
  indent: number,
  union: UnionDecl,
  common: CommonField[],
  select: string,
  unknown: string,
): void {
  const name: string = local(union, union.path);
  out.use("KSerializer", "SerialDescriptor", "Decoder", "Encoder", "JsonDecoder",
    "DeserializationStrategy", "contentOrNull", "jsonObject", "jsonPrimitive");
  objectHeader(out, indent, select, `KSerializer<${name}>`);
  out.line(indent + 1,
    "override val descriptor: SerialDescriptor = JsonObject.serializer().descriptor");
  out.line(0, "");
  out.pick(indent + 1, [
    [[0, `private fun select(element: JsonObject): DeserializationStrategy<${name}> =`]],
    [
      [0, "private fun select("],
      [1, "element: JsonObject,"],
      [0, `): DeserializationStrategy<${name}> =`],
    ],
  ]);
  const key: string = JSON.stringify(union.discriminator);
  out.line(indent + 2, `when (element[${key}]?.jsonPrimitive?.contentOrNull) {`);
  for (const branch of union.branches) {
    const condition: string = [
      ...branch.values.map((v) => JSON.stringify(v)),
      ...(branch.absent ? ["null"] : []),
    ].join(", ");
    const target = `${local(union, branch.target.path)}.serializer()`;
    const single = `${condition} -> ${target}`;
    if (`${"  ".repeat(indent + 3)}${single}`.length <= MAX_COLUMNS) {
      out.line(indent + 3, single);
      continue;
    }
    const conditions: string[] = condition.split(", ");
    conditions.forEach((value, index) => {
      const last: boolean = index === conditions.length - 1;
      out.line(indent + 3, `${value}${last ? " ->" : ","}`);
    });
    out.line(indent + 4, target);
  }
  out.pick(indent + 3, [
    [[0, `else -> ${name}.Unknown.serializer()`]],
    [[0, "else ->"], [1, `${name}.Unknown.serializer()`]],
  ]);
  out.line(indent + 2, "}");
  out.line(0, "");

  // A known variant that doesn't decode (a required field missing, a field of the wrong shape)
  // lands as Unknown with its raw JSON instead of failing the whole payload. Catching
  // IllegalArgumentException covers SerializationException, its subclass.
  out.pick(indent + 1, [
    [[0, `override fun deserialize(decoder: Decoder): ${name} {`]],
    [[0, "override fun deserialize("], [1, "decoder: Decoder,"], [0, `): ${name} {`]],
  ]);
  out.line(indent + 2, "val input: JsonDecoder = decoder as JsonDecoder");
  out.line(indent + 2, "val element: JsonObject = input.decodeJsonElement().jsonObject");
  out.line(indent + 2, "return try {");
  out.line(indent + 3, "input.json.decodeFromJsonElement(select(element), element)");
  out.line(indent + 2, "} catch (_: IllegalArgumentException) {");
  out.pick(indent + 3, [
    [[0, `input.json.decodeFromJsonElement(${name}.Unknown.serializer(), element)`]],
    [[0, "input.json.decodeFromJsonElement("], [1, `${name}.Unknown.serializer(),`],
      [1, "element,"], [0, ")"]],
  ]);
  out.line(indent + 2, "}");
  out.line(indent + 1, "}");
  out.line(0, "");

  out.pick(indent + 1, [
    [[0, `override fun serialize(encoder: Encoder, value: ${name}) {`]],
    [[0, "override fun serialize("], [1, "encoder: Encoder,"], [1, `value: ${name},`],
      [0, ") {"]],
  ]);
  out.line(indent + 2, "when (value) {");
  const targets: string[] = [...new Set(union.branches.map((b) => local(union, b.target.path)))];
  for (const target of [...targets, `${name}.Unknown`]) {
    out.pick(indent + 3, [
      [[0, `is ${target} -> ${target}.serializer().serialize(encoder, value)`]],
      [[0, `is ${target} ->`], [1, `${target}.serializer().serialize(encoder, value)`]],
    ]);
  }
  out.line(indent + 2, "}");
  out.line(indent + 1, "}");
  out.line(indent, "}");
  out.line(0, "");

  out.use("JsonEncoder");
  objectHeader(out, indent, unknown, `KSerializer<${name}.Unknown>`);
  out.line(indent + 1,
    "override val descriptor: SerialDescriptor = JsonObject.serializer().descriptor");
  out.line(0, "");
  out.pick(indent + 1, [
    [[0, `override fun deserialize(decoder: Decoder): ${name}.Unknown {`]],
    [[0, "override fun deserialize("], [1, "decoder: Decoder,"], [0, `): ${name}.Unknown {`]],
  ]);
  out.line(indent + 2,
    "val raw: JsonObject = (decoder as JsonDecoder).decodeJsonElement().jsonObject");
  const helpers: Record<string, [string, string, string]> = {
    String: ["string", "contentOrNull", '""'],
    Long: ["long", "longOrNull", "0L"],
    Double: ["double", "doubleOrNull", "0.0"],
    Boolean: ["boolean", "booleanOrNull", "false"],
  };
  const needed = new Set<string>(common.map((f) => f.base));
  for (const base of ["String", "Long", "Double", "Boolean"]) {
    if (!needed.has(base)) continue;
    const [fun, accessor] = helpers[base];
    out.use(accessor);
    out.line(indent + 2,
      `fun ${fun}(key: String): ${base}? = raw[key]?.jsonPrimitive?.${accessor}`);
  }
  out.line(indent + 2, `return ${name}.Unknown(`);
  for (const field of common) {
    const [fun, , fallback] = helpers[field.base];
    const read = `${fun}(${JSON.stringify(field.json)})`;
    const value: string = field.nullable
      ? read
      : `${read} ?: ${field.json === union.discriminator ? '"unknown"' : fallback}`;
    out.line(indent + 3, `${field.name} = ${value},`);
  }
  out.line(indent + 3, "raw = raw,");
  out.line(indent + 2, ")");
  out.line(indent + 1, "}");
  out.line(0, "");
  out.pick(indent + 1, [
    [[0, `override fun serialize(encoder: Encoder, value: ${name}.Unknown) {`]],
    [
      [0, "override fun serialize("],
      [1, "encoder: Encoder,"],
      [1, `value: ${name}.Unknown,`],
      [0, ") {"],
    ],
  ]);
  out.line(indent + 2, "(encoder as JsonEncoder).encodeJsonElement(value.raw)");
  out.line(indent + 1, "}");
  out.line(indent, "}");
}

function countTodos(decls: Decl[]): number {
  let count = 0;
  for (const decl of decls) {
    if (decl.kind === "data") {
      count += decl.fields.reduce((sum, f) => sum + f.type.todos.length, 0);
      count += countTodos(decl.nested);
    } else if (decl.kind === "union") {
      count += countTodos(decl.nested);
    } else if (decl.kind === "opaque") {
      count += decl.target.todos.length;
    }
  }
  return count;
}

function writeFile(path: string, tag: string, decls: Decl[]): { longLines: number } {
  const out = new Writer();
  decls.forEach((decl, index) => {
    if (index > 0) out.line(0, "");
    writeDecl(out, 0, decl);
  });
  // EncodeDefault is still @ExperimentalSerializationApi as of kotlinx-serialization 1.9.0
  const optIn: string[] = out.lines.some((line) => line.includes("@EncodeDefault("))
    ? ["@file:OptIn(ExperimentalSerializationApi::class)", ""]
    : [];
  const code: string = [...optIn, ...out.lines].join("\n");
  const imports: string[] = Object.entries(IMPORTS)
    .filter(([name]) => new RegExp(`\\b${name}\\b`).test(code))
    .map(([, path]) => path)
    .sort();
  const text: string = [
    `// Generated from ${tag} by contracts/generate.ts, do not edit.`,
    "",
    ...optIn,
    `package ${PACKAGE}`,
    "",
    ...imports.map((i) => `import ${i}`),
    "",
    ...out.lines,
    "",
  ].join("\n");
  writeFileSync(path, text);
  return { longLines: out.longLines };
}

function main(): void {
  const schemaDir: string = resolve(arg("schema") ?? join(HERE, "schema"));
  const outDir: string = resolve(arg("out") ?? join(HERE, "..", "protocol", "src", "main", "kotlin",
    ...PACKAGE.split(".")));
  const tag: string = readFileSync(join(HERE, "T3CODE_VERSION"), "utf8").trim();

  const errors: string[] = readdirSync(schemaDir).filter((f) => f.endsWith(".error.txt"));
  for (const error of errors) {
    warn(`${error.replace(".error.txt", "")} has no schema, the exporter failed on it`);
  }
  const files: Array<[string, JsonObject]> = readdirSync(schemaDir)
    .filter((f) => f.endsWith(".json"))
    .sort()
    .map((f) => [basename(f, ".json"), JSON.parse(readFileSync(join(schemaDir, f), "utf8"))]);
  if (files.length === 0) fail(`no schemas in ${schemaDir}`);

  const generator = new Generator(files);
  const rootNames: string[] = files.map(([name]) => name);
  for (const name of rootNames) generator.top(name);
  const shared: string[] = [...generator.defs.keys()]
    .filter((n) => !rootNames.includes(n) && generator.isClassLike(generator.defs.get(n)!))
    .sort();
  for (const name of shared) generator.top(name);

  if (existsSync(outDir)) rmSync(outDir, { recursive: true });
  mkdirSync(outDir, { recursive: true });

  let longLines = 0;
  let todos = 0;
  const written: string[] = [];
  for (const name of rootNames) {
    const decl: Decl = generator.topDecls.get(name)!;
    const file: string = `${decl.path[0]}.kt`;
    longLines += writeFile(join(outDir, file), tag, [decl]).longLines;
    todos += countTodos([decl]);
    written.push(file);
  }
  const sharedDecls: Decl[] = shared.map((n) => generator.topDecls.get(n)!);
  if (sharedDecls.length > 0) {
    longLines += writeFile(join(outDir, "Common.kt"), tag, sharedDecls).longLines;
    todos += countTodos(sharedDecls);
    written.push("Common.kt");
  }

  console.log(`Generated ${written.length} files into ${outDir}`);
  console.log(`${todos} TODO(contracts) fields, ${warnings} warnings`);
  if (longLines > 0) warn(`${longLines} lines are over ${MAX_COLUMNS} columns`);
}

main();
