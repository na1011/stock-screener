import { createPinia } from 'pinia'
import PrimeVue from 'primevue/config'
import Tooltip from 'primevue/tooltip'
import { createSSRApp, type Component } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import { renderToString } from 'vue/server-renderer'
import { describe, expect, it } from 'vitest'

import type { ScreeningResponse } from '@/api/types'

import ResultCards from './ResultCards.vue'
import ResultTable from './ResultTable.vue'

/**
 * 결과 표·카드의 <b>종가</b>가 응답의 통화로 그려지는지 본다.
 *
 * <p>두 컴포넌트를 한 파일에서 재는 이유는 <b>같은 사실을 두 곳이 그리기 때문</b>이다 —
 * 데스크톱은 표, 모바일은 카드이고 한쪽만 고치면 <b>기기에 따라 통화가 달라진다.</b>
 * 그 어긋남은 두 기기를 나란히 놓기 전에는 드러나지 않는다.
 *
 * <p>`RouterLink`를 쓰므로 라우터를 건다. 경로 목록은 비워도 되지만 <b>라우터 자체가 없으면</b>
 * SSR이 컴포넌트 해석에서 죽는다.
 */
const response = (currencyCode: string | null): ScreeningResponse =>
  ({
    asOf: '2026-08-05',
    asOfRequested: null,
    truncated: false,
    evaluationMode: 'FILTER',
    sortedBy: 'STOCK',
    universe: { code: 'US_TOP1000', name: '미국 시총 상위 1,000', memberCount: 1000 },
    currencyCode,
    relativePopulations: [],
    screen: { id: null, code: null, name: '즉석 조건', type: 'CUSTOM' },
    conditions: [],
    columns: [],
    rows: [
      {
        stockId: 1,
        ticker: 'AAPL',
        name: 'Apple',
        marketCode: 'US',
        close: 150.25,
        score: null,
        maxScore: null,
        scoreRatio: null,
        matchedCount: 0,
        notEvaluableCount: 0,
        values: {},
        outcomes: [],
      },
    ],
  }) as unknown as ScreeningResponse

const render = (component: Component, currencyCode: string | null): Promise<string> => {
  const app = createSSRApp(component, { result: response(currencyCode), asOf: '2026-08-05' })
  app.use(createPinia())
  app.use(PrimeVue)
  app.directive('tooltip', Tooltip)
  // 이름과 파라미터가 라우터 정의와 같아야 한다 — 다르면 RouterLink가 「No match」로
  // 죽고, 그 예외는 통화와 아무 상관이 없다.
  app.use(createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/stocks/:stockId(\\d+)', name: 'stock', component: { template: '<div />' } }],
  }))
  return renderToString(app)
}

describe.each([
  ['ResultTable', ResultTable],
  ['ResultCards', ResultCards],
])('%s — 종가는 응답의 통화로 그린다', (_name, component) => {
  it('달러 결과는 「$」와 소수 둘이다', async () => {
    const html = await render(component as Component, 'USD')

    expect(html).toContain('$150.25')
    expect(html).not.toContain('150원')
  })

  it('원화 결과는 소수를 안 보이고 「원」을 붙인다', async () => {
    const html = await render(component as Component, 'KRW')

    expect(html).toContain('150원')
    expect(html).not.toContain('$')
  })
})
