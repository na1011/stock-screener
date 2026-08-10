import type { BatchExecution } from '@/api/types'

/**
 * 배치 실행의 진행 상태를 숫자로 답한다.
 *
 * `lib/admin.ts`와 나눈 이유는 책임이 다르기 때문이다. 저쪽은 스스로를 "서버 값을 **사람
 * 말로 옮기는** 유일한 지점"이라고 선언했고, 여기는 계산이다. 섞으면 라벨 매핑 파일에
 * 산술이 끼어들고, 그 파일이 무엇을 보장하는지가 흐려진다.
 *
 * 공통 규칙 하나 — **값이 없으면 `null`이고 0으로 메우지 않는다**(frontend.md). 0은 "재봤는데
 * 0이었다"는 뜻이고, 여기서 대부분의 부재는 "아직 못 쟀다"다. 둘을 섞으면 화면이 없는 것을
 * 있는 것처럼 단정한다.
 */

/** 진척이 이만큼 멎어 있으면 돌지 않는 것으로 본다. 하트비트가 3초이므로 스무 번을 놓친 것이다. */
const STALL_THRESHOLD_MS = 60_000

/** 지금까지 처리한 작업 단위. 성공과 실패를 함께 센다 — 실패도 "돌긴 돈 것"이다. */
export function processedTasks(execution: BatchExecution): number {
  return execution.successCount + execution.failureCount
}

/**
 * 0..1 진행률.
 *
 * 계획이 없으면 `null`이다. V14 이전 실행과 계획을 신고하지 않는 잡이 그렇고, 화면은 막대
 * 대신 `—`를 그린다.
 *
 * **1을 넘지 않게 자른다.** 넘는다면 잡의 계획 식이 루프와 어긋난 것이고 그건 서버 쪽 결함인데,
 * 막대가 칸 밖으로 삐져나가는 것으로 그 결함을 알릴 수는 없다 — ADR-0037의 전제 쿼리가
 * 세션 시작에 알린다.
 */
export function progressRatio(execution: BatchExecution): number | null {
  const planned = execution.plannedTaskCount
  if (planned === null || planned <= 0) {
    return null
  }
  return Math.min(1, processedTasks(execution) / planned)
}

/**
 * 얼마나 걸렸는가(초). 아직이면 `null`.
 *
 * `lib/admin.ts`의 `elapsedSeconds`와 다르다 — 저쪽은 **끝난** 실행만 답하고(0으로 그리면
 * 즉시 끝난 것으로 읽힌다), 이쪽은 도는 중에도 "지금까지 얼마나"를 답해야 속도를 낼 수 있다.
 */
export function runningSeconds(execution: BatchExecution, now: number): number | null {
  const started = Date.parse(execution.startedAt)
  if (Number.isNaN(started)) {
    return null
  }
  const finished = execution.finishedAt === null ? now : Date.parse(execution.finishedAt)
  if (Number.isNaN(finished)) {
    return null
  }
  return Math.max(0, (finished - started) / 1000)
}

/**
 * 초당 처리 작업 단위.
 *
 * 아무것도 처리하지 않았거나 시간이 아직 0이면 `null`이다. 0으로 답하면 "느리다"로 읽히는데
 * 실제로는 "아직 잴 수 없다"이고, 그 둘은 배치가 멎었는지 판단할 때 정반대의 뜻이다.
 */
export function throughputPerSecond(execution: BatchExecution, now: number): number | null {
  const seconds = runningSeconds(execution, now)
  const processed = processedTasks(execution)
  if (seconds === null || seconds <= 0 || processed <= 0) {
    return null
  }
  return processed / seconds
}

/**
 * 남은 시간(초) 어림.
 *
 * 끝난 실행에는 답하지 않는다. 계획이 없거나 속도를 아직 못 재도 답하지 않는다 —
 * 지금까지의 평균 속도가 남은 구간에도 이어진다는 가정 위에 서므로, 근거가 얇으면
 * 숫자를 내지 않는 편이 낫다.
 */
export function remainingSeconds(execution: BatchExecution, now: number): number | null {
  if (execution.finishedAt !== null || execution.plannedTaskCount === null) {
    return null
  }
  const rate = throughputPerSecond(execution, now)
  if (rate === null) {
    return null
  }
  const remaining = execution.plannedTaskCount - processedTasks(execution)
  if (remaining <= 0) {
    return null
  }
  return remaining / rate
}

/**
 * 도는 중이라는데 진척이 멎었는가.
 *
 * `RUNNING`만으로는 "돌고 있다"와 "프로세스가 죽어 행만 남았다"가 구분되지 않는다. 실제로
 * 그렇게 남은 고아 행이 10건이었고, 화면은 그 열 건을 모두 "실행 중"으로 그렸다.
 *
 * 진척 시각이 아예 없으면 `false`다. 계량 이전 실행이 전부 "멎음"으로 보이면 그 경고가
 * 곧 배경이 된다.
 */
export function isStalled(execution: BatchExecution, now: number): boolean {
  if (execution.status !== 'RUNNING' || execution.progressAt === null) {
    return false
  }
  const last = Date.parse(execution.progressAt)
  if (Number.isNaN(last)) {
    return false
  }
  return now - last > STALL_THRESHOLD_MS
}

/**
 * 사람이 읽는 기간. `초`/`분 초`/`시간 분`으로 자른다.
 *
 * 55분짜리 백필을 "3340초"라고 쓰면 얼마인지 세어야 한다. 반대로 3초짜리를 "0분"이라
 * 쓰면 잰 것이 없어 보인다.
 */
export function formatDuration(seconds: number | null): string {
  if (seconds === null || !Number.isFinite(seconds)) {
    return '—'
  }
  const total = Math.max(0, Math.round(seconds))
  if (total < 60) {
    return `${total}초`
  }
  if (total < 3600) {
    return `${Math.floor(total / 60)}분 ${total % 60}초`
  }
  return `${Math.floor(total / 3600)}시간 ${Math.floor((total % 3600) / 60)}분`
}

/** 자릿수가 큰 수를 천 단위로 끊는다. 43400과 434000은 끊지 않으면 눈으로 구분되지 않는다. */
export function formatCount(value: number): string {
  return value.toLocaleString('ko-KR')
}
