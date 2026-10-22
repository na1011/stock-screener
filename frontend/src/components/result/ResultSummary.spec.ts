import { createPinia } from 'pinia'
import PrimeVue from 'primevue/config'
import { createSSRApp } from 'vue'
import { renderToString } from 'vue/server-renderer'
import { describe, expect, it } from 'vitest'

import type { ScreeningResponse } from '@/api/types'

import ResultSummary from './ResultSummary.vue'

/**
 * 결과 머리말이 분모를 바로 그리는가.
 *
 * <p>ADS 경고(「시총 순위가 정확하지 않을 수 있습니다」)는 걷었다(2026-10-22 사용자 결정 · ADR-0126) — 관측 평면이
 * 표지로 예탁 비율 1을 확인한 ADS만 들이게 된 뒤로 그 문장이 참인 경우가 없다.
 */
const response = (): ScreeningResponse =>
  ({
    asOf: '2026-09-04',
    asOfRequested: null,
    evaluationMode: 'FILTER',
    sortedBy: 'STOCK_ID_ASC',
    universe: { code: 'US_TOP1000', name: '미국 시총 상위 1,000', memberCount: 1000 },
    currencyCode: 'USD',
    relativePopulations: [],
    screen: { id: null, code: null, name: '즉석 조건', type: 'CUSTOM' },
    conditions: [],
    columns: [],
    populationCount: 1000,
    includedCount: 12,
    conditionTallies: [],
    minimumBlocked: null,
    resultCount: 12,
    truncated: false,
    rows: [],
  }) as unknown as ScreeningResponse

const render = (): Promise<string> => {
  const app = createSSRApp(ResultSummary, { result: response() })
  app.use(createPinia())
  app.use(PrimeVue)
  return renderToString(app)
}

describe('ResultSummary의 분모', () => {
  /**
   * IMPORTANT — 픽스처가 `as unknown as`로 캐스팅돼 있어 **타입검사가 필드 이름을 안 본다.**
   * 실제로 이 파일에는 없는 필드(`evaluatedCount`)가 살아 있었고 아무도 못 잡았다.
   * 그래서 **그려진 문구**를 단언한다 — 이름이 바뀌면 여기서 붉는다.
   */
  it('결과 옆에 「판정 대상」을 그린다 — 「평가」가 아니다', async () => {
    const html = await render()

    expect(html).toContain('판정 대상')
    expect(html).toContain('1,000종목')
    expect(html).not.toContain('평가 ')
  })
})

describe('ResultSummary의 ADS', () => {
  it('ADS가 섞여 있다고 순위를 의심하는 문장을 그리지 않는다', async () => {
    const html = await render()

    expect(html).not.toContain('미국예탁증서')
  })
})
