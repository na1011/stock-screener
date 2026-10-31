import { EVALUATION_MODES } from '@/api/types'
import type { EvaluationMode, IsoDate } from '@/api/types'
import type { DraftCondition } from '@/stores/screenDraft'

/**
 * 편집 중인 초안을 **그 탭의 `sessionStorage`**에 둔다 — 새로고침에 조건 · 출처 · 조회 대상 · 기준일이 살아남는다.
 *
 * `sessionStorage`를 고른 이유: 초안은 지속 성향이 아니라 **지금 이 탭의 작업**이다. `localStorage`면 다른 탭의 초안을 덮고,
 * URL 쿼리면 링크를 받은 사람의 편집기까지 바꾼다(`lib/detailVisibility.ts`의 같은 판단 · `auth.ts` 복귀 경로와 같은 저장소).
 *
 * **서버가 정하는 값은 싣지 않는다** — 프리셋 설명(결손 고지)과 편집 가능 여부. 배포로 고지가 바뀌거나 권한이 바뀌어도 옛 값이 되살아나기
 * 때문이고, 스토어가 전략 목록을 받으면 다시 맞춘다(`resyncOrigin`). 실행 기록(`lastSent`)·휴지통(`lastRemoved`)도 싣지 않는다 — 결과 화면과
 * 짝이라 결과가 없는 새로고침 뒤에는 뜻이 없다.
 *
 * **저장분에 소유자를 담는다** — 같은 탭에서 로그아웃 뒤 다른 계정이 들어오면 앞 사람의 초안을 안 되살린다. 로그아웃은 이 키를 지운다
 * ({@link forgetPersistedDraft}).
 *
 * 저장소가 없거나 막혔거나(사생활 모드) 담긴 값이 이 판의 모양이 아니면 버리고 새로 시작한다 — 버린 사실은 콘솔에 남긴다.
 */
export const DRAFT_STORAGE_KEY = 'screenDraft.v2'

/** 저장하는 출처 — 프리셋 설명은 뺀다(서버가 다시 준다). */
export type PersistedOrigin =
  | { kind: 'custom' }
  | { kind: 'preset'; code: string; name: string }
  | { kind: 'screen'; id: number; name: string }

export interface PersistedDraft {
  origin: PersistedOrigin
  conditions: DraftCondition[]
  evaluationMode: EvaluationMode
  universeCode: string | null
  asOf: IsoDate | null
  dirty: boolean
}

interface Stored extends PersistedDraft {
  version: 2
  ownerId: number | null
}

/** 세션 저장소. 저장소 자체가 없는 실행 환경(노드 시험)이면 `null` — 그때는 영속하지 않는다(실패가 아니라 없는 것이다). */
function storage(): Storage | null {
  return typeof globalThis.sessionStorage === 'undefined' ? null : globalThis.sessionStorage
}

/** 그 소유자의 초안. 없거나 다른 계정의 것이거나 이 판의 모양이 아니면 `null`. */
export function readPersistedDraft(ownerId: number | null): PersistedDraft | null {
  const store = storage()
  if (store === null) {
    return null
  }
  let raw: string | null
  try {
    raw = store.getItem(DRAFT_STORAGE_KEY)
  } catch (e) {
    console.warn('편집 중인 초안을 세션 저장소에서 못 읽었다 — 새 초안으로 시작한다', e)
    return null
  }
  if (raw === null) {
    return null
  }
  let parsed: Partial<Stored>
  try {
    parsed = JSON.parse(raw) as Partial<Stored>
    if (!isStored(parsed)) {
      throw new Error('모양이 이 판과 다르다')
    }
  } catch (e) {
    console.warn('세션 저장소의 초안이 이 판의 모양이 아니라 버린다', e)
    forgetPersistedDraft()
    return null
  }
  if (parsed.ownerId !== ownerId) {
    // 다른 계정의 초안이다 — 되살리지 않고 지운다.
    forgetPersistedDraft()
    return null
  }
  const { origin, conditions, evaluationMode, universeCode, asOf, dirty } = parsed
  return { origin, conditions, evaluationMode, universeCode, asOf, dirty }
}

export function writePersistedDraft(ownerId: number | null, draft: PersistedDraft): void {
  const store = storage()
  if (store === null) {
    return
  }
  const stored: Stored = { version: 2, ownerId, ...draft }
  try {
    store.setItem(DRAFT_STORAGE_KEY, JSON.stringify(stored))
  } catch (e) {
    console.warn('편집 중인 초안을 세션 저장소에 못 썼다 — 새로고침하면 사라진다', e)
  }
}

/** 로그아웃 · 버림 때 지운다. */
export function forgetPersistedDraft(): void {
  const store = storage()
  if (store === null) {
    return
  }
  try {
    store.removeItem(DRAFT_STORAGE_KEY)
  } catch (e) {
    console.warn('세션 저장소의 초안을 못 지웠다', e)
  }
}

function isStored(value: Partial<Stored>): value is Stored {
  const kind = (value.origin as { kind?: unknown } | undefined)?.kind
  return (
    value.version === 2 &&
    (kind === 'custom' || kind === 'preset' || kind === 'screen') &&
    (EVALUATION_MODES as readonly unknown[]).includes(value.evaluationMode) &&
    Array.isArray(value.conditions) &&
    // 키가 숫자가 아니면 키 발급기가 NaN이 되어 새 조건의 키가 서로 부딪힌다.
    value.conditions.every((it) => Number.isInteger((it as { key?: unknown }).key))
  )
}
