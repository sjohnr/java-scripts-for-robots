// Builds data/intro.json for the introduction slides from experiments/intro-scorecard/results.
// Runs before `dev` and `build`; safe to run any time while the experiment is in progress.
import { existsSync, readdirSync, readFileSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const results = join(here, '../../experiments/intro-scorecard/results')
const out = join(here, '../data/intro.json')
const PLANNED = 84

if (!existsSync(results)) {
  console.log(`intro-data: ${results} not found, keeping existing ${out}`)
  process.exit(0)
}

const readJson = p => (existsSync(p) ? JSON.parse(readFileSync(p, 'utf8')) : null)
const median = xs => {
  const v = xs.filter(x => typeof x === 'number' && Number.isFinite(x)).sort((a, b) => a - b)
  if (!v.length) return null
  const m = Math.floor(v.length / 2)
  return v.length % 2 ? v[m] : (v[m - 1] + v[m]) / 2
}
const tally = (xs) => {
  const t = {}
  for (const x of xs) t[x] = (t[x] ?? 0) + 1
  return Object.entries(t).sort((a, b) => b[1] - a[1]).map(([name, count]) => ({ name, count }))
}
// Scorecard columns: Node covers JavaScript and TypeScript.
const column = lang => ({ javascript: 'node', typescript: 'node', python: 'python', go: 'go', java: 'java' }[lang] ?? null)

// Per-run records
const runs = []
for (const id of readdirSync(join(results, 'raw')).sort()) {
  // Attempts interrupted by a usage limit are kept as `<run-id>.limit-<stamp>` and retried; skip them.
  if (id.includes('.limit-')) continue
  const dir = join(results, 'raw', id)
  const meta = readJson(join(dir, 'meta.json'))
  if (!meta) continue
  runs.push({ id, meta, measure: readJson(join(dir, 'measure.json')), native: readJson(join(dir, 'native.json')) })
}
const measured = runs.filter(r => r.measure?.classification)

// Hypothesis 1: condition A languages
const a = measured.filter(r => r.meta.condition === 'A')
const aLangs = a.map(r => r.measure.classification.language)
const aGroups = a.map(r => r.measure.classification.language_group)
const byTask = {}
for (const r of a) (byTask[r.meta.task] ??= []).push(r.measure.classification.language)

// Hypotheses 2 and 3: condition B
const b = measured.filter(r => r.meta.condition === 'B')
const bJava = b.filter(r => r.measure.classification.language === 'java')
const styles = bJava.map(r => r.measure.classification.java_style).filter(Boolean)
const dims = {}
for (const d of ['build', 'launch', 'source', 'imports', 'files', 'dependencies', 'optimization', 'wrapper_dim'])
  dims[d] = tally(styles.map(s => s[d] ?? 'unknown'))
const jsonpick = bJava.filter(r => r.meta.task === 'jsonpick')

// Hypothesis 4: per-language programs (bake-off baselines + generated)
const programs = []
const baselines = readJson(join(results, 'baselines.json')) ?? {}
for (const v of Object.values(baselines)) {
  if (!v.conformance?.pass) continue
  programs.push({
    source: 'bake-off', task: v.task, col: column(v.language), startup: v.startup?.median_ms,
    tokens: v.size?.source_tokens, disk: v.disk?.total_bytes,
    buildStep: v.language === 'go', launcher: false,
  })
}
for (const r of measured) {
  const m = r.measure
  if (!m.conformance?.pass) continue
  programs.push({
    source: r.meta.condition === 'A' ? 'condition A' : 'condition B',
    task: r.meta.task,
    col: column(m.classification.language),
    startup: m.startup?.median_ms,
    aot: m.optimized?.startup_aot?.median_ms,
    // Native image of the same jar (experiments/intro-scorecard/harness/native.py), for the javaw scorecard
    native: r.native?.startup_native?.median_ms,
    nativeDisk: r.native?.image_bytes != null && m.disk?.total_bytes != null ? m.disk.total_bytes + r.native.image_bytes : undefined,
    tokens: m.size?.source_tokens,
    disk: m.disk?.total_bytes,
    deps: m.classification.java_style?.dependencies,
    // Java: anything other than running the source directly needs a build first. Go always compiles.
    buildStep: m.classification.language === 'java'
      ? !['source launch', 'shebang'].includes(m.classification.java_style?.launch)
      : m.classification.language === 'go',
    launcher: m.classification.language === 'java'
      ? m.classification.java_style?.wrapper_dim === 'wrapper script'
      : Boolean(m.classification.launch?.wrapper),
  })
}
// Source size is compared as a ratio to the median Python program (bake-off and generated) for the same task.
const pythonTokens = Object.fromEntries([...new Set(programs.map(p => p.task))]
  .map(task => [task, median(programs.filter(p => p.col === 'python' && p.task === task).map(p => p.tokens))]))
const compare = {}
for (const col of ['go', 'python', 'node', 'java']) {
  const ps = programs.filter(p => p.col === col)
  const generated = ps.filter(p => p.source !== 'bake-off')
  compare[col] = {
    programs: ps.length,
    generated: generated.length,
    startupMs: median(ps.map(p => p.startup)),
    aotStartupMs: median(ps.map(p => p.aot)),
    nativeStartupMs: median(ps.map(p => p.native)),
    // Disk with the native image added (what a javaw --native wrapper leaves behind)
    nativeDiskBytes: median(ps.map(p => p.nativeDisk)),
    nativePrograms: ps.filter(p => p.native != null).length,
    aotPrograms: ps.filter(p => p.aot != null).length,
    tokensVsPython: median(ps.map(p => (pythonTokens[p.task] ? p.tokens / pythonTokens[p.task] : null))),
    diskBytes: median(ps.map(p => p.disk)),
    javaWithDepsDiskBytes: col === 'java' ? median(ps.filter(p => p.deps && p.deps !== 'none').map(p => p.disk)) : null,
    // Best-effort startup: Java with jar + AOT cache, everything else as is.
    bestStartupMs: col === 'java' ? median(ps.map(p => p.aot)) : median(ps.map(p => p.startup)),
    buildStepShare: ps.length ? ps.filter(p => p.buildStep).length / ps.length : null,
    launcherShare: ps.length ? ps.filter(p => p.launcher).length / ps.length : null,
  }
}

const scorecard = readJson(join(results, 'scorecard.json'))
const data = {
  generated: new Date().toISOString(),
  planned: PLANNED,
  runs: { generated: runs.length, measured: measured.length },
  models: [...new Set(runs.map(r => r.meta.model_alias))].sort(),
  tasks: [...new Set(runs.map(r => r.meta.task))].sort(),
  default: {
    n: a.length,
    languages: tally(aLangs),
    groups: tally(aGroups),
    byTask: Object.fromEntries(Object.entries(byTask).map(([t, ls]) => [t, tally(ls)])),
    pass: a.filter(r => r.measure.conformance?.pass).length,
  },
  java: {
    n: b.length,
    java: bJava.length,
    pass: bJava.filter(r => r.measure.conformance?.pass).length,
    combinations: tally(styles.map(s => s.combination)),
    dims,
    jsonpick: {
      n: jsonpick.length,
      withDependencies: jsonpick.filter(r => r.measure.classification.java_style?.dependencies !== 'none').length,
    },
  },
  compare,
  verdicts: (scorecard?.expectations ?? []).map(e => ({
    id: e.id, hypothesis: e.hypothesis, expectation: e.expectation, verdict: e.verdict,
    n: e.n, count: e.count, share: e.share, ciLow: e.ci_low, ciHigh: e.ci_high, exceptions: e.exceptions,
    threshold: e.threshold, comparison: e.comparison, mostCommon: e.most_common ?? null, baselineSet: e.baseline_set ?? null,
  })),
}
writeFileSync(out, JSON.stringify(data, null, 2) + '\n')
console.log(`intro-data: ${measured.length} measured of ${runs.length} generated runs (planned ${PLANNED}) -> data/intro.json`)
