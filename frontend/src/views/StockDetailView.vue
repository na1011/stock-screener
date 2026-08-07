<script setup lang="ts">
defineProps<{ stockId: string }>()
// 종목 상세와 지지·저항은 이후 단계에서 채운다.
</script>

<template>
  <section class="stock-detail">
    <h1>종목 상세</h1>
    <p>준비 중입니다. (id: {{ stockId }})</p>
  </section>
</template>

<style scoped>
.stock-detail {
  max-width: 60rem;
  margin: 0 auto;
  padding: 1.5rem;
}

h1 {
  font-size: 1.25rem;
}

p {
  color: var(--p-text-muted-color);
}
</style>
