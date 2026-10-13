<script setup lang="ts">
import { provisional, status } from '../data/scorecard'
</script>

<template>
  <div class="status" :class="{ provisional }">{{ status }}</div>
</template>

<style scoped>
.status { position: absolute; right: 1.5rem; bottom: 1rem; font-size: 0.65rem; opacity: 0.55; }
.status.provisional { color: #c2410c; opacity: 0.85; }
:global(html.dark) .status.provisional { color: #fb923c; }
</style>
