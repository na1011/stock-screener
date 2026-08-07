<script setup lang="ts">
import { FilterMatchMode } from '@primevue/core/api'
import Column from 'primevue/column'
import DataTable from 'primevue/datatable'
import IconField from 'primevue/iconfield'
import InputIcon from 'primevue/inputicon'
import InputText from 'primevue/inputtext'
import { computed, ref } from 'vue'
import { RouterLink } from 'vue-router'

import type { IndicatorBrief, ScreeningResponse, ScreeningRow } from '@/api/types'
import IndicatorLabel from '@/components/IndicatorLabel.vue'
import ConditionOutcomes from '@/components/result/ConditionOutcomes.vue'
import { MISSING, formatIndicatorValue, formatNumber, formatPrice, formatRatioAsPercent } from '@/lib/format'
import { RESULT_LABELS, SCORE_DECIMAL_SCALE, indicatorBriefOf, valueOf } from '@/lib/resultRow'
import { useCatalogStore } from '@/stores/catalog'

/**
 * 데스크톱 결과 표.
 *
 * 서버가 전량을 주고 페이징하지 않으므로 정렬·필터·페이징은 전부 여기서 한다(ADR-0016).
 * 행 확장에 조건별 판정을 넣는 이유는 "왜 선정되었는가"가 결과와 같은 자리에 있어야 하기
 * 때문이다 — 다른 화면으로 옮기면 사용자는 대개 보지 않는다.
 */
const props = defineProps<{ result: ScreeningResponse; asOf: string }>()

/**
 * 표가 화면의 남은 높이를 채운다. 값은 CSS 변수에 있다 — 위쪽 크롬(헤더·조건바·요약
 * 스트립)의 높이를 CSS가 알고 있으므로, 그 계산을 여기 숫자로 옮기면 두 곳이 어긋난다.
 * DataTable은 이 문자열을 `max-height`에 그대로 넣으므로 `var()`가 통한다.
 */
const SCROLL_HEIGHT = 'var(--app-result-scroll-height)'

const catalog = useCatalogStore()

const expandedRows = ref<Record<string, boolean>>({})
const filters = ref({ global: { value: null as string | null, matchMode: FilterMatchMode.CONTAINS } })

const isScoring = computed(() => props.result.evaluationMode === 'SCORING')

/**
 * 컬럼으로 그릴 지표.
 *
 * 서버가 준 `columns`(기본 컬럼 ∪ 조건이 참조한 지표)를 그대로 따른다. 프론트가 목록을
 * 정하면 조건에 쓴 지표가 표에 없는 일이 생긴다. 컬럼 하나를 푸는 규칙은 `lib/resultRow`에
 * 있다 — 모바일 카드와 같아야 폭만 줄였는데 이름이 달라지는 일이 없다.
 */
const indicatorColumns = computed<IndicatorBrief[]>(() =>
  props.result.columns.map((code) => indicatorBriefOf(code, props.result.conditions, catalog.byCode)),
)

function indicatorName(code: string): string {
  return catalog.byCode.get(code)?.name ?? code
}

function percentileOf(code: string) {
  return catalog.byCode.get(code)?.percentile ?? null
}
</script>

