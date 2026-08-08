<script setup lang="ts">
import { RouterLink, useRoute } from 'vue-router'

/**
 * 관리자 영역의 세 화면 사이 이동.
 *
 * 탭이 아니라 **라우트 셋**인 이유는 딥링크와 새로고침 때문이다. 배치 상세를 보다가 주소를
 * 공유하거나 새로고침하면 같은 자리로 돌아와야 하는데, 한 화면 안의 탭 상태는 주소에 남지
 * 않는다. 나머지 화면(`/screens`·`/watchlist`)이 이미 그 방식이라 규칙도 하나로 유지된다.
 *
 * 제목을 여기 두는 이유는 세 화면이 같은 머리를 갖게 하기 위해서다. 각자 그리면 어느 화면은
 * "관리자", 어느 화면은 "운영 현황"이 되어 지금 어디 있는지가 흔들린다.
 */
const route = useRoute()

const items = [
  { name: 'admin', label: '운영 현황', icon: 'pi pi-chart-bar' },
  { name: 'admin-logs', label: '인터랙션 기록', icon: 'pi pi-history' },
  { name: 'admin-accounts', label: '계정', icon: 'pi pi-users' },
] as const
</script>

<template>
  <header class="admin-nav">
    <h1 class="admin-nav__title">관리자</h1>
    <nav class="admin-nav__tabs" aria-label="관리자 메뉴">
      <RouterLink
        v-for="item in items"
        :key="item.name"
        :to="{ name: item.name }"
        class="admin-nav__tab"
        :class="{ 'admin-nav__tab--current': route.name === item.name }"
        :aria-current="route.name === item.name ? 'page' : undefined"
      >
        <i :class="item.icon" aria-hidden="true" />
        <span>{{ item.label }}</span>
      </RouterLink>
    </nav>
  </header>
</template>

<style scoped>
.admin-nav {
  margin-bottom: 1.5rem;
}

.admin-nav__title {
  font-size: var(--app-text-xl);
  letter-spacing: -0.01em;
  margin: 0 0 0.75rem;
}

.admin-nav__tabs {
  display: flex;
  gap: 0.25rem;
  border-bottom: 1px solid var(--p-content-border-color);
}

/*
 * 밑줄 탭. 현재 위치를 색이 아니라 **구조**로도 말한다 — 다크 한 벌에서 색만으로 가르면
 * 활성과 hover가 눈에 같은 밝기로 붙는다(레일이 같은 문제를 겪었다).
 */
.admin-nav__tab {
  display: flex;
  align-items: center;
  gap: 0.375rem;
  padding: 0.5rem 0.75rem;
  border-bottom: 2px solid transparent;
  margin-bottom: -1px;
  color: var(--p-text-muted-color);
  font-size: var(--app-text-sm);
  transition: color var(--app-motion-fast) var(--app-ease);
}

.admin-nav__tab:hover {
  color: var(--p-text-color);
  text-decoration: none;
}

.admin-nav__tab--current {
  color: var(--p-primary-color);
  border-bottom-color: var(--p-primary-color);
  font-weight: 600;
}
</style>
