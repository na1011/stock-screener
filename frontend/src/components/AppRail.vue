<script setup lang="ts">
import Button from 'primevue/button'
import Menu from 'primevue/menu'
import type { MenuItem } from 'primevue/menuitem'
import { computed, onMounted, ref } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'

import type { ScreenResponse } from '@/api/types'
import AsyncPanel from '@/components/AsyncPanel.vue'
import BrandMark from '@/components/BrandMark.vue'
import StockSearch from '@/components/StockSearch.vue'
import { useScreenOpener, screenKey } from '@/composables/useScreenOpener'
import { APP_NAME } from '@/lib/appInfo'
import { screenTypeLabel } from '@/lib/screen'
import { useAuthStore } from '@/stores/auth'
import { FRESH_DRAFT_TITLE, useScreenDraftStore } from '@/stores/screenDraft'
import { useScreensStore } from '@/stores/screens'
import { useWatchlistStore } from '@/stores/watchlist'

/**
 * 좌측 레일.
 *
 * 전략 목록을 여기 **상주**시키는 것이 요점이다. 예전에는 조건을 바꾸려면 `/screens`로
 * 나갔다 돌아오거나 상단 Select를 열어야 했다. 목록이 늘 보이면 "지금 무엇을 보고 있는가"와
 * "무엇으로 바꿀 수 있는가"가 동시에 답해진다 — spec §5가 요구하는 하나로 이어지는 흐름이다.
 *
 * 로그인 후 셸의 브랜드·검색·사용자 메뉴도 여기로 옮겼다. 상단 가로 바에 그것들이 있으면
 * 작업 영역의 세로 공간을 상시로 먹는데, 이 앱에서 세로는 결과 행 수다.
 */
const auth = useAuthStore()
const screens = useScreensStore()
const watchlist = useWatchlistStore()
const draft = useScreenDraftStore()
const route = useRoute()
const router = useRouter()
const { openByKey, isOpen } = useScreenOpener()

const emit = defineEmits<{ navigate: [] }>()

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

onMounted(() => {
  void screens.ensureLoaded()
  // 배지가 처음부터 맞으려면 여기서 한 번 읽어야 한다. 워치리스트 화면에 들어가야 채워지면
  // "충족된 것이 있다"를 알려주는 자리가 아니게 된다.
  void watchlist.ensureLoaded()
})

const sections = computed(() => [
  { label: screenTypeLabel('PRESET'), items: screens.presets },
  { label: screenTypeLabel('CUSTOM'), items: screens.mine },
])

/** 새 조건을 만드는 중인가. 어느 저장된 전략도 열려 있지 않은 상태다. */
const isFresh = computed(() => draft.origin.kind === 'custom')

async function pick(screen: ScreenResponse): Promise<void> {
  if (await openByKey(screenKey(screen))) {
    // 좁은 폭에서는 레일이 서랍이다. 고르고 나면 닫혀야 결과가 보인다. 실패했으면 열어 둔다 —
    // 닫아 버리면 토스트만 남고 다시 고르려면 서랍부터 열어야 한다.
    emit('navigate')
  }
}

/**
 * 조건을 비우고 스크리너로 간다.
 *
 * 이동이 필요한 이유는 `openByKey`와 같다 — 종목 상세를 보는 중에 누르면 조건만 조용히
 * 비워지고 본문은 그대로였다. 여기는 `openByKey`를 거치지 않으므로 이동을 직접 붙인다.
 */
async function startFresh(): Promise<void> {
  draft.reset()
  await router.push({ name: 'screener' })
  emit('navigate')
}
</script>

