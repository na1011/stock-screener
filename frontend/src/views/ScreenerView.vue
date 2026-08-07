<script setup lang="ts">
import ConditionBuilder from '@/components/condition/ConditionBuilder.vue'

// 결과 표는 다음 단계에서 채운다.
</script>

<template>
  <main class="screener">
    <ConditionBuilder />
  </main>
</template>

<style scoped>
.screener {
  max-width: 76rem;
  margin: 0 auto;
  padding: 1.5rem;
}

@media (max-width: 900px) {
  .screener {
    padding: 1rem;
  }
}
</style>
