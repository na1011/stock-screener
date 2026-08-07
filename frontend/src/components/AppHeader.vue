<script setup lang="ts">
import Button from 'primevue/button'
import Menu from 'primevue/menu'
import type { MenuItem } from 'primevue/menuitem'
import { computed, ref } from 'vue'
import { RouterLink, useRoute } from 'vue-router'

import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const route = useRoute()

const userMenu = ref<InstanceType<typeof Menu> | null>(null)

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
    <RouterLink to="/" class="app-header__brand">주식 스크리너</RouterLink>

    <nav class="app-header__nav" aria-label="주요 화면">
      <RouterLink
        v-for="item in navItems"
        :key="item.to"
        :to="item.to"
        class="app-header__link"
        :class="{ 'app-header__link--active': isActive(item.to) }"
      >
        <i :class="item.icon" aria-hidden="true" />
        <span>{{ item.label }}</span>
      </RouterLink>
    </nav>

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
.app-header {
  display: flex;
  align-items: center;
  gap: 1rem;
  padding: 0.5rem 1rem;
  border-bottom: 1px solid var(--p-content-border-color);
  background: var(--p-content-background);
  position: sticky;
  top: 0;
  z-index: 10;
}

.app-header__brand {
  font-weight: 700;
  color: inherit;
  text-decoration: none;
  white-space: nowrap;
}

.app-header__nav {
  display: flex;
  gap: 0.25rem;
  flex: 1;
}

.app-header__link {
  display: inline-flex;
  align-items: center;
  gap: 0.375rem;
  padding: 0.375rem 0.75rem;
  border-radius: var(--p-border-radius-md);
  color: var(--p-text-muted-color);
  text-decoration: none;
  font-size: 0.9375rem;
}

.app-header__link--active {
  color: var(--p-primary-color);
  background: var(--p-highlight-background);
}

.app-header__user-button :deep(.p-button-label) {
  max-width: 12rem;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 모바일에서는 이름을 감추고 아이콘만 남긴다. 화면 폭을 이름이 먹으면 정작 이동이 좁아진다. */
@media (max-width: 640px) {
  .app-header__brand {
    font-size: 0.9375rem;
  }

  .app-header__link span {
    display: none;
  }

  .app-header__user-button :deep(.p-button-label) {
    display: none;
  }
}
</style>
