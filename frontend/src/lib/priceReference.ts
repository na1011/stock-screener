import type { ZoneRole, ZoneStrength } from '@/api/types'

/**
 * 지지·저항 참고정보의 표시.
 *
 * `lib/outcome`·`lib/direction`과 같은 자리에 두는 이유는 셋 다 **서버 enum을 화면 표현으로
 * 옮기는 유일한 지점**이라는 같은 역할이기 때문이다. 이것만 컴포넌트 안에 인라인으로 남아
 * 있었다.
 *
 * IMPORTANT — 여기서는 **모르는 값에 던지지 않는다.** `outcome`·`direction`과 반대인 것이
 * 의도다. 저 둘은 틀리면 뜻이 왜곡되지만(판정이 사라지거나 유불리가 뒤집힌다) 여기는
 * 구간의 이름과 배치만 흔들린다. 던지면 새 역할 하나 때문에 패널 전체가 사라지는데, 그
 * 패널은 사용자가 오해하면 금전 손실로 이어지는 경고를 담고 있어 **없어지는 것이 가장
 * 나쁘다**(frontend.md).
 */

/** 백엔드 `PriceReference.METHOD`. 값이 이것뿐이라 화면은 한글 문구로 바꿔 보인다. */
export const PRICE_REFERENCE_METHOD_SWING = 'SWING_EXTREMA_CLUSTERING'

export interface ZoneRoleStyle {
  label: string
  /** PrimeVue severity. 색의 정본은 테마 토큰이다. */
  severity: 'info' | 'warn' | 'contrast' | 'secondary'
}

const ROLE_STYLES: Record<ZoneRole, ZoneRoleStyle> = {
  SUPPORT: { label: '아래쪽 구간', severity: 'info' },
  RESISTANCE: { label: '위쪽 구간', severity: 'warn' },
  // 숨기지 않는다. "지금 그 구간 안에 있다"가 정보량이 가장 큰 상태다.
  IN_RANGE: { label: '현재 구간 안', severity: 'contrast' },
}

const STRENGTH_LABELS: Record<ZoneStrength, string> = {
  HIGH: '자주 머무름',
  MEDIUM: '보통',
  LOW: '드묾',
}

/**
 * 구간의 역할 표시.
 *
 * 예전에는 `ROLE_LABELS[zone.role].label`이라 모르는 역할에서 TypeError가 났고, 그 예외가
 * 패널을 통째로 언마운트시켰다 — 경고 문구까지 함께 사라졌다.
 */
export function zoneRoleStyle(role: string): ZoneRoleStyle {
  return Object.hasOwn(ROLE_STYLES, role)
    ? ROLE_STYLES[role as ZoneRole]
    : { label: role, severity: 'secondary' }
}

/** 모르는 강도는 빈 문자열이 아니라 코드를 보인다. 빈 문자열이면 사라진 줄도 모른다. */
export function zoneStrengthLabel(strength: string): string {
  return Object.hasOwn(STRENGTH_LABELS, strength)
    ? STRENGTH_LABELS[strength as ZoneStrength]
    : strength
}

/**
 * 산출 방식 표시.
 *
 * 응답의 `method`를 날것으로 그리면 사용자가 `SWING_EXTREMA_CLUSTERING`을 읽게 된다. 그것은
 * 기계 식별자라 아무 뜻이 없고, 백엔드에서 이름을 바꾸면 화면 문구가 따라 바뀐다.
 */
export function priceReferenceMethodLabel(method: string): string {
  return method === PRICE_REFERENCE_METHOD_SWING
    ? '스윙 고저점 군집화'
    : `알 수 없는 방식 (${method})`
}
