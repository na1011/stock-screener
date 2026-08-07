import { useToast } from 'primevue/usetoast'

import { ApiError } from '@/api/http'

/**
 * 결과를 알리는 토스트.
 *
 * 같은 블록이 화면마다 복사되어 있었다 — `severity`, 표시 시간, 그리고 `ApiError`가 아닌
 * 예외를 다시 던지는 규칙까지. 그 중 마지막이 특히 복사되면 안 되는 것이다: 한 곳이라도
 * 빠뜨리면 프로그래밍 오류가 "요청 실패" 토스트로 둔갑해 원인을 잃는다.
 *
 * 실패 토스트가 성공보다 오래 남는 이유는 사용자가 읽고 조치해야 하기 때문이다. 성공은
 * 확인만 하면 된다.
 */
const FAILURE_LIFE_MS = 5000
const SUCCESS_LIFE_MS = 3000

export interface ApiToast {
  /**
   * 실패를 알린다.
   *
   * IMPORTANT — `ApiError`가 아닌 예외는 **다시 던진다.** 삼키면 렌더 버그·타입 오류가
   * 서버 오류로 보이고, 그 순간 원인을 추적할 길이 사라진다.
   */
  notifyFailure: (summary: string, caught: unknown) => void
  notifySuccess: (summary: string, detail?: string) => void
}

export function useApiToast(): ApiToast {
  const toast = useToast()

  function notifyFailure(summary: string, caught: unknown): void {
    if (!(caught instanceof ApiError)) {
      throw caught
    }
    toast.add({ severity: 'error', summary, detail: caught.userMessage, life: FAILURE_LIFE_MS })
  }

  function notifySuccess(summary: string, detail?: string): void {
    toast.add({ severity: 'success', summary, detail, life: SUCCESS_LIFE_MS })
  }

  return { notifyFailure, notifySuccess }
}
