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

/*
 * 유불리 방향. 10px 하드코딩이라 사실상 안 보였다 — 토큰 밖의 크기이기도 했다.
 *
 * IMPORTANT — **색을 주지 않는다.** 이것은 "지표의 성질"(높을수록 유리한가)이지 이 종목
 * 값의 좋고 나쁨이 아니다. 색을 주면 바로 아래 미터가 말하는 것과 뜻이 겹쳐 어긋난다(ADR-0021).
 */
.indicator-label__marker {
  font-size: var(--app-text-2xs);
  color: var(--p-text-subtle-color);
  cursor: help;
}

/*
 * 표 머리글에 컬럼마다 하나씩 붙는다. 기본 크기로 두면 여덟 컬럼에서 도움말 버튼이 지표
 * 이름보다 먼저 눈에 들어온다 — 늘 필요한 것이 아니라 한 번 읽고 마는 것이다.
 */
.indicator-label__help {
  width: 1.125rem;
  height: 1.125rem;
  opacity: 0.55;
}

.indicator-label__help:hover {
  opacity: 1;
}

.indicator-label__help :deep(.p-button-icon) {
  font-size: var(--app-text-2xs);
}

.indicator-help {
  max-width: 20rem;
}

.indicator-help__title {
  font-size: var(--app-text-md);
  margin: 0 0 0.375rem;
}

.indicator-help__text {
  margin: 0 0 0.375rem;
  font-size: var(--app-text-xs);
  line-height: 1.5;
}

.indicator-help__text--muted {
  color: var(--p-text-muted-color);
}

.indicator-help__direction {
  margin: 0;
  font-size: var(--app-text-2xs);
  font-weight: 600;
}
</style>
