import { describe, expect, it } from 'vitest'

import {
  FINANCIAL_BASIS_STATUSES,
  INDICATOR_BASIS_TAGS,
  type FinancialBasis,
  type FinancialBasisStatus,
  type IndicatorBasisTag,
} from '@/api/types'

import { basisLine, financialBasisStyle, indicatorBasisTagLabel, rowBadgeText } from './financialBasis'

describe('financialBasisStyle', () => {
  it('결과 행에는 새 실적 발표 대기와 잠정 반영만 단다', () => {
    expect(FINANCIAL_BASIS_STATUSES.filter((s) => financialBasisStyle(s).flagsRow)).toEqual(['PENDING', 'PROVISIONAL'])
  })

  it('모름(UNTRACKED)을 없음(NONE)과 같은 모양으로 그리지 않는다', () => {
    expect(financialBasisStyle('UNTRACKED')).not.toEqual(financialBasisStyle('NONE'))
  })

  it('모르는 상태는 던진다', () => {
    expect(() => financialBasisStyle('LATER' as FinancialBasisStatus)).toThrow()
  })
})

describe('basisLine', () => {
  const basis: FinancialBasis = {
    flowLabel: '2026 Q2',
    balanceLabel: null,
    status: 'NONE',
    untracked: null,
    releaseFiledOn: null,
    notice: null,
  }

  it('유량과 잔액 기가 같으면 하나만 말한다', () => {
    expect(basisLine(basis)).toBe('재무 기준 2026 Q2')
  })

  it('잔액 기가 다르면 함께 말한다', () => {
    expect(basisLine({ ...basis, flowLabel: '2024년', balanceLabel: '2026 Q1' })).toBe('재무 기준 2024년 · 자산 2026 Q1')
  })

  it('잠정실적으로 계산했으면 기준 뒤에 「잠정」을 붙인다', () => {
    expect(basisLine({ ...basis, flowLabel: '2026 Q3', status: 'PROVISIONAL' })).toBe('재무 기준 2026 Q3 잠정')
  })
})

describe('결과 행 · 지표 카드 표지 문구', () => {
  it('결과 행은 잠정 반영이면 「잠정」, 막힘이면 「새 실적」이다', () => {
    expect(rowBadgeText('PROVISIONAL')).toBe('잠정')
    expect(rowBadgeText('PENDING')).toBe('새 실적')
  })

  it('지표 카드 표지는 둘 다 글자가 있고 서로 다르다', () => {
    const labels = INDICATOR_BASIS_TAGS.map(indicatorBasisTagLabel)
    expect(labels).toEqual(['대기', '잠정'])
  })

  it('모르는 표지는 던진다', () => {
    expect(() => indicatorBasisTagLabel('LATER' as IndicatorBasisTag)).toThrow()
  })
})
