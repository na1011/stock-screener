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

const emit = defineEmits<{ toggle: [] }>()

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

/**
 * 사용자가 **일부러** 뺀 상태로 보이는가.
 *
 * `complete`를 함께 보는 것이 요점이다. 미완성이면서 꺼진 칩은 **미완성으로 그린다** —
 * 실행에서 빠지는 이유가 둘일 때 먼저 알려야 하는 것은 고쳐야 하는 쪽이고, 두 표시를
 * 겹치면 점선과 실선, 앰버와 중립이 한 칩에서 부딪친다.
 */
const excluded = computed(() => complete.value && !props.condition.enabled)

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
  <!--
    루트가 `<span>`인 이유는 안에 버튼이 **둘**이기 때문이다(`<button>` 안의 `<button>`은
    무효다). 부모가 붙이는 `@click`과 `data-chip-key`는 그대로 루트가 받는다 —
    `.chip__main`으로 옮기려면 `inheritAttrs: false`가 필요하고 그러면 onClick까지 함께
    옮겨가, 마커·배지·캐럿 영역을 눌러도 편집기가 열리지 않게 된다.
  -->
  <span
    class="chip"
    :class="{
      'chip--active': active,
      'chip--incomplete': !complete,
      'chip--excluded': excluded,
      'chip--invalid': invalid,
      'chip--readonly': readonly,
    }"
  >
    <button type="button" class="chip__main" :aria-expanded="active" :aria-invalid="invalid">
      <span
        v-if="direction"
        v-tooltip.top="direction.label"
        class="chip__marker"
        aria-hidden="true"
        >{{ direction.marker }}</span
      >

      <span class="chip__label">{{ label ?? PICK_INDICATOR }}</span>

      <!--
        상태를 말로 적는다. 테두리만으로는 "아직 덜 됐다"와 "일부러 뺐다"가 구별되지 않고,
        색만으로는 구별할 수 없는 사용자가 있다. 둘은 배타다 — `excluded`가 `complete`를
        함께 보므로 한 칩에 두 배지가 붙지 않는다.
      -->
      <span v-if="!complete" class="chip__hint">{{ label === null ? '' : '값 필요' }}</span>
      <span v-else-if="excluded" class="chip__hint chip__hint--muted">제외</span>
      <i v-if="invalid" class="pi pi-exclamation-circle chip__invalid-icon" aria-hidden="true" />
      <i v-else-if="!readonly" class="pi pi-chevron-down chip__caret" aria-hidden="true" />
    </button>

    <!--
      읽기 전용에서도 낸다. 제외는 전략을 **고치는** 것이 아니라 이번 실행에서 빼는 것이고,
      "프리셋에서 조건 하나 빼고 돌려보기"가 이 기능이 있는 이유다.

      `@click.stop`이 없으면 루트로 버블링되어 편집기까지 함께 열린다.
    -->
    <button
      type="button"
      class="chip__toggle"
      :aria-pressed="!condition.enabled"
      :aria-label="`${label ?? PICK_INDICATOR} ${condition.enabled ? '실행에서 빼기' : '다시 넣기'}`"
      v-tooltip.top="condition.enabled ? '실행에서 빼기' : '다시 넣기'"
      @click.stop="emit('toggle')"
    >
      <i :class="condition.enabled ? 'pi pi-eye' : 'pi pi-eye-slash'" aria-hidden="true" />
    </button>
  </span>
</template>

<style scoped>
/*
 * 껍데기가 면과 테두리를 갖고, 안의 두 버튼은 자기 영역만 맡는다. 상태 클래스가 전부
 * 여기 붙는 이유는 그래야 라벨과 토글이 같은 테두리 안에서 함께 흐려지기 때문이다.
 */
.chip {
  display: inline-flex;
  align-items: stretch;
  max-width: 100%;
  /* `size="small"` 컨트롤(29px)과 같은 줄에 선다. 어긋나면 칩 바가 들쭉날쭉해 보인다. */
  min-height: 1.8125rem;
  border: 1px solid var(--p-content-border-color);
  border-radius: var(--p-border-radius-md);
  background: var(--p-surface-800);
  color: var(--p-text-color);
  font-size: var(--app-text-xs);
  line-height: 1;
  white-space: nowrap;
  transition:
    background var(--app-motion-fast) var(--app-ease),
    border-color var(--app-motion-fast) var(--app-ease),
    opacity var(--app-motion-fast) var(--app-ease);
}

