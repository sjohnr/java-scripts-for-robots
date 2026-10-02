#!/usr/bin/env node
// treesum: compact repo overview.

import fs from 'node:fs';
import path from 'node:path';

const HELP = `usage: treesum [DIR] [--top N] [--ext-limit M]
DIR defaults to "."
  --top N         number of largest files to list (default 10)
  --ext-limit M   max distinct extensions shown in by-extension table (default 20)
Fixed ignore list (not descended into): .git, node_modules, build, .gradle, target, dist, .venv, __pycache__, .idea, .vscode
`;

const IGNORE_DIRS = new Set([
  '.git', 'node_modules', 'build', '.gradle', 'target', 'dist',
  '.venv', '__pycache__', '.idea', '.vscode',
]);

function fail(msg, code) {
  process.stderr.write(`error: ${msg}\n`);
  process.exit(code);
}

function fail2(msg) {
  process.stderr.write(`${msg}\n`);
  process.exit(2);
}

function parseArgs(argv) {
  const opts = { dir: '.', top: 10, extLimit: 20 };
  let i = 0;
  let dirSet = false;
  while (i < argv.length) {
    const a = argv[i];
    if (a === '--help') {
      process.stdout.write(HELP);
      process.exit(0);
    } else if (a === '--top') {
      opts.top = argv[++i];
    } else if (a === '--ext-limit') {
      opts.extLimit = argv[++i];
    } else if (a.startsWith('--')) {
      fail2(`unknown flag: ${a}`);
    } else {
      if (dirSet) fail2(`unknown flag: ${a}`);
      opts.dir = a;
      dirSet = true;
    }
    i++;
  }
  return opts;
}

const opts = parseArgs(process.argv.slice(2));
const topN = Number.parseInt(opts.top, 10);
const extLimit = Number.parseInt(opts.extLimit, 10);

let rootStat;
try {
  rootStat = fs.lstatSync(opts.dir);
} catch {
  fail(`cannot read ${opts.dir}`, 2);
}
if (!rootStat.isDirectory()) {
  fail(`cannot read ${opts.dir}`, 2);
}

let fileCount = 0;
let dirCount = 0;
// ext -> { files, loc }
const extStats = new Map();
const allFiles = []; // { size, relPath }

function extOf(name) {
  const idx = name.lastIndexOf('.');
  if (idx <= 0) return ''; // no dot, or dotfile with no other dot (idx===0)
  return name.slice(idx + 1).toLowerCase();
}

function countLoc(filePath) {
  const buf = fs.readFileSync(filePath);
  if (buf.length === 0) return 0;
  let newlines = 0;
  for (let i = 0; i < buf.length; i++) {
    if (buf[i] === 0x0a) newlines++;
  }
  const endsWithNl = buf[buf.length - 1] === 0x0a;
  return newlines + (endsWithNl ? 0 : 1);
}

function walk(dir, relPrefix) {
  let entries;
  try {
    entries = fs.readdirSync(dir, { withFileTypes: true });
  } catch {
    return;
  }
  entries.sort((a, b) => (a.name < b.name ? -1 : a.name > b.name ? 1 : 0));
  for (const ent of entries) {
    const abs = path.join(dir, ent.name);
    const rel = relPrefix ? `${relPrefix}/${ent.name}` : ent.name;
    if (ent.isSymbolicLink()) {
      continue; // not followed, not counted
    }
    if (ent.isDirectory()) {
      if (IGNORE_DIRS.has(ent.name)) continue;
      dirCount++;
      walk(abs, rel);
    } else if (ent.isFile()) {
      fileCount++;
      const size = fs.statSync(abs).size;
      const ext = extOf(ent.name);
      const loc = countLoc(abs);
      let st = extStats.get(ext);
      if (!st) {
        st = { files: 0, loc: 0 };
        extStats.set(ext, st);
      }
      st.files++;
      st.loc += loc;
      allFiles.push({ size, relPath: rel });
    }
    // other types (sockets, fifos, etc.) ignored
  }
}

walk(opts.dir, '');

// by-extension folding for ext-limit
const extEntries = Array.from(extStats.entries()); // [ext, {files, loc}]
extEntries.sort((a, b) => {
  if (b[1].files !== a[1].files) return b[1].files - a[1].files;
  const la = a[0];
  const lb = b[0];
  if (la < lb) return -1;
  if (la > lb) return 1;
  return 0;
});

let shown = extEntries;
let otherRow = null;
if (extEntries.length > extLimit) {
  shown = extEntries.slice(0, extLimit);
  const rest = extEntries.slice(extLimit);
  let files = 0;
  let loc = 0;
  for (const [, st] of rest) {
    files += st.files;
    loc += st.loc;
  }
  otherRow = { files, loc };
}

const lines = [];
lines.push(`files: ${fileCount}`);
lines.push(`dirs: ${dirCount}`);
lines.push('by extension:');
for (const [ext, st] of shown) {
  const label = ext === '' ? '(none)' : ext;
  lines.push(`  ${label}  files=${st.files} loc=${st.loc}`);
}
if (otherRow) {
  lines.push(`  other  files=${otherRow.files} loc=${otherRow.loc}`);
}
lines.push('largest files:');
allFiles.sort((a, b) => {
  if (b.size !== a.size) return b.size - a.size;
  if (a.relPath < b.relPath) return -1;
  if (a.relPath > b.relPath) return 1;
  return 0;
});
for (const f of allFiles.slice(0, topN)) {
  lines.push(`  ${f.size} ${f.relPath}`);
}

process.stdout.write(lines.join('\n') + '\n');
