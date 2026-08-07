import { defineStore } from 'pinia'
import { computed, shallowRef } from 'vue'

import { api } from '@/api/endpoints'
import { ProblemType } from '@/api/http'
import type { ScreeningRequest } from '@/api/types'
import { useAsync } from '@/composables/useAsync'

/** 지표 보존 구간. 서버가 오류로 알려줄 때만 알 수 있다(ADR-0011). */
export interface RetentionWindow {
  from: string
  to: string
}

export const useScreeningStore = defineStore('screening', () => {
  const pending = shallowRef<ScreeningRequest | null>(null)
  const retention = shallowRef<RetentionWindow | null>(null)

  const request = useAsync((signal) => {
    const body = pending.value
    if (body === null) {
      // 여기 도달하면 호출 순서가 어긋난 것이다. 빈 요청을 보내면 서버가 400을 주고,
      // 그 오류는 사용자 입력 탓처럼 보인다.
      throw new Error('실행할 요청이 없다 — run()을 거치지 않고 재시도를 부른 경우다.')
    }
    return api.screening(body, signal)
  })

  const result = computed(() => request.data.value)
  const hasResult = computed(() => request.state.value === 'ready' && result.value !== null)

  async function run(body: ScreeningRequest): Promise<void> {
    pending.value = body
    await request.run()
    captureRetention()
  }

  /**
   * 보존 기간 밖 요청의 오류에서 허용 구간을 꺼내 둔다.
   *
   * 이 값이 없으면 사용자는 날짜를 하나씩 바꿔 가며 같은 오류를 반복해서 만난다. 서버는
   * `retentionFrom`·`retentionTo`를 이미 응답에 담고 있으므로(ADR-0015) 날짜 선택기의
   * 범위로 옮기면 같은 실수를 두 번 하지 않는다.
   */
  function captureRetention(): void {
    const error = request.error.value
    if (error === null || !error.is(ProblemType.INDICATOR_HISTORY_UNAVAILABLE)) {
      return
    }
    const from = error.problem?.retentionFrom
    const to = error.problem?.retentionTo
    if (typeof from === 'string' && typeof to === 'string') {
      retention.value = { from, to }
    }
  }

  return {
    state: request.state,
    error: request.error,
    retry: request.retry,
    result,
    hasResult,
    retention,
    run,
  }
})
