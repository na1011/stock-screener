<script setup lang="ts">
import Button from 'primevue/button'
import Message from 'primevue/message'
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
