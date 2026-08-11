import { useToast } from 'primevue/usetoast'

import { ApiError } from '@/api/http'
import { useAuthStore } from '@/stores/auth'

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
   *
   * 세션이 만료된 상태에서는 토스트를 내지 않는다 — 그 사실은 `App.vue`의 대화상자가 이미
   * 더 크게 말하고 있다. 조건은 {@link useAuthStore}의 `sessionExpired`이지 **`status === 401`이
   * 아니다.** 상태 코드로 판정하면 대화상자가 없는 상황에서도 삼키게 되고, 그때는 아무도
   * 말하지 않는 진짜 침묵이 된다.
   */
  notifyFailure: (summary: string, caught: unknown) => void
  notifySuccess: (summary: string, detail?: string) => void
}

export function useApiToast(): ApiToast {
  const toast = useToast()
  const auth = useAuthStore()

  function notifyFailure(summary: string, caught: unknown): void {
    if (!(caught instanceof ApiError)) {
      throw caught
    }
    // 만료 대화상자가 떠 있는 동안 실패 토스트가 겹치면, 사용자는 로그인해야 한다는 사실보다
    // "요청이 실패했습니다 (401)."을 먼저 읽는다. 실제로 그것이 이 변경의 계기였다.
    if (auth.sessionExpired) {
      return
    }
    toast.add({ severity: 'error', summary, detail: caught.userMessage, life: FAILURE_LIFE_MS })
  }

  function notifySuccess(summary: string, detail?: string): void {
    toast.add({ severity: 'success', summary, detail, life: SUCCESS_LIFE_MS })
  }

  return { notifyFailure, notifySuccess }
}
