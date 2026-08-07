<script setup lang="ts">
import Button from 'primevue/button'
import ConfirmDialog from 'primevue/confirmdialog'
import Message from 'primevue/message'
import Toast from 'primevue/toast'
import { onErrorCaptured, ref } from 'vue'
import { RouterView, useRoute } from 'vue-router'

import AppHeader from '@/components/AppHeader.vue'

/**
 * 로그인 이후 화면의 껍데기.
 *
 * `onErrorCaptured`가 여기 있는 이유는 렌더 예외까지 격리하기 위해서다. `AsyncPanel`은
 * 요청 실패를 막지만 컴포넌트 안에서 던진 예외는 잡지 못하고, 그때 Vue는 트리를 통째로
 * 해제해 **빈 화면**을 남긴다. 헤더 아래에서 잡으면 최소한 다른 화면으로 이동할 수 있다.
 */
const route = useRoute()
const renderError = ref<Error | null>(null)

onErrorCaptured((error) => {
  renderError.value = error instanceof Error ? error : new Error(String(error))
  // false를 반환해 전파를 멈춘다. 콘솔에는 남겨야 하므로 여기서 다시 기록한다 —
  // 삼키면 Phase 6의 Sentry가 붙어도 이 오류만 보이지 않는다.
  console.error('화면 렌더 중 오류', error)
  return false
})

function dismiss(): void {
  renderError.value = null
}
</script>

<template>
  <div class="app-layout">
    <AppHeader />

    <Message v-if="renderError" severity="error" :closable="false" class="app-layout__error">
      <div class="app-layout__error-body">
        <div>
          <strong>이 화면을 그리는 중 문제가 발생했습니다.</strong>
          <p class="app-layout__error-detail">{{ renderError.message }}</p>
        </div>
        <Button label="닫기" size="small" outlined @click="dismiss" />
      </div>
    </Message>

    <main class="app-layout__body">
      <!-- key를 라우트 경로로 두어 종목을 옮길 때 상태가 남지 않게 한다. -->
      <RouterView :key="route.fullPath" />
    </main>

    <Toast position="bottom-right" />
    <ConfirmDialog />
  </div>
</template>

<style scoped>
.app-layout {
  min-height: 100dvh;
  display: flex;
  flex-direction: column;
}

.app-layout__body {
  flex: 1;
  min-width: 0;
  /*
   * 마지막 방어선이다. 어느 화면이 실수로 뷰포트보다 넓어져도 문서 전체가 가로로 밀리지는
   * 않게 한다. `hidden`이 아니라 `clip`인 이유는 hidden이 스크롤 컨테이너를 만들어
   * 헤더의 `position: sticky`가 이 상자를 기준으로 붙어 버리기 때문이다.
   *
   * IMPORTANT — 여기에 기대어 원인을 덮지 않는다. 헤더는 이 상자 **밖**이라 여기 걸리지도
   * 않는다. 넘치는 곳이 보이면 그 컴포넌트의 min-width를 고친다.
   */
  overflow-x: clip;
}

.app-layout__error {
  margin: 1rem;
}

.app-layout__error-body {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 1rem;
}

.app-layout__error-detail {
  margin: 0.25rem 0 0;
  font-size: 0.875rem;
}
</style>
