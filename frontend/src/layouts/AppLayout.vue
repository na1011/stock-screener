<script setup lang="ts">
import Button from 'primevue/button'
import ConfirmDialog from 'primevue/confirmdialog'
import Drawer from 'primevue/drawer'
import Message from 'primevue/message'
import Toast from 'primevue/toast'
import { onErrorCaptured, onMounted, onUnmounted, ref, watch } from 'vue'
import { RouterView, useRoute } from 'vue-router'

import AppHeader from '@/components/AppHeader.vue'
import AppRail from '@/components/AppRail.vue'
import { useBreakpoint } from '@/composables/useBreakpoint'
import { useAuthStore } from '@/stores/auth'
import { useScreenDraftStore } from '@/stores/screenDraft'

/**
 * 로그인 이후 화면의 껍데기.
 *
 * 좌측 레일 + 작업 영역 두 열이다. 예전에는 가로 헤더 + 세로 스택이었는데, 그 구조에서는
 * 브랜드·검색·사용자 메뉴가 작업 영역의 세로 공간을 상시로 먹었다. 이 앱에서 세로는 결과 행
 * 수이고, 결과가 화면의 대부분을 차지해야 한다.
 *
 * `onErrorCaptured`가 여기 있는 이유는 렌더 예외까지 격리하기 위해서다. `AsyncPanel`은
 * 요청 실패를 막지만 컴포넌트 안에서 던진 예외는 잡지 못하고, 그때 Vue는 트리를 통째로
 * 해제해 **빈 화면**을 남긴다. 레일 옆에서 잡으면 최소한 다른 화면으로 이동할 수 있다.
 */
const route = useRoute()
const auth = useAuthStore()
const draft = useScreenDraftStore()
const { isBuilderNarrow } = useBreakpoint()
const renderError = ref<Error | null>(null)
const drawerOpen = ref(false)

/**
 * 탭으로 돌아왔을 때 세션이 아직 살아 있는지 다시 묻는다.
 *
 * 사용자가 보고한 증상의 앞쪽 절반이 여기다 — "API를 호출하기 전까지는 인터랙션이 무리없이
 * 됨." 세션은 서버에서 이미 끊겼는데 화면은 그것을 알 계기가 없어서, 사용자는 한참 조작한
 * 뒤 첫 요청에서야 알게 된다. 자리를 비웠다 돌아오는 것이 만료의 가장 흔한 경로이므로
 * 그 순간에 한 번 묻는다.
 *
 * 주기적 폴링은 하지 않는다. 개인용 서비스라 세션 만료를 몇 초 일찍 아는 값이 크지 않고,
 * 상시 요청은 조용히 쿼터와 로그를 먹는다.
 */
const REVALIDATE_MIN_INTERVAL_MS = 30_000
let lastRevalidatedAt = 0

function revalidateSession(): void {
  if (document.visibilityState !== 'visible') {
    return
  }
  // 탭을 자주 오가는 사용자에게 요청이 붙어 나가지 않게 한다.
  const now = Date.now()
  if (now - lastRevalidatedAt < REVALIDATE_MIN_INTERVAL_MS) {
    return
  }
  lastRevalidatedAt = now
  // `probe`는 ApiError가 아닌 예외를 다시 던진다(프로그래밍 오류를 서버 오류로 둔갑시키지
  // 않으려고). 여기서 받지 않으면 unhandled rejection이 되고 원인이 콘솔에서만 사라진다.
  void auth.revalidate().catch((caught: unknown) => {
    console.error('세션 재확인 실패', caught)
  })
}

onMounted(() => document.addEventListener('visibilitychange', revalidateSession))
onUnmounted(() => document.removeEventListener('visibilitychange', revalidateSession))

/**
 * 넓은 폭에서는 **이 상자가 스크롤한다**(ADR-0047). 그래서 라우트가 바뀔 때 맨 위로
 * 되돌리는 일도 여기 몫이다 — 라우터의 `scrollBehavior`는 window를 움직이는데 그것은
 * 더 이상 스크롤하지 않는다.
 *
 * 라우터를 고치지 않는 이유는 둘이다. 좁은 폭에서는 여전히 window가 스크롤하므로 그쪽
 * 설정이 유효하고, 라우터가 특정 CSS 클래스를 알게 되면 셸을 바꿀 때마다 함께 고쳐야 한다.
 */
const body = ref<HTMLElement | null>(null)

watch(
  () => route.fullPath,
  () => body.value?.scrollTo({ top: 0 }),
  // `RouterView`가 `:key`로 재마운트되므로 그 뒤에 움직여야 한다.
  { flush: 'post' },
)

