<script setup lang="ts">
import Tag from 'primevue/tag'
import { computed, ref } from 'vue'

import type { FinancialPeriod, StockFinancials } from '@/api/types'
import { MISSING, formatAmount, formatDate, formatSignedPercent } from '@/lib/format'

/**
 * 실적 원수치.
 *
 * IMPORTANT — 이 패널의 위험은 **우리가 만든 값이 공시된 값처럼 보이는 것**이다(ADR-0059).
 * 셋을 반드시 지킨다.
 *
 * 1. **4분기에 유도 표시를 단다.** DART에 4분기 보고서가 없어 사업보고서에서 3분기 누적을
 *    뺀 값이다. 표시가 없으면 사용자는 공시된 실적으로 읽는다.
 * 2. **기간을 회계연도·분기로만 말한다.** 서버가 `period_start`/`period_end`를 주지 않는다 —
 *    합성값이라 결산월이 12월이 아닌 종목에서 아직 오지 않은 기간을 가리킨다.
 * 3. **고지를 서버 문구 그대로 쓴다.** 화면이 자기 문장을 만들면 두 곳이 갈리고, 갈린 쪽은
 *    화면에서 드러나지 않는다.
 *
 * 연간과 분기를 나눈 것도 의도다. 매출·이익은 기간에 걸쳐 쌓이는 유량이고 자산·자본은 시점
 * 잔액이라, 한 표에 섞으면 사용자가 "이 분기 자산이 늘었다"를 잘못 읽는다.
 */
const props = defineProps<{ financials: StockFinancials }>()

type Tab = 'annual' | 'quarterly'
const tab = ref<Tab>('annual')

const rows = computed<FinancialPeriod[]>(() =>
  tab.value === 'annual' ? props.financials.annual : props.financials.quarterly,
)

/** 유도 표시가 실제로 쓰이는 표에서만 그 고지를 낸다. */
const hasDerived = computed(() => rows.value.some((row) => row.derived))

/**
 * 분기 영업활동현금흐름은 저장하지 않는다(서버가 그 이유를 문구로 준다). 열이 통째로 비면
 * 수집 결손처럼 보이므로, **비어 있는 것이 정상인 표에서는 열 자체를 내린다.**
 */
const showCashFlow = computed(() => rows.value.some((row) => row.operatingCashFlow !== null))

function label(row: FinancialPeriod): string {
  return row.fiscalPeriod === 'FY' ? `${row.fiscalYear}년` : `${row.fiscalYear} ${row.fiscalPeriod}`
}
</script>

