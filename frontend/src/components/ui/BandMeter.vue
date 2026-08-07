<script setup lang="ts">
import { computed } from 'vue'

import type { BandReading } from '@/lib/band'

/**
 * 값이 어느 구간에 있는지 보이는 막대.
 *
 * IMPORTANT — 이것이 ADR-0021의 "형태로 가른다"에서 **값 구간 쪽 형태**다. 판정 3상태는
 * `Tag` 알약으로만, 값 구간은 이 막대와 숫자 색조로만 그린다. 같은 초록·빨강을 쓰되 형태가
 * 달라 한 화면에서 섞이지 않으며, 그래서 **판정 `Tag`가 있는 결과 화면에는 두지 않는다.**
 *
 * 판정을 여기서 하지 않는다 — `lib/band.readBand`가 이미 읽은 것을 받기만 한다. 미터와 숫자
 * 색조가 각자 계산하면 언젠가 한쪽만 고쳐지고, 초록 숫자 아래 빨간 막대가 나온다.
 *
 * 색만으로 뜻을 전하지 않는다. 구간 모드에는 자연어 라벨("우수", "과매수")이 함께 있고,
 * 눈금의 **자리**가 색과 무관하게 위치를 말한다.
 */
const props = defineProps<{
  reading: BandReading
  /** 화면 낭독기가 읽을 문장을 만들 때 쓰는 지표 이름. */
  indicatorName: string
}>()

/** 왼쪽이 낮은 값이다. 구간 모드는 눈금, 백분위는 눈금 없는 연속 막대. */
const segments = computed(() =>
  Array.from({ length: props.reading.segments }, (_, index) => index),
)

const toneClass = computed(() => `meter--${props.reading.tone.toLowerCase()}`)

const ariaLabel = computed(() => {
  const where = props.reading.label ?? `${Math.round(props.reading.position * 100)}% 지점`
  return `${props.indicatorName} — ${where}`
})
</script>

<template>
  <div class="meter" :class="toneClass" role="img" :aria-label="ariaLabel">
    <div v-if="segments.length > 0" class="meter__track meter__track--segmented">
      <span
        v-for="index in segments"
        :key="index"
        class="meter__segment"
        :class="{ 'meter__segment--on': index === reading.activeSegment }"
      />
    </div>
    <div v-else class="meter__track">
      <span class="meter__fill" :style="{ width: `${reading.position * 100}%` }" />
    </div>

    <span v-if="reading.label" class="meter__label">{{ reading.label }}</span>
  </div>
</template>

<style scoped>
.meter {
  display: flex;
  align-items: center;
  gap: 0.5rem;
}

.meter__track {
  display: flex;
  flex: 1 1 auto;
  min-width: 0;
  height: 4px;
  border-radius: 2px;
  background: var(--p-tone-track);
  overflow: hidden;
}

.meter__track--segmented {
  /* 눈금 사이를 배경색으로 갈라 개수가 세어진다. 색을 못 보는 경우에도 자리는 남는다. */
  gap: 2px;
  background: transparent;
}

.meter__segment {
  flex: 1 1 0;
  min-width: 0;
  border-radius: 2px;
  background: var(--p-tone-track);
  transition: background var(--app-motion-base) var(--app-ease);
}

.meter__fill {
  border-radius: 2px;
  background: var(--p-tone-neutral-fill);
  transition:
    width var(--app-motion-base) var(--app-ease),
    background var(--app-motion-base) var(--app-ease);
}

.meter__label {
  flex: 0 0 auto;
  font-size: var(--app-text-2xs);
  font-weight: 600;
  color: var(--p-tone-neutral-text);
  white-space: nowrap;
}

/*
 * 색조. 값이 어느 쪽인지는 `lib/band`가 정하고 여기는 그것을 칠하기만 한다. 중립일 때
 * 회색을 쓰는 이유는 "정해진 방향이 없다"(RSI의 과매수 등)와 "유리하지도 불리하지도
 * 않다"가 같은 뜻이기 때문이다.
 */
.meter--favorable .meter__segment--on,
.meter--favorable .meter__fill {
  background: var(--p-tone-favorable-fill);
}

.meter--favorable .meter__label {
  color: var(--p-tone-favorable-text);
}

.meter--unfavorable .meter__segment--on,
.meter--unfavorable .meter__fill {
  background: var(--p-tone-unfavorable-fill);
}

.meter--unfavorable .meter__label {
  color: var(--p-tone-unfavorable-text);
}

.meter--neutral .meter__segment--on,
.meter--neutral .meter__fill {
  background: var(--p-tone-neutral-fill);
}
</style>
