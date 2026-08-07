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
import { formatIndicatorValue, formatNumber, formatPrice, formatRatioAsPercent } from '@/lib/format'
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

/** 카드에 미리 보일 지표. 서버가 준 컬럼 순서의 앞 둘이다. */
const previewColumns = computed(() =>
  props.result.columns.slice(0, 2).map((code) => {
    const meta = catalog.byCode.get(code)
    return {
      code,
      name: meta?.name ?? code,
      unit: meta?.unit ?? null,
      decimalScale: meta?.decimalScale ?? 2,
    }
  }),
)

const sortOptions = computed(() => [
  ...(isScoring.value ? [{ label: '점수 높은 순', value: 'score' }] : []),
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
      return (b.score ?? 0) - (a.score ?? 0)
    case 'matched':
      return b.matchedCount - a.matchedCount
    case 'close':
      return (b.close ?? 0) - (a.close ?? 0)
    default:
      return (a.name ?? '').localeCompare(b.name ?? '', 'ko-KR')
  }
}

function valueOf(row: ScreeningRow, code: string): number | null {
  return Object.hasOwn(row.values, code) ? (row.values[code] ?? null) : null
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
    <div class="cards__controls">
      <IconField class="cards__search">
        <InputIcon class="pi pi-search" />
        <InputText v-model="query" placeholder="종목명 · 티커 검색" />
      </IconField>
      <Select v-model="sortBy" :options="sortOptions" option-label="label" option-value="value" />
    </div>

    <p class="cards__count">{{ visibleRows.length.toLocaleString('ko-KR') }}종목</p>

    <p v-if="visibleRows.length === 0" class="cards__empty">조건에 맞는 종목이 없습니다.</p>

    <article v-for="row in visibleRows" :key="row.stockId" class="card">
      <header class="card__header">
        <div class="card__title">
          <RouterLink
            :to="{ name: 'stock', params: { stockId: row.stockId }, query: { asOf } }"
            class="card__name"
          >
            {{ row.name ?? '이름 없음' }}
          </RouterLink>
          <span class="card__ticker">{{ row.ticker }}</span>
        </div>
        <span class="card__price tabular">{{ formatPrice(row.close) }}</span>
      </header>

      <div class="card__metrics">
        <span v-if="isScoring" class="card__metric">
          <span class="card__metric-label">점수</span>
          <strong class="tabular">{{ formatNumber(row.score, 2) }}</strong>
          <span class="card__metric-sub">{{ formatRatioAsPercent(row.scoreRatio) }}</span>
        </span>
        <span class="card__metric">
          <span class="card__metric-label">충족</span>
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
          :value="`데이터 없음 ${row.notEvaluableCount}`"
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
  gap: 0.75rem;
}

.cards__controls {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

.cards__search {
  width: 100%;
}

.cards__count {
  margin: 0;
  font-size: 0.8125rem;
  color: var(--p-text-muted-color);
}

.cards__empty {
  padding: 2rem 0;
  text-align: center;
  color: var(--p-text-muted-color);
}

.card {
  border: 1px solid var(--p-content-border-color);
  border-radius: var(--p-border-radius-md);
  padding: 0.75rem;
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
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

.card__name {
  font-weight: 600;
  color: inherit;
}

.card__ticker {
  font-size: 0.75rem;
  color: var(--p-text-muted-color);
}

.card__price {
  font-weight: 600;
  white-space: nowrap;
}

.card__metrics {
  display: flex;
  flex-wrap: wrap;
  gap: 0.75rem 1.25rem;
}

.card__metric {
  display: flex;
  align-items: baseline;
  gap: 0.25rem;
  font-size: 0.875rem;
}

.card__metric-label,
.card__metric-sub {
  font-size: 0.75rem;
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
