<script setup lang="ts">
import Button from 'primevue/button'
import InputNumber from 'primevue/inputnumber'
import Select from 'primevue/select'
import SelectButton from 'primevue/selectbutton'
import { computed } from 'vue'

import { OPERAND_TYPES } from '@/api/types'
import type { ComparisonOperatorName, OperandType } from '@/api/types'
import IndicatorLabel from '@/components/IndicatorLabel.vue'
import { topQuintileCondition } from '@/lib/direction'
import { useCatalogStore } from '@/stores/catalog'
import type { DraftCondition } from '@/stores/screenDraft'
import { useScreenDraftStore } from '@/stores/screenDraft'

const props = defineProps<{ condition: DraftCondition; index: number; readonly: boolean }>()

const catalog = useCatalogStore()
const draft = useScreenDraftStore()

const indicator = computed(() =>
  props.condition.indicatorCode === null ? null : (catalog.byCode.get(props.condition.indicatorCode) ?? null),
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
  () => catalog.operators.find((op) => op.name === props.condition.operator)?.requiresUpperBound ?? false,
)

/**
 * 비교 대상의 종류.
 *
 * 유니온에서 파생시킨다 — 손으로 적은 배열은 서버가 종류를 늘려도 그대로 남아, 그 종류를
 * 고를 수 없게 되고(만들 수 없는 조건이 생긴다) 저장된 화면이 그것을 쓰면 LITERAL 분기로
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

/** 백분위 지표일 때만 나온다. 부등호와 경계값은 서버가 준 것을 그대로 넣는다. */
const topQuintile = computed(() => topQuintileCondition(indicator.value?.percentile))

function onIndicatorChange(): void {
  // 지표가 바뀌면 백분위(0~1)와 일반 지표(PER 10 등)의 값 범위가 달라진다. 이전 값을 남기면
  // "PER_PCTL <= 10" 같은 전 종목이 걸리는 조건이 조용히 만들어진다.
  props.condition.value = null
  props.condition.upperBound = null
  draft.markDirty()
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
}
</script>

<template>
  <div class="condition-row">
    <div class="condition-row__field condition-row__field--indicator">
      <label class="condition-row__label" :for="`indicator-${condition.key}`">지표</label>
      <Select
        :id="`indicator-${condition.key}`"
        v-model="condition.indicatorCode"
        :options="catalog.grouped"
        option-group-label="label"
        option-group-children="items"
        option-label="name"
        option-value="code"
        placeholder="지표 선택"
        filter
        filter-placeholder="지표 검색"
        fluid
        :disabled="readonly"
        class="condition-row__control"
        @change="onIndicatorChange"
      />
      <div v-if="indicator" class="condition-row__hint">
        <IndicatorLabel
          :name="indicator.name"
          :higher-is-better="indicator.higherIsBetter"
          :percentile="indicator.percentile"
          :description="indicator.description"
          :interpretation="indicator.interpretation"
        />
        <Button
          v-if="topQuintile && !readonly"
          label="상위 20%"
          size="small"
          text
          @click="applyTopQuintile"
        />
      </div>
    </div>

    <div class="condition-row__field condition-row__field--operator">
      <label class="condition-row__label" :for="`operator-${condition.key}`">조건</label>
      <Select
        :id="`operator-${condition.key}`"
        v-model="condition.operator"
        :options="operatorOptions"
        option-label="label"
        option-value="name"
        fluid
        :disabled="readonly"
        class="condition-row__control"
        @change="draft.markDirty()"
      />
    </div>

    <div class="condition-row__field condition-row__field--operand">
      <label class="condition-row__label">비교 대상</label>
      <div class="condition-row__operand">
        <SelectButton
          :model-value="condition.operandType"
          :options="operandTypeOptions"
          option-label="label"
          option-value="value"
          :allow-empty="false"
          :disabled="readonly"
          size="small"
          @update:model-value="onOperandTypeChange"
        />

        <template v-if="condition.operandType === 'LITERAL'">
          <InputNumber
            v-model="condition.value"
            fluid
            :max-fraction-digits="4"
            :disabled="readonly"
            :placeholder="requiresUpperBound ? '하한' : '값'"
            class="condition-row__number"
            @update:model-value="draft.markDirty()"
          />
          <template v-if="requiresUpperBound">
            <span class="condition-row__tilde">~</span>
            <InputNumber
              v-model="condition.upperBound"
              fluid
              :max-fraction-digits="4"
              :disabled="readonly"
              placeholder="상한"
              class="condition-row__number"
              @update:model-value="draft.markDirty()"
            />
          </template>
        </template>

        <template v-else>
          <Select
            v-model="condition.operandIndicatorCode"
            :options="catalog.grouped"
            option-group-label="label"
            option-group-children="items"
            option-label="name"
            option-value="code"
            placeholder="지표 선택"
            filter
            fluid
            :disabled="readonly"
            class="condition-row__operand-indicator"
            @change="draft.markDirty()"
          />
          <span class="condition-row__times">×</span>
          <InputNumber
            v-model="condition.multiplier"
            fluid
            :max-fraction-digits="4"
            :disabled="readonly"
            placeholder="1"
            class="condition-row__number condition-row__number--narrow"
            @update:model-value="draft.markDirty()"
          />
        </template>
      </div>
    </div>

    <div v-if="draft.evaluationMode === 'SCORING'" class="condition-row__field condition-row__field--weight">
      <label class="condition-row__label" :for="`weight-${condition.key}`">가중치</label>
      <InputNumber
        :id="`weight-${condition.key}`"
        v-model="condition.weight"
        fluid
        :min="0"
        :max-fraction-digits="2"
        placeholder="1"
        :disabled="readonly"
        @update:model-value="draft.markDirty()"
      />
    </div>

    <div class="condition-row__field condition-row__field--remove">
      <Button
        type="button"
        icon="pi pi-trash"
        text
        severity="secondary"
        :aria-label="`${index + 1}번째 조건 삭제`"
        :disabled="readonly"
        @click="draft.removeCondition(condition.key)"
      />
    </div>
  </div>
