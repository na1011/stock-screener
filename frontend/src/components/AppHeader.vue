<script setup lang="ts">
import Button from 'primevue/button'
import Menu from 'primevue/menu'
import type { MenuItem } from 'primevue/menuitem'
import Popover from 'primevue/popover'
import { computed, ref } from 'vue'
import { RouterLink, useRoute } from 'vue-router'

import StockSearch from '@/components/StockSearch.vue'
import { useBreakpoint } from '@/composables/useBreakpoint'
import { APP_NAME } from '@/lib/appInfo'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const route = useRoute()
const { isCompact } = useBreakpoint()

const userMenu = ref<InstanceType<typeof Menu> | null>(null)
const searchPanel = ref<InstanceType<typeof Popover> | null>(null)

/**
 * 관리자 항목은 권한이 있을 때만 목록에 들어간다.
 *
 * 비활성으로 보여주지 않는 이유는 frontend.md에 있다 — 관리 기능이 보이는 것만으로도
 * 운영 구조가 드러난다. 지인이 쓰는 서비스라도 계정별 권한 경계는 유지한다.
 */
const userMenuItems = computed<MenuItem[]>(() => {
  const items: MenuItem[] = []
  if (auth.isAdmin) {
    items.push({
      label: '관리자',
      icon: 'pi pi-cog',
      // Phase 6에서 화면이 붙는다. 지금은 항목이 권한에 따라만 나타나는지를 보이는 자리다.
      disabled: true,
    })
    items.push({ separator: true })
  }
  items.push({ label: '로그아웃', icon: 'pi pi-sign-out', command: () => auth.signOut() })
  return items
})

const navItems = [
  { to: '/', label: '스크리너', icon: 'pi pi-filter' },
  { to: '/screens', label: '내 화면', icon: 'pi pi-bookmark' },
]

function isActive(to: string): boolean {
  return to === '/' ? route.path === '/' : route.path.startsWith(to)
}
</script>

<template>
  <header class="app-header">
    <RouterLink to="/" class="app-header__brand" :aria-label="APP_NAME">
      <!-- 값이 다른 막대 셋. 이 화면이 다루는 것이 시세라는 것을 글자보다 먼저 알린다. -->
      <svg class="app-header__mark" viewBox="0 0 16 16" aria-hidden="true" focusable="false">
        <rect x="0" y="9" width="4" height="7" rx="1" />
        <rect x="6" y="5" width="4" height="11" rx="1" />
        <rect x="12" y="0" width="4" height="16" rx="1" />
      </svg>
      <span class="app-header__wordmark">{{ APP_NAME }}</span>
    </RouterLink>

    <nav class="app-header__nav" aria-label="주요 화면">
      <RouterLink
        v-for="item in navItems"
        :key="item.to"
        :to="item.to"
        class="app-header__link"
        :class="{ 'app-header__link--active': isActive(item.to) }"
      >
        <i :class="item.icon" aria-hidden="true" />
        <span class="app-header__link-label">{{ item.label }}</span>
      </RouterLink>
    </nav>

    <!--
      좁은 폭에서는 검색창을 줄이지 않고 버튼 뒤로 접는다. 예전에는 8rem으로 눌러 두었는데
      그 폭에서는 종목명이 두 글자도 보이지 않아 쓸 수 없었다. 컴포넌트를 갈아 끼우므로
      CSS로 감추는 것과 달리 좁은 폭에서 두 벌이 함께 살아 있지 않다.
    -->
    <div class="app-header__search">
      <template v-if="isCompact">
        <Button
          type="button"
          text
          icon="pi pi-search"
          aria-label="종목 검색"
          aria-haspopup="true"
          @click="searchPanel?.toggle($event)"
        />
        <Popover ref="searchPanel" class="app-header__search-panel">
          <StockSearch class="app-header__search-field" />
        </Popover>
      </template>
      <StockSearch v-else class="app-header__search-field" />
    </div>

    <div class="app-header__user">
      <Button
        type="button"
        text
        size="small"
        icon="pi pi-user"
        :label="auth.displayName"
        aria-haspopup="true"
        aria-controls="user-menu"
        class="app-header__user-button"
        @click="userMenu?.toggle($event)"
      />
      <Menu id="user-menu" ref="userMenu" :model="userMenuItems" :popup="true" />
    </div>
  </header>
