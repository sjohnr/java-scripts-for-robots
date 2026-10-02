#!/usr/bin/env node
// jsonpick: extract a value by path from JSON without a jq dependency.

import fs from 'node:fs';

const HELP = `usage: jsonpick PATH [FILE] [--shape] [--depth N]
PATH is dot-separated object keys, [N] for zero-based array index (e.g. a.b[0].c).
FILE omitted or - means stdin.
  --shape     print a depth-limited type/array-length summary instead of the value
  --depth N   depth for --shape (default 2)
`;

function fail2(msg) {
  process.stderr.write(`${msg}\n`);
  process.exit(2);
}

function parseArgs(argv) {
  const opts = { path: null, file: null, shape: false, depth: 2 };
  let i = 0;
  let pathSet = false;
  let fileSet = false;
  while (i < argv.length) {
    const a = argv[i];
    if (a === '--help') {
      process.stdout.write(HELP);
      process.exit(0);
    } else if (a === '--shape') {
      opts.shape = true;
    } else if (a === '--depth') {
      opts.depth = argv[++i];
    } else if (a.startsWith('--')) {
      fail2(`unknown flag: ${a}`);
    } else {
      if (!pathSet) {
        opts.path = a;
        pathSet = true;
      } else if (!fileSet) {
        opts.file = a;
        fileSet = true;
      } else {
        fail2(`unknown flag: ${a}`);
      }
    }
    i++;
  }
  return opts;
}

const opts = parseArgs(process.argv.slice(2));
if (opts.path === null) {
  fail2('unknown flag: (missing PATH)');
}
const depth = Number.parseInt(opts.depth, 10);

function readInput(file) {
  if (!file || file === '-') {
    return fs.readFileSync(0, 'utf8');
  }
  try {
    return fs.readFileSync(file, 'utf8');
  } catch {
    process.stderr.write(`error: cannot read ${file}\n`);
    process.exit(2);
  }
}

const text = readInput(opts.file);

let doc;
try {
  doc = JSON.parse(text);
} catch (e) {
  // Try to extract a line/col-ish reason from the JS error message.
  const reason = jsonErrorReason(text, e);
  process.stderr.write(`error: invalid JSON: ${reason}\n`);
  process.exit(2);
}

function jsonErrorReason(src, e) {
  // Modern V8 JSON.parse errors already include "(line N column M)" or
  // "position N" in the message. Fall back to reporting the end-of-input
  // offset when the engine's message lacks a position (e.g. empty input).
  const msg = e.message || 'parse error';
  if (/line \d+ column \d+/.test(msg) || /position \d+/.test(msg)) {
    return msg;
  }
  return `${msg} (byte offset ${src.length})`;
}

// --- Path parsing ---
// a.b[0].c  -> [{type:'key', name:'b'}, {type:'index', n:0}, {type:'key', name:'c'}]
function parsePath(p) {
  if (p === null || p === undefined) return [];
  let s = p;
  if (s === '' || s === '.') return [];
  if (s.startsWith('.')) s = s.slice(1);
  const tokens = [];
  let i = 0;
  while (i < s.length) {
    if (s[i] === '[') {
      const end = s.indexOf(']', i);
      const numStr = s.slice(i + 1, end);
      tokens.push({ type: 'index', n: Number.parseInt(numStr, 10) });
      i = end + 1;
      if (s[i] === '.') i++;
    } else {
      let j = i;
      while (j < s.length && s[j] !== '.' && s[j] !== '[') j++;
      const name = s.slice(i, j);
      tokens.push({ type: 'key', name });
      i = j;
      if (s[i] === '.') i++;
    }
  }
  return tokens;
}

function navigate(root, tokens) {
  let cur = root;
  for (const tok of tokens) {
    if (tok.type === 'key') {
      if (cur === null || typeof cur !== 'object' || Array.isArray(cur) || !(tok.name in cur)) {
        return { found: false };
      }
      cur = cur[tok.name];
    } else {
      if (!Array.isArray(cur) || tok.n < 0 || tok.n >= cur.length) {
        return { found: false };
      }
      cur = cur[tok.n];
    }
  }
  return { found: true, value: cur };
}

const tokens = parsePath(opts.path);
const result = navigate(doc, tokens);

if (!result.found) {
  process.stderr.write(`error: path not found: ${opts.path}\n`);
  process.exit(1);
}

const value = result.value;

function canonicalNumber(n) {
  if (Number.isInteger(n) && Object.is(n, -0) === false) {
    return String(n);
  }
  if (Object.is(n, -0)) return '0';
  return String(n);
}

if (opts.shape) {
  process.stdout.write(shapeOf(value, depth) + '\n');
} else {
  if (value === null) {
    process.stdout.write('null\n');
  } else if (typeof value === 'string') {
    process.stdout.write(value + '\n');
  } else if (typeof value === 'number') {
    process.stdout.write(canonicalNumber(value) + '\n');
  } else if (typeof value === 'boolean') {
    process.stdout.write((value ? 'true' : 'false') + '\n');
  } else {
    process.stdout.write(JSON.stringify(value) + '\n');
  }
}

function shapeOf(v, budget) {
  if (v === null) return 'null';
  if (typeof v === 'string') return 'string';
  if (typeof v === 'number') return 'number';
  if (typeof v === 'boolean') return 'boolean';
  if (Array.isArray(v)) {
    const L = v.length;
    if (L === 0) return 'array[0]';
    if (budget <= 0) return `array[${L}]`;
    return `array[${L}] of ${shapeOf(v[0], budget - 1)}`;
  }
  // object
  const keys = Object.keys(v);
  if (budget <= 0) return `object{${keys.length} keys}`;
  if (keys.length === 0) return 'object{}';
  const parts = keys.map((k) => `${k}: ${shapeOf(v[k], budget - 1)}`);
  return `object{${parts.join(', ')}}`;
}
