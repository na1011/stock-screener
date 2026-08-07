<script setup lang="ts">
import { FilterMatchMode } from '@primevue/core/api'
import Column from 'primevue/column'
import DataTable from 'primevue/datatable'
import IconField from 'primevue/iconfield'
import InputIcon from 'primevue/inputicon'
import InputText from 'primevue/inputtext'
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'

import type { IndicatorBrief, ScreeningResponse, ScreeningRow } from '@/api/types'
import IndicatorLabel from '@/components/IndicatorLabel.vue'
import ConditionOutcomes from '@/components/result/ConditionOutcomes.vue'
import RatioBar from '@/components/ui/RatioBar.vue'
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

/**
 * 스크롤 컨테이너의 **보이는 폭**을 재서 `--result-viewport`로 내려보낸다.
 *
 * IMPORTANT — 이것이 없으면 확장 영역(선정 이유)이 가로 스크롤 밖으로 밀려난다. 실제로 두
 * 번 밀려났다: `59da89c`가 `calc(100vw - 2*gutter - 1.25rem)`으로 고쳤는데, 그 커밋의 주석이
 * *"표가 풀 폭이 아니게 되는 순간(사이드바 추가 등) 이 계산은 조용히 틀린다"*고 예고했고
 * 다음 날 `16366f7`이 좌측 레일(240px)을 세웠다. 1440px에서 확장 영역 1372px vs 실제
 * 스크롤포트 1141px — 획득 점수가 225px 밖에 있었다.
 *
 * 그래서 **유도하지 않고 잰다.** 레일이 있든 없든, 서랍이든 아니든, 세로 스크롤바가 있든
 * 없든 정확하다. 레일 폭을 미디어 쿼리로 빼는 안은 다음 레이아웃 변경에서 같은 회귀가
 * 나므로 버렸다. `--result-viewport`는 디자인 토큰이 아니라 **런타임 측정값**이라
 * `main.css`가 아니라 여기서 이 컴포넌트 루트에만 쓴다.
 */
const tableRoot = ref<{ $el?: HTMLElement } | null>(null)
let viewportObserver: ResizeObserver | null = null

onMounted(() => {
  const root = tableRoot.value?.$el
  if (!root) {
    // 여기 오면 DataTable이 루트 엘리먼트를 내주지 않은 것이다. 조용히 넘기면 확장 영역이
    // 다시 표 전체 폭(2,000px+)을 따라가고, 그 사실은 행을 펼쳐 봐야만 드러난다.
    throw new Error('결과 표의 루트 엘리먼트를 찾지 못했다 — 확장 영역 폭을 잴 수 없다')
  }
  // 스크롤은 컨테이너가 한다. 못 찾으면 루트로 대신한다 — 폭이 거의 같고, 값이 없는 것보다
  // 낫다(그때는 세로 스크롤바 폭만큼 넉넉해진다).
  const port = root.querySelector<HTMLElement>('.p-datatable-table-container') ?? root
  viewportObserver = new ResizeObserver(() => {
    root.style.setProperty('--result-viewport', `${port.clientWidth}px`)
  })
  viewportObserver.observe(port)
})

onBeforeUnmount(() => {
  viewportObserver?.disconnect()
  viewportObserver = null
})

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
 * 컬럼 안에서의 상대 크기(0..1). 값이 없거나 컬럼이 상수면 그리지 않는다.
 *
 * 색을 유불리로 칠하지 않는 이유가 있다. 값 옆에는 이미 방향 마커(▲▼)가 있어 어느 쪽이
 * 유리한지 말하고 있고, 막대까지 색으로 판단을 얹으면 **PER이 큰 종목의 긴 막대가 붉게**
 * 나오는 식이 되어 크기와 유불리 두 가지를 한 그래픽이 뒤섞어 말하게 된다. 막대는 크기만
 * 말한다(ADR-0021의 채널 분리와 같은 이유다).
 *
 * 최솟값에도 흔적을 남기는 바닥값은 `RatioBar`가 갖는다 — 선정 이유의 점수 막대와 같아야 한다.
 */
