<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{
  items: { name: string, count: number }[]
  /** Total for the "x of n" labels; defaults to the sum of counts. */
  total?: number
}>()

const n = computed(() => props.total ?? props.items.reduce((s, i) => s + i.count, 0))
const max = computed(() => Math.max(1, ...props.items.map(i => i.count)))
</script>

<template>
  <div class="cb">
    <div v-for="i in items" :key="i.name" class="cb-row">
      <div class="cb-name">{{ i.name }}</div>
      <div class="cb-track"><div class="cb-fill" :style="{ width: `${(100 * i.count) / max}%` }" /></div>
      <div class="cb-count">{{ i.count }} of {{ n }}</div>
    </div>
  </div>
</template>

<style scoped>
.cb { display: grid; gap: 0.55rem; }
.cb-row { display: grid; grid-template-columns: minmax(9rem, 14rem) 1fr 5rem; align-items: center; gap: 0.9rem; }
.cb-name { font-weight: 600; font-size: 0.95rem; }
.cb-track { height: 1.4rem; background: rgba(128, 128, 128, 0.14); border-radius: 5px; overflow: hidden; }
.cb-fill { height: 100%; background: #ed8b00; border-radius: 5px; }
.cb-count { font-size: 0.85rem; opacity: 0.7; font-variant-numeric: tabular-nums; text-align: right; }
</style>
