import { describe, expect, it } from 'vitest'

import Aura from '@primeuix/themes/aura'

import { TONE_FAVORABLE_TEXT, TONE_UNFAVORABLE_TEXT, cobalt, ink } from './preset'

/**
 * 대비비를 자동 검증 대상으로 만든다.
 *
 * ADR-0021의 `## 검증`이 "색이 실제로 읽히는지(대비비)는 자동 검증 대상이 아니다"라고 적어
 * 두었다. 그 공백에서 실제로 결함이 자랐다 — **채운 버튼의 글자색이 카드·레일·바의 배경색과
 * 완전히 같은 hex였다.** Aura 다크 기본값 `contrastColor: {surface.900}`이 살아 있었고,
 * 이 앱이 `surface`를 자체 램프로 덮었기 때문이다. 컴파일도 테스트도 통과했고, 라이트
 * 스킴에는 증상이 없어(그쪽은 `#ffffff` 하드코딩) 아무도 눈치채지 못했다.
 *
 * IMPORTANT — 램프를 여기 옮겨 적지 않고 `preset.ts`에서 import한다. 사본을 검사하면
 * 프리셋을 바꿔도 검사가 통과한다.
 *
 * 알파가 섞인 값(토널 면·hover 오버레이)은 배경 위에 합성한 뒤 잰다. 다크에서 같은 오버레이가
 * 배경마다 다른 결과를 내기 때문에, **다섯 면 전부**에서 확인해야 의미가 있다.
 */

/** WCAG 2.x 상대 광도. */
function luminance(hex: string): number {
  const n = Number.parseInt(hex.slice(1), 16)
  const channel = (c: number): number => {
    const s = c / 255
    return s <= 0.04045 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4
  }
  return (
    0.2126 * channel((n >> 16) & 255) +
    0.7152 * channel((n >> 8) & 255) +
    0.0722 * channel(n & 255)
  )
}

function contrast(a: string, b: string): number {
  const la = luminance(a)
  const lb = luminance(b)
  return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05)
}

/** 반투명 오버레이를 불투명 배경 위에 합성한다. `color-mix(… transparent N%)`의 결과와 같다. */
function over(fg: string, bg: string, alpha: number): string {
  const f = Number.parseInt(fg.slice(1), 16)
  const b = Number.parseInt(bg.slice(1), 16)
  const channel = (shift: number): number =>
    Math.round(alpha * ((f >> shift) & 255) + (1 - alpha) * ((b >> shift) & 255))
  return `#${[16, 8, 0].map((s) => channel(s).toString(16).padStart(2, '0')).join('')}`
}

/**
 * 버튼이 실제로 놓이는 다섯 면.
 *
 * `들린 카드`는 `--app-surface-raised`(surface.0 3%를 900에 섞은 것)의 위쪽 끝이다. 카드
 * 위쪽에 놓인 버튼이 만나는 가장 밝은 면이라 여기가 최악값이 된다.
 */
const SURFACES: Record<string, string> = {
  캔버스: ink[950],
  카드: ink[900],
  팝오버: ink[850],
  들린카드: over(ink[0], ink[900], 0.03),
}

/** `preset.ts`의 `button.colorScheme.dark`가 정한 값들. 바꾸면 여기도 함께 바꾼다. */
const FILLED_BACKGROUND = cobalt[600]
const FILLED_LABEL = ink[0]
const TONAL_BORDER = cobalt[500]
const TONAL_LABEL = cobalt[400]
const TONAL_ALPHA = 0.12
const GHOST_HOVER_ALPHA = 0.16

describe('채운 버튼', () => {
  it('글자가 본문 대비 기준을 넘는다', () => {
    expect(contrast(FILLED_LABEL, FILLED_BACKGROUND)).toBeGreaterThanOrEqual(4.5)
  })

  it('버튼 면이 어느 배경 위에서든 비텍스트 기준을 넘는다', () => {
    const failed = Object.entries(SURFACES)
      .map(([name, bg]) => [name, contrast(FILLED_BACKGROUND, bg)] as const)
      .filter(([, cr]) => cr < 3)
    expect(failed, 'WCAG 1.4.11 — UI 요소는 인접 색과 3:1이어야 한다').toEqual([])
  })

  it('IMPORTANT — 글자색이 어느 면 색과도 같지 않다', () => {
    // 이 결함이 실재했다. Aura 기본값 `contrastColor: {surface.900}`이 곧 패널 배경이라,
    // 버튼 라벨과 카드 배경이 **같은 hex**였다. 되돌아가도 컴파일·다른 테스트는 전부 통과한다.
    //
    // 대비비 검사만으로는 못 잡는다 — `#121722` 라벨도 코발트 위에서는 5.92:1로 기준을
    // 넘기 때문이다. "면으로 쓰는 색을 글자로 쓰지 않는다"를 따로 단정해야 한다.
    expect(Object.values(SURFACES).map((s) => s.toLowerCase())).not.toContain(
      FILLED_LABEL.toLowerCase(),
    )
  })
})

