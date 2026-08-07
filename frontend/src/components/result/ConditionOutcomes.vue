<script setup lang="ts">
import Tag from 'primevue/tag'
import { computed } from 'vue'

import type { ConditionInfo, EvaluationMode, ScreeningRow } from '@/api/types'
import { describeCondition } from '@/lib/conditionText'
import { MISSING, formatIndicatorValue, formatNumber, formatRatioAsPercent } from '@/lib/format'
import { outcomeStyle } from '@/lib/outcome'
import { SCORE_DECIMAL_SCALE } from '@/lib/resultRow'

/**
 * 이 종목이 왜 이 결과에 있는가.
 *
 * MUST — 조건별 판정을 **세 상태 그대로** 보인다(ADR-0003). 충족·불충족·데이터없음이 화면에서
 * 서로 달라야 사용자가 종목이 왜 빠졌는지 알 수 있다.
 *
 * MUST — SCORING이면 조건별 획득 점수를 보인다(frontend.md). 총점만 주고 내역을 감추면
 * 사용자가 결과를 신뢰할 근거가 없다.
 */
const props = defineProps<{
  conditions: ConditionInfo[]
  row: ScreeningRow
  evaluationMode: EvaluationMode
  /** 지표 코드 → 이름. 조건 문장이 코드 대신 이름을 쓰게 한다. */
  indicatorName: (code: string) => string
}>()

const isScoring = computed(() => props.evaluationMode === 'SCORING')

const items = computed(() =>
  props.conditions.map((condition) => {
    const outcome = props.row.outcomes.find((it) => it.conditionIndex === condition.index) ?? null
    return {
      condition,
      outcome,
      style: outcome === null ? null : outcomeStyle(outcome.status),
      text: describeCondition(condition, props.indicatorName),
    }
  }),
)
</script>

<template>
  <div class="outcomes">
    <div v-if="isScoring" class="outcomes__total">
      <span class="outcomes__total-label">총점</span>
      <strong class="tabular">{{ formatNumber(row.score, SCORE_DECIMAL_SCALE) }}</strong>
      <span class="outcomes__total-max tabular">/ {{ formatNumber(row.maxScore, SCORE_DECIMAL_SCALE) }}</span>
      <span class="outcomes__total-ratio">({{ formatRatioAsPercent(row.scoreRatio) }})</span>
    </div>

    <ul class="outcomes__list">
      <li v-for="item in items" :key="item.condition.index" class="outcomes__item">
        <Tag
          v-if="item.style"
          v-tooltip.top="item.style.tooltip"
          :value="item.style.label"
          :severity="item.style.severity"
          :icon="item.style.icon"
          class="outcomes__status"
        />
        <span v-else class="outcomes__status outcomes__status--absent">판정 없음</span>

        <span class="outcomes__text">{{ item.text }}</span>

        <span class="outcomes__actual tabular">
          실제
          {{
            item.outcome?.actualValue === null || item.outcome === null
              ? MISSING
              : formatIndicatorValue(
                  item.outcome.actualValue,
                  item.condition.indicator.unit,
                  item.condition.indicator.decimalScale,
                )
          }}
        </span>

        <span v-if="isScoring" class="outcomes__score tabular">
          {{ formatNumber(item.outcome?.earnedScore ?? null, SCORE_DECIMAL_SCALE) }} /
          {{ formatNumber(item.condition.weight ?? 1, SCORE_DECIMAL_SCALE) }}점
        </span>
      </li>
    </ul>

    <p v-if="row.notEvaluableCount > 0" class="outcomes__note">
      {{ row.notEvaluableCount }}개 조건은 지표 값이 없어 판정하지 못했습니다. 조건에 맞지 않았다는
      뜻이 아닙니다.
    </p>
  </div>
</template>

<style scoped>
.outcomes {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

.outcomes__total {
  display: flex;
  align-items: baseline;
  gap: 0.375rem;
  font-size: 0.9375rem;
}

.outcomes__total-label {
  color: var(--p-text-muted-color);
  font-size: 0.8125rem;
}

.outcomes__total-max,
.outcomes__total-ratio {
  color: var(--p-text-muted-color);
  font-size: 0.8125rem;
}

.outcomes__list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 0.375rem;
}

.outcomes__item {
  display: grid;
  grid-template-columns: 7.5rem 1fr auto auto;
  gap: 0.75rem;
  align-items: center;
  font-size: 0.875rem;
}

.outcomes__status {
  justify-self: start;
}

.outcomes__status--absent {
  color: var(--p-text-muted-color);
  font-size: 0.8125rem;
}

.outcomes__actual,
.outcomes__score {
  color: var(--p-text-muted-color);
  font-size: 0.8125rem;
  white-space: nowrap;
}

.outcomes__note {
  margin: 0;
  font-size: 0.8125rem;
  color: var(--p-text-muted-color);
}

@media (max-width: 767px) {
  .outcomes__item {
    grid-template-columns: auto 1fr;
    grid-template-areas:
      'status text'
      'actual score';
    row-gap: 0.125rem;
  }

  .outcomes__status {
    grid-area: status;
  }

  .outcomes__text {
    grid-area: text;
  }

  .outcomes__actual {
    grid-area: actual;
  }

  .outcomes__score {
    grid-area: score;
    justify-self: end;
  }
}
</style>
