<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{
  items: { name: string, count: number }[]
  /** Languages that must appear in the legend even at zero, e.g. ['java']. */
  always?: string[]
}>()

const colors: Record<string, string> = {
  python: '#3b78b5', javascript: '#7aa63a', typescript: '#2f74c0', shell: '#6b7280',
  go: '#1ea6c4', rust: '#a4553a', java: '#ed8b00', other: '#9ca3af',
}
const labels: Record<string, string> = {
  python: 'Python', javascript: 'JavaScript', typescript: 'TypeScript', shell: 'Shell',
  go: 'Go', rust: 'Rust', java: 'Java', other: 'Other',
}

const total = computed(() => props.items.reduce((s, i) => s + i.count, 0))
const legend = computed(() => {
  const seen = new Set(props.items.map(i => i.name))
  return [...props.items, ...(props.always ?? []).filter(n => !seen.has(n)).map(name => ({ name, count: 0 }))]
})
const pct = (n: number) => (total.value ? Math.round((100 * n) / total.value) : 0)
</script>

<template>
  <div class="lb">
    <div class="lb-bar">
      <div
        v-for="i in items" :key="i.name" class="lb-seg"
        :style="{ flexGrow: i.count, background: colors[i.name] ?? colors.other }"
      >
        <span v-if="pct(i.count) >= 12">{{ labels[i.name] ?? i.name }} {{ pct(i.count) }}%</span>
      </div>
    </div>
    <div class="lb-legend">
      <div v-for="i in legend" :key="i.name" class="lb-key" :class="{ zero: i.count === 0, java: i.name === 'java' }">
        <i :style="{ background: colors[i.name] ?? colors.other }" />
        <span class="lb-name">{{ labels[i.name] ?? i.name }}</span>
        <span class="lb-count">{{ i.count }} of {{ total }}</span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.lb { margin-top: 1.5rem; }
.lb-bar { display: flex; height: 4.5rem; border-radius: 10px; overflow: hidden; background: rgba(128, 128, 128, 0.15); }
.lb-seg { display: flex; align-items: center; justify-content: center; color: white; font-weight: 700; font-size: 1.1rem; min-width: 4px; }
.lb-legend { display: flex; flex-wrap: wrap; gap: 0.6rem 2rem; margin-top: 1rem; font-size: 0.95rem; }
.lb-key { display: flex; align-items: center; gap: 0.45rem; }
.lb-key i { width: 0.8rem; height: 0.8rem; border-radius: 3px; }
.lb-name { font-weight: 600; }
.lb-count { opacity: 0.65; font-variant-numeric: tabular-nums; }
.lb-key.zero i { background: transparent !important; border: 2px dashed #ed8b00; }
.lb-key.java .lb-name { color: #c2410c; }
:global(html.dark) .lb-key.java .lb-name { color: #fb923c; }
</style>
