import { effectScope } from 'vue'
import { describe, expect, it } from 'vitest'

import { ApiError } from '@/api/http'

import { useAsync } from './useAsync'

/**
 * 프론트에서 가장 미묘한 로직인데 테스트가 없었다.
 *
 * 특히 `await` 뒤의 stale 가드(`if (current.signal.aborted) return`)는 "정리하다가 지워지는"
 * 종류이고, 사라졌을 때의 증상은 **조건을 바꿨는데 이전 결과가 표에 남는 것**이라 버그로
 * 보이지 않는다.
 *
 * `effectScope`로 감싸는 이유는 `onScopeDispose`가 쓰일 자리를 주기 위해서다. DOM은 필요
 * 없으므로 `environment: 'node'`에서 새 의존성 없이 돈다.
 */
function withScope<T>(body: () => T): { result: T; dispose: () => void } {
  const scope = effectScope()
  const result = scope.run(body) as T
  return { result, dispose: () => scope.stop() }
}

/** 밖에서 완료 시점을 정하는 Promise. 늦게 도착한 응답을 재현하는 데 쓴다. */
function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason: unknown) => void
  const promise = new Promise<T>((res, rej) => {
    resolve = res
    reject = rej
  })
  return { promise, resolve, reject }
}

describe('useAsync', () => {
  it('처음에는 idle이고 아무것도 들고 있지 않다', () => {
    const { result: async, dispose } = withScope(() => useAsync(() => Promise.resolve(1)))

    expect(async.state.value).toBe('idle')
    expect(async.data.value).toBeNull()
    expect(async.error.value).toBeNull()
    dispose()
  })

  it('성공하면 ready가 되고 값을 들고 있다', async () => {
    const { result: async, dispose } = withScope(() => useAsync(() => Promise.resolve('값')))

    await async.run()

    expect(async.state.value).toBe('ready')
    expect(async.data.value).toBe('값')
    dispose()
  })

  it('IMPORTANT — 늦게 도착한 이전 응답이 최신 결과를 덮어쓰지 않는다', async () => {
    // 이 가드가 사라지면 조건을 빠르게 두 번 바꿨을 때 첫 번째 결과가 표에 남는다.
    // 사용자에게는 "조건을 바꿨는데 결과가 그대로"로 보이고, 버그처럼 보이지 않는다.
    const first = deferred<string>()
    const second = deferred<string>()
    const calls = [first, second]
    let index = 0

    const { result: async, dispose } = withScope(() =>
      useAsync(() => (calls[index++] ?? first).promise),
    )

    const firstRun = async.run()
    const secondRun = async.run() // 여기서 첫 요청이 취소된다

    second.resolve('두 번째')
    await secondRun
    expect(async.data.value).toBe('두 번째')

    // 이제 첫 요청이 뒤늦게 도착한다.
    first.resolve('첫 번째')
    await firstRun

    expect(async.data.value).toBe('두 번째')
    expect(async.state.value).toBe('ready')
    dispose()
  })

  it('취소된 실행의 실패는 오류로 남지 않는다', async () => {
    // 취소를 오류로 기록하면, 이미 떠난 화면의 실패가 새 화면의 에러 배너로 뜬다.
    const first = deferred<string>()
    const second = deferred<string>()
    const calls = [first, second]
    let index = 0

    const { result: async, dispose } = withScope(() =>
      useAsync(() => (calls[index++] ?? first).promise),
    )

    const firstRun = async.run()
    const secondRun = async.run()

    second.resolve('두 번째')
    await secondRun

    first.reject(new ApiError(500, null, '늦게 도착한 실패'))
    await firstRun

    expect(async.error.value).toBeNull()
    expect(async.state.value).toBe('ready')
    expect(async.data.value).toBe('두 번째')
    dispose()
  })

  it('AbortError는 오류로 기록하지 않는다', async () => {
    const { result: async, dispose } = withScope(() =>
      useAsync(() => Promise.reject(new DOMException('aborted', 'AbortError'))),
    )

    await async.run()

    expect(async.error.value).toBeNull()
    expect(async.data.value).toBeNull()
    dispose()
  })

  it('ApiError는 error 상태로 담고 던지지 않는다', async () => {
    const failure = new ApiError(503, null, '준비되지 않았다')
    const { result: async, dispose } = withScope(() => useAsync(() => Promise.reject(failure)))

    await expect(async.run()).resolves.toBeUndefined()

    expect(async.state.value).toBe('error')
    expect(async.error.value).toBe(failure)
    dispose()
  })

  it('ApiError가 아닌 예외는 error로 표시하면서 다시 던진다', async () => {
    // 삼키면 화면이 영원히 '로딩 중'으로 남고 콘솔에도 아무것도 남지 않는다. 상태를 error로
    // 두는 것과 다시 던지는 것 **둘 다** 필요하다 — 하나만 하면 화면이나 로그 중 하나가 잃는다.
    const bug = new TypeError('undefined의 속성을 읽었다')
    const { result: async, dispose } = withScope(() => useAsync(() => Promise.reject(bug)))

    await expect(async.run()).rejects.toBe(bug)

    expect(async.state.value).toBe('error')
    expect(async.error.value).toBeNull()
    dispose()
  })

  it('실행하면 이전 오류를 지운다', async () => {
    let shouldFail = true
    const { result: async, dispose } = withScope(() =>
      useAsync(() =>
        shouldFail ? Promise.reject(new ApiError(500, null, '실패')) : Promise.resolve('성공'),
      ),
    )

    await async.run()
    expect(async.error.value).not.toBeNull()

    shouldFail = false
    await async.run()

    expect(async.error.value).toBeNull()
    expect(async.state.value).toBe('ready')
    dispose()
  })

  it('reset은 idle로 되돌리고 진행 중인 요청을 취소한다', async () => {
    const pending = deferred<string>()
    const { result: async, dispose } = withScope(() => useAsync(() => pending.promise))

    const running = async.run()
    async.reset()

    expect(async.state.value).toBe('idle')

    pending.resolve('뒤늦게 도착')
    await running

    expect(async.data.value).toBeNull()
    expect(async.state.value).toBe('idle')
    dispose()
  })

  it('스코프가 끝나면 진행 중인 요청의 결과를 받지 않는다', async () => {
    // 사용자가 다른 화면으로 옮긴 뒤 응답이 도착하는 경우다. 언마운트된 컴포넌트의
    // 상태를 갱신하면 그 값은 아무도 보지 않으면서 메모리에만 남는다.
    const pending = deferred<string>()
    const { result: async, dispose } = withScope(() => useAsync(() => pending.promise))

    const running = async.run()
    dispose()

    pending.resolve('떠난 뒤 도착')
    await running

    expect(async.data.value).toBeNull()
  })
})

