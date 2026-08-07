import { EVALUATION_MODES } from '@/api/types'
import type { EvaluationMode } from '@/api/types'

/**
 * 판정 방식의 화면 표기.
 *
 * 같은 두 값이 다섯 파일에서 **세 가지 다른 문구 쌍**으로 쓰이고 있었다. 사용자는 화면마다
 * 같은 것을 다른 이름으로 만나게 된다.
 *
 * 그런데 문구가 셋인 것 자체는 실수가 아니다 — 고르는 버튼에는 무엇을 하는지가 동사로 보여야
 * 하고, 요약의 `<dd>`에는 명사가 들어가야 하며, `Tag`에는 두 글자밖에 못 넣는다. 하나의
 * 문자열로는 표현할 수 없다. 그래서 **합치지 않고 변형에 이름을 붙여 한 곳에 모은다.** 여기
 * 나란히 있으면 하나를 고칠 때 나머지 둘이 눈에 들어온다.
 */
export interface EvaluationModeLabels {
  /** 결과 머리말·저장 요약처럼 명사가 들어갈 자리. */
  noun: string
  /** Tag처럼 두 글자가 최대인 자리. */
  short: string
  /** 고르는 버튼. 무엇을 하는지가 동사로 보여야 한다. */
  action: string
}

export function evaluationModeLabels(mode: EvaluationMode): EvaluationModeLabels {
  switch (mode) {
    case 'FILTER':
      return { noun: '조건 충족', short: '필터', action: '조건 충족으로 거르기' }
    case 'SCORING':
      return { noun: '가중치 점수', short: '점수', action: '가중치 점수로 줄 세우기' }
    default:
      // 판정 방식이 늘면 여기서 컴파일이 멈춘다. 라벨이 틀리면 결과 집합 전체를 잘못
      // 설명하게 되므로, 낯선 이름을 보이는 쪽이 아니라 던지는 쪽을 택한다.
      return assertNever(mode)
  }
}

/**
 * 고르는 버튼의 선택지.
 *
 * 유니온에서 파생시킨다. 손으로 적은 배열은 판정 방식이 늘어도 그대로 남아, 만들 수 없는
 * 조건이 생기는데 아무 데서도 티가 나지 않는다.
 */
export const evaluationModeOptions: { label: string; value: EvaluationMode }[] = EVALUATION_MODES.map(
  (mode) => ({ label: evaluationModeLabels(mode).action, value: mode }),
)

function assertNever(value: never): never {
  throw new Error(`처리하지 않은 판정 방식이다: ${String(value)}`)
}
