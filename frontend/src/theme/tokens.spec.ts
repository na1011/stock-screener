import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'

import { walkSource } from '@/lib/sourceScan'

/**
 * ADR-0021의 `## 검증`을 실제로 돌리는 것을 만든다.
 *
 * 그 절은 손으로 치는 `rg` 명령 여덟 줄이었고 **아무것도 그것을 돌리지 않았다.** 그 공백에서
 * 실제로 결함이 자랐다 — `FinancialsPanel.vue`가 어디에도 선언되지 않은 이름
 * (`--surface-card`·`--surface-border`·`--radius-lg`·`--primary-color`·`--text-color-secondary`)만
 * 참조해, **카드 면과 테두리가 통째로 사라진 채** 오래 살아 있었다. `contrast.spec.ts`가 대비비를
 * 두고 밟은 것과 같은 자리다.
 *
 * 폴백 없는 미정의 `var()`는 그 선언을 IACVT로 만든다. **단축이면 더 크게 무너진다** —
 * `border: 1px solid var(--미정의)`는 색만 잃는 것이 아니라 `border-style`까지 unset되어
 * 테두리가 **아예 그려지지 않는다.** 컴파일도 통과하고 `vue-tsc`는 CSS를 읽지 않으므로,
 * 이 검사가 없으면 다음 컴포넌트에서 같은 일이 다시 무증상으로 자란다.
 */
const SRC = fileURLToPath(new URL('..', import.meta.url))

/**
 * `.spec.ts`는 스캔하지 않는다.
 *
 * 아래 정규식과 실패 메시지가 `var(--…)`·`font-size: 0.8rem` 같은 리터럴을 담고 있어,
 * 제외하지 않으면 **스캐너가 자기 자신을 물어** 언제나 실패한다. `terminology.spec.ts`가
 * 같은 이유로 같은 제외를 둔다.
 */
const SCANNED = (name: string): boolean =>
  (name.endsWith('.vue') || name.endsWith('.css') || name.endsWith('.ts')) &&
  !name.endsWith('.spec.ts')

/**
 * 블록 주석은 참조로 세지 않는다.
 *
 * 주석은 "왜 이 값을 안 쓰는지"를 적으려고 그 값을 부른다 — `breakpoints.ts`가
 * `@media (max-width: var(--x))`를 그렇게 인용한다. 그것까지 세면 검사가 늘 빨개져 아무도
 * 안 보게 된다. ADR §검증 1의 주석이 색 리터럴을 두고 같은 판단을 적어 두었다.
 *
 * 줄바꿈만 남기고 지우는 것은 **실패 메시지의 줄 번호를 밀지 않기 위해서다.**
 */
const BLOCK_COMMENT = /\/\*[\s\S]*?\*\//g

/**
 * 커스텀 프로퍼티 **선언**.
 *
 * 앞에 식별자 문자가 붙으면 선언이 아니라 **BEM 수정자**다(`.rail__item--open::before`,
 * `.chip--readonly:hover`). lookbehind 없이 `--x:`만 잡으면 그런 선택자 8줄이 "선언된 변수"로
 * 등록되는데, **과다 수집은 곧 거짓 음성**이고 이 검사의 존재 이유가 그것을 없애는 것이다.
 * `:style="{ '--seg-count': … }"`의 따옴표 형태도 이 하나가 함께 잡는다 — **이름과 `:` 사이의
 * 닫는 따옴표를 넘어야** 한다. 그것을 빠뜨리면 `SegmentedControl`의 런타임 주입 둘이 선언으로
 * 안 잡혀, 정상인 코드가 위반으로 뜬다.
 */
