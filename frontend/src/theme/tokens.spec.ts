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

/** ADR-0021 §규칙: `.vue`와 `main.css`는 색을 소비만 한다. */
const HEX_LITERAL = /#[0-9a-fA-F]{3,8}\b/

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
  for (const match of text.matchAll(DECLARATION)) declared.add(match[1])
  for (const match of text.matchAll(SET_PROPERTY)) declared.add(match[1])
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
      [...line.matchAll(BARE_REFERENCE)]
        .map((match) => match[1])
        .filter((name) => !name.startsWith('--p-') && !declared.has(name)),
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
      STYLE_FILE(source.file) && HEX_LITERAL.test(line) && !line.includes('var(--p-')
        ? [line.trim()]
        : [],
    )

    expect(format(strays)).toEqual([])
  })
})
