<script setup lang="ts">
import Button from 'primevue/button'
import Menu from 'primevue/menu'
import type { MenuItem } from 'primevue/menuitem'
import { computed, onMounted, ref } from 'vue'
import { RouterLink, useRoute } from 'vue-router'

import { SCREEN_TYPES } from '@/api/types'
import type { ScreenResponse } from '@/api/types'
import AsyncPanel from '@/components/AsyncPanel.vue'
import BrandMark from '@/components/BrandMark.vue'
import StockSearch from '@/components/StockSearch.vue'
import { useFreshDraft } from '@/composables/useFreshDraft'
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
const { openByKey, isOpen } = useScreenOpener()
const { startFresh } = useFreshDraft()

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

/**
 * 목록의 두 구획.
 *
 * `SCREEN_TYPES`에서 파생시킨다 — 손으로 적은 배열은 서버가 종류를 늘려도 그대로 남아, 새
 * 종류의 전략이 **목록에서 통째로 사라지는데 아무 데서도 티가 나지 않는다**(frontend.md의
 * "순서·선택지 배열은 유니온에서 파생시킨다"). 그 배열은 `wire-contract.json`이 지키고 있다.
 */
const sections = computed(() =>
  SCREEN_TYPES.map((type) => ({
    type,
    label: screenTypeLabel(type),
    items: type === 'PRESET' ? screens.presets : screens.mine,
  })),
)

/** 새 전략을 만드는 중인가. 어느 저장된 전략도 열려 있지 않은 상태다. */
const isFresh = computed(() => draft.origin.kind === 'custom')

async function pick(screen: ScreenResponse): Promise<void> {
  if (await openByKey(screenKey(screen))) {
    // 좁은 폭에서는 레일이 서랍이다. 고르고 나면 닫혀야 결과가 보인다. 실패했으면 열어 둔다 —
    // 닫아 버리면 토스트만 남고 다시 고르려면 서랍부터 열어야 한다.
    emit('navigate')
  }
}

/*
 * 조건을 비우고 스크리너로 간다. 확인 절차와 이동은 `useFreshDraft`가 갖는다.
 *
 * 여기가 확인 없이 즉시 날리던 것이 이 추출의 계기다. 버튼이 목록 위로 올라와 눈에 띄는
 * 자리가 되었으므로, 잘못 누르는 대가가 "조건 스무 개가 말없이 사라지는 것"이면 안 된다.
 */
</script>

