<script setup lang="ts" generic="T">
import SelectButton from 'primevue/selectbutton'
import { computed } from 'vue'

/**
 * 두셋 중 하나를 고르는 토글.
 *
 * `SelectButton`을 세 곳(`ConditionEditor`의 비교 대상, `ScreenSettingsPopover`의 판정 방식,
 * `SaveScreenDialog`의 저장 방식)이 **같은 prop 네 개를 복붙**해 쓰고 있었다. 그보다 나쁜 것은
 * 폭이었다 — 옆의 `Select`·`InputNumber`는 전부 `fluid`인데 `SelectButton`만 `fluid`를 받지 않아
 * 혼자 내용 폭(≈95px)만 차지했고, 그래서 팝오버의 필드 하나만 짧게 잘려 보였다.
 *
 * 선택 표시를 배경 갈아끼우기가 아니라 **움직이는 썸**으로 한다. 즉시 갈아끼워지면 무엇이
 * 바뀌었는지 눈이 따라가지 못하고, 두 항목 중 어느 쪽이 방금 켜졌는지는 위치가 가장 잘 말한다.
 */
const props = defineProps<{
  modelValue: T
  /** `readonly`로 두지 않는다 — PrimeVue의 `options`가 `any[]`라 대입되지 않는다. */
  options: { label: string; value: T }[]
  /** 라벨이 `<span>`이라 컨트롤과 이어지지 않는 자리에 준다. `<button>`은 labelable이 아니다. */
  ariaLabel?: string
}>()

const emit = defineEmits<{ 'update:modelValue': [value: T] }>()

/**
 * 썸이 설 자리. 못 찾으면 0이다.
 *
 * `-1`을 그대로 쓰면 썸이 트랙 왼쪽 밖으로 나간다. `allow-empty="false"`라 값은 항상 있지만,
 * 스토어가 목록에 없는 값을 들고 있는 순간(옵션이 서버에서 오는 경우)이 있을 수 있다.
 */
const activeIndex = computed(() => {
  const found = props.options.findIndex((option) => option.value === props.modelValue)
  return found < 0 ? 0 : found
})
</script>

<template>
  <SelectButton
    class="segmented"
    :style="{ '--seg-count': options.length, '--seg-index': activeIndex }"
    :model-value="modelValue"
    :options="options"
    option-label="label"
    option-value="value"
    :allow-empty="false"
    size="small"
    :aria-label="ariaLabel"
    @update:model-value="(value: T) => emit('update:modelValue', value)"
  />
</template>

<style scoped>
/*
 * 트랙. 입력 배경(950)을 쓰는 이유는 옆에 서는 것이 `Select`·`InputNumber`이기 때문이다 —
 * 같은 줄의 컨트롤이 서로 다른 바탕이면 하나만 눌린 것처럼 보인다.
 */
.segmented {
  position: relative;
  display: flex;
  width: 100%;
  padding: 2px;
  border: 1px solid var(--p-form-field-border-color);
  border-radius: var(--p-border-radius-md);
  background: var(--p-form-field-background);
}

/*
 * 썸. 칸이 모두 같은 폭이므로 한 칸 이동은 썸 자기 폭의 100%와 정확히 같다.
 * `--seg-count`·`--seg-index`는 위 `:style`이 넣는다.
 */
.segmented::after {
  content: '';
  position: absolute;
  top: 2px;
  bottom: 2px;
  left: 2px;
  width: calc((100% - 4px) / var(--seg-count));
  transform: translateX(calc(var(--seg-index) * 100%));
  border-radius: calc(var(--p-border-radius-md) - 1px);
  background: var(--p-surface-700);
  transition: transform var(--app-motion-base) var(--app-ease);
  pointer-events: none;
}

/* 칸을 균등 분할한다. `min-width: 0`이 없으면 긴 라벨 하나가 나머지를 밀어낸다. */
.segmented :deep(.p-togglebutton) {
  position: relative;
  z-index: 1;
  flex: 1 1 0;
  min-width: 0;
  padding: 0;
  border: 0;
  border-radius: calc(var(--p-border-radius-md) - 1px);
  background: transparent;
}

/*
 * preset이 `togglebutton.content.checkedBackground`로 켜진 칸을 칠한다(ADR-0021). 여기서는
 * 썸이 그 일을 하므로 벗긴다 — 두 겹으로 칠하면 썸이 움직이는 것이 보이지 않는다.
 */
.segmented :deep(.p-togglebutton-content) {
  flex: 1 1 auto;
  justify-content: center;
  min-width: 0;
  padding: 0.3125rem 0.5rem;
  background: transparent;
  transition: color var(--app-motion-fast) var(--app-ease);
}

.segmented :deep(.p-togglebutton-label) {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.segmented :deep(.p-togglebutton:not(.p-togglebutton-checked)) {
  color: var(--p-text-muted-color);
}

.segmented :deep(.p-togglebutton:not(.p-togglebutton-checked):hover) {
  color: var(--p-text-color);
}
</style>