</template>

<style scoped>
/*
 * IMPORTANT — 이 줄의 자식은 전부 `flex: 0 0 auto`이거나 `min-width: 0`이어야 한다.
 * 예전에는 브랜드(nowrap)·nav(flex:1)·검색(14rem)·사용자 이름(12rem)이 전부 내용 기반
 * 최소 크기를 그대로 들고 있어서, 641~899px 구간에서 헤더가 뷰포트를 밀어내고 문서 전체에
 * 가로 스크롤이 생겼다. sticky 헤더는 그 스크롤을 따라가지 않아 오른쪽에서 잘려 나갔다.
 * 그 구간은 자동 검사가 하나도 덮지 않는다(TODO §1 ⑮).
 */
.app-header {
  position: sticky;
  top: 0;
  z-index: 20;
  display: flex;
  align-items: center;
  gap: 0.5rem;
  height: var(--app-header-height);
  padding: 0 var(--app-gutter);
  border-bottom: 1px solid var(--p-content-border-color);
  background: color-mix(in srgb, var(--p-content-background) 86%, transparent);
  backdrop-filter: blur(12px);
}

.app-header__brand {
  display: inline-flex;
  align-items: center;
  gap: 0.5rem;
  flex: 0 0 auto;
  color: var(--p-text-color);
  font-weight: 700;
  font-size: 0.9375rem;
  letter-spacing: -0.01em;
  white-space: nowrap;
}

.app-header__brand:hover {
  text-decoration: none;
}

.app-header__mark {
  width: 1rem;
  height: 1rem;
  fill: var(--p-primary-color);
  flex: 0 0 auto;
}

.app-header__nav {
  display: flex;
  align-items: stretch;
  align-self: stretch;
  gap: 0.125rem;
  flex: 0 0 auto;
  margin-inline-start: 0.5rem;
}

/* 활성 표시가 배경이 아니라 밑줄인 이유는 표와 톤을 맞추기 위해서다. 이 화면의 구조는
   면이 아니라 선으로 나뉜다. */
.app-header__link {
  position: relative;
  display: inline-flex;
  align-items: center;
  gap: 0.375rem;
  padding: 0 0.625rem;
  color: var(--p-text-muted-color);
  font-size: 0.875rem;
  white-space: nowrap;
}

.app-header__link:hover {
  color: var(--p-text-color);
  text-decoration: none;
}

.app-header__link--active {
  color: var(--p-text-color);
}

.app-header__link--active::after {
  content: '';
  position: absolute;
  inset-inline: 0.5rem;
  bottom: 0;
  height: 2px;
  border-radius: 1px 1px 0 0;
  background: var(--p-primary-color);
}

/* 남는 자리를 검색이 먹되, 좁아지면 먼저 줄어드는 것도 검색이다. */
.app-header__search {
  display: flex;
  justify-content: flex-end;
  flex: 1 1 auto;
  min-width: 0;
  margin-inline-start: auto;
}

.app-header__search-field {
  width: 100%;
  max-width: 18rem;
}

.app-header__search-panel .app-header__search-field {
  max-width: none;
  width: 16rem;
}

.app-header__user {
  flex: 0 0 auto;
}

.app-header__user-button :deep(.p-button-label) {
  max-width: 10rem;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 폭을 이름과 라벨이 먹으면 정작 이동이 좁아진다. 아이콘만 남긴다. */
@media (max-width: 640px) {
  .app-header__wordmark,
  .app-header__link-label {
    display: none;
  }

  .app-header__user-button :deep(.p-button-label) {
    display: none;
  }

  .app-header__nav {
    margin-inline-start: 0.25rem;
  }
}
</style>