<template>
  <section class="financials" aria-labelledby="financials-heading">
    <header class="financials__header">
      <h2 id="financials-heading" class="financials__title">실적</h2>
      <div class="financials__tabs" role="tablist" aria-label="실적 기간">
        <button
          v-for="option in (['annual', 'quarterly'] as const)"
          :key="option"
          type="button"
          role="tab"
          class="financials__tab"
          :class="{ 'financials__tab--active': tab === option }"
          :aria-selected="tab === option"
          @click="tab = option"
        >
          {{ option === 'annual' ? '연간' : '분기' }}
        </button>
      </div>
    </header>

    <p class="financials__lead">
      회사가 공시한 재무제표 수치입니다. 위 지표는 이 값들로 계산합니다.
    </p>

    <p v-if="rows.length === 0" class="financials__empty">
      표시할 재무제표가 없습니다.
    </p>

    <template v-else>
      <div class="financials__scroll">
        <table class="financials__table">
          <caption class="sr-only">기간별 실적 원수치</caption>
          <thead>
            <tr>
              <th scope="col">기간</th>
              <th scope="col" class="financials__num">매출액</th>
              <th scope="col" class="financials__num">영업이익</th>
              <th scope="col" class="financials__num">당기순이익</th>
              <th v-if="showCashFlow" scope="col" class="financials__num">영업활동현금흐름</th>
              <th scope="col" class="financials__num">자산총계</th>
              <th scope="col" class="financials__num">자본총계</th>
              <th scope="col">공시일</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in rows" :key="`${row.fiscalYear}-${row.fiscalPeriod}`">
              <th scope="row" class="financials__period">
                {{ label(row) }}
                <Tag v-if="row.derived" value="유도" severity="warn" class="financials__badge" />
                <Tag v-if="row.revised" value="정정" severity="info" class="financials__badge" />
              </th>
              <td class="financials__num">
                {{ formatAmount(row.revenue) }}
                <span class="financials__yoy">{{ formatSignedPercent(row.revenueYoy) }}</span>
              </td>
              <td class="financials__num">
                {{ formatAmount(row.operatingIncome) }}
                <span class="financials__yoy">{{ formatSignedPercent(row.operatingIncomeYoy) }}</span>
              </td>
              <td class="financials__num">
                {{ formatAmount(row.netIncome) }}
                <span class="financials__yoy">{{ formatSignedPercent(row.netIncomeYoy) }}</span>
              </td>
              <td v-if="showCashFlow" class="financials__num">
                {{ formatAmount(row.operatingCashFlow) }}
              </td>
              <td class="financials__num">{{ formatAmount(row.totalAssets) }}</td>
              <td class="financials__num">{{ formatAmount(row.totalEquity) }}</td>
              <td>{{ row.publishedAt ? formatDate(row.publishedAt) : MISSING }}</td>
            </tr>
          </tbody>
        </table>
      </div>

      <p class="financials__foot">
        괄호 없는 작은 숫자는 <strong>전년 동기 대비 증감률</strong>입니다.
      </p>
      <p v-if="hasDerived" class="financials__foot">{{ financials.notice.derivedFourthQuarter }}</p>
      <p v-if="!showCashFlow" class="financials__foot">{{ financials.notice.quarterlyCashFlow }}</p>
    </template>
  </section>
</template>

<style scoped>
.financials {
  border: 1px solid var(--surface-border);
  border-radius: var(--radius-lg);
  padding: 1.25rem;
  background: var(--surface-card);
}

.financials__header {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  justify-content: space-between;
  gap: 0.75rem;
}

.financials__title {
  margin: 0;
  font-size: 1.05rem;
  font-weight: 600;
}

.financials__tabs {
  display: flex;
  gap: 0.25rem;
}

.financials__tab {
  border: 1px solid var(--surface-border);
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--text-color-secondary);
  padding: 0.25rem 0.75rem;
  font: inherit;
  font-size: 0.85rem;
  cursor: pointer;
}

.financials__tab--active {
  border-color: var(--primary-color);
  color: var(--primary-color);
}

.financials__lead,
.financials__foot {
  margin: 0.5rem 0 0;
  color: var(--text-color-secondary);
  font-size: 0.85rem;
  line-height: 1.5;
}

.financials__empty {
  margin: 0.75rem 0 0;
  color: var(--text-color-secondary);
}

/* 열이 여덟이라 좁은 폭에서는 가로 스크롤이 접는 것보다 낫다 — 접으면 기간 간 비교가 끊긴다. */
.financials__scroll {
  margin-top: 0.75rem;
  overflow-x: auto;
}

.financials__table {
  width: 100%;
  border-collapse: collapse;
  font-size: 0.9rem;
  white-space: nowrap;
}

.financials__table th,
.financials__table td {
  padding: 0.5rem 0.75rem;
  border-bottom: 1px solid var(--surface-border);
  text-align: left;
}

.financials__table thead th {
  color: var(--text-color-secondary);
  font-weight: 500;
  font-size: 0.8rem;
}

.financials__num {
  text-align: right;
  font-variant-numeric: tabular-nums;
}

.financials__period {
  font-weight: 600;
}

.financials__badge {
  margin-left: 0.35rem;
  font-size: 0.7rem;
}

.financials__yoy {
  display: block;
  color: var(--text-color-secondary);
  font-size: 0.75rem;
}
</style>