/**
 * 배경 갱신.
 *
 * `run`이 보장하는 것은 상태가 <b>바뀌는</b> 것이고 `refresh`가 보장하는 것은 상태가
 * <b>안 바뀌는</b> 것이다. 뒤엣것은 깨져도 티가 안 난다 — 자동 갱신마다 화면이 한 번
 * 깜빡이고 펼쳐 둔 행이 접히는데, 그것을 회귀로 알아채기 어렵다.
 */
describe('refresh', () => {
  it('IMPORTANT — 이미 데이터가 있으면 loading으로 떨어지지 않는다', async () => {
    // AsyncPanel이 loading에서 슬롯을 파괴한다. 여기서 상태를 건드리면 5초마다 배치 상세가
    // 접히고, 진행률을 지켜보려고 켠 갱신이 정작 보려던 것을 닫는다.
    const pending = deferred<string>()
    const calls = [deferred<string>(), pending]
    let index = 0
    const { result: async, dispose } = withScope(() =>
      useAsync(() => (calls[index++] ?? pending).promise),
    )

    calls[0]!.resolve('처음')
    await async.run()

    const refreshing = async.refresh()
    expect(async.state.value).toBe('ready')
    expect(async.data.value).toBe('처음')

    pending.resolve('다음')
    await refreshing
    expect(async.state.value).toBe('ready')
    expect(async.data.value).toBe('다음')
    dispose()
  })

  it('보여줄 것이 아직 없으면 첫 로딩이다 — 그때는 스켈레톤이 떠야 한다', async () => {
    const pending = deferred<string>()
    const { result: async, dispose } = withScope(() => useAsync(() => pending.promise))

    const refreshing = async.refresh()
    expect(async.state.value).toBe('loading')

    pending.resolve('값')
    await refreshing
    expect(async.state.value).toBe('ready')
    dispose()
  })

  it('IMPORTANT — 실패를 삼키지 않고 던진다. 조용히 넘기면 갱신이 멈춘 줄 모른다', async () => {
    let shouldFail = false
    const { result: async, dispose } = withScope(() =>
      useAsync(() =>
        shouldFail ? Promise.reject(new ApiError(503, null, '일시적 오류')) : Promise.resolve('처음'),
      ),
    )

    await async.run()
    shouldFail = true

    await expect(async.refresh()).rejects.toBeInstanceOf(ApiError)
    dispose()
  })

  it('실패해도 이미 그린 것을 지우지 않는다 — 일시적 실패 한 번이 목록을 비우면 안 된다', async () => {
    let shouldFail = false
    const { result: async, dispose } = withScope(() =>
      useAsync(() =>
        shouldFail ? Promise.reject(new ApiError(503, null, '일시적 오류')) : Promise.resolve('처음'),
      ),
    )

    await async.run()
    shouldFail = true
    await async.refresh().catch(() => undefined)

    expect(async.data.value).toBe('처음')
    expect(async.state.value).toBe('ready')
    expect(async.error.value).toBeNull()
    dispose()
  })
})
