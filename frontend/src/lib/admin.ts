import type {
  BatchExecution,
  BatchStatus,
  ExecutionScopeReason,
  InteractionAction,
  TriggeredBy,
  WriteLockHolder,
} from '@/api/types'

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
    case 'GAP_FILL':
      // 정기 경로가 티커로 좁혀 부른 실행 — 리밸런싱 꼬리와 14:00 사슬의 화해 ①(ADR-0166).
      return '좁힌 정기 실행'
    default:
      return assertNever(triggeredBy)
  }
}

/** 범위 덧말 — 실행 주체 칸에 붙는 짧은 낱말과, 왜 연체 판정에 안 세는지의 툴팁. */
export interface ScopeNote {
  label: string
  title: string
}

const NOT_COUNTED = '연체 판정에 세지 않습니다'

/**
 * 범위 표지(V63 · V65)의 덧말. **`NARROW`만 말한다** — 연체 시계에 안 세는 실행이 그것이다.
 *
 * 사유(`scopeReason`)마다 낱말이 다르다 — 「시장 미완료」를 「좁힘」으로 그리면 정기 범위로 돈 실행을 손으로 좁힌 실행으로
 * 읽는다. **note를 파싱하지 않는다**(서버가 구조화된 칸으로 준다). 모르는 사유는 던진다 — 뜻이 왜곡되는 자리다.
 *
 * `FULL`과 표지 전(`null`)은 덧말이 없다. 표지 전 행에 「범위 모름」을 달면 이력 전체가 그 낱말로 덮이고,
 * 그 사실은 연체 배너가 한 번 말한다. 사유 기록 전(V65 전) `NARROW`는 예전 낱말 「좁힘」이다.
 */
export function scopeNote(
  execution: Pick<BatchExecution, 'scope' | 'scopeReason' | 'incompleteMarkets'>,
): ScopeNote | null {
  switch (execution.scope) {
    case 'FULL':
    case null:
      return null
    case 'NARROW':
      break
    default:
      return assertNever(execution.scope)
  }
  const base =
    execution.scopeReason === null
      ? { label: '좁힘', title: `정기 실행과 범위가 달라 ${NOT_COUNTED}(사유 기록 전 실행)` }
      : reasonNote(execution.scopeReason, execution.incompleteMarkets)
  // 시작부터 좁힌 실행이 시장까지 못 끝냈으면 그것도 말한다 — 사유는 처음 것 그대로다(서버 규칙).
  if (execution.scopeReason !== 'MARKET_INCOMPLETE' && execution.incompleteMarkets.length > 0) {
    return {
      label: `${base.label} · 시장 미완료 ${execution.incompleteMarkets.join(', ')}`,
      title: base.title,
    }
  }
  return base
}

function reasonNote(reason: ExecutionScopeReason, markets: string[]): ScopeNote {
  switch (reason) {
    case 'ARGS':
      return { label: '좁힘', title: `티커·연수·표 등 인자로 좁혀 정기 실행과 범위가 달라 ${NOT_COUNTED}` }
    case 'RERUN':
      return { label: '재실행', title: `실패분만 다시 돈 실행이라 ${NOT_COUNTED}` }
    case 'GAP_FILL':
      return { label: '좁힘', title: `정기 경로가 결손 티커로 좁혀 부른 실행이라 ${NOT_COUNTED}` }
    case 'PAST_DATE':
      return { label: '과거 기준일', title: `기준일이 지금이 아니라 정기 실행과 범위가 달라 ${NOT_COUNTED}` }
    case 'NO_SCHEDULED_RANGE':
      return { label: '정기 범위 없음', title: `정기 실행이 없는 잡이라 ${NOT_COUNTED}` }
    case 'MARKET_INCOMPLETE':
      return {
        label: markets.length > 0 ? `시장 미완료 ${markets.join(', ')}` : '시장 미완료',
        title:
          `정기 범위로 돌았지만 마감 때 소스의 답을 다 받지 못한 시장이 있어 ${NOT_COUNTED} — ` +
          '연체로 남아 다음 무거운 잡 차례에 다시 돕니다',
      }
    default:
      return assertNever(reason)
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
  ADMIN_CORPORATE_ACTION_JUDGE: '기업 행위 판정',
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

/**
 * 쓰기 잠금 보유자를 한 줄로(ADR-0175). 버튼을 잠근 사유이자 409가 말하는 사유다.
 *
 * 보유 종류는 **낯선 이름을 그대로 보인다** — 이름만 흔들리는 자리라 던지면 실행 영역이 통째로 사라진다.
 */
export function writeLockHolderText(holder: WriteLockHolder): string {
  if (holder.waiting) {
    return `차례를 기다리는 쓰기 크론(${holder.purpose ?? '목적 모름'})`
  }
  const who = WRITE_LOCK_HOLDER_KINDS[holder.kind] ?? holder.kind
  if (holder.kind === 'SESSION') {
    return `${who} 「${holder.sessionId ?? '?'}」`
  }
  const pid = holder.pid === null ? '' : ` pid ${holder.pid}`
  return `${who}${pid}${holder.purpose ? ` · ${holder.purpose}` : ''}`
}

const WRITE_LOCK_HOLDER_KINDS: Record<string, string> = {
  WEB: '이 앱',
  BOOTRUN: 'bootRun',
  SESSION: '손 쓰기 세션',
}
