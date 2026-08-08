<script setup lang="ts">
import Button from 'primevue/button'
import InputNumber from 'primevue/inputnumber'
import Select from 'primevue/select'
import { computed, ref, watch } from 'vue'

import { OPERAND_TYPES } from '@/api/types'
import type { ComparisonOperatorName, IndicatorBand, OperandType } from '@/api/types'
import FormField from '@/components/ui/FormField.vue'
import SegmentedControl from '@/components/ui/SegmentedControl.vue'
import { PICK_INDICATOR, SEARCH_INDICATOR } from '@/lib/conditionText'
import { formatEnteredNumber } from '@/lib/format'
import { topQuintileCondition } from '@/lib/direction'
import { useCatalogStore } from '@/stores/catalog'
import type { DraftCondition } from '@/stores/screenDraft'
import { useScreenDraftStore } from '@/stores/screenDraft'

/**
 * 조건 하나를 고치는 팝오버.
 *
 * IMPORTANT — **구간 프리셋이 먼저, 숫자가 나중이다.** 이 순서가 이 컴포넌트의 요점이다.
 * 예전에는 값 입력칸 하나뿐이라 "PER에 무슨 숫자를 넣어야 하는가"에 화면이 답하지 않았고,
 * 그것이 `.claude/rules/frontend.md`가 MUST NOT으로 금지한 "값만 나열"이었다. 대상 사용자는
 * 초보~중급 투자자다. 아는 사람은 `직접 입력`을 한 번 더 눌러 내려가면 된다.
 *
 * 구간이 없는 지표(주가 스케일 — SMA·ATR·MACD·종가)는 프리셋 없이 직접 입력만 낸다.
 * 없는 것을 지어내지 않는다. 근거는 ADR-0023에 있다.
 */
const props = defineProps<{ condition: DraftCondition; readonly: boolean }>()
const emit = defineEmits<{ remove: []; done: [] }>()

const catalog = useCatalogStore()
const draft = useScreenDraftStore()

const indicator = computed(() =>
  props.condition.indicatorCode === null
    ? null
    : (catalog.byCode.get(props.condition.indicatorCode) ?? null),
)

const bands = computed<IndicatorBand[]>(() => indicator.value?.bands ?? [])

/** 백분위 지표일 때만 나온다. 부등호와 경계값은 서버가 준 것을 그대로 넣는다. */
const topQuintile = computed(() => topQuintileCondition(indicator.value?.percentile))

/**
 * 직접 입력을 펼쳐 두는가.
 *
 * 제시할 구간이 없으면 접을 것이 없으므로 처음부터 펼친다. 이미 값이 들어 있는 조건을
 * 다시 열 때도 펼친다 — 고치러 온 사람에게 프리셋 목록을 먼저 보이면 한 번 더 눌러야 한다.
 */
const manualOpen = ref(false)

watch(
  () => props.condition.key,
  () => {
    manualOpen.value = bands.value.length === 0 || props.condition.value !== null
  },
  { immediate: true },
)

/**
 * BETWEEN은 지표 참조를 우변으로 쓸 수 없다 — 서버가 거부한다("상·하한을 각각 조건으로
 * 나누어라"). 선택지 자체를 없애 400을 만들지 않는다.
 */
const operatorOptions = computed(() =>
  catalog.operators.filter(
    (op) => props.condition.operandType !== 'INDICATOR' || !op.requiresUpperBound,
  ),
)

const requiresUpperBound = computed(
  () =>
    catalog.operators.find((op) => op.name === props.condition.operator)?.requiresUpperBound ?? false,
)

/**
 * 비교 대상의 종류.
 *
 * 유니온에서 파생시킨다 — 손으로 적은 배열은 서버가 종류를 늘려도 그대로 남아, 그 종류를
 * 고를 수 없게 되고(만들 수 없는 조건이 생긴다) 저장된 전략이 그것을 쓰면 LITERAL 분기로
 * 잘못 그려진다.
 */
const OPERAND_TYPE_LABELS: Record<OperandType, string> = {
  LITERAL: '값',
  INDICATOR: '지표',
}

const operandTypeOptions: { label: string; value: OperandType }[] = OPERAND_TYPES.map((value) => ({
  label: OPERAND_TYPE_LABELS[value],
  value,
}))

/** 값 입력칸 뒤에 붙는 단위. 서버가 준 문자열을 그대로 쓴다. */
const unitSuffix = computed(() => indicator.value?.unit ?? '')

const indicatorOptions = computed(() =>
  catalog.grouped.map((group) => ({
    label: group.label,
    items: group.items.map((it) => ({ label: it.name, value: it.code })),
  })),
)

