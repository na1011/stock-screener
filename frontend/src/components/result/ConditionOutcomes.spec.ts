import { createPinia } from 'pinia'
import PrimeVue from 'primevue/config'
import Tooltip from 'primevue/tooltip'
import { createSSRApp } from 'vue'
import { renderToString } from 'vue/server-renderer'
import { describe, expect, it } from 'vitest'

import type { ConditionInfo, ScreeningRow } from '@/api/types'
import { MONEY_UNIT } from '@/lib/format'

import ConditionOutcomes from './ConditionOutcomes.vue'

/**
 * 「선정 이유」의 <b>임계와 실제값</b>이 그 결과가 선 시장의 통화로 그려지는지 본다.
 *
 * <p>여기가 조용한 자리인 이유는 지표 카탈로그가 <b>시장 중립</b>이라 금액 지표의 단위를
 * `'원'` 문자열 하나로만 말하기 때문이다({@link MONEY_UNIT}). 화면이 그것을 행의 통화로
 * 갈아끼우지 않으면 미국 실행의 시가총액 임계가 「원」으로 뜨고, 조건 칩과 결과 표가
 * <b>같은 임계를 다른 통화로</b> 적는다.
 */
const condition: ConditionInfo = {
  index: 0,
  indicator: {
    code: 'MARKET_CAP',
    name: '시가총액',
    unit: MONEY_UNIT,
    decimalScale: 0,
    higherIsBetter: true,
    interpretation: '클수록 규모가 크다',
  },
  operator: 'GTE',
  operatorLabel: '이상',
  operand: { type: 'LITERAL', value: 7_940_000_000 },
  weight: null,
  required: true,
}

const row: ScreeningRow = {
  stockId: 1,
  ticker: 'AAPL',
  name: 'Apple',
  marketCode: 'US',
  close: 150.25,
  score: null,
  maxScore: null,
  scoreRatio: null,
  matchedCount: 1,
  notEvaluableCount: 0,
  values: { MARKET_CAP: 3_500_000_000_000 },
  outcomes: [
    { conditionIndex: 0, status: 'MATCHED', actualValue: 3_500_000_000_000, earnedScore: null },
  ],
}

const render = (currencyCode: string | null): Promise<string> => {
  const app = createSSRApp(ConditionOutcomes, {
    conditions: [condition],
    row,
    evaluationMode: 'FILTER',
    indicatorName: (code: string) => code,
    currencyCode,
  })
  app.use(createPinia())
  app.use(PrimeVue)
  // main.ts와 같다. 빠지면 v-tooltip이 getSSRProps의 TypeError로 터지는데,
  // 그 예외가 원인을 안 가리킨다.
  app.directive('tooltip', Tooltip)
  return renderToString(app)
}

describe('ConditionOutcomes — 임계와 실제값은 결과의 통화로 그린다', () => {
  it('달러 결과는 임계도 실제값도 「$」다', async () => {
    const html = await render('USD')

    expect(html).toContain('$7,940,000,000')
    expect(html).toContain('$3,500,000,000,000')
    expect(html).not.toContain('7,940,000,000원')
  })

  it('원화 결과는 「원」이다 — 카탈로그의 단위 문자열이 그대로 맞는 경우다', async () => {
    const html = await render('KRW')

    expect(html).toContain('7,940,000,000원')
    expect(html).not.toContain('$')
  })

  it('통화를 모르면 숫자만 낸다 — 아무 통화나 붙이지 않는다', async () => {
    const html = await render(null)

    expect(html).toContain('7,940,000,000')
    expect(html).not.toContain('$')
    expect(html).not.toContain('7,940,000,000원')
  })
})
