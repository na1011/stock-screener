<script setup lang="ts">
import Button from 'primevue/button'
import Popover from 'primevue/popover'
import { computed, ref } from 'vue'

import type { PercentileGuide } from '@/api/types'
import { directionStyle, indicatorDirection } from '@/lib/direction'

/**
 * 지표 이름 + 유리한 방향 + 설명.
 *
 * MUST — 지표를 값만 나열하지 않는다(frontend.md). 초보 사용자는 PER 8.1이 좋은 값인지 알 수
 * 없다. 서버가 `description`·`interpretation`·`higherIsBetter`를 이미 주므로 쓰지 않을 이유가
 * 없고, 쓰지 않으면 그 필드들이 응답에 있는 의미가 사라진다.
 *
 * 방향 판단은 {@link indicatorDirection}에 맡긴다 — 이 컴포넌트도 지표 코드를 보지 않는다.
 */
const props = defineProps<{
  name: string
  higherIsBetter: boolean | null
  percentile?: PercentileGuide | null
  description?: string | null
  interpretation?: string | null
  /** 도움말 버튼을 감춘다. 표 머리글처럼 자리가 좁은 곳에서 쓴다. */
  compact?: boolean
}>()

const help = ref<InstanceType<typeof Popover> | null>(null)

const direction = computed(() =>
  directionStyle(
    indicatorDirection({ higherIsBetter: props.higherIsBetter, percentile: props.percentile }),
  ),
)

const hasHelp = computed(() => Boolean(props.description) || Boolean(props.interpretation))
</script>

<template>
  <span class="indicator-label">
    <span class="indicator-label__name">{{ name }}</span>
    <span
      v-tooltip.top="direction.label"
      class="indicator-label__marker"
      :aria-label="direction.label"
      >{{ direction.marker }}</span
    >
    <template v-if="hasHelp && !compact">
      <Button
        type="button"
        text
        rounded
        size="small"
        icon="pi pi-info-circle"
        class="indicator-label__help"
        :aria-label="`${name} 설명`"
        @click="help?.toggle($event)"
      />
      <Popover ref="help">
        <div class="indicator-help">
          <h3 class="indicator-help__title">{{ name }}</h3>
          <p v-if="description" class="indicator-help__text">{{ description }}</p>
          <p v-if="interpretation" class="indicator-help__text indicator-help__text--muted">
            {{ interpretation }}
          </p>
          <p v-if="percentile" class="indicator-help__text indicator-help__text--muted">
            {{ percentile.label }}
          </p>
          <p class="indicator-help__direction">{{ direction.marker }} {{ direction.label }}</p>
        </div>
      </Popover>
    </template>
  </span>
</template>

<style scoped>
.indicator-label {
  display: inline-flex;
  align-items: center;
  gap: 0.25rem;
}

/* 표 머리글에서 "PER 백분위"가 "PER / 백분 / 위"로 끊기던 것을 막는다. 컬럼 하한이
   넉넉해도 이것이 없으면 셀 폭이 아니라 단어 단위로 감긴다. */
.indicator-label__name {
  white-space: nowrap;
}

.indicator-label__marker {
  font-size: 0.75rem;
  color: var(--p-text-muted-color);
  cursor: help;
}

.indicator-label__help {
  width: 1.5rem;
  height: 1.5rem;
}

.indicator-help {
  max-width: 22rem;
}

.indicator-help__title {
  font-size: 0.9375rem;
  margin: 0 0 0.5rem;
}

.indicator-help__text {
  margin: 0 0 0.5rem;
  font-size: 0.875rem;
  line-height: 1.5;
}

.indicator-help__text--muted {
  color: var(--p-text-muted-color);
}

.indicator-help__direction {
  margin: 0;
  font-size: 0.8125rem;
  font-weight: 600;
}
</style>
