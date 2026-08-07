<script setup lang="ts">
import Button from 'primevue/button'
import IconField from 'primevue/iconfield'
import InputIcon from 'primevue/inputicon'
import InputText from 'primevue/inputtext'
import Select from 'primevue/select'
import Tag from 'primevue/tag'
import { computed, ref } from 'vue'
import { RouterLink } from 'vue-router'

import type { ScreeningResponse, ScreeningRow } from '@/api/types'
import ConditionOutcomes from '@/components/result/ConditionOutcomes.vue'
import {
  LOCALE,
  formatCount,
  formatIndicatorValue,
  formatNumber,
  formatPrice,
  formatRatioAsPercent,
} from '@/lib/format'
import { RESULT_LABELS, SCORE_DECIMAL_SCALE, indicatorBriefOf, valueOf } from '@/lib/resultRow'
import { useCatalogStore } from '@/stores/catalog'

/**
 * 모바일 결과 목록.
 *
 * 표가 아니라 카드인 것이 요점이다. 350행 × 10컬럼을 좁은 화면에 넣으면 가로 스크롤 안에서
 * 종목명조차 읽히지 않는다 — spec §5가 "같은 화면의 축소본을 주지 말라"고 요구하는 지점이
 * 정확히 여기다. 대신 훑어보기에 필요한 것(이름·가격·점수·핵심 지표 둘)만 크게 보이고,
 * 조건별 판정은 펼쳐서 본다.
 */
const props = defineProps<{ result: ScreeningResponse; asOf: string }>()

const catalog = useCatalogStore()

const query = ref('')
const expanded = ref<number | null>(null)

const isScoring = computed(() => props.result.evaluationMode === 'SCORING')

/**
 * 카드에 미리 보일 지표. 서버가 준 컬럼 순서의 앞 둘이다.
 *
 * 푸는 규칙은 데스크톱 표와 같은 `lib/resultRow`를 쓴다. 예전에는 카탈로그만 봤기 때문에
 * 카탈로그 요청이 실패하면 데스크톱은 "PER"을, 모바일은 원본 코드를 보였다.
 */
const previewColumns = computed(() =>
  props.result.columns
    .slice(0, 2)
    .map((code) => indicatorBriefOf(code, props.result.conditions, catalog.byCode)),
)

const sortOptions = computed(() => [
  ...(isScoring.value ? [{ label: `${RESULT_LABELS.score} 높은 순`, value: 'score' }] : []),
  { label: '충족 조건 많은 순', value: 'matched' },
  { label: '종목명 순', value: 'name' },
  { label: '종가 높은 순', value: 'close' },
])

const sortBy = ref<string>(props.result.evaluationMode === 'SCORING' ? 'score' : 'matched')

const visibleRows = computed(() => {
  const keyword = query.value.trim().toLowerCase()
  const filtered =
    keyword === ''
      ? props.result.rows
      : props.result.rows.filter(
          (row) =>
            (row.name ?? '').toLowerCase().includes(keyword) ||
            (row.ticker ?? '').toLowerCase().includes(keyword),
        )
  return [...filtered].sort(comparator)
})

function comparator(a: ScreeningRow, b: ScreeningRow): number {
  switch (sortBy.value) {
    case 'score':
      return descendingWithMissingLast(a.score, b.score)
    case 'matched':
      return b.matchedCount - a.matchedCount
    case 'close':
      return descendingWithMissingLast(a.close, b.close)
    default:
      return (a.name ?? '').localeCompare(b.name ?? '', LOCALE)
  }
}

/**
 * 큰 값이 위로, 값이 없는 것은 언제나 아래로.
 *
 * `(b ?? 0) - (a ?? 0)`으로 쓰지 않는다. 내림차순에서는 결과가 같아 보이지만, 그것은 값이
 * 없는 것을 0으로 **간주해서** 우연히 맞은 것이다. 이 프로젝트에서 `?? 0`은 "계산되지 않았다"를
 * 0으로 둔갑시키는 금지된 관용구이고, 정렬이 오름차순으로 바뀌는 순간 값 없는 종목이
 * 최상위로 올라온다.
 */
function descendingWithMissingLast(a: number | null, b: number | null): number {
  if (a === null && b === null) {
    return 0
  }
  if (a === null) {
    return 1
  }
  if (b === null) {
    return -1
  }
  return b - a
}

function indicatorName(code: string): string {
  return catalog.byCode.get(code)?.name ?? code
}

function toggle(stockId: number): void {
  expanded.value = expanded.value === stockId ? null : stockId
}
</script>

