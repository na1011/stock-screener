import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'

import { Theme } from '@primeuix/themes'

import { stripBlockComments, walkSource } from '@/lib/sourceScan'

import { ScreenerPreset } from './preset'

/**
 * ADR-0021의 `## 검증`을 실제로 돌리는 것을 만든다.
 *
 * 그 절은 손으로 치는 `rg` 명령이었고 **아무것도 그것을 돌리지 않았다.** 그 공백에서
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

/**
 * `--p-*` **참조**. 폴백이 있어도 센다.
 *
 * IMPORTANT — 여기가 위 `BARE_REFERENCE`와 갈리는 자리다. 앱 변수는 폴백이 있으면 선언이
 * 없어도 정상이지만, `--p-*`는 **그 이름이 PrimeVue에 존재해야 한다는 사실 자체**를 폴백이
 * 가린다. 실제로 `--p-warn-color`가 `var(--p-warn-color, var(--p-text-color))`로 불렸고,
 * 선언은 무효가 되지 않은 채 경고 문구의 색만 조용히 본문색으로 떨어져 있었다. 무폴백만
 * 세는 검사였으면 그 형태를 지금도 못 잡는다.
 */
const PRIME_REFERENCE = /var\(\s*(--p-[\w-]+)\s*[,)]/g

/** 생성된 CSS 안의 `--p-*` **선언**. */
const PRIME_DECLARATION = /(--p-[\w-]+)\s*:/g

/**
 * PrimeVue가 실제로 내는 이름 집합.
 *
 * ADR-0021은 원래 이것을 **보지 않기로** 적어 두었다 — "생성 집합이 PrimeVue 내부 API에
 * 물려 있어, 거기 기댄 검사는 의존성을 올릴 때 원인 불명으로 빨개진다." 셋이 그 사유를
 * 무력화한다. (1) `Theme`은 `@primeuix/themes`의 **공개 export**다. (2) 의존성이 정확
 * 버전으로 고정돼 있어(ADR-0019) 올릴 때 빨개지는 것은 확인해야 할 신호다. (3) 아래 검사가
 * **집합 크기를 먼저 단정해** "원인 불명"을 없앤다 — 생성이 무너지면 참조 39개가 전부
 * 미정의로 뜨는 대신 그 한 줄이 실패한다.
 *
 * `options`는 `main.ts`가 넘기는 것과 `Theme.defaults.options`가 채우는 기본값을 합친
 * 것이다. `darkModeSelector`는 다크 값을 감싸는 **선택자**만 정하고 이름은 바꾸지 않으므로
 * 이 검사의 결과에 영향이 없다 — 그 값이 `main.ts`와 맞는지는 `preset.spec.ts`가 본다.
 */
function generated(): { common: Set<string>; component: Set<string> } {
  Theme.setTheme({
    preset: ScreenerPreset,
    options: { prefix: 'p', darkModeSelector: '.app-dark', cssLayer: false },
  })

  const common = new Set<string>()
  const component = new Set<string>()
  const collect = (into: Set<string>, css: unknown): void => {
    for (const name of captures(String(css ?? ''), PRIME_DECLARATION)) into.add(name)
  }

  const shared = Theme.getCommon('', {})
  collect(common, shared.primitive.css)
  collect(common, shared.semantic.css)
  collect(common, shared.global.css)

  // 컴포넌트 토큰은 **그 컴포넌트 스타일이 로드된 뒤에만** 존재한다. 그래서 공통과 따로
  // 모아 두고, 실패 메시지에서 "없는 이름"과 "아직 없는 이름"을 가르는 데에만 쓴다.
  for (const name of Object.keys(ScreenerPreset.components ?? {})) {
    collect(component, Theme.getComponent(name, {}).css)
  }

  return { common, component }
}

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

function read(accept: (name: string) => boolean): Source[] {
  return walkSource(SRC, accept).map((path) => ({
    file: path.slice(SRC.length),
    text: stripBlockComments(readFileSync(path, 'utf8')),
  }))
}

const files = walkSource(SRC, SCANNED)
const sources: Source[] = read(SCANNED)

/**
 * `.spec.ts`까지 훑는 두 번째 목록.
 *
 * 아래 「값 색조 매핑의 정본」이 이것을 쓴다 — `band.spec.ts`·`unavailableReason.spec.ts`가
 * 그 함수를 부르는 것이 **정상이고 기대 목록에 들어 있다.** 위 스캐너들이 `.spec.ts`를
 * 제외하는 것은 정규식과 실패 메시지가 자기 자신을 물기 때문이며(같은 파일 위쪽에 이유가
 * 있다), 여기서는 검사하는 낱말을 조각으로 지어 그 문제를 피한다.
 */
