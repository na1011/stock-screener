import { readFileSync, readdirSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'

import { BREAKPOINTS, maxWidthQuery } from './breakpoints'

/**
 * CSS가 토큰 밖의 경계값을 쓰지 못하게 막는다.
 *
 * 미디어 쿼리는 `var()`를 받지 않아 CSS와 JS가 값을 직접 공유할 수 없다(`breakpoints.ts` 참조).
 * 공유가 불가능하면 **어긋남이라도 잡는다** — 특히 `MOBILE`은 JS가 컴포넌트를 갈아 끼우는
 * 경계이면서 CSS가 레이아웃을 바꾸는 경계이기도 해서, 둘이 1px만 달라도 그 사이 폭에서
 * 데스크톱 표가 모바일 레이아웃을 그린다. 그 구간은 좁아서 눈으로는 발견되지 않는다.
 */
const SRC = fileURLToPath(new URL('..', import.meta.url))
const SCANNED_EXTENSIONS = ['.vue', '.css']

/** 미디어 쿼리의 폭 조건. `(max-width: 767px)`·`(min-width: 900px)` 둘 다 잡는다. */
const WIDTH_CONDITION = /\((?:max|min)-width:\s*(\d+)px\s*\)/g

function walk(dir: string): string[] {
  return readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const path = join(dir, entry.name)
    if (entry.isDirectory()) {
      return walk(path)
    }
    return SCANNED_EXTENSIONS.some((ext) => entry.name.endsWith(ext)) ? [path] : []
  })
}

interface Usage {
  file: string
  px: number
}

function collectUsages(): Usage[] {
  return walk(SRC).flatMap((file) => {
    const source = readFileSync(file, 'utf8')
    return [...source.matchAll(WIDTH_CONDITION)].map((match) => ({
      file: file.slice(SRC.length),
      px: Number(match[1]),
    }))
  })
}

describe('화면 폭 경계', () => {
  const tokens = new Set<number>(Object.values(BREAKPOINTS))
  const usages = collectUsages()

  it('스캐너가 실제로 파일을 읽는다', () => {
    // 경로가 어긋나 0건을 훑으면 아래 검사가 전부 통과해 버린다 — 통과가 곧 "검사하지 않음"이
    // 되는 상태를 먼저 배제한다.
    expect(usages.length).toBeGreaterThan(5)
  })

  it('CSS의 모든 경계값이 토큰 안에 있다', () => {
    const strays = usages.filter((it) => !tokens.has(it.px))

    expect(
      strays.map((it) => `${it.file}: ${it.px}px`),
      `lib/breakpoints.ts에 없는 폭이다. 값을 토큰에 맞추거나, 새 경계라면 토큰에 이름을 붙여 추가한다.`,
    ).toEqual([])
  })

  it('표 ↔ 카드 경계가 CSS에도 실제로 쓰이고 있다', () => {
    // 이 값만은 JS와 CSS 양쪽에 있어야 한다. CSS 쪽이 사라지면 토큰 검사는 통과하는데
    // 행 확장 안의 레이아웃이 폭을 따라가지 않는다.
    expect(usages.some((it) => it.px === BREAKPOINTS.MOBILE)).toBe(true)
  })
})

describe('maxWidthQuery', () => {
  it('matchMedia에 넣을 수 있는 형태를 만든다', () => {
    expect(maxWidthQuery(BREAKPOINTS.MOBILE)).toBe('(max-width: 767px)')
  })
})
