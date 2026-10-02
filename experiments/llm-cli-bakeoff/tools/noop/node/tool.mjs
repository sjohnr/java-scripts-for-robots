#!/usr/bin/env node
// noop: measures pure process-startup floor. No I/O, no argument-dependent logic
// except --help.

const args = process.argv.slice(2);

if (args.includes('--help')) {
  process.stdout.write('usage: noop\nprints "ok" and exits 0\n');
  process.exit(0);
}

process.stdout.write('ok\n');
process.exit(0);
