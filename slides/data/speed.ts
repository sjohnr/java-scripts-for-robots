// Speed test numbers for the "Today: Performance" slides, formatted for display.
// data/speed.json comes from scripts/speed-data.mjs (experiments/speed-test/results). Missing values show as TBD.
import speed from './speed.json'

export type Launcher = 'python' | 'small-disk' | 'portable' | 'fast-start' | 'native'

interface Raw { warm?: number, warmSd?: number, first?: number, diskKb?: number, lines?: number }
const raw = speed.launchers as Record<string, Raw>

const TBD = 'TBD'
const time = (s?: number) => s == null ? TBD : s < 1 ? `${Math.round(s * 1000)} ms` : `${s.toFixed(1)} s`
const disk = (kb?: number) => kb == null ? TBD : kb < 1024 ? `${kb} KB` : `${(kb / 1024).toFixed(1)} MB`
const python = raw.python?.warm

/** CALLS: tool calls in an agent session, for the "per session" row. */
export const CALLS = 50

function fmt(name: Launcher) {
  const r = raw[name] ?? {}
  return {
    warm: time(r.warm),
    first: time(r.first),
    ratio: r.warm != null && python ? `${(r.warm / python).toFixed(1)}x` : TBD,
    session: time(r.warm == null ? undefined : r.warm * CALLS),
    disk: disk(r.diskKb),
    lines: r.lines == null ? TBD : `${r.lines} lines`,
  }
}

// camelCase keys, so slides can write {{ s.fastStart.warm }} (Markdown turns quotes in s['fast-start'] into curly ones)
export const s = {
  python: fmt('python'),
  smallDisk: fmt('small-disk'),
  portable: fmt('portable'),
  fastStart: fmt('fast-start'),
  native: fmt('native'),
}

export const lib = disk(speed.libKb ?? undefined)
export const env = speed.env