<template>
  <aside class="rail">
    <!--
      머리 띠. 브랜드와 검색을 한 상자에 묶고 아래를 선으로 끊는다.

      브랜드가 nav 항목처럼 읽히던 것이 문제였다 — `RouterLink to="/"`인데 바로 아래 "스크리너"도
      같은 곳으로 가서, 같은 목적지 둘이 나란히 선 목록의 첫 줄로 보였다. 띠로 갈라야 로고가
      로고로 보인다.
    -->
    <div class="rail__head">
      <RouterLink to="/" class="rail__brand" :aria-label="APP_NAME" @click="emit('navigate')">
        <BrandMark size="1.25rem" />
        <span class="rail__wordmark">{{ APP_NAME }}</span>
      </RouterLink>
      <StockSearch />
    </div>

    <nav class="rail__nav" aria-label="주요 메뉴">
      <RouterLink
        to="/"
        class="app-list-button rail__item"
        :class="{ 'rail__item--current': route.path === '/' }"
        @click="emit('navigate')"
      >
        <i class="pi pi-filter rail__icon" aria-hidden="true" />
        <span>스크리너</span>
      </RouterLink>
      <RouterLink
        to="/watchlist"
        class="app-list-button rail__item"
        :class="{ 'rail__item--current': route.path.startsWith('/watchlist') }"
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

    <!--
      새 전략은 목록 **바로 위**에 둔다. 아래 항목들과 상호배타이기 때문이다 — 편집기에 올라가
      있는 것은 저장된 전략 하나이거나 즉석 초안이거나 둘 중 하나다. 그래서 활성 표시도
      목록 항목과 같은 `--open`을 쓴다. 하단 사용자 메뉴 옆에 있던 동안에는 그 관계가 보이지
      않았고, 무엇보다 주 생성 액션이 가장 눈에 안 띄는 자리에 있었다.
    -->
    <button
      type="button"
      class="app-list-button rail__item rail__fresh"
      :class="{ 'rail__item--open': isFresh }"
      @click="startFresh(() => emit('navigate'))"
    >
      <i class="pi pi-plus rail__icon" aria-hidden="true" />
      <span>{{ FRESH_DRAFT_TITLE }}</span>
    </button>

    <div class="rail__scroll">
      <AsyncPanel
        :state="screens.state"
        :error="screens.error"
        :retry="screens.retry"
        panel="screens"
        :skeleton-rows="4"
      >
        <section
          v-for="section in sections"
          :key="section.type"
          class="rail__section"
          :aria-labelledby="`rail-section-${section.type}`"
        >
          <!--
            구획 제목. 오른쪽으로 흐르는 선이 붙는 이유는 **한국어에 대문자가 없어서**다.
            "작게 + 어둡게"만으로는 항목과 갈리지 않았고(실제로 11px/600과 13px/400이 같은
            무게로 읽혔다), 색을 더 내리면 대비 기준에 못 미친다. 구분선에 라벨이 얹힌 형태는
            누를 수 있는 항목과 결코 혼동되지 않는다 — 색이 아니라 구조로 가른다.
          -->
          <h2 :id="`rail-section-${section.type}`" class="rail__section-title">
            <span>{{ section.label }}</span>
            <span class="rail__rule" aria-hidden="true" />
            <span class="rail__count tabular">{{ section.items.length }}</span>
            <!--
              `/screens` 진입점은 여기 하나다. 주 nav에 두었을 때는 바로 아래 목록이 같은 것을
              가리켜 인접한 두 레벨이 같아졌다. 캡션에 붙으면 캡션이 **기능을 갖게 되어** 항목과
              다른 범주가 된다. 프리셋 쪽에는 두지 않는다 — 고칠 수도 지울 수도 없다(서버가 403).
            -->
            <RouterLink
              v-if="section.type === 'CUSTOM'"
              to="/screens"
              class="rail__manage"
              :class="{ 'rail__manage--current': route.path.startsWith('/screens') }"
              aria-label="전략 관리"
              v-tooltip.right="'전략 관리'"
              @click="emit('navigate')"
            >
              <i class="pi pi-cog" aria-hidden="true" />
            </RouterLink>
          </h2>
          <ul v-if="section.items.length > 0" class="rail__list">
            <li v-for="screen in section.items" :key="screenKey(screen)">
              <button
                type="button"
                class="app-list-button rail__item rail__item--screen"
                :class="{ 'rail__item--open': isOpen(screen) }"
                :aria-current="isOpen(screen) ? 'true' : undefined"
                :title="screen.name"
                @click="pick(screen)"
              >
                <span class="rail__item-label">{{ screen.name }}</span>
              </button>
            </li>
          </ul>
          <!-- 비어 있다는 사실 자체가 안내다. 목록이 그냥 없으면 로딩 중인지 빈 것인지 모른다. -->
          <p v-else class="rail__empty">
            {{
              section.type === 'CUSTOM'
                ? '아직 저장한 전략이 없습니다.'
                : '제공되는 프리셋이 없습니다.'
            }}
          </p>
        </section>
      </AsyncPanel>
    </div>

    <div class="rail__foot">
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

/* 머리 띠. 아래 선이 "여기까지가 서비스, 아래부터가 내용"을 가른다. */
.rail__head {
  flex: 0 0 auto;
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
  padding-bottom: 0.625rem;
  border-bottom: 1px solid var(--p-content-border-color);
}

.rail__brand {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.25rem 0.5rem 0;
  color: var(--p-text-color);
  font-weight: 700;
  /* 14px이던 것을 올린다. 아래 항목(13px)과 한 칸 차이라 이름이 라벨처럼 읽혔다. */
  font-size: var(--app-text-lg);
  letter-spacing: -0.02em;
}

.rail__brand:hover {
  text-decoration: none;
}

