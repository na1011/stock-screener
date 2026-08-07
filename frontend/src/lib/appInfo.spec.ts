import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'

import { APP_NAME } from './appInfo'

describe('APP_NAME', () => {
  it('index.html의 <title>과 같은 이름을 쓴다', () => {
    // index.html은 번들 밖이라 이 상수를 import할 수 없다. 공유가 불가능하면 최소한
    // 어긋남은 잡는다 — 이름을 바꿀 때 한 곳이 남으면 브라우저 탭과 화면 제목이 서로
    // 다른 서비스를 가리킨다.
    const html = readFileSync(new URL('../../index.html', import.meta.url), 'utf8')

    expect(html).toContain(`<title>${APP_NAME}</title>`)
  })
})
