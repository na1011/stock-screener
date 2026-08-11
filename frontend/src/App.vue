<script setup lang="ts">
import Button from 'primevue/button'
import Dialog from 'primevue/dialog'
import { RouterView, useRoute } from 'vue-router'

import { useAuthStore } from '@/stores/auth'

/**
 * 세션 만료 안내가 **셸이 아니라 여기** 있는 이유.
 *
 * `AppLayout`은 로그인한 뒤의 화면만 감싼다 — `/login`과 404는 그 밖이다(`router/index.ts`).
 * 만료는 어느 화면에서든 날 수 있으므로 안내도 라우트 밖에 있어야 한다.
 *
 * IMPORTANT — 즉시 `/login`으로 밀지 않는다. OAuth는 전체 페이지 이동이라 편집 중인 조건이
 * 통째로 사라지는데, 예고 없이 그러면 사용자는 무엇을 잃었는지도 모른다. 화면을 그대로 두고
 * 사용자가 누를 때 옮긴다.
 */
const auth = useAuthStore()
const route = useRoute()
</script>

<template>
  <RouterView />

  <!--
    닫을 수 없다. 닫으면 요청이 계속 거절되는데 화면에는 아무 설명도 남지 않아,
    "토스트만 뜨던" 예전 상태보다 오히려 나쁘다.
  -->
  <Dialog
    :visible="auth.sessionExpired"
    modal
    :closable="false"
    :close-on-escape="false"
    :draggable="false"
    header="로그인이 만료되었습니다"
    class="session-expired"
  >
    <p class="session-expired__body">
      세션이 만료되어 서버가 요청을 거절했습니다. 다시 로그인하면 보던 화면으로 돌아옵니다 —
      다만 <strong>저장하지 않은 편집은 사라집니다.</strong>
    </p>
    <template #footer>
      <Button label="다시 로그인" icon="pi pi-sign-in" @click="auth.signIn(route.fullPath)" />
    </template>
  </Dialog>
</template>

<style scoped>
.session-expired {
  max-width: var(--app-overlay-lg);
}

.session-expired__body {
  margin: 0;
  line-height: 1.55;
}
</style>
