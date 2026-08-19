import { createSSRApp } from 'vue'
import { renderToString } from 'vue/server-renderer'
import { describe, expect, it } from 'vitest'

import RatioBar from './RatioBar.vue'

/**
 * ADR-0021 §검증이 `rg withDefaults`로 세던 자리다.
 *
 * 존재를 세는 것으로는 부족하다. 기본값이 빠졌을 때 실제로 나가는 것은 `width:NaN%`이고
 * (`undefined >= 1`이 false라 `Math.max(0, undefined)`로 흘러든다), 그러면 **관리자 진행률
 * 막대가 아무 오류 없이 사라진다.** 같이 falsy가 된 `floor`는 채움을 52%에서 50%로 조용히
 * 갈라 놓는다 — 부재를 찾는 검사로는 그 절반을 못 본다. 그래서 값을 단정한다.
 *
 * DOM 없이 서버 렌더로 확인한다. 이 저장소의 vitest는 `environment: 'node'`이고
 * `vue/server-renderer`는 `vue`가 이미 싣고 있어 **새 의존성이 없다.** 출력에는 스코프 해시
 * (`data-v-…`)가 섞이므로 전체 문자열을 비교하지 않는다.
 */
const render = (props: Record<string, unknown>): Promise<string> =>
  renderToString(createSSRApp(RatioBar, props))

describe('RatioBar', () => {
  it('옵셔널 prop을 생략해도 기본값이 흐른다', async () => {
    const html = await render({ value: 0.5 })

    // `floor` 기본값 true — 0..1을 4..100으로 편다.
    expect(html).toContain('width:52%')
    // `trackRatio` 기본값 1 — 트랙은 인라인 폭을 갖지 않는다(기존 소비처의 DOM 그대로).
    expect(html).toContain('class="ratio-bar" role="img" style=""')
    expect(html).not.toContain('NaN')
    expect(html).not.toContain('undefined')
  })

  it('바닥값을 끄면 0이 0으로 그려진다', async () => {
    // 채움이 이진값인 자리(점수 레인)에서는 바닥값이 거짓말을 한다 — 못 받은 조건에 4%가
    // 남아 "조금은 받았다"로 읽힌다.
    expect(await render({ value: 0, floor: false })).toContain('width:0%')
    expect(await render({ value: 0 })).toContain('width:4%')
  })

  it('트랙 길이가 뜻을 가지면 인라인 폭이 붙는다', async () => {
    expect(await render({ value: 1, trackRatio: 0.5 })).toContain('width:50%')
  })

  it('값이 없으면 아무것도 그리지 않는다', async () => {
    // 0으로 메우면 "가장 작다"로 읽힌다.
    expect(await render({ value: null })).not.toContain('ratio-bar')
  })
})
