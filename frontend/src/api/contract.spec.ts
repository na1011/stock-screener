import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'

import { ProblemType } from './http'
import {
  ALLOWED_EMAIL_NOTE_MAX_LENGTH,
  DEFAULT_DECIMAL_SCALE,
  MAX_CONDITIONS,
  SCREEN_DESCRIPTION_MAX_LENGTH,
  SCREEN_NAME_MAX_LENGTH,
} from './limits'
import {
  BATCH_STATUSES,
  EVALUATION_MODES,
  FAVORABLE_ENDS,
  INDICATOR_CATEGORIES,
  INDICATOR_POPULATIONS,
  INTERACTION_ACTIONS,
  JOB_PARAMETERS,
  OPERAND_TYPES,
  OUTCOME_STATUSES,
  SCREEN_TYPES,
  TRIGGERED_BY,
  ZONE_ROLES,
  ZONE_STRENGTHS,
} from './types'
import { populationNote } from '@/lib/population'
import { PRICE_REFERENCE_METHOD_SWING } from '@/lib/priceReference'

/**
 * 프론트가 손으로 적어 둔 서버 값이 실제 서버와 같은가.
 *
 * 같은 픽스처를 백엔드 `WireContractTest`가 반대 방향으로 단언한다 — 서버가 값을 바꾸면
 * 거기서, 프론트 유니온이 낡으면 여기서 멈춘다(ADR-0020).
 *
 * 픽스처는 `src/test/resources/` 아래에 있어 브라우저 번들과 무관하다. `fs`로 직접 읽으므로
 * 번들러를 거치지 않고, 나가는 바이트도 0이다.
 *
 * 이 파일이 매번 도는 것은 `frontendTest`가 **출력을 선언하지 않아 Gradle이 항상 실행하기**
 * 때문이다. `frontendTest.inputs`에 픽스처를 적어 둔 것은 그 태스크가 언젠가 UP-TO-DATE
 * 대상이 될 때를 대비한 것이지, 지금 이 파일을 돌게 하는 이유가 아니다.
 */
const CONTRACT: {
  enums: Record<string, string[]>
  problemTypes: string[]
  constants: Record<string, string | number>
} = JSON.parse(
  readFileSync(
    new URL('../../../src/test/resources/contract/wire-contract.json', import.meta.url),
    'utf8',
  ),
)

/** 순서는 계약이 아니다 — 프론트가 표시 순서를 스스로 정한다(예: 지표 분류 랭크). */
function sorted(values: readonly string[]): string[] {
  return [...values].sort()
}

describe('서버 enum의 거울', () => {
  const pairs: [string, readonly string[]][] = [
    ['IndicatorCategory', INDICATOR_CATEGORIES],
    ['EvaluationMode', EVALUATION_MODES],
    ['ScreenType', SCREEN_TYPES],
    ['OutcomeStatus', OUTCOME_STATUSES],
    ['IndicatorPopulation', INDICATOR_POPULATIONS],
    ['FavorableEnd', FAVORABLE_ENDS],
    ['OperandType', OPERAND_TYPES],
    ['ZoneRole', ZONE_ROLES],
    ['ZoneStrength', ZONE_STRENGTHS],
    ['InteractionAction', INTERACTION_ACTIONS],
    ['BatchStatus', BATCH_STATUSES],
    ['TriggeredBy', TRIGGERED_BY],
    ['JobParameters', JOB_PARAMETERS],
  ]

  it.each(pairs)('%s이 픽스처와 같은 값 집합이다', (name, values) => {
    const fixture = CONTRACT.enums[name]

    expect(fixture, `픽스처에 ${name}이 없다`).toBeDefined()
    expect(sorted(values)).toEqual(sorted(fixture ?? []))
  })

  it('픽스처의 enum을 하나도 빠뜨리지 않았다', () => {
    // 백엔드가 새 enum을 픽스처에 추가했는데 프론트가 거울을 만들지 않은 경우다.
    // 이것이 없으면 "대조하지 않음"이 "통과"로 보인다.
    expect(sorted(Object.keys(CONTRACT.enums))).toEqual(sorted(pairs.map(([name]) => name)))
  })
})

describe('problem+json의 type URN', () => {
  it('분기 대상 URN이 서버와 같다', () => {
    // 프론트는 한글 메시지가 아니라 이 값으로 분기한다. 슬러그가 어긋나면 error.is(...)가
    // 조용히 false가 되어, 보존 구간 포착과 중복 이름 처리가 일반 오류로 강등된다.
    expect(sorted(Object.values(ProblemType))).toEqual(sorted(CONTRACT.problemTypes))
  })
})

describe('서버 상수의 거울', () => {
  it('숫자 제한이 서버 값과 같다', () => {
    expect(MAX_CONDITIONS).toBe(CONTRACT.constants.maxConditions)
    expect(SCREEN_NAME_MAX_LENGTH).toBe(CONTRACT.constants.screenNameMaxLength)
    expect(SCREEN_DESCRIPTION_MAX_LENGTH).toBe(CONTRACT.constants.screenDescriptionMaxLength)
    expect(ALLOWED_EMAIL_NOTE_MAX_LENGTH).toBe(CONTRACT.constants.allowedEmailNoteMaxLength)
    expect(DEFAULT_DECIMAL_SCALE).toBe(CONTRACT.constants.defaultDecimalScale)
  })

  it('문자열 상수가 서버 값과 같다', () => {
    expect(PRICE_REFERENCE_METHOD_SWING).toBe(CONTRACT.constants.priceReferenceMethod)
  })

  it('모집단 설명이 서버 enum을 빠짐없이 덮는다', () => {
    // 빠지면 그 모집단만 "모르는 모집단" 문장으로 넘어간다 — 사라지지는 않지만 사용자가
    // 읽는 문장이 일반론이 되고, 업종이면 표본 수를 보라는 안내를 잃는다.
    for (const population of INDICATOR_POPULATIONS) {
      expect(populationNote(population)).not.toContain('모집단을 기준으로 계산된 상대 지표')
    }
  })
})
