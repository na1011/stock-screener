import { defineStore } from 'pinia'
import { computed } from 'vue'

import { api } from '@/api/endpoints'
import type { IndicatorResponse, OperatorResponse } from '@/api/types'
import { useAsync } from '@/composables/useAsync'

/**
 * 지표 카탈로그와 유니버스 목록.
 *
 * 두 요청을 **따로** 들고 있는 것이 의도다. 하나로 묶으면 유니버스 조회가 실패했을 때 조건
 * 빌더 전체가 못 뜨는데, 유니버스는 선택 사항이고 지표 없이는 조건을 만들 수 없다는 점에서
 * 둘의 중요도가 다르다. 영역 단위 격리(spec §8)는 이런 분리에서 나온다.
 *
 * 카탈로그는 배포 사이에 바뀌지 않으므로 한 번만 받아 캐시한다.
 */
export const useCatalogStore = defineStore('catalog', () => {
  const catalogRequest = useAsync((signal) => api.catalog(signal))
  const universeRequest = useAsync((signal) => api.universes(signal))

  const indicators = computed<IndicatorResponse[]>(() => catalogRequest.data.value?.indicators ?? [])
  const operators = computed<OperatorResponse[]>(() => catalogRequest.data.value?.operators ?? [])
  const universes = computed(() => universeRequest.data.value ?? [])

  /** 코드로 지표를 찾는다. 결과 표의 컬럼 머리글과 조건 빌더가 같은 정의를 쓰게 한다. */
  const byCode = computed(() => new Map(indicators.value.map((it) => [it.code, it])));

  /**
   * 조건의 좌변으로 고를 수 있는 지표를 분류별로 묶는다.
   *
   * 정렬은 서버의 `sortOrder`를 따른다 — 프론트가 자기 기준으로 정렬하면 지표가 추가될 때마다
   * 순서 규칙을 다시 정해야 하고, 카탈로그가 의도한 배치가 사라진다.
   */
  const grouped = computed(() => {
    const labels: Record<string, string> = {
      PRICE: '가격',
      TECHNICAL: '기술적 지표',
      FUNDAMENTAL: '재무 지표',
      DERIVED: '파생·백분위',
    }
    const order = ['PRICE', 'TECHNICAL', 'FUNDAMENTAL', 'DERIVED']
    const buckets = new Map<string, IndicatorResponse[]>()
    for (const indicator of [...indicators.value].sort((a, b) => a.sortOrder - b.sortOrder)) {
      const bucket = buckets.get(indicator.category)
      if (bucket) {
        bucket.push(indicator)
      } else {
        buckets.set(indicator.category, [indicator])
      }
    }
    return order
      .filter((category) => buckets.has(category))
      .map((category) => ({
        label: labels[category] ?? category,
        items: buckets.get(category) ?? [],
      }))
  })

  async function ensureCatalog(): Promise<void> {
    if (catalogRequest.state.value === 'idle' || catalogRequest.state.value === 'error') {
      await catalogRequest.run()
    }
  }

  async function ensureUniverses(): Promise<void> {
    if (universeRequest.state.value === 'idle' || universeRequest.state.value === 'error') {
      await universeRequest.run()
    }
  }

  return {
    catalogState: catalogRequest.state,
    catalogError: catalogRequest.error,
    retryCatalog: catalogRequest.retry,
    universeState: universeRequest.state,
    universeError: universeRequest.error,
    retryUniverses: universeRequest.retry,

    indicators,
    operators,
    universes,
    byCode,
    grouped,
    ensureCatalog,
    ensureUniverses,
  }
})
