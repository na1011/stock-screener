import { createPinia } from 'pinia'
import PrimeVue from 'primevue/config'
import { createSSRApp } from 'vue'
import { renderToString } from 'vue/server-renderer'
import { describe, expect, it } from 'vitest'

import type { ScreeningResponse } from '@/api/types'

import ResultSummary from './ResultSummary.vue'

/**
 * 「시총 상위 N」이 사실이 아닐 수 있다는 것을 결과 머리말이 말하는가.
 *
 * <p>값을 옳게 만들려면 ADS 비율이 필요한데 그것을 주는 소스가 없어서, 고치는 대신 드러내기로
 * 했다(ADR-0126 §`AKTX`). <b>그러면 드러나는지가 곧 그 결정의 이행 여부다.</b>
 *
 * <p>서버가 세어 준 수를 그대로 읽는지도 함께 본다 — 화면이 카탈로그로 판단하면 그 요청만
 * 실패했을 때 경고가 조용히 사라진다. 그 사고가 백분위 주의 문구에서 실제로 있었다.
 */
const response = (adsSuspectCount: number): ScreeningResponse =>
  ({
    asOf: '2026-09-04',
    asOfRequested: null,
    evaluationMode: 'FILTER',
    sortedBy: 'STOCK_ID_ASC',
    universe: { code: 'US_TOP1000', name: '미국 시총 상위 1,000', memberCount: 1000 },
    currencyCode: 'USD',
    adsSuspectCount,
    relativePopulations: [],
    screen: { id: null, code: null, name: '즉석 조건', type: 'CUSTOM' },
    conditions: [],
    columns: [],
    evaluatedCount: 1000,
    resultCount: 12,
    truncated: false,
    rows: [],
  }) as unknown as ScreeningResponse

const render = (adsSuspectCount: number): Promise<string> => {
  const app = createSSRApp(ResultSummary, { result: response(adsSuspectCount) })
  app.use(createPinia())
  app.use(PrimeVue)
  return renderToString(app)
}

describe('ResultSummary의 ADS 경고', () => {
  it('ADS 종목이 섞여 있으면 순위를 못 믿을 수 있다고 말한다', async () => {
    const html = await render(1)

    expect(html).toContain('미국예탁증서')
    expect(html).toContain('1종목')
    // 「정확하지 않을 수 있다」까지 말해야 한다. 수만 보이면 초보 사용자는 그것이 좋은
    // 것인지 나쁜 것인지 알 수 없다(`.claude/rules/frontend.md`).
    expect(html).toContain('정확하지 않을 수 있습니다')
  })

  it('0이면 아무 말도 하지 않는다', async () => {
    const html = await render(0)

    expect(html).not.toContain('미국예탁증서')
  })
})
