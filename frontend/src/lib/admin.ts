import type { BatchExecution, BatchStatus, InteractionAction, TriggeredBy } from '@/api/types'

/**
 * 관리자 화면이 서버 값을 사람 말로 옮기는 유일한 지점.
 *
 * 세 종류가 서로 다르게 다뤄지는 것이 의도다(frontend.md의 "모르는 값을 만났을 때").
 *
 * - **배치 상태·트리거**: 뜻이 왜곡되므로 **던진다.** `PARTIAL_SUCCESS`를 못 읽어 "알 수 없음"으로
 *   떨어뜨리면 848종목 성공한 배치가 실패로 보이거나 그 반대가 된다. `wire-contract.json`이
 *   서버와의 어긋남을 빌드 시점에 잡으므로, 런타임까지 갈 일은 계약이 이미 깨진 경우뿐이다.
 * - **인터랙션 행위**: 이름만 흔들리므로 **낯선 이름을 그대로 보인다.** 새 행위 하나 때문에
 *   로그 화면 전체가 사라지는 것이 가장 나쁘다.
 * - **실패 단계·종류**: 유니온으로 좁히지도 않는다. 서버가 값을 늘리면 그대로 보인다.
 */
export interface BatchStatusStyle {
  label: string
  /** PrimeVue severity. 색의 정본은 테마 토큰이다. */
  severity: 'success' | 'danger' | 'warn' | 'info' | 'secondary'
  tooltip: string
}

export function batchStatusStyle(status: BatchStatus): BatchStatusStyle {
  switch (status) {
    case 'RUNNING':
      return { label: '실행 중', severity: 'info', tooltip: '아직 끝나지 않았다.' }
    case 'SUCCESS':
      return { label: '성공', severity: 'success', tooltip: '모든 대상을 처리했다.' }
    case 'PARTIAL_SUCCESS':
      return {
        label: '부분 실패',
        severity: 'warn',
        // 실패와 구분되어야 한다. 848종목 성공에 2종목 실패한 배치가 "실패"로 보이면
        // 매일 빨간불이 뜨고, 곧 아무도 그 불을 보지 않게 된다(spec §4).
        tooltip: '일부 종목만 실패했다. 나머지는 정상 처리됐다.',
      }
    case 'FAILED':
      return { label: '실패', severity: 'danger', tooltip: '배치 자체가 중단됐다.' }
    case 'ABORTED':
      return {
        label: '중단',
        severity: 'secondary',
        // 쿼터 소진이 대표적이다. 실패가 아니라 정상적인 일시정지이므로 빨간색이 아니다.
        tooltip: '외부 요인으로 멈췄다. 다음 실행이 이어서 처리한다.',
      }
    default:
      return assertNever(status)
  }
}

export function triggeredByLabel(triggeredBy: TriggeredBy): string {
  switch (triggeredBy) {
    case 'SCHEDULER':
      return '스케줄러'
    case 'ADMIN':
      return '관리자'
    case 'MANUAL':
      return '직접 실행'
    default:
      return assertNever(triggeredBy)
  }
}

/**
 * 인터랙션 행위의 한글 이름.
 *
 * 모르는 값은 던지지 않고 그대로 보인다. 서버가 행위를 하나 더했는데 프론트가 낡았을 때,
 * 로그 화면이 통째로 죽는 것보다 낯선 이름 한 줄이 섞이는 편이 낫다 — 그 이름 자체가
 * "거울이 낡았다"는 신호이기도 하다.
 */
const ACTION_LABELS: Record<InteractionAction, string> = {
  LOGIN: '로그인',
  SCREENING_RUN: '스크리닝 실행',
  SCREEN_CREATE: '전략 저장',
  SCREEN_UPDATE: '전략 수정',
  SCREEN_DELETE: '전략 삭제',
  WATCHLIST_ADD: '관심종목 담기',
  WATCHLIST_UPDATE: '관심종목 수정',
  WATCHLIST_REMOVE: '관심종목 해제',
  ADMIN_JOB_RUN: '배치 기동',
  ADMIN_BATCH_RETRY: '배치 재실행',
  ADMIN_ALLOWLIST_ADD: '계정 추가',
  ADMIN_ALLOWLIST_UPDATE: '계정 수정',
  ADMIN_ALLOWLIST_REMOVE: '계정 삭제',
}

export function interactionActionLabel(action: InteractionAction): string {
  return ACTION_LABELS[action] ?? action
}

/**
 * 실행이 얼마나 걸렸는가.
 *
 * 아직 안 끝났으면 null이다. 0으로 두면 "즉시 끝났다"로 읽힌다 — 값 부재를 0으로 메우지
 * 않는다는 규칙이 여기에도 적용된다(frontend.md).
 */
export function elapsedSeconds(execution: BatchExecution): number | null {
  if (execution.finishedAt === null) {
    return null
  }
  const started = Date.parse(execution.startedAt)
  const finished = Date.parse(execution.finishedAt)
  if (Number.isNaN(started) || Number.isNaN(finished)) {
    return null
  }
  return Math.max(0, Math.round((finished - started) / 1000))
}

/**
 * 재실행 버튼을 켤 조건.
 *
 * 미해결 실패가 있고, 그 잡이 부분 재실행을 지원해야 한다. **지원 여부는 서버가 준
 * `retrySupported`이고 프론트가 잡 이름으로 판단하지 않는다** — 판단하면 잡이 늘 때마다
 * 화면을 고쳐야 한다.
 */
export function canRetry(execution: BatchExecution, retrySupported: boolean): boolean {
  return retrySupported && execution.unresolvedFailureCount > 0
}

function assertNever(value: never): never {
  throw new Error(`처리하지 않은 값이다: ${String(value)}`)
}
