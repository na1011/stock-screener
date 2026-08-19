<script setup lang="ts">
import { computed } from 'vue'

/**
 * 0..1 비율을 가로 막대로.
 *
 * 세 자리가 같은 것을 필요로 한다 — 결과 표의 **크기 막대**(이 컬럼 안에서 값이 큰가),
 * 선정 이유의 **점수 레인**(트랙 길이가 배점, 채움이 획득), 관리자 배치 이력의
 * **진행 막대**(계획 대비 얼마나 처리했나)다. 각자 그리면 같은 종류의 막대가 화면마다
 * 다른 두께·색·바닥값으로 나온다.
 *
 * 셋이 같은 것은 두께·색이고, `trackRatio`와 `floor`로 **갈리는 것**은 트랙이 폭 전부를
 * 쓰는가와 0에 흔적을 남기는가다. 규칙의 정본은 소비처가 아니라 여기 있다.
 *
 * `BandMeter`와 합치지 않는다. 저쪽은 눈금 모드와 유불리 색조를 함께 갖는 별개의 것이고,
 * 합치면 "막대"라는 이름 아래 성격이 다른 두 규칙이 prop으로 갈라진다.
 *
 * 색은 accent 하나뿐이다. 판정 3상태·값 구간과 채널이 겹치지 않게(ADR-0021) **크기만
 * 말하고 유불리는 말하지 않는다.**
 */
const props = withDefaults(
  defineProps<{
    /** 0..1. 값이 없으면 막대를 그리지 않는다 — 0으로 메우면 "가장 작다"로 읽힌다. */
    value: number | null
    /**
     * 트랙 **자체가** 차지할 폭(0..1). 기본 1은 주어진 폭 전부다.
     *
     * 1보다 작게 주면 트랙의 **길이가 뜻을 갖는다.** 선정 이유의 점수 레인이 그렇게 쓴다 —
     * 트랙 길이가 배점이고 채움이 획득이라, 배점 3점 조건의 트랙이 1점 조건의 3배다.
     */
    trackRatio?: number
    /** 값 0에 흔적을 남길 것인가. {@link MIN_PERCENT} 참조. */
    floor?: boolean
    /** 화면 낭독기가 읽을 문장. 옆에 숫자가 함께 있으면 생략한다. */
    ariaLabel?: string
  }>(),
  // IMPORTANT — 타입 전용 `defineProps<…>()`는 기본값을 줄 수단이 없다. 옵셔널 prop만
  // 더하고 여기를 빼면 기존 소비처 셋에서 `undefined`가 흘러 `width: NaN%`가 나가고
  // (`undefined >= 1`이 false라 `Math.max(0, undefined)`로 떨어진다), 관리자 진행률 막대가
  // **아무 오류 없이 사라진다.** 같이 falsy가 된 `floor`는 채움을 52%에서 50%로 조용히
  // 갈라 놓는다 — 증상이 둘이라 `undefined`만 찾으면 어느 쪽도 못 본다.
  // 실측은 `RatioBar.spec.ts`가 서버 렌더로 다시 낸다.
  { trackRatio: 1, floor: true },
)

/**
 * 채움의 바닥값(%).
 *
 * 0..1을 0..100이 아니라 **MIN..100**으로 편다. `Math.max(MIN, …)`으로 자르지 않는 이유는
 * 그러면 하위 구간이 전부 같은 길이로 뭉개지기 때문이다. 바닥을 두는 이유는 컬럼 안에서
 * 가장 작은 값이 흔적도 없이 사라지면 훑는 눈에 "여기엔 아무것도 없다"로 읽히기 때문이다.
 *
 * IMPORTANT — 그래서 `floor`는 **끌 수 있어야 한다.** 채움이 0 아니면 1인 이진값인 자리
 * (선정 이유의 점수 레인)에서는 바닥값이 거짓말을 한다 — 못 받은 조건에 4%가 남아
 * "조금은 받았다"로 읽힌다. 거기서는 빈 트랙이 곧 전하려는 메시지다.
 *
 * 두 소비처의 판단이 갈린 것이지 한쪽이 틀린 것이 아니다. `ResultTable`의 크기 막대는
 * 켜고, 점수 레인은 끈다.
 */
const MIN_PERCENT = 4

const width = computed(() => {
  if (props.value === null || Number.isNaN(props.value)) {
    return null
  }
  const ratio = Math.min(1, Math.max(0, props.value))
  if (!props.floor) {
    return ratio * 100
  }
  return MIN_PERCENT + ratio * (100 - MIN_PERCENT)
})

/** 트랙이 폭 전부를 쓰면 인라인 스타일을 붙이지 않는다 — 기존 소비처의 DOM이 그대로다. */
const trackStyle = computed(() =>
  props.trackRatio >= 1 ? undefined : { width: `${Math.max(0, props.trackRatio) * 100}%` },
)
</script>

<template>
  <span v-if="width !== null" class="ratio-bar" role="img" :aria-label="ariaLabel" :style="trackStyle">
    <span class="ratio-bar__fill" :style="{ width: `${width}%` }" />
  </span>
</template>

<style scoped>
.ratio-bar {
  display: block;
  height: 3px;
  border-radius: 2px;
  background: var(--p-surface-700);
  overflow: hidden;
}

.ratio-bar__fill {
  display: block;
  height: 100%;
  border-radius: inherit;
  background: color-mix(in srgb, var(--p-primary-color) 80%, transparent);
  transition: width var(--app-motion-base) var(--app-ease);
}
</style>
