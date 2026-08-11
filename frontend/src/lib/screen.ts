import type { ScreenType } from '@/api/types'
import type { DraftOrigin } from '@/stores/screenDraft'

/**
 * 저장된 전략의 **출처** 표시.
 *
 * IMPORTANT — 출처이지 권한이 아니다. "고칠 수 있는가"는 서버가 주는 `editable`이 답하고
 * `stores/screenDraft`가 그것을 든다. 이 파일은 목록 섹션 제목과 태그에만 쓴다.
 *
 * 세 곳(전략 목록, 스크리너의 선택기, 조건 빌더의 태그)이 같은 두 낱말을 각자 적고 있었다.
 *
 * IMPORTANT — 영문 식별자는 `screen`인데 사용자에게는 "전략"이라 말한다. 의도적으로 어긋난
 * 것이고 근거는 ADR-0027에 있다. "화면"으로 되돌리지 않는다 — 그 낱말은 UI 뷰를 가리킨다.
 */
export function screenTypeLabel(type: ScreenType): string {
  switch (type) {
    case 'PRESET':
      return '프리셋'
    case 'CUSTOM':
      return '내 전략'
    default:
      // 전략 종류가 늘면 여기서 컴파일이 멈춘다. 목록의 섹션 구성이 함께 바뀌어야 하므로
      // 낯선 이름을 흘려보내는 것으로는 부족하다.
      return assertNever(type)
  }
}

/**
 * 전략 하나를 가리키는 최소 정보.
 *
 * 목록 응답(`ScreenResponse`)과 스크리닝 응답의 `screen`(`ScreeningScreenInfo`)이 둘 다 이
 * 모양이다. 좁은 타입으로 받는 이유는 {@link matchesOrigin}이 **어느 쪽에서 왔는지 몰라도
 * 되게** 하기 위해서다.
 */
export interface ScreenRef {
  id: number | null
  /** 프리셋만 갖는다. */
  code: string | null
  type: ScreenType
}

/**
 * 이 전략이 지금 편집 중인 것과 같은가.
 *
 * IMPORTANT — 두 곳이 이 대조를 필요로 한다. 레일의 활성 표시(`useScreenOpener.isOpen`)와
 * **결과가 낡았는지 판정**(`ScreenerView`의 `stale`)이다. 각자 적으면 레일은 "이 전략이
 * 열려 있다"고 하는데 결과는 "다른 전략 것"이라고 말하는 상태가 생긴다.
 *
 * 즉석 조건(`custom`)은 언제나 false다. 저장되지 않은 초안은 가리킬 이름이 없고, "같은
 * 즉석 조건"이라는 개념 자체가 없다 — 조건을 하나만 고쳐도 다른 것이 된다.
 */
export function matchesOrigin(ref: ScreenRef, origin: DraftOrigin): boolean {
  if (origin.kind === 'preset') {
    return ref.type === 'PRESET' && ref.code === origin.code
  }
  if (origin.kind === 'screen') {
    return ref.type === 'CUSTOM' && ref.id === origin.id
  }
  return false
}

/**
 * 눈앞의 결과가 지금 조건과 어긋난 **이유**. 어긋나지 않았으면 null.
 *
 * 둘을 접지 않는다. "조건을 고쳤다"는 사용자가 방금 한 일이고 "다른 전략을 열었다"는
 * 목록에서 고른 것이라, 무엇을 되돌리면 되는지가 다르다.
 */
export type StaleReason =
  /** 조건을 손댔다. 프리셋 복제 직후도 여기다 — 조건은 그대로여도 요청 형태가 바뀐다. */
  | 'EDITED'
  /** 다른 전략을 열었거나 "새 전략"으로 비웠다. 어느 쪽이든 결과는 지금 조건의 것이 아니다. */
  | 'SCREEN'
  | null

