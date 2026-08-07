import { describe, expect, it } from 'vitest'

import type { ZoneRole, ZoneStrength } from '@/api/types'

import {
  PRICE_REFERENCE_METHOD_SWING,
  priceReferenceMethodLabel,
  zoneRoleStyle,
  zoneStrengthLabel,
} from './priceReference'

describe('zoneRoleStyle', () => {
  const roles: ZoneRole[] = ['SUPPORT', 'RESISTANCE', 'IN_RANGE']

  it('세 역할이 서로 다른 라벨과 색을 갖는다', () => {
    // IN_RANGE를 지지/저항 둘로 접으면 "지금 그 구간 안에 있다"는, 정보량이 가장 큰
    // 상태가 사라진다.
    expect(new Set(roles.map((r) => zoneRoleStyle(r).label)).size).toBe(3)
    expect(new Set(roles.map((r) => zoneRoleStyle(r).severity)).size).toBe(3)
  })

  it('모르는 역할에 던지지 않고 코드를 그대로 보인다', () => {
    // IMPORTANT — 예전에는 undefined에 .label을 읽어 TypeError가 났고, 그 예외가 패널을
    // 통째로 언마운트시켜 "확정된 매매 신호가 아니다"라는 경고까지 함께 사라졌다.
    expect(() => zoneRoleStyle('BREAKOUT')).not.toThrow()
    expect(zoneRoleStyle('BREAKOUT').label).toBe('BREAKOUT')
    expect(zoneRoleStyle('BREAKOUT').severity).toBe('secondary')
  })
})

describe('zoneStrengthLabel', () => {
  const strengths: ZoneStrength[] = ['HIGH', 'MEDIUM', 'LOW']

  it('세 강도가 서로 다른 문구를 갖는다', () => {
    expect(new Set(strengths.map(zoneStrengthLabel)).size).toBe(3)
  })

  it('모르는 강도를 빈 문자열로 삼키지 않는다', () => {
    // 빈 문자열이면 구간의 강도가 화면에서 그냥 없어지고, 없어진 줄도 모른다.
    expect(zoneStrengthLabel('EXTREME')).toBe('EXTREME')
  })
})

describe('priceReferenceMethodLabel', () => {
  it('기계 식별자를 사용자에게 그대로 보이지 않는다', () => {
    const label = priceReferenceMethodLabel(PRICE_REFERENCE_METHOD_SWING)

    expect(label).not.toContain(PRICE_REFERENCE_METHOD_SWING)
    expect(label).toBe('스윙 고저점 군집화')
  })

  it('모르는 방식은 모른다고 말하되 원문을 함께 남긴다', () => {
    // 감추면 무엇으로 계산된 구간인지 확인할 방법이 없어진다.
    expect(priceReferenceMethodLabel('VOLUME_PROFILE')).toContain('VOLUME_PROFILE')
  })
})