function barRatio(row: ScreeningRow, code: string): number | null {
  const value = valueOf(row, code)
  const range = columnRanges.value.get(code)
  if (value === null || range === undefined || range.max === range.min) {
    return null
  }
  return (value - range.min) / (range.max - range.min)
}
</script>

<template>
  <DataTable
    ref="tableRoot"
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
          <!-- 값이 없는 칸에는 막대가 없다(`RatioBar`가 null을 받으면 그리지 않는다).
               있으면 0인 것으로 읽힌다. 숫자가 바로 위에 있으므로 낭독기에는 감춘다. -->
          <RatioBar :value="barRatio(data, indicator.code)" class="result-table__bar" />
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
  color: var(--p-text-subtle-color);
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

/* 두께·색·바닥값은 `RatioBar`가 갖는다. 여기서 정하는 것은 숫자와의 간격뿐이다. */
.result-table__bar {
  margin-top: 3px;
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
 * 갖는다. 그대로 두면 `.outcomes__item`이 그 폭을 다 먹어 조건별 **획득 점수가 가로 스크롤
 * 밖으로** 밀려난다. 총점만 보이고 내역이 안 보이는 상태이고, 그것은 "선정 이유를
 * 노출한다"(frontend.md, design §5)를 만족하지 못한다 — 사용자가 가로로 스크롤해야
 * 발견되는 것은 보이지 않는 것과 같다.
 *
 * 폭은 스크립트가 `ResizeObserver`로 **재서** `--result-viewport`에 넣는다. 뷰포트에서
 * 유도하던 이전 방식(`calc(100vw - …)`)은 좌측 레일이 생긴 날 조용히 틀렸다 — 자세한 경위는
 * 위 스크립트의 주석에 있다. 폴백 `100%`는 표 전체 폭이라 옛 결함과 같은 상태이므로,
 * 측정이 실패하면 스크립트가 던지게 해 두었다.
 */
/*
 * 확장 행의 `<td>`에서 좌우 패딩을 걷는다. 그것을 남겨 두면 안쪽 상자가 스크롤포트 폭을
 * 그대로 받되 10px 안에서 시작해 **딱 그만큼 넘친다.** 여백은 아래 상자가 자기 것으로 갖는다.
 */
.result-table :deep(.p-datatable-tbody > tr.p-datatable-row-expansion > td) {
  /* 선택자가 길어야 하는 이유가 있다 — PrimeVue의 `.p-datatable.p-datatable-sm
     .p-datatable-tbody > tr > td`가 (0,3,2)라 이보다 짧으면 진다. */
  padding: 0;
}

.result-table__expansion {
  position: sticky;
  left: 0;
  width: var(--result-viewport, 100%);
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

/*
 * IMPORTANT — 정렬 아이콘을 **라벨 왼쪽**에 둔다. 오른쪽에 두면 머리글이 본문보다 22px
 * 왼쪽에서 끝나 열이 미묘하게 틀어져 보인다.
 *
 * 아이콘은 활성·hover가 아닐 때 `opacity: 0`이지 `display: none`이 아니다(위 규칙). 자리를
 * 유지해야 hover마다 라벨이 튀지 않기 때문인데, 오른쪽 끝에 두면 그 **보이지 않는 자리**가
 * 라벨과 숫자 사이를 벌린다. 헤더 상자(`contentRight`)는 본문과 맞아 있었고 어긋난 것은
 * 눈에 보이는 글자뿐이라, 원인이 잘 드러나지 않는 종류의 어긋남이었다.
 *
 * `row-reverse`에서 main-start는 오른쪽이므로 `flex-start`가 오른쪽으로 몬다. DOM 순서가
 * [라벨][아이콘]이므로 라벨이 오른쪽 끝에 서고 아이콘은 왼쪽 여유 공간을 쓴다.
 */
.result-table :deep(th.result-table__num .p-datatable-column-header-content) {
  flex-direction: row-reverse;
  justify-content: flex-start;
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