</template>

<style scoped>
/*
 * 열 하한의 합이 컨테이너 폭을 넘으면 그리드는 넘긴 채로 그린다 — `minmax()`의 하한은
 * 축소되지 않는다. 예전 값(12+7+16rem + auto 둘 + gap)은 합이 약 47.5rem(760px)이라
 * 한 열로 무너지는 900px 바로 위 구간에서 실제로 터졌다.
 *
 * 지금 합은 10+6+12rem + 4.5rem(가중치) + 1.875rem(삭제) + gap 3rem + padding 1.5rem
 * ≈ 38.9rem(622px)이다. 무너지는 경계(900px)보다 넉넉히 아래에 둔다.
 */
.condition-row {
  display: grid;
  grid-template-columns:
    minmax(10rem, 1.4fr) minmax(6rem, 0.6fr) minmax(12rem, 1.8fr) auto auto;
  gap: 0.75rem;
  align-items: start;
  padding: 0.75rem;
  border: 1px solid var(--p-content-border-color);
  border-radius: var(--p-border-radius-md);
  background: var(--p-content-background);
}

.condition-row__field {
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
  /* 그리드 아이템의 자동 최소 크기는 내용 기반이다. 0으로 내리지 않으면 안쪽 셀렉트의
     긴 지표 이름이 열 하한을 무시하고 밀어낸다. */
  min-width: 0;
}

.condition-row__label {
  font-size: 0.75rem;
  color: var(--p-text-muted-color);
  white-space: nowrap;
}

.condition-row__control {
  width: 100%;
}

.condition-row__operand {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  flex-wrap: wrap;
}

/* flex-basis로 폭을 주고 min-width를 0으로 내린다. `width`만 주면 안쪽 input의 자동
   최소 크기(≈12rem)가 남아 줄바꿈 대신 넘쳐 나간다 — `fluid`가 그것을 푸는 전제다. */
.condition-row__number {
  flex: 1 1 5rem;
  min-width: 0;
}

.condition-row__number--narrow {
  flex: 0 1 4.5rem;
}

.condition-row__operand-indicator {
  flex: 1 1 8rem;
  min-width: 0;
}

.condition-row__field--weight {
  width: 4.5rem;
}

.condition-row__hint {
  display: flex;
  align-items: center;
  gap: 0.25rem;
  flex-wrap: wrap;
  font-size: 0.8125rem;
  color: var(--p-text-muted-color);
}

.condition-row__tilde,
.condition-row__times {
  color: var(--p-text-muted-color);
}

.condition-row__field--remove {
  justify-content: center;
  align-items: center;
  width: 1.875rem;
  padding-top: 1.1rem;
}

/* 모바일은 한 조건이 한 카드가 된다. 가로로 줄여 넣으면 셀렉트가 글자를 잘라
   무엇을 고른 것인지 읽을 수 없다. */
@media (max-width: 900px) {
  .condition-row {
    grid-template-columns: 1fr;
    position: relative;
  }

  .condition-row__field--remove {
    position: absolute;
    top: 0.25rem;
    right: 0.25rem;
    width: auto;
    padding-top: 0;
  }

  /* 삭제 버튼이 절대 배치로 빠지므로 지표 라벨이 그 아래를 지나지 않게 자리를 비운다. */
  .condition-row__field--indicator {
    padding-right: 2rem;
  }

  .condition-row__field--weight {
    width: 100%;
  }

  .condition-row__number--narrow {
    flex: 1 1 6rem;
  }
}
</style>
