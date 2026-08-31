import { createPinia, setActivePinia } from 'pinia'
import { computed, effectScope, nextTick, ref } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { useScreenDraftStore } from '@/stores/screenDraft'

import { useSelectableUniverse } from './useSelectableUniverse'

/**
 * 「전체」를 못 쓰는 상태에서 조회 대상이 실제로 채워지는가 — <b>목록이 늦게 도착해도</b>.
 *
 * <p>이 배선이 컴포넌트 안에 있던 시절 <b>한 번도 발화하지 못했다.</b> 감시 대상이
 * `[allowsAllUniverses, universeCode]` 둘뿐이라, 목록이 도착해도 값이 둘 다 안 바뀌어
 * (`false → false`, `null → null`) 재발화하지 않았다. 결과는 미국을 켠 뒤 <b>프리셋을 열고
 * 실행하면 매번 4xx</b>였고, `vue-tsc`도 렌더 시험도 그것을 못 잡는다 — 잡히는 것은
 * <b>시간에 따라 값이 도착하는 것</b>을 재는 이 시험뿐이다.
 *
 * <p>스토어를 통째로 세우지 않고 필요한 것만 갈아 끼운다. 여기서 재는 것은 카탈로그가
 * 어떻게 채워지는가가 아니라 <b>채워졌을 때 배선이 도는가</b>이기 때문이다.
 */
const universes = ref<Array<{ code: string; currencyCode: string }>>([])

/**
 * MUST: `computed`로 둔다 — 실물이 pinia의 computed이기 때문이다.
 *
 * 평범한 getter로 두면 **반응성 모양이 달라져 이 시험이 결함을 못 잡는다.** computed는 값이
 * 안 바뀌면(`false → false`) 자기를 읽는 watcher를 깨우지 않지만, getter는 읽을 때마다 원본
 * ref를 직접 추적해 목록이 바뀔 때마다 watcher가 돈다. 실제로 처음에 getter로 뒀다가
 * **결함을 되돌려도 초록**이었다 — 대조군이 안 붉으면 대상 코드보다 시험을 먼저 의심한다.
 */
const allowsAll = computed(() => new Set(universes.value.map((u) => u.currencyCode)).size === 1)

vi.mock('@/stores/catalog', () => ({
  useCatalogStore: () => ({
    get universes() {
      return universes.value
    },
    get allowsAllUniverses() {
      return allowsAll.value
    },
  }),
}))

describe('useSelectableUniverse', () => {
  // MUST: 스코프를 닫는다. 안 닫으면 앞 시험의 watcher가 살아남아 같은 모듈 상태
  // (`universes`)에 계속 반응하고, 그 붉음은 이 배선의 결함처럼 보인다.
  let scope: ReturnType<typeof effectScope> | null = null

  beforeEach(() => {
    setActivePinia(createPinia())
    universes.value = []
  })

  afterEach(() => {
    scope?.stop()
    scope = null
  })

  const run = async (): Promise<ReturnType<typeof useScreenDraftStore>> => {
    const draft = useScreenDraftStore()
    expect(draft.universeCode).toBeNull()
    scope = effectScope()
    scope.run(() => useSelectableUniverse())
    await nextTick()
    return draft
  }

  it('IMPORTANT — 목록이 나중에 도착해도 대상을 채운다', async () => {
    // 배선이 걸리는 순간 목록은 아직 비어 있다. 여기서 멈추면 영영 안 채워진다.
    const draft = await run()
    expect(draft.universeCode).toBeNull()

    universes.value = [
      { code: 'KOSDAQ_TOP150', currencyCode: 'KRW' },
      { code: 'US_TOP1000', currencyCode: 'USD' },
    ]
    await nextTick()

    expect(draft.universeCode).toBe('KOSDAQ_TOP150')
  })

  it('통화가 하나면 「전체」를 그대로 둔다 — 고를 수 있는 것을 뺏지 않는다', async () => {
    const draft = await run()
    universes.value = [
      { code: 'KOSDAQ_TOP150', currencyCode: 'KRW' },
      { code: 'KOSPI_TOP200', currencyCode: 'KRW' },
    ]
    await nextTick()

    expect(draft.universeCode).toBeNull()
  })

  it('사용자가 고른 대상을 덮지 않는다', async () => {
    const draft = await run()
    draft.universeCode = 'US_TOP1000'
    universes.value = [
      { code: 'KOSDAQ_TOP150', currencyCode: 'KRW' },
      { code: 'US_TOP1000', currencyCode: 'USD' },
    ]
    await nextTick()

    expect(draft.universeCode).toBe('US_TOP1000')
  })

  it('저장된 전략을 불러와 대상이 다시 비면 채운다', async () => {
    universes.value = [
      { code: 'KOSDAQ_TOP150', currencyCode: 'KRW' },
      { code: 'US_TOP1000', currencyCode: 'USD' },
    ]
    const draft = await run()
    expect(draft.universeCode).toBe('KOSDAQ_TOP150')

    // `loadFrom`이 대상을 안 정한 전략의 null을 그대로 넣는 자리다.
    draft.universeCode = null
    await nextTick()

    expect(draft.universeCode).toBe('KOSDAQ_TOP150')
  })
})
