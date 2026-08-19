import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

import { Theme } from '@primeuix/themes'
import { describe, expect, it } from 'vitest'

import { stripBlockComments } from '@/lib/sourceScan'

import { CONTROL, ScreenerPreset } from './preset'

/**
 * ADR-0021이 **값들 사이의 짝**으로 세워 둔 것을 검사한다.
 *
 * `tokens.spec.ts`가 "소스에 무엇이 적혀 있으면 안 되는가"를 훑는 반면 여기는 "떨어져 있는
 * 값 둘이 여전히 맞는가"를 묻는다. 셋 다 손으로 치는 `rg`였고 아무도 돌리지 않았다 —
 * 그중 컨트롤 높이는 `rg 'line-height: 1.4'`가 **body의 `1.45`도 잡아** 진짜가 사라져도
 * 통과하는 상태였다. 그래서 값을 세는 대신 **높이를 계산한다.**
 */
const html = readFileSync(fileURLToPath(new URL('../../index.html', import.meta.url)), 'utf8')
const bootstrap = readFileSync(fileURLToPath(new URL('../main.ts', import.meta.url)), 'utf8')
const css = stripBlockComments(
  readFileSync(fileURLToPath(new URL('../assets/main.css', import.meta.url)), 'utf8'),
)

/** CSS 한 뭉치를 규칙으로 가른다. `@media` 안쪽도 **가장 안쪽 선택자**로 잡힌다. */
function rules(text: string): { selectors: string[]; body: string }[] {
  return text.split('}').flatMap((chunk) => {
    const open = chunk.lastIndexOf('{')
    if (open < 0) {
      return []
    }
    const selector = chunk.slice(chunk.lastIndexOf('{', open - 1) + 1, open)
    return [
      {
        selectors: selector.split(',').map((one) => one.trim()),
        body: chunk.slice(open + 1),
      },
    ]
  })
}

const allRules = rules(css)

/** `0.8125rem` → `13`. 브라우저와 같이 루트 16px을 가정한다. */
function px(rem: string): number {
  const value = Number(rem.replace('rem', ''))
  expect(Number.isFinite(value), `rem 값을 못 읽었다: ${rem}`).toBe(true)
  return value * 16
}

/** `:root`가 선언한 `--app-*` 상수. 이 파일에는 한 번씩만 나온다. */
function appToken(name: string): string {
  const match = css.match(new RegExp(`${name}:\\s*([^;]+);`))
  expect(match, `${name} 선언을 못 찾았다`).not.toBeNull()
  return (match?.[1] ?? '').trim()
}

/** 다크 스킴에서 실제로 나가는 컴포넌트 토큰. 라이트 값이 앞에 함께 있어 잘라내야 한다. */
function darkTokens(component: string): { name: string; value: string }[] {
  const generated = String(Theme.getComponent(component, {}).css ?? '')
  const start = generated.indexOf('.app-dark')
  if (start < 0) {
    return []
  }
  return [...generated.slice(start).matchAll(/(--p-[\w-]+)\s*:\s*([^;]*)/g)].flatMap((match) =>
    match[1] === undefined || match[2] === undefined
      ? []
      : [{ name: match[1], value: match[2].trim() }],
  )
}

Theme.setTheme({
  preset: ScreenerPreset,
  options: { prefix: 'p', darkModeSelector: '.app-dark', cssLayer: false },
})

describe('다크 한 벌 (ADR-0021)', () => {
  /**
   * `index.html`의 클래스와 `main.ts`의 선택자가 **서로 맞는가.**
   *
   * `rg`는 둘을 따로 봤다. 한쪽만 바뀌는 것이 진짜 회귀인데, 그때 `darkModeSelector`가 걸리지
   * 않아 **손보지 않은 Aura 라이트 팔레트**로 떨어진다. 컴파일도 다른 테스트도 통과한다.
   */
  it('다크 선택자와 html 클래스가 맞는다', () => {
    const classes = (html.match(/<html[^>]*\sclass="([^"]*)"/)?.[1] ?? '').split(/\s+/)
    const selector = bootstrap.match(/darkModeSelector:\s*'([^']+)'/)?.[1]

    expect(selector, "main.ts에 darkModeSelector가 없다 — 기본값 'system'으로 떨어진다").toBeDefined()
    expect(selector?.startsWith('.'), `클래스 선택자가 아니다: ${selector}`).toBe(true)
    expect(classes).toContain((selector ?? '').slice(1))
  })

  /**
   * UA가 그리는 것(폼 컨트롤 기본, 스크롤바)까지 다크인가.
   *
   * `darkModeSelector`는 `--p-*`만 정한다. 이 메타가 빠지면 앱 색은 그대로인데 UA가 그리는
   * 부분만 라이트로 돌아간다 — 화면 대부분이 멀쩡해 눈으로 알아채기 어렵다.
   */
  it('color-scheme이 다크로 고정돼 있다', () => {
    expect(html).toMatch(/<meta\s+name="color-scheme"\s+content="dark"/)
  })
})

