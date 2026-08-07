<script setup lang="ts">
/**
 * 라벨 + 컨트롤 한 벌.
 *
 * 같은 CSS가 세 곳(`ConditionEditor`·`ScreenSettingsPopover`·`SaveScreenDialog`)에 따로 적혀
 * 있었고 이미 어긋나 있었다 — 앞의 둘은 `2xs`/`0.1875rem`인데 대화상자만 `sm`/`0.375rem`이라
 * 같은 종류의 폼이 화면마다 다른 밀도로 보였다.
 *
 * `<label>`로 감싸는 것이 요점이다. `ScreenSettingsPopover`는 라벨이 `<span>`이라 컨트롤과
 * **연결되어 있지 않았다** — 화면 낭독기에는 이름 없는 입력 셋이었다. `controlId`를 주면
 * 명시적으로 잇고, 주지 않으면 감싸기로 암묵 연결된다.
 */
defineProps<{
  label: string
  /** 컨트롤의 id. `<label for>`로 명시적으로 잇는다. 감싸기로 충분하면 생략한다. */
  controlId?: string
  /** 컨트롤 아래 한 줄. 없으면 자리를 차지하지 않는다. */
  hint?: string | null
}>()
</script>

<template>
  <label class="form-field" :for="controlId">
    <span class="form-field__label">{{ label }}</span>
    <slot />
    <span v-if="hint" class="form-field__hint">{{ hint }}</span>
  </label>
</template>

<style scoped>
.form-field {
  display: flex;
  flex-direction: column;
  gap: 0.1875rem;
}

.form-field__label {
  font-size: var(--app-text-2xs);
  font-weight: 600;
  letter-spacing: 0.02em;
  color: var(--p-text-muted-color);
}

/* 라벨보다 한 단계 더 뒤. 있으면 좋지만 읽을 차례는 컨트롤 다음이다. */
.form-field__hint {
  font-size: var(--app-text-2xs);
  color: var(--p-text-subtle-color);
}
</style>
