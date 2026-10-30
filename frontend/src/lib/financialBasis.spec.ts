import { describe, expect, it } from 'vitest'

import { FINANCIAL_BASIS_STATUSES, type FinancialBasis, type FinancialBasisStatus } from '@/api/types'

import { basisLine, financialBasisStyle } from './financialBasis'

describe('financialBasisStyle', () => {
  it('결과 행에는 새 실적 발표 대기만 단다', () => {
    expect(FINANCIAL_BASIS_STATUSES.filter((s) => financialBasisStyle(s).flagsRow)).toEqual(['PENDING'])
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
})
