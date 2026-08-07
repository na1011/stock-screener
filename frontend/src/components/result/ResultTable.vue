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

/**
 * 지표 컬럼마다 이 결과 안에서의 최소·최대.
 *
 * 숫자 아래 막대의 기준이다. 139행을 눈으로 훑을 때 "어느 것이 큰가"가 숫자만으로는 읽히지
 * 않는다 — 자릿수가 같으면 더 그렇다.
 *
 * IMPORTANT — 값이 없는 행은 **계산에서 뺀다.** `?? 0`으로 메우면 0이 최소가 되어 나머지
 * 막대가 전부 짧아지고, 그것은 "부채비율 0%인 회사가 있다"고 그리는 것과 같은 잘못이다
 * (frontend.md). `valueOf`가 `Object.hasOwn`으로 이미 갈라 준다.
 */
const columnRanges = computed(() => {
  const ranges = new Map<string, { min: number; max: number }>()
  for (const indicator of indicatorColumns.value) {
    let min = Number.POSITIVE_INFINITY
    let max = Number.NEGATIVE_INFINITY
    for (const row of props.result.rows) {
      const value = valueOf(row, indicator.code)
      if (value === null) {
        continue
      }
      min = Math.min(min, value)
      max = Math.max(max, value)
    }
    if (Number.isFinite(min) && Number.isFinite(max)) {
      ranges.set(indicator.code, { min, max })
    }
  }
  return ranges
})

/**
 * 막대의 폭(%). 값이 없거나 컬럼이 상수면 그리지 않는다.
 *
 * 색을 유불리로 칠하지 않는 이유가 있다. 값 옆에는 이미 방향 마커(▲▼)가 있어 어느 쪽이
 * 유리한지 말하고 있고, 막대까지 색으로 판단을 얹으면 **PER이 큰 종목의 긴 막대가 붉게**
 * 나오는 식이 되어 크기와 유불리 두 가지를 한 그래픽이 뒤섞어 말하게 된다. 막대는 크기만
 * 말한다.
 */
function barWidth(row: ScreeningRow, code: string): number | null {
  const value = valueOf(row, code)
  const range = columnRanges.value.get(code)
  if (value === null || range === undefined || range.max === range.min) {
    return null
  }
  // 최솟값도 흔적은 남긴다. 0%면 "값이 없다"와 구별되지 않는다.
  return 4 + ((value - range.min) / (range.max - range.min)) * 96
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
          <span class="tabular result-table__score">
            {{ formatNumber(data.score, SCORE_DECIMAL_SCALE) }}
          </span>
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
          <span class="tabular result-table__secondary">
            {{ formatRatioAsPercent(data.scoreRatio) }}
          </span>
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
        <span class="tabular result-table__secondary">
          {{ data.matchedCount }} / {{ result.conditions.length }}
        </span>
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
      <!--
        값과 막대를 한 상자에 묶는다. 셀이 넓어(min-width 11rem) 막대를 셀 폭에 그대로 두면
        오른쪽 끝의 숫자와 왼쪽 끝의 막대가 떨어져, 막대가 옆 컬럼 것처럼 보인다.
      -->
      <template #body="{ data }: { data: ScreeningRow }">
        <span class="result-table__cell">
          <span
            class="tabular"
            :class="{ 'result-table__missing': valueOf(data, indicator.code) === null }"
            :title="valueOf(data, indicator.code) === null ? RESULT_LABELS.notCalculated : undefined"
          >
            {{ formatIndicatorValue(valueOf(data, indicator.code), indicator.unit, indicator.decimalScale) }}
          </span>
          <!-- 값이 없는 칸에는 막대가 없다. 있으면 0인 것으로 읽힌다. -->
          <span
            v-if="barWidth(data, indicator.code) !== null"
            class="result-table__bar"
            aria-hidden="true"
          >
            <span class="result-table__bar-fill" :style="{ width: `${barWidth(data, indicator.code)}%` }" />
          </span>
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
  width: 15rem;
  max-width: 100%;
}

/* 티커·시장은 3순위다. 종목명과 같은 밝기면 무엇을 먼저 읽어야 하는지 알 수 없다. */
.result-table__sub {
  font-size: var(--app-text-2xs);
  color: var(--p-surface-500);
}

/*
 * 값의 상대 크기를 셀 안에서 말한다. 표 아래에 별도 차트를 두지 않는 이유는, 훑어보는
 * 동안 눈이 표를 떠나면 안 되기 때문이다. 값 자체는 그대로 있고 막대는 배경에 가깝다 —
 * 숫자를 읽는 것을 방해하면 안 된다.
 */
.result-table__cell {
  display: inline-block;
  min-width: 4.5rem;
  text-align: right;
}

/*
 * 2px에 55% 투명도로는 다크 배경에서 사실상 보이지 않았다 — "배경에 가깝다"와 "없다"는
 * 다르다. 트랙을 한 단계 올리고(800 → 700) 채움을 진하게 해 눈에 걸리게 한다. 그래도
 * 3px이라 숫자를 읽는 것을 방해하지는 않는다.
 */