function bandLabel(band: IndicatorBand): string {
  const scale = indicator.value?.decimalScale ?? 2
  const unit = unitSuffix.value
  const operatorLabel = catalog.operators.find((op) => op.name === band.operator)?.label ?? ''
  if (band.upperBound !== null) {
    return `${formatEnteredNumber(band.value, scale)}~${formatEnteredNumber(band.upperBound, scale)}${unit}`
  }
  return `${formatEnteredNumber(band.value, scale)}${unit} ${operatorLabel}`.trim()
}

/**
 * 구간 하나를 조건으로 만든다.
 *
 * IMPORTANT — 부등호를 여기서 정하지 않는다. 서버가 `operator`·`value`·`upperBound`를 이미
 * 번역해 내려준다(ADR-0023). `band.from`/`to`를 보고 화면이 유도하면 그 규칙이 두 곳에 생기고,
 * 어긋나도 값이 그럴듯해 드러나지 않는다.
 */
function applyBand(band: IndicatorBand): void {
  props.condition.operandType = 'LITERAL'
  props.condition.operator = band.operator
  props.condition.value = band.value
  props.condition.upperBound = band.upperBound
  draft.markDirty()
  // 고르면 끝이다. 한 번 더 닫게 하면 프리셋의 이점이 사라진다.
  emit('done')
}

function applyTopQuintile(): void {
  const condition = topQuintile.value
  if (condition === null) {
    return
  }
  props.condition.operandType = 'LITERAL'
  props.condition.operator = condition.operator as ComparisonOperatorName
  props.condition.value = condition.value
  props.condition.upperBound = null
  draft.markDirty()
  emit('done')
}

function onOperandTypeChange(value: OperandType | null): void {
  if (value === null) {
    return
  }
  props.condition.operandType = value
  if (value === 'INDICATOR' && props.condition.operator === 'BETWEEN') {
    props.condition.operator = 'GT'
  }
  draft.markDirty()
}
</script>

<template>
  <div class="editor">
    <header class="editor__head">
      <div class="editor__title">
        <h3 class="editor__name">{{ indicator?.name ?? '지표' }}</h3>
        <span v-if="unitSuffix" class="editor__unit">{{ unitSuffix }}</span>
      </div>
      <Button
        v-if="!readonly"
        type="button"
        text
        rounded
        size="small"
        severity="danger"
        icon="pi pi-trash"
        aria-label="이 조건 삭제"
        v-tooltip.top="'이 조건 삭제'"
        @click="emit('remove')"
      />
    </header>

    <!-- 무엇을 뜻하는 지표인지. 이름만으로는 값을 정할 수 없다. -->
    <p v-if="indicator?.interpretation" class="editor__interpretation">
      {{ indicator.interpretation }}
    </p>
    <p v-if="indicator?.percentile" class="editor__interpretation">
      {{ indicator.percentile.label }}
    </p>

    <template v-if="!readonly">
      <!-- 구간 프리셋. 숫자를 모르는 사용자가 먼저 만나는 것. -->
      <p v-if="bands.length > 0" class="editor__bands-title">구간에서 고르기</p>
      <div v-if="bands.length > 0" class="editor__bands">
        <button
          v-for="band in bands"
          :key="band.label"
          type="button"
          class="app-list-button editor__band"
          @click="applyBand(band)"
        >
          <span class="editor__band-range tabular">{{ bandLabel(band) }}</span>
          <span class="editor__band-label">{{ band.label }}</span>
        </button>
      </div>

      <Button
        v-if="topQuintile"
        label="상위 20% 바로 적용"
        icon="pi pi-bolt"
        size="small"
        text
        class="editor__quintile"
        @click="applyTopQuintile"
      />

      <button
        v-if="bands.length > 0"
        type="button"
        class="app-list-button editor__manual-toggle"
        :aria-expanded="manualOpen"
        @click="manualOpen = !manualOpen"
      >
        <i :class="manualOpen ? 'pi pi-chevron-down' : 'pi pi-chevron-right'" aria-hidden="true" />
        <span>직접 입력</span>
      </button>

      <div v-show="manualOpen" class="editor__manual">
        <FormField label="비교 대상">
          <SegmentedControl
            :model-value="condition.operandType"
            :options="operandTypeOptions"
            aria-label="비교 대상"
            @update:model-value="onOperandTypeChange"
          />
        </FormField>

        <FormField label="조건">
          <Select
            :model-value="condition.operator"
            :options="operatorOptions"
            option-label="label"
            option-value="name"
            size="small"
            fluid
            @update:model-value="
              (value) => {
                condition.operator = value
                draft.markDirty()
              }
            "
          />
        </FormField>

        <template v-if="condition.operandType === 'LITERAL'">
          <FormField :label="requiresUpperBound ? '하한' : '값'">
            <InputNumber
              :model-value="condition.value"
              :max-fraction-digits="4"
              :suffix="unitSuffix"
              size="small"
              fluid
              @update:model-value="
                (value) => {
                  condition.value = value
                  draft.markDirty()
                }
              "
            />
          </FormField>
          <FormField v-if="requiresUpperBound" label="상한">
            <InputNumber
              :model-value="condition.upperBound"
              :max-fraction-digits="4"
              :suffix="unitSuffix"
              size="small"
              fluid
              @update:model-value="
                (value) => {
                  condition.upperBound = value
                  draft.markDirty()
                }
              "
            />
          </FormField>
        </template>

        <template v-else>
          <FormField label="비교할 지표">
            <Select
              :model-value="condition.operandIndicatorCode"
              :options="indicatorOptions"
              option-group-label="label"
              option-group-children="items"
              option-label="label"
              option-value="value"
              :placeholder="PICK_INDICATOR"
              filter
              :filter-placeholder="SEARCH_INDICATOR"
              size="small"
              fluid
              @update:model-value="
                (value) => {
                  condition.operandIndicatorCode = value
                  draft.markDirty()
                }
              "
            />
          </FormField>
          <FormField label="배수">
            <InputNumber
              :model-value="condition.multiplier"
              :max-fraction-digits="4"
              placeholder="1"
              size="small"
              fluid
              @update:model-value="
                (value) => {
                  condition.multiplier = value
                  draft.markDirty()
                }
              "
            />
          </FormField>
        </template>

        <!-- 가중치는 점수 방식에서만 뜻이 있다. 거르기에서는 자리를 차지할 이유가 없다. -->
        <FormField v-if="draft.evaluationMode === 'SCORING'" label="가중치">
          <InputNumber
            :model-value="condition.weight"
            :min="0"
            :max-fraction-digits="2"
            placeholder="1"
            size="small"
            fluid
            @update:model-value="
              (value) => {
                condition.weight = value
                draft.markDirty()
              }
            "
          />
        </FormField>
      </div>
    </template>

    <p v-else class="editor__readonly">읽기 전용 전략입니다. 복제하면 고칠 수 있습니다.</p>
  </div>
