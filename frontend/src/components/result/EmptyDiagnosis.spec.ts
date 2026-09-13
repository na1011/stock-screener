import { createPinia } from 'pinia'
import PrimeVue from 'primevue/config'
import { createSSRApp } from 'vue'
import { renderToString } from 'vue/server-renderer'
import { afterEach, describe, expect, it } from 'vitest'

import type { ConditionTally, ScreeningResponse } from '@/api/types'

import EmptyDiagnosis from './EmptyDiagnosis.vue'

/**
 * 0건일 때 화면이 **왜 0건인지** 말하는가(P27 축④ · ADR-0142).
 *
 * <p>축이 요구하는 것은 둘이다 — ① 0건에서 사유에 닿는 경로가 있을 것 ② 「조건 미충족」과
 * 「원자료 결손」이 갈릴 것. 종목 상세로만 답하면 <b>0건일 때는 클릭할 종목이 없어 원리적으로
 * 못 닿는다</b>는 것이 그 축이 겨눈 자리다.
 */
const condition = (index: number, name: string) =>
  ({
    index,
    indicator: { code: `IND${index}`, name, unit: null, decimalScale: 2 },
    operator: 'LT',
    operatorLabel: '미만',
    operand: { type: 'LITERAL', value: 10 },
    required: true,
    weight: null,
  }) as unknown as ScreeningResponse['conditions'][number]

const response = (tallies: ConditionTally[], minimumBlocked: number | null): ScreeningResponse =>
  ({
    asOf: '2026-09-10',
    asOfRequested: null,
    evaluationMode: 'FILTER',
    sortedBy: 'STOCK_ID_ASC',
    universe: { code: 'KOSPI_TOP200', name: '코스피 시총 상위 200', memberCount: 200 },
    currencyCode: 'KRW',
    adsSuspectCount: 0,
    relativePopulations: [],
    screen: { id: null, code: 'STAGE2_BREAKOUT', name: '2국면 돌파', type: 'PRESET' },
    conditions: tallies.map((t, i) => condition(t.conditionIndex, `지표${i}`)),
    columns: [],
    populationCount: 200,
    includedCount: 0,
    conditionTallies: tallies,
    minimumBlocked,
    resultCount: 0,
    truncated: false,
    rows: [],
  }) as unknown as ScreeningResponse

const tally = (
  conditionIndex: number,
  notMatched: number,
  notEvaluable: number,
  wouldPassWithout: number | null,
): ConditionTally => ({ conditionIndex, notMatched, notEvaluable, wouldPassWithout })

const render = (tallies: ConditionTally[], minimumBlocked: number | null): Promise<string> => {
  const app = createSSRApp(EmptyDiagnosis, {
    result: response(tallies, minimumBlocked),
    indicatorName: (code: string) => code,
  })
  app.use(createPinia())
  app.use(PrimeVue)
  return renderToString(app)
}

/** `localStorage`를 심어 접힘 상태를 만든다. 없으면 읽기가 기본값(펼침)으로 떨어진다. */
const withCollapsed = (collapsed: boolean): void => {
  const store = new Map([['stock-screener.result.diagnosis-collapsed', String(collapsed)]])
  ;(globalThis as unknown as { window: unknown }).window = {
    localStorage: {
      getItem: (k: string) => store.get(k) ?? null,
      setItem: (k: string, v: string) => void store.set(k, v),
    },
  }
}

afterEach(() => {
  delete (globalThis as unknown as { window?: unknown }).window
})

describe('0건 진단', () => {
  it('기본은 펼침이다 — 감추면 0건에서 사유에 닿는 유일한 경로가 없는 것과 같아진다', async () => {
    const html = await render([tally(0, 186, 3, 1)], 1)

    expect(html).toContain('186')
    expect(html).toContain('접기')
  })

  it('MUST: 접어도 모집단과 「한 조건만으로는 안 열린다」는 남는다', async () => {
    withCollapsed(true)
    const html = await render([tally(0, 190, 0, 0), tally(1, 180, 0, 0)], 3)

    expect(html).toContain('판정 대상')
    expect(html).toContain('한 조건만 풀어서는 열리지 않습니다')
    expect(html).toContain('조건 2개 펼치기')
    // 목록만 접힌다 — 접힌 상태가 「아무 말도 안 하는 상태」가 되면 축④를 되돌린 것이다.
    expect(html).not.toContain('불충족')
  })

  it('조건별로 몇 종목이 걸렸는지와 모집단을 말한다', async () => {
    const html = await render([tally(0, 186, 3, 1), tally(1, 148, 0, 2)], 1)

    expect(html).toContain('판정 대상')
    expect(html).toContain('200')
    expect(html).toContain('186')
  })

  it('MUST: 「불충족」과 「데이터 없음」을 갈라 그린다 — 합치면 데이터 문제를 전략 문제로 읽는다', async () => {
    const html = await render([tally(0, 0, 200, 200)], 1)

    expect(html).toContain('데이터 없음')
    expect(html).toContain('조건에 맞지 않았다는 뜻이 아닙니다')
  })

  it('한 조건만 빼면 열리는 경우 그 수를 말한다', async () => {
    const html = await render([tally(0, 199, 0, 40)], 1)

    expect(html).toContain('이 조건만 빼면')
    expect(html).toContain('40')
  })

  it('IMPORTANT — 한 조건만 풀어서 안 열리면 그 사실을 말한다 (안 그러면 가장 빡빡할 때 입을 다문다)', async () => {
    // 어느 종목도 정확히 하나만 실패하지 않으면 한계 기여가 전 조건에서 통과 수와 같아진다.
    const html = await render([tally(0, 190, 0, 0), tally(1, 180, 0, 0)], 3)

    expect(html).toContain('한 조건만 풀어서는 열리지 않습니다')
    expect(html).toContain('3개')
    expect(html).not.toContain('이 조건만 빼면')
  })
})
