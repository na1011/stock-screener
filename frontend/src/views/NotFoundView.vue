<script setup lang="ts">
import { RouterLink } from 'vue-router'
</script>

<template>
  <main class="not-found">
    <h1>페이지를 찾을 수 없습니다</h1>
    <p>주소가 잘못되었거나 삭제된 화면입니다.</p>
    <RouterLink to="/">스크리너로 돌아가기</RouterLink>
  </main>
</template>

<style scoped>
.not-found {
  max-width: 32rem;
  margin: 0 auto;
  padding: 6rem var(--app-gutter);
  text-align: center;
}

h1 {
  font-size: 1.25rem;
  letter-spacing: -0.01em;
  margin-bottom: 0.5rem;
}

p {
  color: var(--p-text-muted-color);
  margin-bottom: 1.5rem;
}
</style>