.rail__nav {
  flex: 0 0 auto;
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
  /*
   * IMPORTANT — muted(#8792a6)였던 것을 본문 색(#dce3ed)으로 올린다. 이 한 줄이 레일이
   * 평평해 보이던 진짜 원인이었다. 항목과 구획 제목(subtle #78859a)이 램프에서 두 칸
   * 떨어져 있어 **눈에는 같은 회색**이었고, 그래서 제목을 작게·굵게 해도 위계가 서지 않았다.
   * 본문 색으로 올리면 간격이 다섯 칸으로 벌어져 제목이 저절로 물러난다. 되돌리면
   * 구획 제목의 크기·굵기를 아무리 만져도 다시 평평해진다.
   */
  color: var(--p-text-color);
  font-size: var(--app-text-sm);
}

.rail__item:hover {
  text-decoration: none;
}

/*
 * 활성 표시가 **두 종류**다. 이것이 레일이 평평해 보이던 나머지 절반이다.
 *
 * `--current`는 **장소**(주 nav)다. 면만 준다.
 * `--open`은 **내용**(편집기에 올라간 것 — 전략 목록과 "새 전략")이다. accent 면 + 코발트 막대.
 *
 * 둘이 같은 표시를 쓰면 "스크리너에 있다"와 "저평가 가치주를 보고 있다"가 같은 무게로 나와
 * 둘 다 동시에 켜졌을 때 무엇이 무엇에 속하는지 읽히지 않는다. 강약이 갈려야 포함 관계가 보인다.
 */
.rail__item--current,
.rail__item--open {
  position: relative;
}

.rail__item--current {
  background: var(--p-surface-800);
}

.rail__item--open {
  background: var(--app-accent-surface);
  font-weight: 600;
}

.rail__item--open::before {
  content: '';
  position: absolute;
  inset-inline-start: 0;
  top: 20%;
  bottom: 20%;
  width: 2px;
  border-radius: 0 2px 2px 0;
  background: var(--p-primary-color);
}

/*
 * IMPORTANT — 활성 배경을 hover·focus에도 명시적으로 다시 적는다.
 *
 * `main.css`의 `.app-list-button:hover`와 여기 scoped 규칙은 **특정도가 같다**(둘 다 0,2,0).
 * 그러면 승자를 정하는 것은 순서인데, PrimeVue와 Vue의 scoped 스타일이 런타임에 주입되므로
 * 순서를 여기서 보장할 수 없다. 이 블록이 없으면 **열려 있는 항목에 마우스를 올리는 순간
 * 코발트 틴트가 회색으로 되돌아간다.** 지금까지 드러나지 않았던 이유는 활성과 hover가 둘 다
 * `surface.800`이라 바뀌어도 같아 보였기 때문이다.
 */
.rail__item--current,
.rail__item--current:hover,
.rail__item--current:focus-visible {
  background: var(--p-surface-800);
}

.rail__item--open,
.rail__item--open:hover,
.rail__item--open:focus-visible {
  background: var(--app-accent-surface);
}

.rail__icon {
  flex: 0 0 auto;
  font-size: var(--app-text-xs);
  color: var(--p-text-muted-color);
}

.rail__item--current .rail__icon,
.rail__item--open .rail__icon {
  color: var(--p-primary-color);
}

/*
 * 새 전략. 채운 버튼으로 만들지 않는다 — `.app-list-button` 계열 안에서 혼자 다른 종류가 되어
 * "누를 수 있는 것"이 두 가지로 읽힌다. 코발트 글자 + 안쪽 1px 윤곽이면, 나머지 행이 전부
 * 본문 색·400인 목록에서 첫 번째로 읽힌다. 윤곽을 `border`가 아니라 inset 그림자로 주는 것은
 * 행 높이가 다른 행과 1px씩 어긋나지 않게 하기 위해서다.
 */
.rail__fresh {
  flex: 0 0 auto;
  min-height: 2rem;
  color: var(--p-primary-color);
  font-weight: 600;
  box-shadow: inset 0 0 0 1px var(--app-accent-border);
}

.rail__fresh .rail__icon,
.rail__fresh:hover {
  color: var(--p-primary-color);
}