describe('색 예산 (ADR-0021)', () => {
  /**
   * 주황은 지지·저항 참고정보 **전용**이다.
   *
   * Aura는 `warn`을 orange로 둔다. 같은 색이 되면 "판정하지 못했다"와 "확정된 매매 신호가
   * 아니다"가 화면에서 구별되지 않는다 — 오해가 금전 손실로 이어지는 자리다.
   *
   * 조건별로 세던 `rg`는 `Tag`의 `root` 한 줄만 봤고, 그래서 `Button`의 **text·outlined
   * 변형과 포커스 링**이 주황으로 남아 있었다. 소비처가 없어 화면에 안 떴을 뿐이다.
   */
  it('다크 스킴 어디에도 주황이 남아 있지 않다', () => {
    const strays = Object.keys(ScreenerPreset.components ?? {}).flatMap((component) =>
      darkTokens(component)
        .filter((token) => token.value.includes('orange'))
        .map((token) => `${component}  ${token.name} = ${token.value}`),
    )

    expect(strays).toEqual([])
  })

  /**
   * 위 검사가 **비어서** 통과하는 것을 막는다.
   *
   * warn 토큰을 통째로 지워도 "주황이 없다"는 참이 된다. 실제로 amber를 쓰고 있는지 여기서
   * 따로 단정한다.
   */
  it('Tag와 Button의 warn이 amber다', () => {
    for (const component of ['tag', 'button']) {
      const warn = darkTokens(component).filter((token) => token.name.includes('warn'))
      expect(warn.length, `${component}에 다크 warn 토큰이 없다`).toBeGreaterThan(0)
      expect(
        warn.some((token) => token.value.includes('amber')),
        `${component}의 warn이 amber를 쓰지 않는다`,
      ).toBe(true)
    }
  })
})

describe('컨트롤 높이 (ADR-0021)', () => {
  /**
   * `preset.ts`의 패딩과 `main.css`의 `line-height`·`--app-text-*`가 **함께** 목표 높이를
   * 만든다. 한쪽만 바꾸면 계산이 어긋나고, 그 어긋남은 Select와 InputNumber가 세로로 붙는
   * 조건 편집기에서만 눈에 띈다.
   *
   * 테두리 2px은 PrimeVue CSS에 `border: 1px solid`로 하드코딩돼 있어 토큰이 없다 —
   * **이 상수만은 여기서 검증할 수단이 없다.**
   */
  const BORDER = 2

  const controlRule = allRules.filter((rule) => rule.selectors.includes(':root .p-inputtext'))

  it('컨트롤 line-height를 거는 규칙이 하나뿐이다', () => {
    // 여러 곳에서 걸면 아래 계산이 어느 값을 쓰는지 알 수 없다. `:root .p-inputtext-sm`처럼
    // 이름이 겹치는 규칙에 계산이 붙는 것도 여기서 막힌다.
    const withLineHeight = allRules.filter(
      (rule) =>
        rule.selectors.some((one) => one.startsWith(':root .p-')) &&
        rule.body.includes('line-height'),
    )
    expect(withLineHeight.length).toBe(1)
    expect(controlRule.length).toBe(1)
  })

  it('글자 크기의 정본이 main.css 하나다', () => {
    // PrimeVue는 `formField.fontSize`를 기본 크기로 **읽지 않는다**(`sm`·`lg`만 소비한다).
    // 실제 13px을 만드는 것은 `:root .p-inputtext { font-size: var(--app-text-sm) }`이므로,
    // 이 짝이 빠지면 `--app-text-*`만 바꿔도 아래 높이 계산이 초록으로 남는다.
    expect(CONTROL.fontSize).toBe(appToken('--app-text-sm'))
    expect(CONTROL.sm.fontSize).toBe(appToken('--app-text-xs'))
    expect(controlRule[0]?.body).toContain('font-size: var(--app-text-sm)')
  })

  it('기본 36px · small 29px이 나온다', () => {
    const lineHeight = Number(controlRule[0]?.body.match(/line-height:\s*([\d.]+)/)?.[1])
    expect(Number.isFinite(lineHeight), 'line-height를 못 읽었다').toBe(true)

    const height = (fontSize: string, paddingY: string): number =>
      Math.round(2 * px(paddingY) + BORDER + px(fontSize) * lineHeight)

    expect(height(CONTROL.fontSize, CONTROL.paddingY)).toBe(36)
    expect(height(CONTROL.sm.fontSize, CONTROL.sm.paddingY)).toBe(29)
  })

  it('아이콘 전용 버튼이 정사각이다', () => {
    // 폭을 높이와 같이 두지 않으면 칩 바와 표 머리글에서 혼자 납작하거나 길쭉해 보인다.
    expect(px(CONTROL.iconOnlyWidth)).toBe(36)
    expect(px(CONTROL.sm.iconOnlyWidth)).toBe(29)
  })
})
