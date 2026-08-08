<script setup lang="ts">
import Button from 'primevue/button'
import ConfirmDialog from 'primevue/confirmdialog'
import Drawer from 'primevue/drawer'
import Message from 'primevue/message'
import Toast from 'primevue/toast'
import { onErrorCaptured, ref, watch } from 'vue'
import { RouterView, useRoute } from 'vue-router'

import AppHeader from '@/components/AppHeader.vue'
import AppRail from '@/components/AppRail.vue'
import { useBreakpoint } from '@/composables/useBreakpoint'
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
const draft = useScreenDraftStore()
const { isBuilderNarrow } = useBreakpoint()
const renderError = ref<Error | null>(null)
const drawerOpen = ref(false)

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

      <Drawer v-model:visible="drawerOpen" class="app-layout__drawer" :show-close-icon="false">
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

      <main class="app-layout__body">
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
   * 않게 한다. `hidden`이 아니라 `clip`인 이유는 hidden이 스크롤 컨테이너를 만들어
   * 화면 안의 `position: sticky`가 이 상자를 기준으로 붙어 버리기 때문이다.
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
