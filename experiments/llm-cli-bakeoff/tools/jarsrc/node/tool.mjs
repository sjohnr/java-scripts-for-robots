#!/usr/bin/env node
// jarsrc: query a Gradle-cache-like tree of *-sources.jar files.
//
// Node has no built-in ZIP-container reader. `node:zlib` provides raw
// DEFLATE (inflateRawSync) but nothing that parses the PK central-directory
// / local-file-header framing of a .zip/.jar file. This module hand-rolls a
// minimal ZIP central-directory reader (End Of Central Directory record +
// Central Directory File Headers) and uses zlib.inflateRawSync to decompress
// DEFLATE (method 8) entries; STORED (method 0) entries are copied verbatim.

import fs from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';

const HELP = `usage: jarsrc find <fqcn-or-glob> [--root DIR]
       jarsrc cat <fqcn> [--lines A:B] [--root DIR]
       jarsrc ls <group:artifact[:version]> [--root DIR]
       jarsrc --help

  --root DIR      root of the Gradle-cache-like tree (default .)
  --lines A:B     1-indexed inclusive line range for cat
`;

function fail(msg, code) {
  process.stderr.write(`error: ${msg}\n`);
  process.exit(code);
}

function fail2(msg) {
  process.stderr.write(`${msg}\n`);
  process.exit(2);
}

// ---------- minimal ZIP reader ----------

const EOCD_SIG = 0x06054b50;
const CEN_SIG = 0x02014b50;
const LOC_SIG = 0x04034b50;

function findEOCD(buf) {
  // scan from the end for the EOCD signature (allowing for a comment field)
  const maxBack = Math.min(buf.length, 65536 + 22);
  for (let i = buf.length - 22; i >= buf.length - maxBack && i >= 0; i--) {
    if (buf.readUInt32LE(i) === EOCD_SIG) {
      return i;
    }
  }
  throw new Error('not a zip file (no EOCD)');
}

/** Returns array of { name, offset (local header offset), compSize, method } */
function readCentralDirectory(buf) {
  const eocdOff = findEOCD(buf);
  const totalEntries = buf.readUInt16LE(eocdOff + 10);
  const cdSize = buf.readUInt32LE(eocdOff + 12);
  const cdOffset = buf.readUInt32LE(eocdOff + 16);
  const entries = [];
  let p = cdOffset;
  for (let i = 0; i < totalEntries; i++) {
    if (buf.readUInt32LE(p) !== CEN_SIG) break;
    const method = buf.readUInt16LE(p + 10);
    const compSize = buf.readUInt32LE(p + 20);
    const uncompSize = buf.readUInt32LE(p + 24);
    const nameLen = buf.readUInt16LE(p + 28);
    const extraLen = buf.readUInt16LE(p + 30);
    const commentLen = buf.readUInt16LE(p + 32);
    const localHeaderOffset = buf.readUInt32LE(p + 42);
    const name = buf.toString('utf8', p + 46, p + 46 + nameLen);
    entries.push({ name, method, compSize, uncompSize, localHeaderOffset });
    p += 46 + nameLen + extraLen + commentLen;
  }
  void cdSize;
  return entries;
}

function readEntryData(buf, entry) {
  const lo = entry.localHeaderOffset;
  if (buf.readUInt32LE(lo) !== LOC_SIG) {
    throw new Error('bad local file header');
  }
  const nameLen = buf.readUInt16LE(lo + 26);
  const extraLen = buf.readUInt16LE(lo + 28);
  const dataStart = lo + 30 + nameLen + extraLen;
  const dataEnd = dataStart + entry.compSize;
  const compData = buf.subarray(dataStart, dataEnd);
  if (entry.method === 0) {
    return Buffer.from(compData);
  } else if (entry.method === 8) {
    return zlib.inflateRawSync(compData);
  }
  throw new Error(`unsupported zip compression method ${entry.method}`);
}

