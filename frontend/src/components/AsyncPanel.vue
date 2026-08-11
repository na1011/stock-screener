<script setup lang="ts">
import Button from 'primevue/button'
import Message from 'primevue/message'
import Skeleton from 'primevue/skeleton'
import { computed } from 'vue'

import type { ApiError } from '@/api/http'
import { ASYNC_PANELS } from '@/components/asyncPanel'
import type { AsyncPanelName } from '@/components/asyncPanel'
import type { AsyncState } from '@/composables/useAsync'

/**
 * 영역 단위 오류 격리.
 *
 * 한 패널이 실패해도 나머지 화면은 그대로 동작해야 한다(spec §8, frontend.md). 그러려면
 * 실패가 이 컴포넌트 안에서 끝나야 하고, 사용자가 **그 영역만** 다시 시도할 수 있어야 한다.
 *
 * 자동 재시도는 이미 `apiFetch`가 상태 코드로 판별해 두 번 수행했다. 여기까지 왔다는 것은
 * 그래도 실패했다는 뜻이므로, 재시도 대상이면 버튼을 주고 아니면 사용자 조치가 필요하다는
 * 것을 알린다 — 버그를 무한 재시도하게 만들지 않는다(ADR-0015).
 */
const props = defineProps<{
  state: AsyncState
  error: ApiError | null
  retry: () => void
  /**
   * 무엇이 실패했는지 알리는 영역 이름. 자유 문자열이 아니라 `ASYNC_PANELS`의 키다 —
   * 오타가 컴파일 오류가 되고, 조사가 문구 조립과 함께 정해진다.
   */
  panel: AsyncPanelName
  /** 로딩 자리를 채울 스켈레톤 줄 수. */
  skeletonRows?: number
}>()

const subject = computed(() => ASYNC_PANELS[props.panel])
// computed인 이유는 setup에서 한 번 읽으면 값이 바뀌어도 따라가지 않기 때문이다.
// 지금 호출부가 전부 정적 리터럴이라 드러나지 않을 뿐, 다음 호출자를 위한 함정이다.
const rows = computed(() => props.skeletonRows ?? 3)

/**
 * 세션이 끊겨서 실패한 것인가.
 *
 * IMPORTANT — 이 갈래가 없는 동안 401에서 나오던 문장이 **"잠시 뒤 다시 시도해도 같은 결과가
 * 나올 수 있습니다. 입력한 조건을 확인해 주세요."**였다(`isRetryable`이 401에 false를 주므로).
 * 세션이 만료된 사용자에게 조건을 확인하라고 한 것이다. 재시도 버튼도 함께 그려졌고, 눌러도
 * 다시 401이었다.
 *
 * 무엇을 해야 하는지는 `App.vue`의 대화상자가 말한다. 여기서는 재시도를 권하지 않는 것으로 족하다.
 */
const signedOut = computed(() => props.error?.status === 401)
</script>

<template>
  <!--
    IMPORTANT — 이 래퍼는 블록이다. 그래서 **부모의 `gap`이 슬롯 내용에 닿지 않는다.**
    `<div style="display:flex; gap:1.25rem">` 안에 이 패널을 넣으면 그 gap은 패널 하나에만
    적용되고, 슬롯에 넣은 형제들 사이는 0이 된다.

    간격은 슬롯 안에서 마진으로 만든다. 실제로 이 사실을 모른 채 음수 마진으로 간격을
    조절하던 곳에서 관심종목 버튼이 가격 위에 얹혔다(`StockDetailView`).

    `display: contents`로 고치지 않는다 — 소비처가 열 곳이 넘고 전부 지금 요소별 마진으로
    간격을 맞추고 있어, 한 줄로 모든 화면의 간격이 조용히 바뀐다.
  -->
  <div class="async-panel">
    <template v-if="state === 'ready'">
      <slot />
    </template>

    <div
      v-else-if="state === 'loading'"
      class="async-panel__loading"
      :aria-label="`${subject.subject} 불러오는 중`"
    >
      <slot name="loading">
        <Skeleton v-for="row in rows" :key="row" height="1.5rem" class="async-panel__skeleton" />
      </slot>
    </div>

    <Message v-else-if="state === 'error'" severity="error" :closable="false">
      <div class="async-panel__error">
        <div>
          <strong>{{ subject.subject }}{{ subject.particle }} 불러오지 못했습니다.</strong>
          <!-- 401의 본문은 "요청이 실패했습니다 (401)."이다. 세션 얘기를 대신 한다. -->
          <p v-if="signedOut" class="async-panel__detail">로그인이 만료되었습니다.</p>
          <p v-else class="async-panel__detail">{{ error?.userMessage }}</p>
          <p v-if="signedOut" class="async-panel__hint">
            다시 로그인하면 이 영역도 함께 복구됩니다.
          </p>
          <p v-else-if="error && !error.retryable" class="async-panel__hint">
            잠시 뒤 다시 시도해도 같은 결과가 나올 수 있습니다. 입력한 조건을 확인해 주세요.
          </p>
        </div>
        <!-- 세션이 끊긴 것이면 눌러도 다시 401이다. 되지 않을 것을 권하지 않는다. -->
        <Button
          v-if="!signedOut"
          label="다시 시도"
          icon="pi pi-refresh"
          size="small"
          outlined
          @click="retry()"
        />
      </div>
    </Message>

    <div v-else class="async-panel__idle">
      <slot name="idle" />
    </div>
  </div>
</template>

<style scoped>
.async-panel__skeleton {
  margin-bottom: 0.5rem;
}

.async-panel__error {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 1rem;
  flex-wrap: wrap;
}

.async-panel__detail {
  margin: 0.25rem 0 0;
  font-size: var(--app-text-md);
}

.async-panel__hint {
  margin: 0.25rem 0 0;
  font-size: var(--app-text-sm);
  opacity: 0.85;
}
</style>
