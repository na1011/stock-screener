import { createSSRApp } from 'vue'
import { renderToString } from 'vue/server-renderer'
import { describe, expect, it } from 'vitest'

import type { PriceReference } from '@/api/types'

import PriceReferencePanel from './PriceReferencePanel.vue'

/**
 * 지지·저항의 **가격**이 그 종목의 통화로 그려지는지 본다.
 *
 * <p>여기가 특히 조용한 자리다 — 구간은 두 수의 쌍이라 통화가 틀려도 「그럴듯한 범위」로
 * 읽히고, 사용자가 대조할 다른 화면이 없다. `formatPrice`가 통화를 **필수 인자**로 받는
 * 이유가 그것인데, 인자를 받는 것과 **호출부가 옳은 것을 넘기는 것**은 다르다.
 */
const reference = (over: Partial<PriceReference> = {}): PriceReference => ({
  kind: 'SUPPORT_RESISTANCE',
  method: 'SWING',
  disclaimer: '확정된 매매 신호가 아닙니다.',
  window: { from: '2026-02-02', to: '2026-08-05', tradingDays: 120 },
  insufficientData: false,
  lastClose: 150.25,
  zones: [
    {
      role: 'RESISTANCE',
      lower: 160.5,
      upper: 172.75,
      touchCount: 3,
      lastTouchedOn: '2026-07-30',
      strength: 'HIGH',
    },
  ],
  ...over,
})

const render = (currencyCode: string | null, over: Partial<PriceReference> = {}): Promise<string> =>
  renderToString(createSSRApp(PriceReferencePanel, { reference: reference(over), currencyCode }))

describe('PriceReferencePanel — 가격은 받은 통화로 그린다', () => {
  it('달러면 소수 둘과 「$」다 — 원화 규칙을 쓰면 $150.25가 150원이 된다', async () => {
    const html = await render('USD')

    expect(html).toContain('$150.25')
    expect(html).toContain('$160.50')
    expect(html).toContain('$172.75')
    expect(html).not.toContain('원</strong>')
  })

  it('원화면 소수를 안 보이고 「원」을 붙인다', async () => {
    const html = await render('KRW', { lastClose: 70100, zones: [] })

    expect(html).toContain('70,100원')
    expect(html).not.toContain('$')
  })

  it('통화를 아직 모르면 숫자만 낸다 — 아무 통화나 붙이는 것보다 낫다', async () => {
    const html = await render(null, { lastClose: 70100, zones: [] })

    expect(html).toContain('70,100')
    expect(html).not.toContain('$')
    expect(html).not.toContain('70,100원')
  })

  it('경고 문구는 통화와 무관하게 항상 남는다 — 사라지는 것이 가장 나쁘다', async () => {
    expect(await render('USD')).toContain('확정된 매매 신호가 아닙니다.')
  })
})