<template>
  <DataTable
    v-model:expanded-rows="expandedRows"
    v-model:filters="filters"
    :value="result.rows"
    data-key="stockId"
    :global-filter-fields="['name', 'ticker']"
    sort-mode="single"
    :default-sort-order="-1"
    paginator
    :rows="25"
    :rows-per-page-options="[25, 50, 100]"
    scrollable
    :scroll-height="SCROLL_HEIGHT"
    size="small"
    current-page-report-template="{first}–{last} / 전체 {totalRecords}종목"
    :paginator-template="'FirstPageLink PrevPageLink PageLinks NextPageLink LastPageLink RowsPerPageDropdown CurrentPageReport'"
    class="result-table"
  >
    <template #header>
      <IconField class="result-table__search">
        <InputIcon class="pi pi-search" />
        <InputText v-model="filters.global.value" :placeholder="RESULT_LABELS.search" />
      </IconField>
    </template>

    <template #empty>
      <p class="result-table__empty">{{ RESULT_LABELS.empty }}</p>
    </template>

    <Column expander style="width: 3rem" />

    <Column field="name" header="종목" sortable frozen style="min-width: 12rem">
      <template #body="{ data }: { data: ScreeningRow }">
        <RouterLink :to="{ name: 'stock', params: { stockId: data.stockId }, query: { asOf } }">
          {{ data.name ?? RESULT_LABELS.unnamed }}
        </RouterLink>
        <div class="result-table__sub">{{ data.ticker }} · {{ data.marketCode }}</div>
      </template>
    </Column>

    <Column
      field="close"
      header="종가"
      sortable
      style="min-width: 7rem"
      body-class="result-table__num"
      header-class="result-table__num"
    >
      <template #body="{ data }: { data: ScreeningRow }">
        <span class="tabular">{{ formatPrice(data.close) }}</span>
      </template>
    </Column>

    <template v-if="isScoring">
      <Column
        field="score"
        :header="RESULT_LABELS.score"
        sortable
        style="min-width: 6rem"
        body-class="result-table__num"
        header-class="result-table__num"
      >
        <template #body="{ data }: { data: ScreeningRow }">
          <span class="tabular">{{ formatNumber(data.score, SCORE_DECIMAL_SCALE) }}</span>
        </template>
      </Column>
      <Column
        field="scoreRatio"
        :header="RESULT_LABELS.scoreRatio"
        sortable
        style="min-width: 6rem"
        body-class="result-table__num"
        header-class="result-table__num"
      >
        <template #body="{ data }: { data: ScreeningRow }">
          <span class="tabular">{{ formatRatioAsPercent(data.scoreRatio) }}</span>
        </template>
      </Column>
    </template>

    <Column
      field="matchedCount"
      :header="RESULT_LABELS.matched"
      sortable
      style="min-width: 6rem"
      body-class="result-table__num"
      header-class="result-table__num"
    >
      <template #body="{ data }: { data: ScreeningRow }">
        <span class="tabular">{{ data.matchedCount }} / {{ result.conditions.length }}</span>
      </template>
    </Column>

    <Column
      v-if="result.rows.some((row) => row.notEvaluableCount > 0)"
      field="notEvaluableCount"
      :header="RESULT_LABELS.notEvaluable"
      sortable
      style="min-width: 8.5rem"
      body-class="result-table__num"
      header-class="result-table__num"
    >
      <template #body="{ data }: { data: ScreeningRow }">
        <span class="tabular result-table__not-evaluable">
          {{ data.notEvaluableCount > 0 ? data.notEvaluableCount : MISSING }}
        </span>
      </template>
    </Column>

    <Column
      v-for="indicator in indicatorColumns"
      :key="indicator.code"
      :field="`values.${indicator.code}`"
      sortable
      style="min-width: 11rem"
      body-class="result-table__num"
      header-class="result-table__num"
    >
      <template #header>
        <IndicatorLabel
          :name="indicator.name"
          :higher-is-better="indicator.higherIsBetter"
          :percentile="percentileOf(indicator.code)"
          :description="catalog.byCode.get(indicator.code)?.description"
          :interpretation="indicator.interpretation"
        />
      </template>
      <template #body="{ data }: { data: ScreeningRow }">
        <span
          class="tabular"
          :class="{ 'result-table__missing': valueOf(data, indicator.code) === null }"
          :title="valueOf(data, indicator.code) === null ? RESULT_LABELS.notCalculated : undefined"
        >
          {{ formatIndicatorValue(valueOf(data, indicator.code), indicator.unit, indicator.decimalScale) }}
        </span>
      </template>
    </Column>

    <template #expansion="{ data }: { data: ScreeningRow }">
      <div class="result-table__expansion">
        <ConditionOutcomes
          :conditions="result.conditions"
          :row="data"
          :evaluation-mode="result.evaluationMode"
          :indicator-name="indicatorName"
        />
      </div>
    </template>
  </DataTable>
</template>

<style scoped>
.result-table__search {
  width: 18rem;
  max-width: 100%;
}

.result-table__sub {
  font-size: 0.75rem;
  color: var(--p-text-muted-color);
}

.result-table__missing {
  color: var(--p-text-muted-color);
}

.result-table__not-evaluable {
  color: var(--p-text-muted-color);
}

.result-table__expansion {
  padding: 0.75rem 1rem;
  background: var(--p-surface-950);
}

.result-table__empty {
  padding: 1.5rem;
  text-align: center;
  color: var(--p-text-muted-color);
}

/* 숫자는 오른쪽에 모여야 자릿수가 눈으로 비교된다. `.tabular`가 폭을 고정하고 이것이
   기준선을 맞춘다 — 둘 중 하나만 있으면 열이 여전히 흔들린다. */
.result-table :deep(td.result-table__num),
.result-table :deep(th.result-table__num) {
  text-align: right;
}

.result-table :deep(th.result-table__num .p-datatable-column-header-content) {
  justify-content: flex-end;
}

/*
 * 고정 컬럼(종목)은 자기 배경을 갖는다 — 없으면 가로 스크롤에서 뒤 내용이 비쳐 보인다.
 * 그런데 그 배경이 행 hover를 따라가지 않으면 커서를 올린 줄만 첫 칸이 다른 색이 된다.
 */
.result-table :deep(.p-datatable-tbody > tr:hover > .p-datatable-frozen-column) {
  background: var(--p-surface-800);
}
</style>
