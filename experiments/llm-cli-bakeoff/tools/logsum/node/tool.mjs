#!/usr/bin/env node
// logsum: streaming summary of a log file (or stdin).

import fs from 'node:fs';
import readline from 'node:readline';

const HELP = `usage: logsum [FILE] [--since TIMESTAMP] [--level LEVEL] [--top N]
FILE omitted or - means read stdin.
  --since TIMESTAMP   only consider lines with TIMESTAMP >= this value
  --level LEVEL       only consider lines at exactly this level
  --top N             number of top error groups to show (default 5)
`;

const LEVELS = ['DEBUG', 'INFO', 'WARN', 'ERROR'];
const TS_RE = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}$/;

function fail(msg, code) {
  process.stderr.write(`error: ${msg}\n`);
  process.exit(code);
}

function parseArgs(argv) {
  const opts = { file: null, since: null, level: null, top: 5 };
  let i = 0;
  let fileSet = false;
  while (i < argv.length) {
    const a = argv[i];
    if (a === '--help') {
      process.stdout.write(HELP);
      process.exit(0);
    } else if (a === '--since') {
      opts.since = argv[++i];
    } else if (a === '--level') {
      opts.level = argv[++i];
    } else if (a === '--top') {
      opts.top = argv[++i];
    } else if (a.startsWith('--')) {
      fail2(`unknown flag: ${a}`);
    } else {
      if (fileSet) {
        fail2(`unknown flag: ${a}`);
      }
      opts.file = a;
      fileSet = true;
    }
    i++;
  }
  return opts;
}

function fail2(msg) {
  process.stderr.write(`${msg}\n`);
  process.exit(2);
}

const opts = parseArgs(process.argv.slice(2));

if (opts.since !== null && !TS_RE.test(opts.since)) {
  fail(`invalid value for --since: ${opts.since}`, 2);
}
if (opts.level !== null && !LEVELS.includes(opts.level)) {
  fail(`invalid value for --level: ${opts.level}`, 2);
}
const topN = Number.parseInt(opts.top, 10);

let input;
if (!opts.file || opts.file === '-') {
  input = process.stdin;
} else {
  if (!fs.existsSync(opts.file) || !fs.statSync(opts.file).isFile()) {
    fail(`cannot read ${opts.file}`, 2);
  }
  try {
    input = fs.createReadStream(opts.file);
  } catch {
    fail(`cannot read ${opts.file}`, 2);
  }
}

const byLevel = { DEBUG: 0, INFO: 0, WARN: 0, ERROR: 0 };
let total = 0;
let first = null;
let last = null;

// normalized message -> { count, displayMessage }
const errorGroups = new Map();

const UUID_RE = /[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/g;
const HEX_RE = /\b(?=[0-9a-fA-F]*[a-fA-F])[0-9a-fA-F]{6,}\b/g;
const DIGIT_RE = /[0-9]+/g;

function normalize(msg) {
  let s = msg.replace(UUID_RE, '<uuid>');
  s = s.replace(HEX_RE, '<hex>');
  s = s.replace(DIGIT_RE, '<num>');
  return s;
}

function processLine(line) {
  // split into 3 tokens: TIMESTAMP LEVEL MESSAGE (MESSAGE verbatim rest)
  const firstSpace = line.indexOf(' ');
  if (firstSpace === -1) return;
  const ts = line.slice(0, firstSpace);
  const rest = line.slice(firstSpace + 1);
  const secondSpace = rest.indexOf(' ');
  if (secondSpace === -1) return;
  const level = rest.slice(0, secondSpace);
  const message = rest.slice(secondSpace + 1);

  if (!TS_RE.test(ts)) return;
  if (!LEVELS.includes(level)) return;

  if (opts.since !== null && !(ts >= opts.since)) return;
  if (opts.level !== null && level !== opts.level) return;

  total++;
  byLevel[level]++;
  if (first === null) first = ts;
  last = ts;

  if (level === 'ERROR') {
    const norm = normalize(message);
    let g = errorGroups.get(norm);
    if (!g) {
      g = { count: 0, displayMessage: message };
      errorGroups.set(norm, g);
    }
    g.count++;
  }
}

function finish() {
  const lines = [];
  lines.push(`total: ${total}`);
  lines.push(`by level: DEBUG=${byLevel.DEBUG} INFO=${byLevel.INFO} WARN=${byLevel.WARN} ERROR=${byLevel.ERROR}`);
  lines.push(`first: ${first === null ? 'n/a' : first}`);
  lines.push(`last: ${last === null ? 'n/a' : last}`);
  lines.push('top errors:');

  const groups = Array.from(errorGroups.values());
  groups.sort((a, b) => {
    if (b.count !== a.count) return b.count - a.count;
    if (a.displayMessage < b.displayMessage) return -1;
    if (a.displayMessage > b.displayMessage) return 1;
    return 0;
  });
  const top = groups.slice(0, topN);
  for (const g of top) {
    lines.push(`  ${g.count} ${g.displayMessage}`);
  }

  process.stdout.write(lines.join('\n') + '\n');
}

const rl = readline.createInterface({ input, crlfDelay: Infinity });
rl.on('line', processLine);
rl.on('close', finish);
input.on('error', () => {
  fail(`cannot read ${opts.file}`, 2);
});