describe('토널 버튼', () => {
  it('테두리가 어느 배경 위에서든 비텍스트 기준을 넘는다', () => {
    // 테두리만 밝히려다 실패한 적이 있다 — `{primary.700}`(2.44)을 400의 45% 혼합으로
    // 바꿨더니 배경 쪽으로 섞이며 2.19로 **내려갔다.** 불투명한 500을 쓰는 이유다.
    const failed = Object.entries(SURFACES)
      .map(([name, bg]) => [name, contrast(TONAL_BORDER, bg)] as const)
      .filter(([, cr]) => cr < 3)
    expect(failed).toEqual([])
  })

  it('라벨이 자기 면 위에서 본문 대비 기준을 넘는다', () => {
    const failed = Object.entries(SURFACES)
      .map(([name, bg]) => {
        const fill = over(TONAL_LABEL, bg, TONAL_ALPHA)
        return [name, contrast(TONAL_LABEL, fill)] as const
      })
      .filter(([, cr]) => cr < 4.5)
    expect(failed).toEqual([])
  })
})

describe('고스트 버튼', () => {
  it('hover가 다섯 면 모두에서 보인다', () => {
    // IMPORTANT — 불투명 `surface.800`을 쓰면 팝오버·들린 카드에서 1.04까지 떨어진다.
    // 그 면들이 이미 `surface.800`에 가깝기 때문이다. 반투명이어야 하는 이유가 이것이고,
    // 이 검사가 그 회귀를 잡는다.
    const failed = Object.entries(SURFACES)
      .map(([name, bg]) => {
        const hovered = over(TONAL_LABEL, bg, GHOST_HOVER_ALPHA)
        return [name, Number(contrast(hovered, bg).toFixed(2))] as const
      })
      .filter(([, cr]) => cr < 1.2)
    expect(failed, 'hover가 배경과 구별되지 않으면 누를 수 있다는 신호가 사라진다').toEqual([])
  })

  it('불투명 surface.800은 이 목적에 맞지 않는다 — 그 사실을 못 박는다', () => {
    // 회귀 방지가 아니라 **근거 보존**이다. 다음 사람이 "hover에 surface.800을 쓰면 되지
    // 않나"라고 물을 때 답이 코드 안에 있어야 한다.
    expect(contrast(ink[800], ink[850])).toBeLessThan(1.2)
  })
})

/**
 * Aura 원시 팔레트에서 `{red.400}` 같은 참조를 푼다.
 *
 * 여기서 hex를 옮겨 적지 않는 이유는 위 IMPORTANT와 같다 — 사본을 검사하면 프리셋을 바꿔도
 * 검사가 통과한다.
 */
function resolve(token: string): string {
  const match = /^\{(\w+)\.(\d+)\}$/.exec(token)
  if (match === null) {
    throw new Error(`팔레트 참조가 아니다: ${token}`)
  }
  const primitive = (Aura as unknown as Record<string, Record<string, Record<string, string>>>)
    .primitive
  const hex = primitive?.[match[1] as string]?.[match[2] as string]
  if (hex === undefined) {
    throw new Error(`Aura 원시 팔레트에 없다: ${token}`)
  }
  return hex
}

/**
 * 값 색조(`--p-tone-*`).
 *
 * 이 검사가 있는 이유는 **아무도 재지 않는 자리였기 때문**이다. 위 세 describe는 버튼만
 * 재므로 색조는 대비가 어떻든 전부 통과한다. 그리고 지표 카드와 **시세 등락**이 함께 쓰는
 * 색이라(ADR-0065) "읽히기는 하는가"가 두 곳에 걸린다.
 */
describe('값 색조', () => {
  it('유리·불리 모두 어느 면 위에서든 본문 대비 기준을 넘는다', () => {
    const failed = [
      ['유리', resolve(TONE_FAVORABLE_TEXT)],
      ['불리', resolve(TONE_UNFAVORABLE_TEXT)],
    ].flatMap(([label, hex]) =>
      Object.entries(SURFACES)
        .map(
          ([name, bg]) =>
            [`${label}/${name}`, Number(contrast(hex as string, bg).toFixed(2))] as const,
        )
        .filter(([, cr]) => cr < 4.5),
    )
    expect(failed).toEqual([])
  })

  it('IMPORTANT — 값 색조가 accent(cobalt)와 같은 칸이 아니다', () => {
    // 같으면 "유리하다"와 "누를 수 있다"가 한 색이 된다.
    for (const token of [TONE_FAVORABLE_TEXT, TONE_UNFAVORABLE_TEXT]) {
      expect(resolve(token).toLowerCase()).not.toBe(cobalt[400].toLowerCase())
    }
  })
})