</template>

<style scoped>
.editor {
  width: var(--app-overlay-md);
  max-width: 100%;
}

.editor__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 0.5rem;
}

.editor__title {
  display: flex;
  align-items: baseline;
  gap: 0.25rem;
  min-width: 0;
}

.editor__name {
  margin: 0;
  font-size: var(--app-text-md);
  font-weight: 600;
}

.editor__unit {
  font-size: var(--app-text-2xs);
  color: var(--p-text-muted-color);
}

.editor__interpretation {
  margin: 0.25rem 0 0;
  font-size: var(--app-text-2xs);
  line-height: 1.45;
  color: var(--p-text-muted-color);
}

/*
 * 목록이 무엇인지 말한다. 없으면 설명문 바로 아래 다섯 줄이 근거 없이 떠 있고, "직접 입력"
 * 토글만 이름을 가져 두 선택지가 대등해 보이지 않는다.
 */
.editor__bands-title {
  margin: 0.625rem 0 0.125rem;
  font-size: var(--app-text-2xs);
  font-weight: 600;
  color: var(--p-text-muted-color);
}

.editor__bands {
  display: flex;
  flex-direction: column;
  gap: 1px;
  margin: 0 -0.25rem;
}

/*
 * 구간 한 줄. 값과 뜻을 좌우로 벌린다 — TradingView가 "15 to 25 / Moderate"를 위아래로 두는
 * 자리인데, 한글 라벨이 짧아 한 줄에 들어간다. 세로로 두면 목록이 두 배로 길어진다.
 */
.editor__band {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 0.5rem;
  width: 100%;
  padding: 0.3125rem 0.375rem;
  font-size: var(--app-text-xs);
}

.editor__band-range {
  color: var(--p-text-color);
  white-space: nowrap;
}

.editor__band-label {
  color: var(--p-text-muted-color);
  white-space: nowrap;
}

.editor__quintile {
  margin-top: 0.25rem;
}

/* 구획을 가르는 선이라 hover 배경 대신 글자 색만 밝아진다. `.app-list-button`의 배경을 덮는다. */
.editor__manual-toggle {
  display: flex;
  align-items: center;
  gap: 0.375rem;
  width: 100%;
  margin-top: 0.375rem;
  padding: 0.3125rem 0.375rem;
  border-top: 1px solid var(--p-content-border-color);
  border-radius: 0;
  color: var(--p-text-muted-color);
  font-size: var(--app-text-2xs);
  font-weight: 600;
}

.editor__manual-toggle:hover,
.editor__manual-toggle:focus-visible {
  background: transparent;
  color: var(--p-text-color);
}

.editor__manual {
  display: flex;
  flex-direction: column;
  gap: 0.375rem;
  margin-top: 0.25rem;
}

.editor__readonly {
  margin: 0.5rem 0 0;
  font-size: var(--app-text-2xs);
  color: var(--p-text-muted-color);
}
</style>