<template>
  <aside class="rail">
    <RouterLink to="/" class="rail__brand" :aria-label="APP_NAME" @click="emit('navigate')">
      <BrandMark />
      <span class="rail__wordmark">{{ APP_NAME }}</span>
    </RouterLink>

    <div class="rail__search">
      <StockSearch />
    </div>

    <nav class="rail__nav" aria-label="주요 메뉴">
      <RouterLink
        to="/"
        class="app-list-button rail__item"
        :class="{ 'rail__item--active': route.path === '/' && !isFresh }"
        @click="emit('navigate')"
      >
        <i class="pi pi-filter rail__icon" aria-hidden="true" />
        <span>스크리너</span>
      </RouterLink>
      <RouterLink
        to="/screens"
        class="app-list-button rail__item"
        :class="{ 'rail__item--active': route.path.startsWith('/screens') }"
        @click="emit('navigate')"
      >
        <i class="pi pi-bookmark rail__icon" aria-hidden="true" />
        <span>전략 관리</span>
      </RouterLink>
      <RouterLink
        to="/watchlist"
        class="app-list-button rail__item"
        :class="{ 'rail__item--active': route.path.startsWith('/watchlist') }"
        @click="emit('navigate')"
      >
        <i class="pi pi-star rail__icon" aria-hidden="true" />
        <span>관심종목</span>
        <!--
          충족 중인 항목 수. 0이면 배지를 그리지 않는다 — 늘 0이 붙어 있으면 그 자리는
          곧 배경이 되고, 실제로 무언가 충족된 날에도 눈에 띄지 않는다.
        -->
        <span v-if="watchlist.satisfiedCount > 0" class="rail__badge tabular">
          {{ watchlist.satisfiedCount }}
        </span>
      </RouterLink>
    </nav>

    <div class="rail__scroll">
      <AsyncPanel
        :state="screens.state"
        :error="screens.error"
        :retry="screens.retry"
        panel="screens"
        :skeleton-rows="4"
      >
        <section v-for="section in sections" :key="section.label" class="rail__section">
          <h2 class="rail__section-title">
            {{ section.label }}
            <span class="rail__count tabular">{{ section.items.length }}</span>
          </h2>
          <ul v-if="section.items.length > 0" class="rail__list">
            <li v-for="screen in section.items" :key="screenKey(screen)">
              <button
                type="button"
                class="app-list-button rail__item rail__item--screen"
                :class="{ 'rail__item--active': isOpen(screen) }"
                @click="pick(screen)"
              >
                <span class="rail__item-label">{{ screen.name }}</span>
              </button>
            </li>
          </ul>
          <!-- 비어 있다는 사실 자체가 안내다. 목록이 그냥 없으면 로딩 중인지 빈 것인지 모른다. -->
          <p v-else class="rail__empty">아직 저장한 전략이 없습니다.</p>
        </section>
      </AsyncPanel>
    </div>

    <div class="rail__foot">
      <button
        type="button"
        class="app-list-button rail__item rail__item--fresh"
        :class="{ 'rail__item--active': isFresh }"
        @click="startFresh"
      >
        <i class="pi pi-plus rail__icon" aria-hidden="true" />
        <span>{{ FRESH_DRAFT_TITLE }}</span>
      </button>

      <Button
        type="button"
        text
        size="small"
        icon="pi pi-user"
        :label="auth.displayName"
        aria-haspopup="true"
        aria-controls="user-menu"
        class="rail__user"
        @click="userMenu?.toggle($event)"
      />
      <Menu id="user-menu" ref="userMenu" :model="userMenuItems" :popup="true" />
    </div>
  </aside>
</template>

<style scoped>
.rail {
  display: flex;
  flex-direction: column;
  width: var(--app-rail-width);
  height: 100dvh;
  padding: 0.625rem 0.5rem;
  gap: 0.5rem;
  border-inline-end: 1px solid var(--p-content-border-color);
  background: var(--p-content-background);
}

.rail__brand {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.25rem 0.5rem 0.5rem;
  color: var(--p-text-color);
  font-weight: 700;
  font-size: var(--app-text-md);
  letter-spacing: -0.01em;
}

.rail__brand:hover {
  text-decoration: none;
}

