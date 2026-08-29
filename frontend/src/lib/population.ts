import type { IndicatorPopulation } from '@/api/types'

/**
 * 상대 비교의 모집단을 사용자에게 설명하는 문장.
 *
 * 서버 enum이 값을 정하므로 템플릿이 아니라 여기 둔다. 두 모집단이 한 화면에 함께 놓일 수
 * 있고, 그때 **둘 다 보여야** 사용자가 조건마다 "상위 50%"의 뜻이 다르다는 것을 안다 —
 * 값은 둘 다 0~1이라 표에서는 구분되지 않는다.
 */
const POPULATION_NOTES: Record<IndicatorPopulation, string> = {
  UNIVERSE:
    '유니버스 백분위는 각 지수 안에서 매깁니다 — 코스피200 종목은 코스피200 안에서, 코스닥150 종목은 코스닥150 안에서. 두 지수를 함께 보면 서로 다른 모집단의 순위가 한 표에 섞입니다.',
  SECTOR:
    '업종 상대값은 같은 업종 종목 안에서 매깁니다 — 지수는 가리지 않지만 업종 분류 체계가 다른 시장끼리는 섞지 않습니다. 비교한 종목 수는 각 지표의 "표본 수"로 확인할 수 있고, 5종목에 못 미치면 값을 내지 않습니다.',
}

/**
 * 모르는 모집단을 만나도 **던지지 않고 이름을 그대로 보인다.**
 *
 * 던지면 새 값 하나 때문에 주의 문구가 통째로 사라지는데, 이 문구는 **부재가 위험한 상태**다 —
 * 없으면 화면이 모집단을 밝히지 않은 채 "상위 20%"라고 말하게 된다. 조금 어긋난 문장이
 * 사라진 문장보다 낫다(`.claude/rules/frontend.md`).
 */
export function populationNote(population: string): string {
  return (
    POPULATION_NOTES[population as IndicatorPopulation] ??
    `이 결과에는 '${population}' 모집단을 기준으로 계산된 상대 지표가 있습니다. 조회 대상을 좁혀도 순위는 그 안에서 매긴 것이 아닐 수 있습니다.`
  )
}
