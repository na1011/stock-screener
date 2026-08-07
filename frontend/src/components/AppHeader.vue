<script setup lang="ts">
import Button from 'primevue/button'
import { RouterLink } from 'vue-router'

import BrandMark from '@/components/BrandMark.vue'
import { APP_NAME } from '@/lib/appInfo'

/**
 * 좁은 폭(≤900px)에서만 나오는 상단 바.
 *
 * 넓은 폭에서는 좌측 레일이 브랜드·검색·이동·사용자 메뉴를 전부 들고 있어 이 바가 없다.
 * 세로 공간이 결과 행 수이기 때문이다.
 *
 * IMPORTANT — 여기에 검색을 두지 않는다. 레일(서랍)이 이미 갖고 있어서 두 벌이 함께 살아
 * 있게 되고, `StockSearch`는 AbortController로 진행 중인 요청을 관리하는 컴포넌트다.
 * 같은 것이 둘 떠 있으면 어느 쪽 결과가 보이는지가 포커스에 따라 갈린다.
 */
defineEmits<{ open: [] }>()
</script>

<template>
  <header class="app-header">
    <Button
      type="button"
      text
      size="small"
      icon="pi pi-bars"
      aria-label="메뉴 열기"
      @click="$emit('open')"
    />
    <RouterLink to="/" class="app-header__brand" :aria-label="APP_NAME">
      <BrandMark size="1rem" />
      <span class="app-header__wordmark">{{ APP_NAME }}</span>
    </RouterLink>
  </header>
</template>

<style scoped>
.app-header {
  position: sticky;
  top: 0;
  z-index: 20;
  display: flex;
  align-items: center;
  gap: 0.375rem;
  height: var(--app-header-height);
  padding: 0 0.5rem;
  border-bottom: 1px solid var(--p-content-border-color);
  background: color-mix(in srgb, var(--p-content-background) 86%, transparent);
  backdrop-filter: blur(12px);
}

.app-header__brand {
  display: inline-flex;
  align-items: center;
  gap: 0.375rem;
  min-width: 0;
  color: var(--p-text-color);
  font-weight: 700;
  font-size: var(--app-text-sm);
}

.app-header__brand:hover {
  text-decoration: none;
}

.app-header__wordmark {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 아주 좁으면 워드마크를 접는다. 로고만으로도 홈으로 가는 길은 남는다. */
@media (max-width: 640px) {
  .app-header__wordmark {
    display: none;
  }
}
</style>