const DECLARATION = /(?<![\w-])(--[a-zA-Z][\w-]*)['"]?\s*:/g

/** `setProperty('--result-viewport', …)` — 이름 뒤가 `:`가 아니라 `,`라 위 정규식이 못 본다. */
const SET_PROPERTY = /setProperty\(\s*['"](--[\w-]+)['"]/g

/** **폴백 없는** 참조만 센다. `var(--x, y)`는 선언이 없어도 무효가 되지 않는다. */
const BARE_REFERENCE = /var\(\s*(--[\w-]+)\s*\)/g

/** ADR-0021 §규칙: 글자 크기는 `--app-text-*`에서만 정의한다. 예외는 없다. */
const FONT_SIZE_LITERAL = /font-size:\s*[0-9]/

/**
 * ADR-0021 §규칙: `.vue`와 `main.css`는 색을 소비만 한다.
 *
 * hex만 보던 동안 `LoginView`의 `box-shadow: … rgb(0 0 0 / 70%)`가 남아 있었다 — **규칙은
 * 처음부터 "hex·rgb·hsl"이라고 적혀 있었고 검사만 절반이었다.** 함수형 표기를 함께 본다.
 *
 * `\b`가 `color-mix(in srgb, …)`를 지킨다. `srgb`의 `s`가 낱말 문자라 `\brgb`가 걸리지 않는다.
 */
const COLOR_LITERAL = /#[0-9a-fA-F]{3,8}\b|\b(?:rgba?|hsla?)\(/

/** 색·크기 규칙이 걸리는 곳은 스타일이다. 토큰의 정본(`theme/preset.ts`)은 대상이 아니다. */
const STYLE_FILE = (file: string): boolean => file.endsWith('.vue') || file.endsWith('.css')

interface Source {
  file: string
  text: string
}

interface Hit {
  where: string
  what: string
}

const files = walkSource(SRC, SCANNED)

const sources: Source[] = files.map((path) => ({
  file: path.slice(SRC.length),
  text: readFileSync(path, 'utf8').replace(BLOCK_COMMENT, (block) =>
    block.replace(/[^\n]/g, ' '),
  ),
}))

/** 선언은 세 경로로 들어온다 — CSS 선언 · `:style` 바인딩 · `setProperty`. */
const declared = new Set<string>()
for (const { text } of sources) {
  for (const name of captures(text, DECLARATION)) declared.add(name)
  for (const name of captures(text, SET_PROPERTY)) declared.add(name)
}

/**
 * 캡처 그룹 1만 모은다.
 *
 * 위 정규식은 전부 그룹을 하나씩 갖지만 `noUncheckedIndexedAccess`가 그것을 모른다. 좁히는
 * 자리를 여기 하나로 모아, 부르는 쪽이 매번 단언으로 우회하지 않게 한다.
 */
function captures(text: string, pattern: RegExp): string[] {
  return [...text.matchAll(pattern)].flatMap((match) =>
    match[1] === undefined ? [] : [match[1]],
  )
}

function scan(pick: (line: string, source: Source) => string[]): Hit[] {
  return sources.flatMap((source) =>
    source.text.split('\n').flatMap((line, index) =>
      pick(line, source).map((what) => ({
        where: `${source.file}:${index + 1}`,
        what,
      })),
    ),
  )
}

const format = (hits: Hit[]): string[] => hits.map((hit) => `${hit.where}  ${hit.what}`)

/**
 * **전역**으로 나가는 스타일 텍스트 — `main.css`와 `.vue`의 `scoped` 없는 `<style>`.
 *
 * 아래 `:root` 접두어 검사가 이것을 필요로 한다. `scoped` 블록은 컴파일러가 `[data-v-…]`를
 * 붙여 특정도가 0,2,0이 되므로 PrimeVue의 0,1,0을 이미 이긴다 — 위험한 것은 전역뿐이다.
 * 지금 해당하는 `.vue`는 `layouts/AppLayout.vue` 하나이고(서랍이 body로 teleport돼 scoped가
 * 닿지 않는다), **아무 검사도 그 블록을 훑지 않았다.**
 */
const STYLE_BLOCK = /<style(?![^>]*\bscoped\b)[^>]*>([\s\S]*?)<\/style>/g

function globalStyles(): Source[] {
  return sources.flatMap((source) => {
    if (source.file.endsWith('.css')) {
      return [source]
    }
    if (!source.file.endsWith('.vue')) {
      return []
    }
    // 줄 번호를 보존한다. 블록 밖을 공백으로 지워야 실패 메시지의 `파일:줄`이 소스와 맞는다.
    const blanked = source.text.replace(/[^\n]/g, ' ')
    let text = blanked
    for (const match of source.text.matchAll(STYLE_BLOCK)) {
      const body = match[1]
      if (body === undefined || match.index === undefined) continue
      const start = match.index + match[0].length - body.length - '</style>'.length
      text = text.slice(0, start) + body + text.slice(start + body.length)
    }
    return text.trim() === '' ? [] : [{ file: source.file, text }]
  })
}

/**
 * 규칙 하나의 선택자 텍스트. `}`로 자른 조각에서 **가장 안쪽** 선택자만 꺼낸다.
 *
 * `@media (…) { :root { … }`처럼 조각 안에 `{`가 둘일 수 있다. 앞의 `{`까지 포함하면
 * `@media` 문자열이 선택자로 섞여, `:root`로 시작하는지를 묻는 검사가 헛돈다.
 */
function selectorOf(chunk: string): string | null {
  const open = chunk.lastIndexOf('{')
  if (open < 0) return null
  return chunk.slice(chunk.lastIndexOf('{', open - 1) + 1, open)
}

/** 선택자의 클래스 성분. 특정도를 재는 것이 아니라 **몇 개인지만** 센다. */
const CLASS_COMPONENT = /\.(-?[_a-zA-Z][\w-]*)/g

describe('디자인 토큰 (ADR-0021)', () => {
  it('스캐너가 실제로 파일을 읽는다', () => {
    // 경로가 어긋나 0건을 훑으면 아래 검사가 전부 통과해 버린다 — 통과가 곧 "검사하지 않음"이
    // 되는 상태를 먼저 배제한다. `breakpoints.spec.ts`·`terminology.spec.ts`와 같은 가드다.
    expect(files.length).toBeGreaterThan(20)
    expect(declared.size).toBeGreaterThan(20)
  })

  it('선언되지 않은 변수를 폴백 없이 참조하지 않는다', () => {
    // `--p-*`는 PrimeVue가 소유한다(ADR-0021: "`--p-*`를 소비만 한다"). 앱이 선언하지 않는
    // 것이 정상이므로 여기서 세면 전부 위반이 된다.
    const strays = scan((line) =>
      captures(line, BARE_REFERENCE).filter(
        (name) => !name.startsWith('--p-') && !declared.has(name),
      ),
    )

    expect(format(strays)).toEqual([])
  })

  it('글자 크기를 rem 리터럴로 적지 않는다', () => {
    const strays = scan((line, source) =>
      source.file.endsWith('.vue') && FONT_SIZE_LITERAL.test(line) ? [line.trim()] : [],
    )

    expect(format(strays)).toEqual([])
  })

  it('스타일에 색 리터럴을 적지 않는다', () => {
    // **스타일 파일만 본다.** `theme/preset.ts`는 색의 정본이라 hex가 있는 것이 정상이고,
    // 그것까지 세면 검사가 정본을 위반으로 부르며 늘 빨개진다. ADR §검증 3-1의 주석이
    // 같은 함정을 "검사가 자기 자신을 물고"라고 적어 두었다.
    // 폴백(`var(--p-…, #…)`)은 ADR §규칙이 명시한 예외다.
    const strays = scan((line, source) =>
      STYLE_FILE(source.file) && COLOR_LITERAL.test(line) && !line.includes('var(--p-')
        ? [line.trim()]
        : [],
    )

    expect(format(strays)).toEqual([])
  })

  /**
   * ADR-0021 §규칙: PrimeVue 주입 CSS를 이기는 `:root` 접두어.
   *
   * PrimeVue는 자기 CSS를 런타임에 `<head>`로 넣으므로 `main.css`보다 **항상 뒤**에 온다.
   * 같은 특정도면 뒤가 이긴다. 접두어 없이 적혀 있던 `font-size: 0.875rem` 규칙은 **한 번도
   * 적용된 적이 없었고** 그 사실이 화면 어디에도 드러나지 않았다.
   *
   * 클래스 성분이 **하나뿐인** `.p-` 규칙만 요구한다. 그것이 PrimeVue의 컴포넌트 규칙과
   * 동률이 되는 형태이기 때문이다. 둘 이상이면 대개 이기지만 **항상은 아니다** —
   * `main.css`의 `.p-inputnumber > .p-inputnumber-input`은 PrimeVue의
   * `.p-inputnumber-fluid .p-inputnumber-input`과 동률이고 **지는 것이 의도**라, 이 검사가
   * 요구하지 않는 자리에 그 판단이 주석으로 남아 있다. 특정도를 완전히 모형화하지 않는
   * 대신 그 경계를 여기 적어 둔다.
   */
  it('전역 스타일의 `.p-` 규칙이 PrimeVue를 이기는 접두어를 갖는다', () => {
    const strays = globalStyles().flatMap((source) => {
      let offset = 0
      return source.text.split('}').flatMap((chunk) => {
        const start = source.text.slice(0, offset).split('\n').length
        offset += chunk.length + 1
        const selector = selectorOf(chunk)
        if (selector === null) return []
        // 선택자가 열리는 줄. 조각의 끝(`}`)을 가리키면 여러 줄짜리 선택자 목록에서
        // 지목이 위반 지점에서 멀어진다.
        const line = start + chunk.slice(0, chunk.lastIndexOf('{')).split('\n').length - 1
        return selector
          .split(',')
          .map((one) => one.trim())
          .filter((one) => {
            const classes = [...one.matchAll(CLASS_COMPONENT)].flatMap((m) =>
              m[1] === undefined ? [] : [m[1]],
            )
            return (
              classes.length === 1 &&
              classes[0]?.startsWith('p-') === true &&
              !one.startsWith(':root')
            )
          })
          .map((one) => ({
            where: `${source.file}:${line}`,
            what: `${one} — PrimeVue 주입 CSS와 동률이라 적용되지 않는다. \`:root\`를 앞에 붙여라`,
          }))
      })
    })

    expect(format(strays)).toEqual([])
  })
})