/**
 * 눈앞의 결과가 낡았는가, 낡았다면 왜.
 *
 * IMPORTANT — 판정의 축이 **`dirty`에서 "무엇을 보냈는가"로 옮겨졌다.** `dirty`는 "저장본과
 * 다른가"이고 실행으로 내려가지 않아, 즉석 조건은 실행에 **성공한 직후에도** true였다.
 * 그래서 화면이 방금 받은 결과를 "이전 실행 결과"라고 불렀다. 두 질문("저장본과 다른가",
 * "실행한 것과 다른가")을 한 깃발이 겸하던 것이 원인이다.
 *
 * 판정 순서에 근거가 있다.
 * 1. 보낸 것과 지금 보낼 것이 같으면 결과는 유효하다 — 다른 무엇도 볼 필요가 없다.
 * 2. 실행할 것이 없으면(`canRun`) 조건을 비운 것이다. 그때는 열린 전략도 없으므로 `SCREEN`이
 *    맞다("지금 조건과 다른 전략의 결과입니다").
 * 3. 즉석 조건은 `SCREEN`이 될 수 없다 — {@link matchesOrigin}이 `custom`에 언제나 false를
 *    주므로, 이 갈래가 없으면 **즉석 조건을 고칠 때마다 "다른 전략의 결과"라고 말하게 된다.**
 *    복제 직후(`forkAsCustom`)도 여기 걸린다: 조건은 그대로인데 요청 형태만 바뀐 상태다.
 *
 * 컴포넌트가 아니라 여기 있는 이유는 이 저장소가 컴포넌트를 테스트하지 않기 때문이다
 * (`vite.config.ts`의 `environment: 'node'`). 판정이 뷰에 있으면 검증할 길이 없다.
 */
export function staleReasonOf(args: {
  /** 화면에 그릴 결과가 있는가. 없으면 낡고 자시고 할 것이 없다. */
  hasResult: boolean
  /** 그 결과를 만든 전략. 서버가 응답에 실어 준 것이다 — 클라이언트가 따로 기억하지 않는다. */
  resultScreen: ScreenRef | null
  origin: DraftOrigin
  matchesLastSent: boolean
  canRun: boolean
}): StaleReason {
  if (!args.hasResult || args.resultScreen === null) {
    return null
  }
  if (args.matchesLastSent) {
    return null
  }
  if (!args.canRun) {
    return 'SCREEN'
  }
  if (args.origin.kind === 'custom') {
    return 'EDITED'
  }
  return matchesOrigin(args.resultScreen, args.origin) ? 'EDITED' : 'SCREEN'
}

/**
 * 그 이유를 사용자에게 말하는 한 줄.
 *
 * 문구가 여기 있는 이유는 **판정하는 쪽(`ScreenerView`)과 그리는 쪽(`ResultSummary`)이
 * 다르기 때문**이다(`.claude/rules/frontend.md`의 (b) 조건).
 */
export function staleNotice(reason: StaleReason): string | null {
  switch (reason) {
    case 'EDITED':
      return '조건을 고쳤습니다. 아래는 이전 실행 결과입니다.'
    case 'SCREEN':
      // "다른 전략을 열었다"로 좁히지 않는다. 조건을 비운 경우("새 전략")도 같은 상태이고,
      // 그때 열린 전략은 없다. 무엇이 어긋났는지를 말하는 편이 둘 다에 맞는다.
      return '지금 조건과 다른 전략의 결과입니다. 아래는 이전 실행 결과입니다.'
    case null:
      return null
    default:
      // 어긋나는 길이 늘면 여기서 컴파일이 멈춘다. 런타임까지 가면 새 경로에서만 경고가
      // 조용히 사라지고, 사용자는 낡은 결과를 새 것으로 읽는다 — 그것이 이 기능의 결함 그 자체다.
      return assertNever(reason)
  }
}

function assertNever(value: never): never {
  throw new Error(`처리하지 않은 전략 종류다: ${String(value)}`)
}