.rail__fresh:hover,
.rail__fresh:focus-visible {
  background: var(--app-accent-surface);
}

.rail__item-label {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/*
 * 목록이 길어져도 머리·nav·새 전략·하단은 제자리에 있어야 한다.
 *
 * 위쪽 `border-top`을 두지 않는다. "새 전략"과 이 목록 사이에 선이 있으면 둘이 다른 것으로
 * 읽히는데, 실제로는 상호배타인 같은 범주다.
 */
.rail__scroll {
  flex: 1 1 auto;
  min-height: 0;
  overflow-y: auto;
  padding-top: 0.25rem;
}

.rail__section + .rail__section {
  margin-top: 0.875rem;
}

/*
 * 구획 제목. 스크롤해도 붙어 있게 한다 — 전략이 쌓이면 "지금 보는 것이 프리셋인가 내
 * 것인가"와 관리 링크가 함께 위로 밀려 사라진다.
 *
 * 배경을 주는 이유는 sticky라 아래 항목이 뒤로 지나가기 때문이다. `.rail`이 자기 배경을
 * 갖고 있어 서랍(surface.850) 안에서도 어긋나지 않는다.
 */
.rail__section-title {
  position: sticky;
  top: 0;
  z-index: 1;
  display: flex;
  align-items: center;
  gap: 0.375rem;
  margin: 0 0 0.125rem;
  padding: 0.25rem 0.5rem;
  font-size: var(--app-text-2xs);
  font-weight: 600;
  letter-spacing: 0.05em;
  color: var(--p-text-subtle-color);
  background: var(--p-content-background);
}

/* 제목 오른쪽으로 흐르는 선. 항목과 다른 범주라는 것을 색이 아니라 구조로 말한다. */
.rail__rule {
  flex: 1 1 auto;
  min-width: 0;
  height: 1px;
  background: linear-gradient(90deg, var(--p-content-border-color), transparent);
}

/*
 * 관리 링크. 음수 마진으로 캡션 높이를 늘리지 않게 한다.
 *
 * hover에서만 나타나게 하지 않는다 — 좁은 폭에서 레일은 서랍이고 거기엔 hover가 없다.
 * 그러면 `/screens`로 가는 길이 모바일에서 통째로 사라진다.
 */
.rail__manage {
  display: flex;
  align-items: center;
  justify-content: center;
  flex: 0 0 auto;
  width: 1.5rem;
  height: 1.5rem;
  margin: -0.25rem -0.25rem -0.25rem 0;
  border-radius: var(--p-border-radius-sm);
  color: var(--p-text-subtle-color);
  font-size: var(--app-text-xs);
  transition: color var(--app-motion-fast) var(--app-ease);
}

/* 전역 `a:hover`가 밑줄을 준다. 아이콘 하나짜리 링크에는 어울리지 않는다. */
.rail__manage:hover {
  color: var(--p-text-color);
  text-decoration: none;
}

/* `/screens`에 있을 때 켜진다. 없으면 그 페이지에서 레일에 활성이 하나도 없다. */
.rail__manage--current {
  color: var(--p-primary-color);
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
 *
 * `AppLayout`이 `isBuilderNarrow`일 때만 서랍을 그리므로 **900px 이하 ⟺ 서랍**이다. 그래서
 * prop 없이 이 미디어 쿼리가 서랍 전용 규칙을 겸할 수 있다. 레일을 `AppLayout` 밖에서
 * 렌더하기 시작하면 그 등가가 깨지고 아래 손가락용 치수가 엉뚱한 데 적용된다.
 */
@media (max-width: 900px) {
  .rail {
    width: 100%;
    height: 100%;
    border-inline-end: 0;
  }

  /* 서랍은 손가락으로 쓴다. 30px 행은 그때만 짧다. */
  .rail__item {
    min-height: 2.25rem;
  }

  .rail__fresh {
    min-height: 2.5rem;
  }

  .rail__manage {
    width: 2rem;
    height: 2rem;
    margin-inline-end: -0.375rem;
  }

  .rail__section-title {
    padding-block: 0.375rem;
  }
}
</style>