.result-table__bar {
  display: block;
  height: 3px;
  margin-top: 3px;
  border-radius: 2px;
  background: var(--p-surface-700);
}

.result-table__bar-fill {
  display: block;
  height: 100%;
  border-radius: inherit;
  background: color-mix(in srgb, var(--p-primary-color) 80%, transparent);
}

/*
 * 점수·달성률·충족은 같은 것을 세 가지로 말한다. 셋이 같은 밝기면 어느 것을 기준으로
 * 줄이 세워졌는지 알 수 없다. 정렬의 기준인 점수만 올리고 나머지는 내린다.
 *
 * IMPORTANT — 값에 초록·빨강 색조를 넣지 않는다. 이 표에는 판정 Tag가 함께 있어 두 채널이
 * 한 줄에 섞인다(ADR-0021의 "형태로 가른다"). 위계는 밝기로만 만든다.
 */
.result-table__score {
  color: var(--p-text-hover-color);
  font-weight: 600;
}

.result-table__secondary {
  color: var(--p-text-muted-color);
}

.result-table__missing {
  color: var(--p-text-muted-color);
}

.result-table__not-evaluable {
  color: var(--p-text-muted-color);
}

/*
 * IMPORTANT — 확장 영역을 스크롤 컨테이너 왼쪽에 고정한다.
 *
 * 확장 행의 `<td>`는 표 **전체 폭**(컬럼 min-width 합, 지표가 여섯이면 2,400px를 넘는다)을
 * 갖는다. 그대로 두면 `.outcomes__item`의 `1fr`이 그 폭을 다 먹어 조건별 **획득 점수가 가로
 * 스크롤 밖으로** 밀려난다. 총점만 보이고 내역이 안 보이는 상태이고, 그것은 "선정 이유를
 * 노출한다"(frontend.md, design §5)를 만족하지 못한다 — 사용자가 가로로 스크롤해야
 * 발견되는 것은 보이지 않는 것과 같다.
 *
 * 폭을 뷰포트 기준으로 잡는 이유는 스크롤 컨테이너의 보이는 폭을 CSS로 직접 참조할 방법이
 * 없기 때문이다. 표는 좌우 `--app-gutter`만 두고 풀 폭이라 그 값이 곧 보이는 폭이다.
 */
.result-table__expansion {
  position: sticky;
  left: 0;
  width: calc(100vw - 2 * var(--app-gutter) - 1.25rem);
  max-width: 100%;
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

/* ------------------------------------------------------------------------
 * 밀도.
 *
 * 행 56px에 본문 15px이었다. 1000px 높이에 열 행이 들어갔고, 139종목을 훑는 데 열네 번을
 * 스크롤해야 했다. 본문 12px·머리글 11px·행 `--app-row-height`로 내리면 스무 행이 넘는다.
 * preset의 `datatable` 패딩과 짝이라 한쪽만 바꾸면 행 높이가 패딩에 붙들린다.
 * ------------------------------------------------------------------------ */
.result-table :deep(.p-datatable-tbody > tr > td) {
  height: var(--app-row-height);
  font-size: var(--app-text-xs);
}

/* 139행을 훑을 때 커서가 어느 줄에 있는지가 즉시 갈아끼워지면 눈이 자리를 놓친다. */
.result-table :deep(.p-datatable-tbody > tr),
.result-table :deep(.p-datatable-tbody > tr > .p-datatable-frozen-column) {
  transition: background var(--app-motion-fast) var(--app-ease);
}

.result-table :deep(.p-datatable-thead > tr > th) {
  font-size: var(--app-text-2xs);
}

.result-table :deep(.p-datatable-column-title) {
  font-size: var(--app-text-2xs);
  letter-spacing: 0.02em;
}

/*
 * 정렬 아이콘은 **활성 컬럼과 hover에서만** 보인다.
 *
 * 머리글 하나에 글리프가 넷이었다(라벨 + 방향 ▲▼ + 도움말 ⓘ + 정렬 ↑↓). 컬럼이 여덟이면
 * 서른 개가 넘는 기호가 늘 떠 있어 정작 지표 이름이 안 읽혔다. 정렬은 누를 때 알면 되는
 * 것이고, 지금 무엇으로 정렬됐는지만 상시로 보이면 된다.
 *
 * `visibility`가 아니라 `opacity`인 이유는 자리를 유지하기 위해서다 — 사라지면 hover마다
 * 머리글 폭이 흔들린다.
 */
.result-table :deep(th .p-datatable-sort-icon) {
  opacity: 0;
  transition: opacity var(--app-motion-fast) var(--app-ease);
}

.result-table :deep(th:hover .p-datatable-sort-icon),
.result-table :deep(th:focus-within .p-datatable-sort-icon),
.result-table :deep(th[aria-sort]:not([aria-sort='none']) .p-datatable-sort-icon) {
  opacity: 1;
}

/* 페이지네이터도 표의 일부다. 여기만 커지면 표가 작아 보이는 것이 아니라 어색해 보인다. */
.result-table :deep(.p-paginator) {
  font-size: var(--app-text-2xs);
  padding: 0.25rem 0.5rem;
}
</style>