.chip:hover {
  border-color: var(--p-surface-600);
  background: var(--p-surface-700);
}

/* 실제로 눌러 편집기를 여는 곳. 리셋만 하고 배치는 껍데기가 정한 흐름을 따른다. */
.chip__main {
  display: inline-flex;
  align-items: center;
  gap: 0.3125rem;
  min-width: 0;
  padding: 0 0.375rem 0 0.5rem;
  border: 0;
  border-radius: inherit;
  background: transparent;
  color: inherit;
  font: inherit;
  cursor: pointer;
}

.chip__main:focus-visible,
.chip__toggle:focus-visible {
  outline: var(--p-focus-ring-width) var(--p-focus-ring-style) var(--p-focus-ring-color);
  /* 칩 안쪽에 그린다. 밖으로 나가면 옆 칩과 겹친다. */
  outline-offset: -2px;
}

/*
 * 실행에서 빼는 스위치. 라벨과 세로선으로 가른다 — 색이나 간격으로만 나누면 칩 하나에
 * 누를 곳이 둘이라는 사실이 보이지 않는다.
 */
.chip__toggle {
  display: inline-flex;
  align-items: center;
  flex: 0 0 auto;
  padding: 0 0.375rem;
  border: 0;
  border-inline-start: 1px solid var(--p-content-border-color);
  border-radius: 0 var(--p-border-radius-md) var(--p-border-radius-md) 0;
  background: transparent;
  color: var(--p-text-subtle-color);
  font-size: var(--app-text-2xs);
  cursor: pointer;
  transition: color var(--app-motion-fast) var(--app-ease);
}

.chip__toggle:hover {
  color: var(--p-text-color);
  background: color-mix(in srgb, var(--p-surface-0) 6%, transparent);
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

/*
 * 일부러 뺀 것. **점선을 쓸 수 없다** — 미완성이 이미 점선이고, 둘은 사용자가 해야 할 일이
 * 정반대다(하나는 채우는 것, 하나는 아무것도 안 해도 되는 것).
 *
 * 그래서 축을 둘 다 다르게 잡는다: 테두리는 실선 그대로 두고 **글자에 취소선**을 긋는다.
 * 취소선은 색맹에게도, 대비를 못 올리는 자리에서도 성립한다. 배지는 앰버가 아니라
 * 중립이다 — 앰버는 "손봐야 한다"는 뜻으로 이미 쓰이고 있다(ADR-0021의 색 예산).
 */
.chip--excluded {
  opacity: 0.65;
  background: transparent;
}

.chip--excluded .chip__label {
  text-decoration: line-through;
  color: var(--p-text-subtle-color);
}

.chip--excluded .chip__toggle {
  color: var(--p-text-color);
}

.chip--invalid {
  border-style: solid;
  border-color: var(--p-red-400);
  background: color-mix(in srgb, var(--p-red-400) 12%, transparent);
  color: var(--p-red-300);
}

/*
 * 읽기 전용이어도 **토글은 눌린다.** 제외는 전략을 고치는 것이 아니라 이번 실행에서
 * 빼는 것이라, 잠긴 프리셋에서도 할 수 있어야 한다(ADR-0048).
 */
.chip--readonly .chip__main {
  cursor: default;
}

.chip--readonly:hover {
  border-color: var(--p-content-border-color);
  background: var(--p-surface-800);
}

.chip--readonly.chip--excluded:hover {
  background: transparent;
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

/* "제외"는 손볼 것이 없다. 앰버는 "채워야 한다"는 뜻으로 이미 쓰이므로 중립으로 둔다. */
.chip__hint--muted {
  background: var(--p-surface-700);
  color: var(--p-text-muted-color);
}

.chip__caret,
.chip__invalid-icon {
  flex: 0 0 auto;
  font-size: var(--app-text-2xs);
  color: var(--p-text-subtle-color);
}

/* 열린 칩의 캐럿은 팝오버가 어디 붙어 있는지 가리킨다. 테두리와 같은 색으로 올린다. */
.chip--active .chip__caret {
  color: var(--p-primary-color);
}

.chip--invalid .chip__invalid-icon {
  color: var(--p-red-300);
}
</style>
