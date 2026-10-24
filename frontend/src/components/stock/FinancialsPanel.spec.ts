import { createSSRApp } from 'vue'
import { renderToString } from 'vue/server-renderer'
import { describe, expect, it } from 'vitest'

import type { FinancialPeriod, StockFinancials } from '@/api/types'

import FinancialsPanel from './FinancialsPanel.vue'

/**
 * 금액이 **그 행의 통화**로 그려지는지 본다.
 *
 * <p>`vue-tsc`는 이것을 못 잡는다 — prop을 잘못 이어도 타입이 맞으면 컴파일된다. 실제로
 * 이 패널은 한동안 **종목의 시장 통화**를 받아 그렸고, 그러면 두산밥캣(KRW 시장·USD 재무)의
 * 달러 매출이 「조원」으로 뜬다. 값이 나오므로 화면 어디에서도 드러나지 않는다.
 *
 * DOM 없이 서버 렌더로 확인한다. `RatioBar.spec.ts`와 같은 방식이고 **새 의존성이 없다** —
 * 이 저장소의 vitest는 `environment: 'node'`이고 `vue/server-renderer`는 `vue`가 이미 싣는다.
 */
const period = (over: Partial<FinancialPeriod>): FinancialPeriod => ({
  fiscalYear: 2025,
  fiscalPeriod: 'FY',
  publishedAt: '2026-03-20',
  revised: false,
  derived: false,
  currencyCode: 'KRW',
  revenue: null,
  operatingIncome: null,
  netIncome: null,
  operatingCashFlow: null,
  totalAssets: null,
  totalLiabilities: null,
  totalEquity: null,
  revenueYoy: null,
  operatingIncomeYoy: null,
  netIncomeYoy: null,
  discontinuedIncome: null,
  commonIncome: null,
  ...over,
})

const financials = (annual: FinancialPeriod[]): StockFinancials => ({
  annual,
  quarterly: [],
  notice: { quarterlyCashFlow: '분기 현금흐름 고지', derivedFourthQuarter: '4분기 유도 고지' },
})

const render = (annual: FinancialPeriod[]): Promise<string> =>
  renderToString(createSSRApp(FinancialsPanel, { financials: financials(annual) }))

describe('FinancialsPanel — 금액은 행의 통화로 그린다', () => {
  it('원화 행은 조·억과 「원」으로 접힌다', async () => {
    const html = await render([period({ fiscalYear: 2022, revenue: 8.62e12 })])

    expect(html).toContain('8.62조원')
    expect(html).not.toContain('$')
  })

  it('달러 행은 T·B·M과 「$」로 접힌다 — 만진법을 천진법에 쓰면 자릿수가 안 읽힌다', async () => {
    const html = await render([period({ fiscalYear: 2025, currencyCode: 'USD', revenue: 6.18e9 })])

    expect(html).toContain('$6.18B')
    // 낱말 '원'으로 물으면 캡션의 「실적 원수치」에 걸린다 — 금액 접미만 본다.
    expect(html).not.toContain('조원')
    expect(html).not.toContain('억원')
  })

  it('IMPORTANT — 한 표에 통화가 섞여도 행마다 제 통화로 그린다', async () => {
    // 회사가 보고 통화를 갈아탄 종목이 실제로 이 모양이다(241560이 2023년에 KRW → USD).
    // 시장 통화 하나로 그리면 둘 중 한쪽이 반드시 틀리고, 그 틀림이 화면에 안 드러난다.
    const html = await render([
      period({ fiscalYear: 2025, currencyCode: 'USD', revenue: 6.18e9 }),
      period({ fiscalYear: 2022, currencyCode: 'KRW', revenue: 8.62e12 }),
    ])

    expect(html).toContain('$6.18B')
    expect(html).toContain('8.62조원')
  })

  it('값이 없으면 0이 아니라 —다 — 유도 4분기를 접었을 때 이렇게 보인다', async () => {
    const html = await render([
      period({ fiscalYear: 2023, fiscalPeriod: 'FY', currencyCode: 'USD', revenue: null }),
    ])

    expect(html).toContain('—')
    expect(html).not.toContain('$0')
  })
})
