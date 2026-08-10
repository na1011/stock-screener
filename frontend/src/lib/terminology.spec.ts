import { readFileSync, readdirSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'

/**
 * 저장된 스크리닝 정의를 "화면"이라 부르지 못하게 막는다(ADR-0027).
 *
 * 영문 식별자는 `screen`이고 한국어 표시어는 "전략"이다. 의도적으로 어긋난 것이라 **되돌려도
 * 컴파일이 통과하고 다른 테스트도 전부 통과한다** — 그래서 여기서만 걸린다. 실제로 그 오역이
 * `V3__condition.sql`의 테이블명에서 시작해 ADR·설계 문서·사용자 노출 문구까지 검증 없이
 * 퍼졌고, "화면"이 UI 뷰와 저장된 조건 세트를 동시에 가리키는 동음이의어가 됐다.
 *
 * IMPORTANT — "화면"이라는 낱말 자체는 금지하지 않는다. 뷰포트·페이지 뜻으로는 여전히 맞는
 * 말이고, 전부 막으면 "좁은 화면"·"화면 폭"까지 걸려 규칙이 곧 무시된다. 아래는 **엔티티
 * 뜻으로만 성립하는 연어**라 오탐이 없다.
 */
const SRC = fileURLToPath(new URL('..', import.meta.url))

/**
 * `.spec.ts`는 스캔하지 않는다.
 *
 * 금지 문구 목록이 이 파일 안에 리터럴로 들어 있어, 제외하지 않으면 스캐너가 자기 자신을
 * 잡아 언제나 실패한다. 픽스처의 문자열은 사용자에게 나가지 않으므로 검사 대상도 아니다.
 */
const SCANNED = (name: string): boolean =>
  (name.endsWith('.vue') || name.endsWith('.ts')) && !name.endsWith('.spec.ts')

/**
 * "유니버스"가 사용자 문구로 돌아오는 것을 막는다.
 *
 * 영문 식별자는 `universe`이고 한국어 표시어는 **"조회 대상"**이다. 직역이 세계관처럼 읽히는
 * 데다, 그 값이 실제로 정하는 것은 스크리닝이 훑을 종목의 범위뿐이다. "대상 시장"도 아니다 —
 * 선택지가 `코스피200`이라 시장이라 부르면 코스피 전체(~800종목)로 읽히는데, 이 앱에서
 * 200이냐 800이냐는 곧 결과의 차이다.
 *
 * 낱말 자체를 막지 않고 연어만 막는다. `lib/universe.ts`가 왜 그 말을 쓰지 않는지를 설명하려면
 * 그 말을 적어야 하고, 전부 막으면 그 설명이 먼저 걸린다.
 */
const FORBIDDEN_UNIVERSE = [
  '대상 유니버스',
  '유니버스 목록',
  '유니버스 선택',
  '유니버스를 골라',
  '유니버스를 고르',
  '특정 유니버스',
  '전체 유니버스',
  '유니버스 리밸런싱',
] as const

/** 엔티티 뜻으로만 쓰이는 연어. UI 뷰 뜻으로는 읽힐 수 없는 조합만 넣는다. */
const FORBIDDEN = [
  '내 화면',
  '화면 관리',
  '화면 이름',
  '화면 목록',
  '화면 삭제',
  '화면 선택',
  '저장한 화면',
  '저장된 화면',
  '화면으로 저장',
  '화면 덮어쓰기',
  '커스텀 화면',
  '남의 화면',
] as const

function walk(dir: string): string[] {
  return readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const path = join(dir, entry.name)
    if (entry.isDirectory()) {
      return walk(path)
    }
    return SCANNED(entry.name) ? [path] : []
  })
}

interface Hit {
  file: string
  line: number
  phrase: string
}

function collectHits(phrases: readonly string[]): { files: number; hits: Hit[] } {
  const files = walk(SRC)
  const hits = files.flatMap((file) =>
    readFileSync(file, 'utf8')
      .split('\n')
      .flatMap((text, index) =>
        phrases
          .filter((phrase) => text.includes(phrase))
          .map((phrase) => ({
            file: file.slice(SRC.length),
            line: index + 1,
            phrase,
          })),
      ),
  )
  return { files: files.length, hits }
}

describe('용어 — 저장된 스크리닝 정의는 "전략"이다', () => {
  const { files, hits } = collectHits(FORBIDDEN)

  it('스캐너가 실제로 파일을 읽는다', () => {
    // 경로가 어긋나 0건을 훑으면 아래 검사가 통과해 버린다 — 통과가 곧 "검사하지 않음"이
    // 되는 상태를 먼저 배제한다. `breakpoints.spec.ts`와 같은 가드다.
    expect(files).toBeGreaterThan(20)
  })

  it('엔티티 뜻의 "화면"이 한 곳도 남지 않았다', () => {
    expect(
      hits.map((it) => `${it.file}:${it.line} — "${it.phrase}"`),
      '저장된 스크리닝 정의는 한국어로 "전략"이다(ADR-0027). "화면"은 UI 뷰만 가리킨다.',
    ).toEqual([])
  })
})

describe('용어 — 조회할 종목의 범위는 "조회 대상"이다', () => {
  const { hits } = collectHits(FORBIDDEN_UNIVERSE)

  it('"유니버스"가 사용자 문구로 남지 않았다', () => {
    expect(
      hits.map((it) => `${it.file}:${it.line} — "${it.phrase}"`),
      '영문 식별자는 universe이고 한국어 표시어는 "조회 대상"이다. 근거는 lib/universe.ts에 있다.',
    ).toEqual([])
  })
})
