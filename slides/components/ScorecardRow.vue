<script setup lang="ts">
import type { Column, Grade, Row } from '../data/scorecard'

defineProps<{ row: Row, columns: Column[], gridStyle: Record<string, string> }>()

const icons: Record<Grade, string> = { win: '✓', ok: '~', lose: '✗', na: '' }
</script>

<template>
  <div class="sc-row-inner" :style="gridStyle">
    <div class="sc-label">
      <span>{{ row.label }}</span>
      <span v-if="row.detail" class="sc-detail">{{ row.detail }}</span>
    </div>
    <div
      v-for="col in columns" :key="col.id"
      class="sc-cell" :class="[`g-${row.cells[col.id]?.grade ?? 'na'}`, { 'sc-hl': col.highlight }]"
    >
      <!-- Keyed on the cell's content, so a changed value (introRows -> javawRows) slides in -->
      <Transition name="sc-swap" mode="out-in">
        <div :key="`${row.cells[col.id]?.value}|${row.cells[col.id]?.grade}`" class="sc-cell-body">
          <span class="sc-value">
            <i v-if="icons[row.cells[col.id]?.grade ?? 'na']">{{ icons[row.cells[col.id]?.grade ?? 'na'] }}</i>
            {{ row.cells[col.id]?.value ?? '–' }}
          </span>
          <span v-if="row.cells[col.id]?.note" class="sc-note">{{ row.cells[col.id]?.note }}</span>
        </div>
      </Transition>
    </div>
  </div>
</template>

<style scoped>
.sc-row-inner { display: grid; grid-column: 1 / -1; border-top: 1px solid var(--sc-rule); }
.sc-label { display: flex; flex-direction: column; justify-content: center; padding: 0.55rem 0.75rem; font-weight: 600; }
.sc-detail { font-size: 0.7rem; font-weight: 400; opacity: 0.6; }
.sc-cell { display: flex; flex-direction: column; align-items: center; justify-content: center; padding: 0.55rem 0.5rem; text-align: center; }
.sc-cell.sc-hl { background: var(--sc-hl-bg); }
.sc-cell-body { display: flex; flex-direction: column; align-items: center; }
.sc-swap-enter-active, .sc-swap-leave-active { transition: opacity 0.35s ease, transform 0.35s ease; }
.sc-swap-enter-from { opacity: 0; transform: translateY(0.5rem); }
.sc-swap-leave-to { opacity: 0; transform: translateY(-0.5rem); }
.sc-value { font-weight: 700; font-size: 1.05rem; display: inline-flex; align-items: baseline; gap: 0.35rem; }
.sc-value i { font-style: normal; font-size: 0.85rem; }
.sc-note { font-size: 0.66rem; opacity: 0.7; line-height: 1.2; margin-top: 0.1rem; }
.g-win .sc-value { color: var(--sc-win); }
.g-ok .sc-value { color: var(--sc-ok); }
.g-lose .sc-value { color: var(--sc-lose); }
.g-na .sc-value { color: var(--sc-na); font-weight: 500; font-size: 0.85rem; }
</style>