/** Read all .java entries from a jar file: [{ fqcn, text }] */
function readJavaEntries(jarPath) {
  const buf = fs.readFileSync(jarPath);
  const entries = readCentralDirectory(buf);
  const out = [];
  for (const e of entries) {
    if (!e.name.endsWith('.java')) continue;
    const fqcn = e.name.slice(0, -'.java'.length).replace(/\//g, '.');
    const data = readEntryData(buf, e);
    out.push({ fqcn, text: data.toString('utf8') });
  }
  return out;
}

// ---------- fixture tree scanning ----------

/** Walk root and discover all jars: { group, artifact, version, jarPath } */
function scanTree(root) {
  const results = [];
  function walk(dir) {
    let entries;
    try {
      entries = fs.readdirSync(dir, { withFileTypes: true });
    } catch {
      return;
    }
    for (const ent of entries) {
      if (ent.isSymbolicLink()) continue;
      const abs = path.join(dir, ent.name);
      if (ent.isDirectory()) {
        walk(abs);
      } else if (ent.isFile() && ent.name.endsWith('-sources.jar')) {
        results.push(abs);
      }
    }
  }
  walk(root);

  const parsed = [];
  for (const jarPath of results) {
    const rel = path.relative(root, jarPath).split(path.sep).join('/');
    const parts = rel.split('/');
    // <group-path...>/<artifact>/<version>/<artifact>-<version>-sources.jar
    if (parts.length < 3) continue;
    const version = parts[parts.length - 2];
    const artifact = parts[parts.length - 3];
    const groupParts = parts.slice(0, parts.length - 3);
    const group = groupParts.join('.');
    parsed.push({ group, artifact, version, jarPath });
  }
  return parsed;
}

function gav(e) {
  return `${e.group}:${e.artifact}:${e.version}`;
}
function ga(e) {
  return `${e.group}:${e.artifact}`;
}

// ---------- version comparison (spec/00-conventions.md) ----------

function splitVersion(v) {
  return v.split(/[.-]/);
}

function segCompare(a, b) {
  const na = isNonNegInt(a);
  const nb = isNonNegInt(b);
  if (na && nb) {
    const ia = BigInt(a);
    const ib = BigInt(b);
    if (ia < ib) return -1;
    if (ia > ib) return 1;
    return 0;
  }
  if (a < b) return -1;
  if (a > b) return 1;
  return 0;
}

function isNonNegInt(s) {
  return /^[0-9]+$/.test(s);
}

function compareVersions(v1, v2) {
  const s1 = splitVersion(v1);
  const s2 = splitVersion(v2);
  const n = Math.min(s1.length, s2.length);
  for (let i = 0; i < n; i++) {
    const c = segCompare(s1[i], s2[i]);
    if (c !== 0) return c;
  }
  if (s1.length !== s2.length) return s1.length < s2.length ? -1 : 1;
  return 0;
}

// ---------- suggestions ----------

function suggestions(target, knownNames) {
  const uniq = Array.from(new Set(knownNames));
  const matches = uniq.filter((n) => n.startsWith(target)).sort();
  return matches.slice(0, 3);
}

function suggestionClause(target, knownNames) {
  const s = suggestions(target, knownNames);
  if (s.length === 0) return '';
  return ` (did you mean: ${s.join(', ')}?)`;
}

// ---------- glob matching for find ----------

function globToRegExp(glob) {
  // '*' matches any run except '.', '**' matches any run including '.'
  let out = '^';
  let i = 0;
  while (i < glob.length) {
    if (glob[i] === '*') {
      if (glob[i + 1] === '*') {
        out += '.*';
        i += 2;
      } else {
        out += '[^.]*';
        i += 1;
      }
    } else {
      out += glob[i].replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
      i += 1;
    }
  }
  out += '$';
  return new RegExp(out);
}

// ---------- argument parsing ----------

const argv = process.argv.slice(2);

if (argv.length === 0) {
  fail2('unknown flag: (missing subcommand)');
}
if (argv[0] === '--help') {
  process.stdout.write(HELP);
  process.exit(0);
}

const subcommand = argv[0];
if (!['find', 'cat', 'ls'].includes(subcommand)) {
  fail2(`unknown flag: ${subcommand}`);
}

const rest = argv.slice(1);
const opts = { root: '.', lines: null, positional: [] };
let i = 0;
while (i < rest.length) {
  const a = rest[i];
  if (a === '--help') {
    process.stdout.write(HELP);
    process.exit(0);
  } else if (a === '--root') {
    opts.root = rest[++i];
  } else if (a === '--lines') {
    opts.lines = rest[++i];
  } else if (a.startsWith('--')) {
    fail2(`unknown flag: ${a}`);
  } else {
    opts.positional.push(a);
  }
  i++;
}

if (opts.positional.length === 0) {
  fail2('unknown flag: (missing argument)');
}
const target = opts.positional[0];

const allJars = scanTree(opts.root);

// ---------- subcommands ----------

function doFind(fqcnOrGlob) {
  const hasWildcard = fqcnOrGlob.includes('*');
  const re = hasWildcard ? globToRegExp(fqcnOrGlob) : null;

  const matches = []; // { fqcn, group, artifact, version }
  const allSimpleNames = [];

  for (const jar of allJars) {
    let javaEntries;
    try {
      javaEntries = readJavaEntries(jar.jarPath);
    } catch {
      continue;
    }
    for (const je of javaEntries) {
      const simple = je.fqcn.includes('.') ? je.fqcn.slice(je.fqcn.lastIndexOf('.') + 1) : je.fqcn;
      allSimpleNames.push(simple);
      const isMatch = hasWildcard ? re.test(je.fqcn) : je.fqcn === fqcnOrGlob;
      if (isMatch) {
        matches.push({ fqcn: je.fqcn, group: jar.group, artifact: jar.artifact, version: jar.version });
      }
    }
  }

  if (matches.length === 0) {
    const simple = fqcnOrGlob.includes('.') ? fqcnOrGlob.slice(fqcnOrGlob.lastIndexOf('.') + 1) : fqcnOrGlob;
    fail(`no matches for ${fqcnOrGlob}${suggestionClause(simple, allSimpleNames)}`, 1);
  }

  matches.sort((a, b) => {
    if (a.fqcn !== b.fqcn) return a.fqcn < b.fqcn ? -1 : 1;
    const gavA = `${a.group}:${a.artifact}:${a.version}`;
    const gavB = `${b.group}:${b.artifact}:${b.version}`;
    return gavA < gavB ? -1 : gavA > gavB ? 1 : 0;
  });

  const out = matches.map((m) => `${m.fqcn} ${m.group}:${m.artifact}:${m.version}`).join('\n');
  process.stdout.write(out + '\n');
}

function doCat(fqcn) {
  // Collect all (group:artifact) -> best version containing fqcn, and text.
  const byGA = new Map(); // ga -> [{version, jar, text}]
  const allSimpleNames = [];

  for (const jar of allJars) {
    let javaEntries;
    try {
      javaEntries = readJavaEntries(jar.jarPath);
    } catch {
      continue;
    }
    for (const je of javaEntries) {
      const simple = je.fqcn.includes('.') ? je.fqcn.slice(je.fqcn.lastIndexOf('.') + 1) : je.fqcn;
      allSimpleNames.push(simple);
      if (je.fqcn === fqcn) {
        const key = ga(jar);
        if (!byGA.has(key)) byGA.set(key, []);
        byGA.get(key).push({ version: jar.version, text: je.text });
      }
    }
  }

  if (byGA.size === 0) {
    const simple = fqcn.includes('.') ? fqcn.slice(fqcn.lastIndexOf('.') + 1) : fqcn;
    fail(`no source for ${fqcn}${suggestionClause(simple, allSimpleNames)}`, 1);
  }

  if (byGA.size > 1) {
    const gas = Array.from(byGA.keys()).sort();
    fail(`${fqcn} is ambiguous across: ${gas.join(', ')}`, 2);
  }

  const candidates = Array.from(byGA.values())[0];
  candidates.sort((a, b) => compareVersions(a.version, b.version));
  const best = candidates[candidates.length - 1];

  let text = best.text;
  if (opts.lines) {
    const [aStr, bStr] = opts.lines.split(':');
    let a = Number.parseInt(aStr, 10);
    let b = Number.parseInt(bStr, 10);
    const allLines = text.split('\n');
    // if text ends with trailing newline, split produces a trailing '' element
    let lineArr = allLines;
    let hadTrailingNl = false;
    if (lineArr.length > 0 && lineArr[lineArr.length - 1] === '' ) {
      hadTrailingNl = true;
      lineArr = lineArr.slice(0, -1);
    }
    const totalLines = lineArr.length;
    if (a < 1) a = 1;
    if (b > totalLines) b = totalLines;
    if (a > totalLines) {
      process.stdout.write('');
      return;
    }
    const slice = lineArr.slice(a - 1, b);
    process.stdout.write(slice.join('\n') + (slice.length > 0 ? '\n' : ''));
    void hadTrailingNl;
    return;
  }

  process.stdout.write(text);
}

function doLs(spec) {
  const parts = spec.split(':');
  if (parts.length === 2) {
    const [group, artifact] = parts;
    const gaKey = `${group}:${artifact}`;
    const versions = allJars.filter((j) => ga(j) === gaKey).map((j) => j.version);
    if (versions.length === 0) {
      const knownGA = Array.from(new Set(allJars.map((j) => ga(j))));
      fail(`no artifact ${gaKey}${suggestionClause(gaKey, knownGA)}`, 1);
    }
    const uniqVersions = Array.from(new Set(versions));
    uniqVersions.sort(compareVersions);
    process.stdout.write(uniqVersions.join('\n') + '\n');
  } else if (parts.length === 3) {
    const [group, artifact, version] = parts;
    const gaKey = `${group}:${artifact}`;
    const jar = allJars.find((j) => ga(j) === gaKey && j.version === version);
    if (!jar) {
      const knownGA = Array.from(new Set(allJars.map((j) => ga(j))));
      const gaExists = allJars.some((j) => ga(j) === gaKey);
      if (gaExists) {
        fail(`no artifact ${gaKey}:${version}`, 1);
      } else {
        fail(`no artifact ${gaKey}${suggestionClause(gaKey, knownGA)}`, 1);
      }
      return;
    }
    let javaEntries;
    try {
      javaEntries = readJavaEntries(jar.jarPath);
    } catch {
      javaEntries = [];
    }
    const fqcns = javaEntries.map((e) => e.fqcn).sort();
    process.stdout.write(fqcns.join('\n') + '\n');
  } else {
    fail2(`unknown flag: ${spec}`);
  }
}

if (subcommand === 'find') {
  doFind(target);
} else if (subcommand === 'cat') {
  doCat(target);
} else if (subcommand === 'ls') {
  doLs(target);
}