onErrorCaptured((error) => {
  renderError.value = error instanceof Error ? error : new Error(String(error))
  // false를 반환해 전파를 멈춘다. 콘솔에는 남겨야 하므로 여기서 다시 기록한다 —
  // 삼키면 Phase 6의 Sentry가 붙어도 이 오류만 보이지 않는다.
  console.error('화면 렌더 중 오류', error)
  return false
})

// 넓어지면 레일이 상시로 보이므로 서랍은 닫혀 있어야 한다. 열린 채 남으면 넓은 화면에서
// 백드롭이 화면을 덮은 채 사라지지 않는다.
watch(isBuilderNarrow, (narrow) => {
  if (!narrow) {
    drawerOpen.value = false
  }
})

function dismiss(): void {
  renderError.value = null
}
</script>

<template>
  <div class="app-layout" :class="{ 'app-layout--narrow': isBuilderNarrow }">
    <AppRail v-if="!isBuilderNarrow" class="app-layout__rail" />

    <template v-else>
      <!-- 좁은 폭에서는 레일이 서랍이다. 상단 바는 그것을 여는 손잡이와 브랜드만 갖는다. -->
      <AppHeader @open="drawerOpen = true" />

      <!--
        `block-scroll`이 없으면 서랍 뒤의 문서가 스크롤된다. 서랍은 fixed인데 그 안의
        종목 검색 드롭다운은 문서 좌표에 박히므로(ADR-0047), 뒤가 움직이면 드롭다운만
        검색창에서 떨어져 나간다. 배경이 안 움직이는 것 자체가 옳기도 하다.
      -->
      <Drawer
        v-model:visible="drawerOpen"
        class="app-layout__drawer"
        :show-close-icon="false"
        block-scroll
      >
        <AppRail @navigate="drawerOpen = false" />
      </Drawer>
    </template>

    <div class="app-layout__main">
      <Message v-if="renderError" severity="error" :closable="false" class="app-layout__error">
        <div class="app-layout__error-body">
          <div>
            <strong>이 페이지를 그리는 중 문제가 발생했습니다.</strong>
            <p class="app-layout__error-detail">{{ renderError.message }}</p>
          </div>
          <Button label="닫기" size="small" outlined @click="dismiss" />
        </div>
      </Message>

      <main ref="body" class="app-layout__body">
        <!-- key를 라우트 경로로 두어 종목을 옮길 때 상태가 남지 않게 한다. -->
        <RouterView :key="route.fullPath" />
      </main>
    </div>

    <Toast position="bottom-right" />

    <!--
      조건 삭제 되돌리기.
      별도 group인 이유는 이 토스트에만 동작 버튼이 붙기 때문이다 — 기본 그룹에 템플릿을
      주면 모든 토스트가 그 모양이 된다. 되돌릴 것이 없으면 버튼을 그리지 않는다.
    -->
    <Toast position="bottom-right" group="undo-condition">
      <template #message="{ message }">
        <div class="app-layout__undo">
          <div>
            <strong>{{ message.summary }}</strong>
            <p class="app-layout__undo-detail">{{ message.detail }}</p>
          </div>
          <Button
            v-if="draft.lastRemoved"
            label="실행취소"
            icon="pi pi-undo"
            size="small"
            text
            @click="draft.undoRemove()"
          />
        </div>
      </template>
    </Toast>

    <ConfirmDialog />
  </div>
</template>

<style scoped>
.app-layout {
  display: grid;
  grid-template-columns: var(--app-rail-width) minmax(0, 1fr);
  min-height: 100dvh;
}

.app-layout--narrow {
  grid-template-columns: minmax(0, 1fr);
  grid-template-rows: var(--app-header-height) minmax(0, 1fr);
}

