import { createPinia } from 'pinia'
import PrimeVue from 'primevue/config'
import ToastService from 'primevue/toastservice'
import Tooltip from 'primevue/tooltip'
import { createSSRApp } from 'vue'
import { renderToString } from 'vue/server-renderer'
import { describe, expect, it } from 'vitest'

import type { FinancialBasis, StockPrice, StockSummary } from '@/api/types'

import StockHeader from './StockHeader.vue'

/**
 * 시세 띠의 금액이 **그 종목의 통화**로 그려지는지 본다.
 *
 * <p>이 컴포넌트가 셋 중 가장 무겁다 — `WatchButton`을 조건 없이 렌더하고, 그것이
 * `useApiToast`(PrimeVue Toast)와 pinia 스토어 둘을 setup에서 부른다. 그래서 SSR 앱에
 * <b>플러그인을 실제로 건다.</b> 새 의존성은 없다 — `pinia`·`primevue`가 이미 런타임
 * 의존성이고 `main.ts`가 같은 것을 건다.
 *
 * <p><b>스토어를 채우지 않는다.</b> 여기서 재는 것은 워치리스트가 아니라 <b>금액 표기</b>이고,
 * 스토어는 렌더가 죽지 않을 만큼만 있으면 된다.
 */
const stock = (currencyCode: string): StockSummary => ({
  id: 1,
  ticker: '241560',
  name: '두산밥캣',
  marketCode: 'KOSPI',
  currencyCode,
  sector: '기계',
  universes: [],
})

const price: StockPrice = {
  tradeDate: '2026-08-05',
  open: 148.1,
  high: 152.4,
  low: 147.05,
  close: 150.25,
  volume: 1_234_567,
  tradeValue: null,
  previousTradeDate: '2026-08-04',
  change: 1.25,
  changeRate: 0.84,
} as StockPrice

const render = (currencyCode: string, financialBasis: FinancialBasis | null = null): Promise<string> => {
  const app = createSSRApp(StockHeader, {
    stock: stock(currencyCode),
    price,
    asOf: '2026-08-05',
    unadjustedActions: { notice: '', effectiveDates: [] },
    financialBasis,
  })
  app.use(createPinia())
  app.use(PrimeVue)
  app.use(ToastService)
  // main.ts와 같다. 빠지면 v-tooltip이 SSR에서 undefined로 터지는데, 그 예외가
  // 「디렉티브가 없다」가 아니라 getSSRProps의 TypeError로 나와 원인을 안 가리킨다.
  app.directive('tooltip', Tooltip)
  return renderToString(app)
}

describe('StockHeader — 시세 금액은 종목의 통화로 그린다', () => {
  it('달러 종목은 「$」와 소수 둘이다', async () => {
    const html = await render('USD')

    expect(html).toContain('$150.25')
    expect(html).toContain('$148.10')
    expect(html).toContain('$152.40')
    // 등락폭도 같은 규칙을 탄다. 여기만 빠지면 종가는 달러인데 등락은 원이 된다.
    expect(html).toContain('+$1.25')
  })

  it('원화 종목은 소수를 안 보이고 「원」을 붙인다', async () => {
    const html = await render('KRW')

    expect(html).toContain('150원')
    expect(html).not.toContain('$')
  })
})

describe('StockHeader — 지표가 쓴 재무 기준을 늘 말한다(ADR-0195)', () => {
  const basis: FinancialBasis = {
    flowLabel: '2026 Q2',
    balanceLabel: null,
    status: 'PENDING',
    untracked: null,
    releaseFiledOn: '2026-10-28',
    notice: '10월 28일 새 실적 발표가 있습니다 — 지표는 2026 Q2 재무 기준이라 아직 반영되지 않았습니다.',
  }

  it('새 실적 발표 대기면 기준과 서버 문구를 함께 그린다', async () => {
    const html = await render('USD', basis)

    expect(html).toContain('재무 기준 2026 Q2')
    expect(html).toContain(basis.notice)
    expect(html).toContain('stock-header__financial--warn')
  })

  it('발표가 없으면 기준만 그린다', async () => {
    const html = await render('USD', { ...basis, status: 'NONE', releaseFiledOn: null, notice: null })

    expect(html).toContain('재무 기준 2026 Q2')
    expect(html).not.toContain('stock-header__financial--')
  })

  it('재무가 없으면 줄을 그리지 않는다', async () => {
    expect(await render('USD', null)).not.toContain('재무 기준')
  })
})