const specSources: Source[] = read(
  (name) => name.endsWith('.vue') || name.endsWith('.css') || name.endsWith('.ts'),
)

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
    // 것이 정상이므로 여기서 세면 전부 위반이 된다. 그 이름들이 **PrimeVue에는 있는가**는
    // 아래 「PrimeVue가 내지 않는 `--p-*`」가 따로 묻는다.
    const strays = scan((line) =>
      captures(line, BARE_REFERENCE).filter(
        (name) => !name.startsWith('--p-') && !declared.has(name),
      ),
    )

    expect(format(strays)).toEqual([])
  })

  it('PrimeVue가 내지 않는 `--p-*`를 참조하지 않는다', () => {
    const { common, component } = generated()

    // 자기검사를 먼저 둔다. 이것이 없으면 생성이 무너졌을 때 참조 전부가 "미정의"로 떠,
    // 원인이 앱이 아니라 검사 쪽이라는 사실이 실패 목록에 묻힌다.
    expect(
      common.size,
      'PrimeVue가 공통 토큰을 이 방식으로 더 이상 내지 않는다 — 앱이 아니라 이 검사를 고쳐라',
    ).toBeGreaterThan(300)

    const strays = scan((line) =>
      captures(line, PRIME_REFERENCE)
        .filter((name) => !common.has(name))
        .map((name) =>
          component.has(name)
            ? `${name} — 컴포넌트 토큰이다. 그 컴포넌트가 그려지기 전에는 존재하지 않는다`
            : `${name} — PrimeVue가 내지 않는 이름이다`,
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
    // 그것까지 세면 검사가 정본을 위반으로 부르며 늘 빨개진다 — 아래 소비처 집합 검사가
    // `components/`·`views/`만 훑는 것도 같은 함정을 피하려는 것이다.
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

/**
 * 값 색조를 **쓰는** 곳. 정의하는 `theme/preset.ts`와 그것을 재는 spec은 세지 않는다 —
 * 세면 검사가 자기 자신을 물고 늘 빨개진다(ADR-0021이 그 함정을 적어 두었다).
 */
const TONE_SCOPE = /^(components|views)\//

/**
 * 값 색조 매핑을 **부르는** 곳. 이름만 조각으로 지어 이 파일 자신이 걸리지 않게 한다 —
 * `.spec.ts`를 훑는 검사라 리터럴로 적으면 스스로를 센다.
 */
const BAND_READERS = new RegExp(
  ['readBand', 'toneForPosition', 'matchBandIndex'].map((name) => `${name}\\(`).join('|'),
)

const filesUsing = (list: Source[], test: (text: string) => boolean): string[] =>
  list
    .filter((source) => test(source.text))
    .map((source) => source.file)
    .sort()

describe('색 채널과 구조 계약 (ADR-0021)', () => {
  /**
   * 판정 `Tag`와 값 색조가 한 줄에 섞이지 않았는가.
   *
   * 초록·빨강이 말해야 하는 것이 둘이라(조건 판정 · 값 구간) 색이 아니라 **형태와 자리**로
   * 갈랐다. `components/result/` 아래가 나오면 판정 알약과 값 색조가 한 줄에 놓인 것이다.
   * `StockHeader`는 시세 등락이고, 그 화면에서는 초록·빨강이 여전히 한 뜻씩만 갖는다.
   */
  it('값 색조는 판정 Tag가 없는 화면에만 있다', () => {
    expect(
      filesUsing(
        sources.filter((source) => TONE_SCOPE.test(source.file)),
        (text) => text.includes('--p-tone-'),
      ),
    ).toEqual([
      'components/stock/IndicatorCard.vue',
      'components/stock/StockHeader.vue',
      'components/ui/BandMeter.vue',
    ])
  })

  /**
   * 주황을 **쓰는** 곳이 지지·저항 하나뿐인가.
   *
   * 위 검사의 반대편이다. `preset.spec.ts`가 팔레트에 주황이 남지 않았음을 보고, 여기서는
   * 화면이 `--p-orange-*`를 직접 부르지 않는지를 본다. 주황이 다른 뜻으로 한 번 더 쓰이면
   * "확정된 매매 신호가 아니다"라는 경고가 그 순간 힘을 잃는다.
   *
   * `StockHeader`가 **왜 이 색을 안 쓰는지**를 주석에 적어 두었다 — 주석을 세면 정상인
   * 코드가 위반으로 뜨므로, 이 목록은 주석이 지워진 텍스트 위에서만 뜻이 있다.
   */
  it('주황을 직접 쓰는 곳이 지지·저항뿐이다', () => {
    expect(
      filesUsing(
        sources.filter((source) => TONE_SCOPE.test(source.file)),
        (text) => text.includes('--p-orange-'),
      ),
    ).toEqual(['components/stock/PriceReferencePanel.vue'])
  })

  /**
   * 값 색조 매핑의 정본이 하나인가.
   *
   * `BandMeter`는 읽은 결과를 **받기만** 한다 — 두 곳이 계산하면 초록 숫자 아래 빨간 막대가
   * 나온다. `ConditionOutcomes`는 같은 함수를 부르되 라벨만 쓰고 tone은 버리며, 그래서 위
   * 검사의 목록에 나타나지 않는 것이 이 결정이 지켜졌다는 증거다.
   */
  it('값 색조 매핑을 부르는 곳이 정해진 다섯뿐이다', () => {
    expect(filesUsing(specSources, (text) => BAND_READERS.test(text))).toEqual([
      'components/result/ConditionOutcomes.vue',
      'lib/band.spec.ts',
      'lib/band.ts',
      'lib/unavailableReason.spec.ts',
      'views/StockDetailView.vue',
    ])
  })

  /**
   * 조건 내역의 행이 **자기 그리드를 갖지 않는가.**
   *
   * 사라졌다면 고정 트랙으로 되돌아간 것이고, 그때 조건문 트랙이 남는 폭을 전부 먹어
   * 오른쪽에 큰 공백이 함께 돌아온다. 컴파일도 다른 테스트도 통과한다.
   */
  it('조건 내역이 subgrid로 트랙을 공유한다', () => {
    const outcomes = sources.find(
      (source) => source.file === 'components/result/ConditionOutcomes.vue',
    )
    expect(outcomes?.text).toContain('grid-template-columns: subgrid')
  })
})
