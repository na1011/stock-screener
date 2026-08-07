<script setup lang="ts">
import { computed } from 'vue'

import { PICK_INDICATOR, summarizeDraftCondition } from '@/lib/conditionText'
import { directionStyle, indicatorDirection } from '@/lib/direction'
import { useCatalogStore } from '@/stores/catalog'
import type { DraftCondition } from '@/stores/screenDraft'
import { isComplete } from '@/stores/screenDraft'

/**
 * 조건 하나를 한 칸으로.
 *
 * 예전에는 조건 한 줄이 세로 90px에 컨트롤 여섯 개를 가로로 폈다. 460px짜리 Select에
 * "ROE" 세 글자가 들어갔고, 조건이 다섯 개면 화면의 절반이 편집기였다. **읽는 것과 고치는
 * 것을 나누면** 읽기는 칩 하나로 줄고 고치기는 팝오버가 맡는다.
 *
 * 상태가 넷이다. 그중 **미완성**이 이 컴포넌트가 존재하는 이유의 절반이다 — 예전에는
 * 값이 빈 조건이 `completeConditions`에서 조용히 걸러져 실행에서 빠졌고, 화면에 아무
 * 표시가 없었다. 사용자는 자기가 만든 조건이 무시된 줄 모른 채 결과를 봤다.
 */
const props = defineProps<{
  condition: DraftCondition
  /** 팝오버가 열려 있는가. 열린 칩은 눌린 상태로 보여야 어디를 고치는 중인지 안다. */
  active: boolean
  /** 서버가 이 조건을 거부했는가. `invalid-condition`의 `conditionIndex`가 가리킨 것. */
  invalid: boolean
  readonly: boolean
}>()

const catalog = useCatalogStore()

const indicator = computed(() =>
  props.condition.indicatorCode === null
    ? null
    : (catalog.byCode.get(props.condition.indicatorCode) ?? null),
)

const operandIndicator = computed(() =>
  props.condition.operandIndicatorCode === null
    ? null
    : (catalog.byCode.get(props.condition.operandIndicatorCode) ?? null),
)

/** 연산자 한글 라벨의 정본은 서버다. 없으면 자리를 비운다 — 지어내지 않는다. */
const operatorLabel = computed(
  () => catalog.operators.find((op) => op.name === props.condition.operator)?.label ?? null,
)

const complete = computed(() => isComplete(props.condition))

const label = computed(() =>
  summarizeDraftCondition(
    props.condition,
    indicator.value,
    operandIndicator.value,
    operatorLabel.value,
  ),
)

/** 유불리 방향. 지표 코드를 보지 않는다 — 판단은 `lib/direction`이 서버 값만으로 한다. */
const direction = computed(() =>
  indicator.value === null
    ? null
    : directionStyle(
        indicatorDirection({
          higherIsBetter: indicator.value.higherIsBetter,
          percentile: indicator.value.percentile,
        }),
      ),
)
</script>

<template>
  <button
    type="button"
    class="chip"
    :class="{
      'chip--active': active,
      'chip--incomplete': !complete,
      'chip--invalid': invalid,
      'chip--readonly': readonly,
    }"
    :aria-expanded="active"
    :aria-invalid="invalid"
  >
    <span v-if="direction" v-tooltip.top="direction.label" class="chip__marker" aria-hidden="true">{{
      direction.marker
    }}</span>

    <span class="chip__label">{{ label ?? PICK_INDICATOR }}</span>

    <!--
      미완성을 말로 적는다. 점선 테두리만으로는 "아직 덜 됐다"와 "비활성"이 구별되지 않고,
      색만으로는 구별할 수 없는 사용자가 있다.
    -->
    <span v-if="!complete" class="chip__hint">{{ label === null ? '' : '값 필요' }}</span>
    <i v-if="invalid" class="pi pi-exclamation-circle chip__invalid-icon" aria-hidden="true" />
    <i v-else-if="!readonly" class="pi pi-chevron-down chip__caret" aria-hidden="true" />
  </button>
</template>

<style scoped>
.chip {
  display: inline-flex;
  align-items: center;
  gap: 0.3125rem;
  max-width: 100%;
  min-height: 1.75rem;
  padding: 0 0.5rem;
  border: 1px solid var(--p-content-border-color);
  border-radius: var(--p-border-radius-md);
  background: var(--p-surface-800);
  color: var(--p-text-color);
  font: inherit;
  font-size: var(--app-text-xs);
  line-height: 1;
  white-space: nowrap;
  cursor: pointer;
  transition:
    background var(--app-motion-fast) var(--app-ease),
    border-color var(--app-motion-fast) var(--app-ease);
}

.chip:hover {
  border-color: var(--p-surface-600);
  background: var(--p-surface-700);
}

/* 열린 칩은 팝오버의 꼬리다. 어느 것을 고치는 중인지 배경만으로는 약하다. */
.chip--active {
  border-color: var(--p-primary-color);
  background: color-mix(in srgb, var(--p-primary-color) 14%, transparent);
}

/*
 * 미완성은 점선이다. 실행에서 빠진다는 사실을 테두리로 먼저 알리고, 옆의 "값 필요"가
 * 말로 확인해 준다.
 */
.chip--incomplete {
  border-style: dashed;
  border-color: var(--p-surface-600);
  background: transparent;
  color: var(--p-text-muted-color);
}

.chip--invalid {
  border-style: solid;
  border-color: var(--p-red-400);
  background: color-mix(in srgb, var(--p-red-400) 12%, transparent);
  color: var(--p-red-300);
}

.chip--readonly {
  cursor: default;
}

.chip--readonly:hover {
  border-color: var(--p-content-border-color);
  background: var(--p-surface-800);
}

.chip__marker {
  flex: 0 0 auto;
  font-size: var(--app-text-2xs);
  color: var(--p-text-muted-color);
}

.chip__label {
  overflow: hidden;
  text-overflow: ellipsis;
}

.chip__hint {
  flex: 0 0 auto;
  padding: 0 0.25rem;
  border-radius: var(--p-border-radius-sm);
  background: color-mix(in srgb, var(--p-amber-400) 18%, transparent);
  color: var(--p-amber-300);
  font-size: var(--app-text-2xs);
  font-weight: 600;
}

.chip__caret,
.chip__invalid-icon {
  flex: 0 0 auto;
  font-size: var(--app-text-2xs);
  color: var(--p-surface-500);
}

/* 열린 칩의 캐럿은 팝오버가 어디 붙어 있는지 가리킨다. 테두리와 같은 색으로 올린다. */
.chip--active .chip__caret {
  color: var(--p-primary-color);
}

.chip--invalid .chip__invalid-icon {
  color: var(--p-red-300);
}
</style>