.rail__search,
.rail__nav {
  flex: 0 0 auto;
}

.rail__nav {
  display: flex;
  flex-direction: column;
  gap: 1px;
}

/*
 * 항목 하나의 생김새. 링크(`RouterLink`)와 버튼이 같은 클래스를 쓴다 — 둘이 다르게 보이면
 * "누를 수 있는 것"이 두 종류로 읽힌다. 리셋은 `.app-list-button`이 맡고 여기는 배치만 정한다.
 */
.rail__item {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  width: 100%;
  min-height: 1.875rem;
  padding: 0 0.5rem;
  color: var(--p-text-muted-color);
  font-size: var(--app-text-sm);
}

.rail__item:hover {
  color: var(--p-text-color);
  text-decoration: none;
}

/*
 * 활성 표시는 면 + 왼쪽 표식이다. 헤더가 쓰던 밑줄을 가져오지 않는 이유는 세로 목록에서
 * 밑줄이 구분선과 구별되지 않기 때문이다.
 *
 * 면만으로는 약했다 — hover 배경과 같은 `surface.800`이라 커서를 올린 항목과 지금 열린
 * 항목이 같아 보였다. 코발트 막대가 그 둘을 가른다.
 */
.rail__item--active {
  position: relative;
  background: var(--p-surface-800);
  color: var(--p-text-color);
  font-weight: 600;
}

.rail__item--active::before {
  content: '';
  position: absolute;
  inset-inline-start: 0;
  top: 20%;
  bottom: 20%;
  width: 2px;
  border-radius: 0 2px 2px 0;
  background: var(--p-primary-color);
}

.rail__icon {
  flex: 0 0 auto;
  font-size: var(--app-text-xs);
  color: var(--p-text-muted-color);
}

.rail__item--active .rail__icon {
  color: var(--p-primary-color);
}

.rail__item-label {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 목록이 길어져도 브랜드·검색·하단은 제자리에 있어야 한다. */
.rail__scroll {
  flex: 1 1 auto;
  min-height: 0;
  overflow-y: auto;
  padding-top: 0.25rem;
  border-top: 1px solid var(--p-content-border-color);
}

.rail__section + .rail__section {
  margin-top: 0.75rem;
}

.rail__section-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin: 0 0 0.25rem;
  padding: 0 0.5rem;
  font-size: var(--app-text-2xs);
  font-weight: 600;
  letter-spacing: 0.04em;
  color: var(--p-text-subtle-color);
}

.rail__count {
  color: var(--p-text-subtle-color);
}

/* 충족 중인 관심종목 수. 항목 오른쪽 끝으로 민다. */
.rail__badge {
  margin-left: auto;
  padding: 0 0.375rem;
  border-radius: 999px;
  font-size: var(--app-text-2xs);
  font-weight: 600;
  line-height: 1.4;
  color: var(--p-primary-contrast-color);
  background: var(--p-primary-color);
}

.rail__list {
  margin: 0;
  padding: 0;
  list-style: none;
}

.rail__empty {
  margin: 0;
  padding: 0 0.5rem;
  font-size: var(--app-text-2xs);
  color: var(--p-text-subtle-color);
}

.rail__foot {
  flex: 0 0 auto;
  padding-top: 0.375rem;
  border-top: 1px solid var(--p-content-border-color);
}

.rail__item--fresh {
  color: var(--p-primary-color);
}

.rail__user {
  width: 100%;
  justify-content: flex-start;
}

.rail__user :deep(.p-button-label) {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  text-align: start;
}

/*
 * 좁은 폭에서는 레일이 서랍으로 열린다(AppLayout이 위치를 정한다). 높이를 100%로 두어
 * 서랍 안에서도 목록만 스크롤되게 한다.
 */
@media (max-width: 900px) {
  .rail {
    width: 100%;
    height: 100%;
    border-inline-end: 0;
  }
}
</style>
