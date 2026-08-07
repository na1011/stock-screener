<script setup lang="ts">
import { computed } from 'vue'

/**
 * 0..1 비율을 가로 막대로.
 *
 * 두 자리가 같은 것을 필요로 한다 — 결과 표의 **크기 막대**(이 컬럼 안에서 값이 큰가)와
 * 선정 이유의 **점수 기여 막대**(이 조건이 총점에 얼마나 보탰나)다. 각자 그리면 같은
 * 종류의 막대가 화면마다 다른 두께·색·바닥값으로 나온다.
 *
 * `BandMeter`와 합치지 않는다. 저쪽은 눈금 모드와 유불리 색조를 함께 갖는 별개의 것이고,
 * 합치면 "막대"라는 이름 아래 성격이 다른 두 규칙이 prop으로 갈라진다.
 *
 * 색은 accent 하나뿐이다. 판정 3상태·값 구간과 채널이 겹치지 않게(ADR-0021) **크기만
 * 말하고 유불리는 말하지 않는다.**
 */
const props = defineProps<{
  /** 0..1. 값이 없으면 막대를 그리지 않는다 — 0으로 메우면 "가장 작다"로 읽힌다. */
  value: number | null
  /** 화면 낭독기가 읽을 문장. 옆에 숫자가 함께 있으면 생략한다. */
  ariaLabel?: string
}>()

/**
 * 채움 폭(%).
 *
 * 0..1을 0..100이 아니라 **MIN..100**으로 편다. `Math.max(MIN, …)`으로 자르지 않는 이유는
 * 그러면 하위 구간이 전부 같은 길이로 뭉개지기 때문이다. 바닥을 두는 이유는 0%가
 * "값이 없다"와 구별되지 않기 때문이다.
 */
const MIN_PERCENT = 4

const width = computed(() => {
  if (props.value === null || Number.isNaN(props.value)) {
    return null
  }
  const ratio = Math.min(1, Math.max(0, props.value))
  return MIN_PERCENT + ratio * (100 - MIN_PERCENT)
})
</script>

<template>
  <span v-if="width !== null" class="ratio-bar" role="img" :aria-label="ariaLabel">
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
