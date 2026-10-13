// Builds data/speed.json for the "Today: Performance" slides from experiments/speed-test/results
// (written by experiments/speed-test/run). Runs before `dev` and `build`, and at the end of the speed test.
// Uses medians: hyperfine's first cold run is often slower while the OS file cache fills.
import { existsSync, readdirSync, readFileSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const results = join(here, '../../experiments/speed-test/results')
const out = join(here, '../data/speed.json')

if (!existsSync(join(results, 'warm.json'))) {
  console.log(`speed-data: no results in ${results}, keeping existing ${out}`)
  process.exit(0)
}

const read = f => JSON.parse(readFileSync(join(results, f), 'utf8'))
const launchers = {}
for (const r of read('warm.json').results)
  launchers[r.command] = { warm: r.median, warmSd: r.stddev }
for (const f of readdirSync(results).filter(f => /^first-csv-.*\.json$/.test(f))) {
  const r = read(f).results[0]
  launchers[r.command.replace(/^csv-/, '')].first = r.median
}
let libKb = null
for (const line of readFileSync(join(results, 'sizes.txt'), 'utf8').split('\n').filter(Boolean)) {
  const [name, kb, lines] = line.split(/\s+/)
  if (name === 'lib') { libKb = Number(kb); continue }
  Object.assign(launchers[name.replace(/^csv-/, '')], { diskKb: Number(kb), lines: Number(lines) })
}
const env = Object.fromEntries(readFileSync(join(results, 'env.txt'), 'utf8').split('\n')
  .map(l => /^(\w+): (.+)$/.exec(l)).filter(Boolean).map(m => [m[1], m[2]]))
env.java = /version "([^"]+)"/.exec(readFileSync(join(results, 'env.txt'), 'utf8'))?.[1]

writeFileSync(out, `${JSON.stringify({ env, libKb, launchers }, null, 2)}\n`)
console.log(`speed-data: wrote ${out}`)
