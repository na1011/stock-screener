import { onScopeDispose, ref, shallowRef } from 'vue'
import type { Ref, ShallowRef } from 'vue'

import { ApiError } from '@/api/http'

/**
 * 비동기 로딩 하나를 상태 기계로 다룬다.
 *
 * 화면 전체가 아니라 **영역마다** 하나씩 쓰는 것이 요점이다. 지지·저항 패널이 실패해도 종목
 * 상세는 살아 있어야 하고(spec §8, frontend.md), 그러려면 실패 상태가 영역 안에 갇혀야 한다.
 * 전역 로딩 플래그 하나로 다루면 한 요청의 실패가 화면 전체를 덮는다.
 *
 * 자동 재시도는 여기서 하지 않는다 — {@code apiFetch}가 이미 상태 코드로 판별해 두 번 재시도
 * 한다. 여기의 {@link Async.retry}는 그 뒤에도 실패했을 때 사용자가 직접 누르는 수단이다.
 */
export type AsyncState = 'idle' | 'loading' | 'ready' | 'error'

export interface Async<T> {
  state: Ref<AsyncState>
  data: ShallowRef<T | null>
  error: ShallowRef<ApiError | null>
  /** 실행한다. 이전 실행이 진행 중이면 취소한다. */
  run: () => Promise<void>
  /** 사용자가 누르는 재시도. `run`과 같지만 호출부에서 의도가 드러난다. */
  retry: () => Promise<void>
  reset: () => void
}

export function useAsync<T>(
  loader: (signal: AbortSignal) => Promise<T>,
  options: { immediate?: boolean } = {},
): Async<T> {
  const state = ref<AsyncState>('idle')
  const data = shallowRef<T | null>(null)
  const error = shallowRef<ApiError | null>(null)

  let controller: AbortController | null = null

  async function run(): Promise<void> {
    // 이전 요청을 취소한다. 취소하지 않으면 늦게 도착한 옛 응답이 새 결과를 덮어쓴다 —
    // 조건을 빠르게 두 번 바꿨을 때 첫 번째 결과가 표에 남는 형태로 드러난다.
    controller?.abort()
    const current = new AbortController()
    controller = current

    state.value = 'loading'
    error.value = null

    try {
      const result = await loader(current.signal)
      if (current.signal.aborted) {
        return
      }
      data.value = result
      state.value = 'ready'
    } catch (caught) {
      if (current.signal.aborted || isAbort(caught)) {
        return
      }
      // ApiError가 아닌 예외는 우리가 다룰 수 없는 결함이다. 삼키면 화면이 영원히
      // '로딩 중'으로 남고 콘솔에도 아무것도 남지 않는다.
      if (!(caught instanceof ApiError)) {
        state.value = 'error'
        throw caught
      }
      error.value = caught
      state.value = 'error'
    }
  }

  function reset(): void {
    controller?.abort()
    controller = null
    state.value = 'idle'
    data.value = null
    error.value = null
  }

  onScopeDispose(() => {
    controller?.abort()
  })

  if (options.immediate) {
    void run()
  }

  return { state, data, error, run, retry: run, reset }
}

function isAbort(error: unknown): boolean {
  return error instanceof DOMException && error.name === 'AbortError'
}