/* ------------------------------------------------------------------------
 * 넓은 폭에서는 **문서가 스크롤하지 않는다.** 셸이 뷰포트를 채우고 본문만 스크롤한다.
 *
 * IMPORTANT — 취향이 아니라 오버레이 때문이다(ADR-0047). PrimeVue는 오버레이를 body에
 * 붙이고 `getBoundingClientRect().top + pageYOffset`, 즉 **문서 좌표**에 `position: absolute`로
 * 박는다. 그리고 스크롤을 듣는 `getScrollableParents`는 `nodeType !== 9`로 document를
 * 배제하므로 **window 스크롤은 듣지도 못한다.** 그래서 sticky 앵커(레일 검색창, 조건 칩 바)에
 * 붙은 오버레이가 스크롤과 함께 흘러가고 닫히지도 않았다.
 *
 * window가 움직이지 않으면 문서 좌표도 움직이지 않는다 — 드롭다운이 검색창에 붙어 있다.
 * 덤으로 `.app-layout__body`가 scrollable parent가 되어 팝오버가 정상적으로 닫힌다
 * (그 대가는 ADR-0047에 적혀 있다: 움직이지 않는 앵커의 팝오버도 함께 닫힌다).
 *
 * **좁은 폭은 제외한다.** `height: 100dvh`로 문서를 묶으면 모바일 주소창이 영영 접히지
 * 않아 720px 화면에서 60px을 상시 잃는데, 애초에 그쪽에는 이 버그가 없다 — 900px 이하에서
 * 조건 바는 `static`이고(`ScreenerView`) `AppHeader`에는 오버레이 앵커가 하나도 없다.
 *
 * 미디어 쿼리가 아니라 `--narrow` 클래스를 쓰는 이유는 폭 경계값을 CSS에 또 적지 않기
 * 위해서다(frontend.md — 경계는 `lib/breakpoints.ts`에만 있다).
 * ------------------------------------------------------------------------ */
.app-layout:not(.app-layout--narrow) {
  /*
   * 행을 명시하지 않으면 암묵 행이 `auto`라 내용 높이로 자란다. 그러면 `height: 100dvh`를
   * 넘긴 채 `overflow: hidden`에 잘려 **스크롤바 없이 아래가 사라진다.**
   */
  grid-template-rows: minmax(0, 1fr);
  height: 100dvh;
  overflow: hidden;
}

.app-layout:not(.app-layout--narrow) .app-layout__main {
  /* flex 자식의 자동 최소 크기를 풀어야 아래 상자가 실제로 스크롤한다. */
  min-height: 0;
}

.app-layout:not(.app-layout--narrow) .app-layout__body {
  /*
   * `hidden auto`로 적는다. `clip auto`라고 써도 브라우저는 `hidden auto`로 계산하므로
   * (CSS Overflow 3 — 한 축이 visible/clip이 아니면 다른 축의 clip은 hidden이 된다)
   * clip이라 적어 두면 코드가 거짓말을 한다.
   */
  overflow: hidden auto;
}

/* 레일은 스크롤과 무관하게 제자리에 있어야 한다. 목록에서 전략을 고르는 것이 이 앱의
   주된 이동 수단이라, 아래로 내려가면 이동 자체가 불가능해진다. */
.app-layout__rail {
  position: sticky;
  top: 0;
  align-self: start;
}

.app-layout__main {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.app-layout__body {
  flex: 1;
  min-width: 0;
  /*
   * 마지막 방어선이다. 어느 화면이 실수로 뷰포트보다 넓어져도 문서 전체가 가로로 밀리지는
   * 않게 한다.
   *
   * IMPORTANT — `clip`인 이유가 **뒤집혔다.** 예전에는 "hidden이 스크롤 컨테이너를 만들어
   * 화면 안의 sticky가 이 상자를 기준으로 붙어 버리기 때문"에 clip을 골랐는데, 넓은 폭에서는
   * 이제 그것이 **원하는 동작**이다(위 `--narrow` 아닌 규칙과 ADR-0047). 여기 남은 `clip`은
   * 좁은 폭 전용이고, 그쪽은 문서가 스크롤해야 하므로 컨테이너를 만들면 안 된다.
   *
   * IMPORTANT — 여기에 기대어 원인을 덮지 않는다. 넘치는 곳이 보이면 그 컴포넌트의
   * min-width를 고친다.
   */
  overflow-x: clip;
}

.app-layout__error {
  margin: 0.75rem var(--app-gutter) 0;
}

.app-layout__error-body {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 1rem;
}

.app-layout__error-detail {
  margin: 0.25rem 0 0;
  font-size: var(--app-text-xs);
}

.app-layout__undo {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 0.75rem;
  width: 100%;
  font-size: var(--app-text-xs);
}

.app-layout__undo-detail {
  margin: 0.125rem 0 0;
  color: var(--p-text-muted-color);
}
</style>

<style>
/* 서랍은 body로 teleport되므로 scoped가 닿지 않는다. 레일이 자기 패딩을 갖고 있어 비운다. */
.app-layout__drawer {
  width: var(--app-rail-width) !important;
}

.app-layout__drawer .p-drawer-header {
  display: none;
}

.app-layout__drawer .p-drawer-content {
  padding: 0;
}
</style>
