import type { FinancialBasis, FinancialBasisStatus } from '@/api/types'

/**
 * 재무 기준 · 새 실적 발표 상태를 화면 표현으로 옮기는 **유일한** 지점(ADR-0195). 결과 표 · 모바일 카드 · 종목 상세가 같은 표현을 쓴다.
 *
 * IMPORTANT — `UNTRACKED`를 `NONE`처럼 그리지 않는다. 「모른다」를 「새 발표 없음」으로 읽히면 이 표시가 막으려던 착시(옛 분기 PER을
 * 지금 값으로 읽는 것)가 그대로 남는다. 결과 행에는 `PENDING`만 단다 — 행마다 「모름」을 달면 외국 발행사 같은 상시 상태가 표를 덮는다.
 * 상세는 셋 다 그리고 문구는 서버 것을 쓴다.
 */
export interface FinancialBasisStyle {
  /** 결과 행에 배지를 다는가. */
  flagsRow: boolean
  /** 상세의 안내 줄 강조. */
  tone: 'warn' | 'muted' | null
}

export function financialBasisStyle(status: FinancialBasisStatus): FinancialBasisStyle {
  switch (status) {
    case 'PENDING':
      return { flagsRow: true, tone: 'warn' }
    case 'NONE':
      return { flagsRow: false, tone: null }
    case 'UNTRACKED':
      return { flagsRow: false, tone: 'muted' }
    default:
      // 상태가 늘면 여기서 컴파일이 멈춘다. 런타임까지 오면 그 상태가 화면에서 조용히 사라지므로 던진다.
      return assertNever(status)
  }
}

/** 결과 행 배지 문구 — 표와 카드가 같은 것을 그린다. */
export const NEW_RELEASE_BADGE = '새 실적'

/** 상세 헤더의 기준 줄 — 「재무 기준 2026 Q2」, 잔액 기가 다르면 「· 자산 2026 Q1」을 붙인다. */
export function basisLine(basis: FinancialBasis): string {
  return basis.balanceLabel === null
    ? `재무 기준 ${basis.flowLabel}`
    : `재무 기준 ${basis.flowLabel} · 자산 ${basis.balanceLabel}`
}

function assertNever(value: never): never {
  throw new Error(`처리하지 않은 재무 기준 상태다: ${String(value)}`)
}
