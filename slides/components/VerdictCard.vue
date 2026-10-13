<script setup lang="ts">
import { computed } from 'vue'
import { verdicts, type Verdict } from '../data/scorecard'

const props = defineProps<{
  /** Hypothesis numbers to include, e.g. [1, 2]. */
  hypotheses: number[]
}>()

const titles: Record<number, string> = {
  1: 'When asked to build a CLI tool, AI rarely produces Java',
  2: 'Asking for Java is enough to overcome the language bias',
  3: 'When asked for Java, the output is inconsistent',
  4: 'Java loses on performance and cost',
}

const groups = computed(() => props.hypotheses.map(h => ({ h, title: titles[h], rows: verdicts.filter(v => v.hypothesis === h) })))

const isShare = (v: Verdict) => v.ciLow != null && v.ciHigh != null && v.threshold != null && v.threshold <= 1
const isEvery = (v: Verdict) => v.comparison === 'every' && v.n > 0
const isCount = (v: Verdict) => v.comparison.startsWith('count') && v.count != null && v.threshold != null
// Count expectations: one slot per item, with room past the threshold.
const slots = (v: Verdict) => Math.max((v.count ?? 0) + 1, (v.threshold ?? 0) * 2)

function observed(v: Verdict) {
  if (v.n === 0) return 'none were observed'
  if (v.comparison === 'every') return `${v.exceptions ?? 0} of ${v.n} exceptions`
  if (v.comparison.startsWith('count')) return `${v.count} in ${v.n} runs`
  if (v.share != null) return `${v.count} of ${v.n} (${Math.round(v.share * 100)}%)`
  return `${v.count} of ${v.n}`
}
function verdictOf(v: Verdict) {
  if (v.n === 0) return { text: 'Not testable', cls: 'v-inconclusive' }
  if (v.verdict === 'supported') return { text: 'Supported', cls: 'v-supported' }
  if (v.verdict === 'not supported') return { text: 'Not supported', cls: 'v-not-supported' }
  return { text: 'Inconclusive', cls: 'v-inconclusive' }
}
</script>

<template>
  <div class="vc">
    <template v-for="g in groups" :key="g.h">
      <div class="vc-group"><span class="vc-h">#{{ g.h }}</span> {{ g.title }}</div>
      <div v-for="v in g.rows" :key="v.id" class="vc-row">
        <div class="vc-label">{{ v.label }}<span class="vc-expected">expected {{ v.expected }}</span></div>
        <div class="vc-track">
          <template v-if="isShare(v)">
            <div class="vc-band" :style="{ left: `${v.ciLow * 100}%`, width: `${(v.ciHigh - v.ciLow) * 100}%` }" />
            <div class="vc-threshold" :style="{ left: `${v.threshold * 100}%` }" />
            <div class="vc-point" :style="{ left: `${v.share * 100}%` }" />
          </template>
          <div v-else-if="isEvery(v)" class="vc-cells">
            <i v-for="i in v.n" :key="i" :class="{ miss: i > v.n - (v.exceptions ?? 0) }" />
          </div>
          <template v-else-if="isCount(v)">
            <div class="vc-cells">
              <i v-for="i in slots(v)" :key="i" :class="{ empty: i > v.count }" />
            </div>
            <div class="vc-threshold" :style="{ left: `${(v.threshold / slots(v)) * 100}%` }" />
          </template>
        </div>
        <div class="vc-observed">{{ observed(v) }}</div>
        <div class="vc-verdict" :class="verdictOf(v).cls">{{ verdictOf(v).text }}</div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.vc { display: grid; gap: 0.3rem; margin-top: 0.5rem; font-variant-numeric: tabular-nums; }
.vc-group { margin-top: 0.7rem; font-weight: 700; font-size: 0.95rem; }
.vc-h { display: inline-block; min-width: 2rem; color: #c2410c; }
:global(html.dark) .vc-h { color: #fb923c; }
.vc-row { display: grid; grid-template-columns: 17rem 1fr 9.5rem 7rem; align-items: center; gap: 0.9rem; padding: 0.3rem 0 0.3rem 2rem; border-top: 1px solid rgba(128, 128, 128, 0.2); }
.vc-label { display: flex; flex-direction: column; font-size: 0.85rem; font-weight: 600; }
.vc-expected { font-size: 0.68rem; font-weight: 400; opacity: 0.6; }
.vc-track { position: relative; height: 0.9rem; background: rgba(128, 128, 128, 0.12); border-radius: 4px; }
.vc-band { position: absolute; top: 0; bottom: 0; background: rgba(237, 139, 0, 0.35); border-radius: 4px; }
.vc-threshold { position: absolute; top: -0.25rem; bottom: -0.25rem; width: 2px; background: currentColor; opacity: 0.7; }
.vc-point { position: absolute; top: 50%; width: 0.7rem; height: 0.7rem; margin: -0.35rem 0 0 -0.35rem; border-radius: 50%; background: #c2410c; }
.vc-cells { position: absolute; inset: 0; display: flex; gap: 2px; }
.vc-cells i { flex: 1; border-radius: 2px; background: rgba(237, 139, 0, 0.75); }
.vc-cells i.miss { background: #d64545; }
.vc-cells i.empty { background: transparent; }
.vc-observed { font-size: 0.75rem; opacity: 0.8; text-align: right; }
.vc-verdict { font-size: 0.72rem; font-weight: 700; text-align: center; padding: 0.2rem 0.4rem; border-radius: 999px; }
.v-supported { background: rgba(31, 157, 85, 0.15); color: #1f9d55; }
.v-not-supported { background: rgba(214, 69, 69, 0.15); color: #d64545; }
.v-inconclusive { background: rgba(128, 128, 128, 0.15); opacity: 0.8; }
:global(html.dark) .v-supported { color: #4ade80; }
:global(html.dark) .v-not-supported { color: #f87171; }
</style>