<template>
  <div class="cards">
    <!--
      검색과 정렬을 한 줄에 둔다. 세로로 쌓으면 둘이 66px을 먹는데, 모바일에서는 그 높이가
      그대로 카드 하나 분량이다. 좁으면 알아서 줄바꿈한다.
    -->
    <div class="cards__controls">
      <IconField class="cards__search">
        <InputIcon class="pi pi-search" />
        <InputText v-model="query" :placeholder="RESULT_LABELS.search" size="small" />
      </IconField>
      <Select
        v-model="sortBy"
        :options="sortOptions"
        option-label="label"
        option-value="value"
        size="small"
        class="cards__sort"
      />
      <span class="cards__count tabular">{{ formatCount(visibleRows.length) }}종목</span>
    </div>

    <p v-if="visibleRows.length === 0" class="cards__empty">{{ RESULT_LABELS.empty }}</p>

    <article v-for="row in visibleRows" :key="row.stockId" class="card">
      <header class="card__header">
        <div class="card__title">
          <RouterLink
            :to="{ name: 'stock', params: { stockId: row.stockId }, query: { asOf } }"
            class="card__name"
          >
            {{ row.name ?? RESULT_LABELS.unnamed }}
          </RouterLink>
          <span class="card__ticker">{{ row.ticker }}</span>
        </div>
        <span class="card__price tabular">{{ formatPrice(row.close) }}</span>
      </header>

      <div class="card__metrics">
        <span v-if="isScoring" class="card__metric">
          <span class="card__metric-label">{{ RESULT_LABELS.score }}</span>
          <strong class="tabular">{{ formatNumber(row.score, SCORE_DECIMAL_SCALE) }}</strong>
          <span class="card__metric-sub">{{ formatRatioAsPercent(row.scoreRatio) }}</span>
        </span>
        <span class="card__metric">
          <span class="card__metric-label">{{ RESULT_LABELS.matched }}</span>
          <strong class="tabular">{{ row.matchedCount }} / {{ result.conditions.length }}</strong>
        </span>
        <span v-for="indicator in previewColumns" :key="indicator.code" class="card__metric">
          <span class="card__metric-label">{{ indicator.name }}</span>
          <strong class="tabular">{{
            formatIndicatorValue(valueOf(row, indicator.code), indicator.unit, indicator.decimalScale)
          }}</strong>
        </span>
      </div>

      <div class="card__footer">
        <Tag
          v-if="row.notEvaluableCount > 0"
          :value="`${RESULT_LABELS.notEvaluable} ${row.notEvaluableCount}`"
          severity="warn"
        />
        <Button
          :label="expanded === row.stockId ? '선정 이유 접기' : '선정 이유 보기'"
          :icon="expanded === row.stockId ? 'pi pi-chevron-up' : 'pi pi-chevron-down'"
          size="small"
          text
          @click="toggle(row.stockId)"
        />
      </div>

      <ConditionOutcomes
        v-if="expanded === row.stockId"
        :conditions="result.conditions"
        :row="row"
        :evaluation-mode="result.evaluationMode"
        :indicator-name="indicatorName"
        class="card__outcomes"
      />
    </article>
  </div>
</template>

<style scoped>
.cards {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

.cards__controls {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 0.375rem;
}

/* 검색이 남는 폭을 먹되 정렬이 잘리지 않을 만큼은 남긴다. */
.cards__search {
  flex: 1 1 8rem;
  min-width: 0;
}

.cards__sort {
  flex: 0 1 9.5rem;
  min-width: 0;
}

.cards__count {
  flex: 0 0 auto;
  margin-inline-start: auto;
  font-size: var(--app-text-2xs);
  color: var(--p-text-subtle-color);
}

.cards__empty {
  padding: 2rem 0;
  text-align: center;
  color: var(--p-text-muted-color);
}

.card {
  border: 1px solid var(--p-content-border-color);
  border-radius: var(--p-border-radius-md);
  background: var(--p-content-background);
  padding: 0.5rem 0.625rem;
  display: flex;
  flex-direction: column;
  gap: 0.375rem;
}

.card__header {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 0.5rem;
}

.card__title {
  display: flex;
  align-items: baseline;
  gap: 0.375rem;
  min-width: 0;
}

/* 긴 종목명이 오른쪽 가격을 밀어내지 않게 한다. min-width: 0만으로는 줄지 않는다 —
   줄일 수 있다는 것과 잘라 그린다는 것은 다르다. */
.card__name {
  font-size: var(--app-text-sm);
  font-weight: 600;
  color: inherit;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 표의 종목 셀과 같은 위계다 — 티커는 3순위. 카드와 표가 같은 것을 다르게 그리면
   폭만 바뀌었는데 다른 화면으로 읽힌다. */
.card__ticker {
  font-size: var(--app-text-2xs);
  color: var(--p-text-subtle-color);
}

.card__price {
  font-size: var(--app-text-sm);
  font-weight: 600;
  white-space: nowrap;
}

.card__metrics {
  display: flex;
  flex-wrap: wrap;
  gap: 0.25rem 0.875rem;
}

.card__metric {
  display: flex;
  align-items: baseline;
  gap: 0.25rem;
  font-size: var(--app-text-xs);
}

.card__metric-label,
.card__metric-sub {
  font-size: var(--app-text-2xs);
  color: var(--p-text-muted-color);
}

.card__footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 0.5rem;
}

.card__outcomes {
  border-top: 1px solid var(--p-content-border-color);
  padding-top: 0.5rem;
}
</style>
