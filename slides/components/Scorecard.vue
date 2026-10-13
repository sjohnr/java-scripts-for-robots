<script setup lang="ts">
import { computed } from 'vue'
import { type Column, type Row, languages, status } from '../data/scorecard'
import ScorecardRow from './ScorecardRow.vue'

const props = withDefaults(defineProps<{
  rows: Row[]
  columns?: Column[]
  /** Reveal one row per click. */
  stepped?: boolean
  caption?: string
}>(), {
  columns: () => languages,
  stepped: false,
  caption: undefined,
})

const captionText = computed(() => props.caption ?? `${status}. Generated programs plus bake-off baselines, JDK 25. Source: experiments/intro-scorecard`)
const gridStyle = computed(() => ({
  gridTemplateColumns: `minmax(10rem, 1.3fr) repeat(${props.columns.length}, minmax(0, 1fr))`,
}))
</script>

<template>
  <div class="scorecard">
    <div class="sc-grid" :style="gridStyle">
      <div class="sc-head" />
      <div
        v-for="col in columns" :key="col.id"
        class="sc-head sc-col" :class="{ 'sc-hl': col.highlight }"
      >
        <span class="sc-col-label">{{ col.label }}</span>
        <span v-if="col.sub" class="sc-col-sub">{{ col.sub }}</span>
      </div>

      <template v-for="row in rows" :key="row.label">
        <div v-if="stepped" v-click class="sc-row">
          <ScorecardRow :row="row" :columns="columns" :grid-style="gridStyle" />
        </div>
        <div v-else class="sc-row">
          <ScorecardRow :row="row" :columns="columns" :grid-style="gridStyle" />
        </div>
      </template>
    </div>
    <div v-if="captionText" class="sc-caption">{{ captionText }}</div>
  </div>
</template>

<style scoped>
.scorecard {
  --sc-win: #1f9d55;
  --sc-ok: #c58a00;
  --sc-lose: #d64545;
  --sc-na: #8a8f98;
  --sc-hl-bg: rgba(237, 139, 0, 0.10);  /* Java orange */
  --sc-hl-edge: rgba(237, 139, 0, 0.55);
  --sc-rule: rgba(128, 128, 128, 0.22);
  font-variant-numeric: tabular-nums;
  margin-top: 0.75rem;
}
:global(html.dark) .scorecard {
  --sc-win: #4ade80;
  --sc-ok: #fbbf24;
  --sc-lose: #f87171;
  --sc-hl-bg: rgba(237, 139, 0, 0.16);
}
.sc-grid { display: grid; }
.sc-row { grid-column: 1 / -1; }
.sc-head { padding: 0.35rem 0.75rem 0.5rem; }
.sc-col { display: flex; flex-direction: column; align-items: center; font-weight: 700; font-size: 1.05rem; }
.sc-col-sub { font-size: 0.65rem; font-weight: 500; opacity: 0.6; text-transform: uppercase; letter-spacing: 0.06em; }
.sc-head.sc-hl { background: var(--sc-hl-bg); border-top: 3px solid var(--sc-hl-edge); border-radius: 6px 6px 0 0; }
.sc-caption { font-size: 0.65rem; opacity: 0.5; margin-top: 0.6rem; text-align: right; }
</style>
