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
</script>

<template>
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
          <p class="async-panel__detail">{{ error?.userMessage }}</p>
          <p v-if="error && !error.retryable" class="async-panel__hint">
            잠시 뒤 다시 시도해도 같은 결과가 나올 수 있습니다. 입력한 조건을 확인해 주세요.
          </p>
        </div>
        <Button label="다시 시도" icon="pi pi-refresh" size="small" outlined @click="retry()" />
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
  font-size: 0.875rem;
}

.async-panel__hint {
  margin: 0.25rem 0 0;
  font-size: 0.8125rem;
  opacity: 0.85;
}
</style>
