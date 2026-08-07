<script setup lang="ts">
import Button from 'primevue/button'
import Tag from 'primevue/tag'
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'

import { api } from '@/api/endpoints'
import AsyncPanel from '@/components/AsyncPanel.vue'
import IndicatorLabel from '@/components/IndicatorLabel.vue'
import PriceReferencePanel from '@/components/stock/PriceReferencePanel.vue'
import { useAsync } from '@/composables/useAsync'
import { useBreakpoint } from '@/composables/useBreakpoint'
import { MISSING, formatCount, formatDate, formatIndicatorValue, formatPrice } from '@/lib/format'
import { DETAIL_CATEGORY_RANK, groupByCategory } from '@/lib/indicatorCategory'
import { useCatalogStore } from '@/stores/catalog'

const props = defineProps<{ stockId: string }>()

const route = useRoute()
const catalog = useCatalogStore()
const { isMobile } = useBreakpoint()

const stockId = computed(() => Number(props.stockId))
const asOf = computed(() => (typeof route.query.asOf === 'string' ? route.query.asOf : null))

const detail = useAsync((signal) => api.stock(stockId.value, asOf.value, signal))
/**
 * 지지·저항은 **별도 요청**이다.
 *
 * 한 응답에 합치지 않는 이유가 셋이다 — 한쪽이 실패해도 다른 쪽은 살고(ADR-0014), 지표가
 * 아니라는 사실이 구조로 드러나며, 모바일에서는 눌러서 받을 수 있다.
 */
const priceReference = useAsync((signal) => api.priceReference(stockId.value, asOf.value, signal))

/** 모바일은 접힌 채로 시작한다. 데스크톱은 자리가 있으므로 함께 보인다. */
const referenceOpen = ref(false)

/** 분류의 라벨·배치와 모르는 분류의 처리는 `lib/indicatorCategory`가 정한다. 빌더와 순서가 다른 것은 의도다. */
const groups = computed(() =>
  groupByCategory(
    detail.data.value?.indicators ?? [],
    (indicator) => indicator.category,
    DETAIL_CATEGORY_RANK,
  ),
)

/**
 * 값이 없어 목록에 오르지 못한 지표 수.
 *
 * 서버는 값이 없는 지표의 행 자체를 보내지 않는다. 그것을 그냥 두면 화면에는 "지표가 원래
 * 그것뿐"인 것처럼 보인다. 몇 개가 왜 빠졌는지 한 줄로 알린다.
 */
const missingCount = computed(() => {
  if (detail.data.value === null || catalog.indicators.length === 0) {
    return 0
  }
  return Math.max(0, catalog.indicators.length - detail.data.value.indicators.length)
})

function percentileOf(code: string) {
  return catalog.byCode.get(code)?.percentile ?? null
}

function openReference(): void {
  referenceOpen.value = true
  if (priceReference.state.value === 'idle') {
    void priceReference.run()
  }
}

onMounted(() => {
  void catalog.ensureCatalog()
  void detail.run()
  if (!isMobile.value) {
    referenceOpen.value = true
    void priceReference.run()
  }
})
</script>

<template>
  <section class="stock-detail">
    <AsyncPanel
      :state="detail.state.value"
      :error="detail.error.value"
      :retry="detail.retry"
      label="종목 정보"
      :skeleton-rows="5"
    >
      <template v-if="detail.data.value">
        <header class="stock-detail__header">
          <div>
            <h1 class="stock-detail__name">{{ detail.data.value.stock.name }}</h1>
            <p class="stock-detail__meta">
              {{ detail.data.value.stock.ticker }} · {{ detail.data.value.stock.marketCode }}
              <template v-if="detail.data.value.stock.sector">
                · {{ detail.data.value.stock.sector }}
              </template>
            </p>
            <div class="stock-detail__universes">
              <Tag
                v-for="universe in detail.data.value.stock.universes"
                :key="universe.code"
                :value="universe.name"
                severity="secondary"
              />
            </div>
          </div>
          <div class="stock-detail__price">
            <span class="stock-detail__price-value tabular">
              {{ formatPrice(detail.data.value.price?.close ?? null) }}
            </span>
            <span class="stock-detail__price-label">
              기준일 {{ formatDate(detail.data.value.asOf) }} 종가(원주가)
            </span>
          </div>
        </header>

        <section v-if="detail.data.value.price" class="stock-detail__ohlc">
          <span>시가 <span class="tabular">{{ formatPrice(detail.data.value.price.open) }}</span></span>
          <span>고가 <span class="tabular">{{ formatPrice(detail.data.value.price.high) }}</span></span>
          <span>저가 <span class="tabular">{{ formatPrice(detail.data.value.price.low) }}</span></span>
          <span>거래량 <span class="tabular">{{ formatCount(detail.data.value.price.volume) }}</span></span>
          <span>
            상장주식수
            <span class="tabular">{{ formatCount(detail.data.value.price.listedShares) }}</span>
          </span>
        </section>
        <p v-else class="stock-detail__no-price">이 날짜의 시세가 없습니다.</p>

        <section v-for="group in groups" :key="group.category" class="indicator-group">
          <h2 class="indicator-group__title">{{ group.label }}</h2>
          <ul class="indicator-group__list">
            <li v-for="indicator in group.items" :key="indicator.code" class="indicator">
              <IndicatorLabel
                :name="indicator.name"
                :higher-is-better="indicator.higherIsBetter"
                :percentile="percentileOf(indicator.code)"
                :description="indicator.description"
                :interpretation="indicator.interpretation"
              />
              <span class="indicator__value tabular">
                {{ formatIndicatorValue(indicator.value, indicator.unit, indicator.decimalScale) }}
              </span>
              <p class="indicator__interpretation">{{ indicator.interpretation }}</p>
            </li>
          </ul>
        </section>

        <p v-if="missingCount > 0" class="stock-detail__missing">
          값이 없어 표시하지 못한 지표가 {{ missingCount }}개 있습니다. 계산에 필요한 이력이나 공시가
          아직 없다는 뜻이며, 값이 {{ MISSING }}이라는 뜻이 아닙니다.
        </p>
      </template>
    </AsyncPanel>

    <!-- 지지·저항은 별도 패널이자 별도 요청이다. 위가 실패해도 여기는 살고, 반대도 마찬가지다. -->
    <div class="stock-detail__reference">
      <Button
        v-if="!referenceOpen"
        label="지지·저항 참고정보 보기"
        icon="pi pi-chart-bar"
        outlined
        @click="openReference"
      />
      <AsyncPanel
        v-else
        :state="priceReference.state.value"
        :error="priceReference.error.value"
        :retry="priceReference.retry"
        label="지지·저항 참고정보"
        :skeleton-rows="3"
      >
        <PriceReferencePanel v-if="priceReference.data.value" :reference="priceReference.data.value" />
      </AsyncPanel>
    </div>
  </section>
</template>

<style scoped>
.stock-detail {
  max-width: 60rem;
  margin: 0 auto;
  padding: 1.5rem;
  display: flex;
  flex-direction: column;
  gap: 1.5rem;
}

.stock-detail__header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 1rem;
  flex-wrap: wrap;
}

.stock-detail__name {
  font-size: 1.5rem;
  margin: 0;
}

.stock-detail__meta {
  margin: 0.25rem 0 0.5rem;
  color: var(--p-text-muted-color);
  font-size: 0.875rem;
}

.stock-detail__universes {
  display: flex;
  gap: 0.375rem;
  flex-wrap: wrap;
}

.stock-detail__price {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
}

.stock-detail__price-value {
  font-size: 1.5rem;
  font-weight: 700;
}

.stock-detail__price-label {
  font-size: 0.75rem;
  color: var(--p-text-muted-color);
}

.stock-detail__ohlc {
  display: flex;
  gap: 1.25rem;
  flex-wrap: wrap;
  font-size: 0.8125rem;
  color: var(--p-text-muted-color);
  padding-bottom: 0.75rem;
  border-bottom: 1px solid var(--p-content-border-color);
}

.stock-detail__no-price,
.stock-detail__missing {
  margin: 0;
  font-size: 0.8125rem;
  color: var(--p-text-muted-color);
}

.indicator-group__title {
  font-size: 1rem;
  margin: 0 0 0.5rem;
}

.indicator-group__list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(18rem, 1fr));
  gap: 0.75rem;
}

.indicator {
  border: 1px solid var(--p-content-border-color);
  border-radius: var(--p-border-radius-md);
  padding: 0.625rem 0.75rem;
  display: grid;
  grid-template-columns: 1fr auto;
  gap: 0.25rem 0.5rem;
}

.indicator__value {
  font-weight: 600;
  justify-self: end;
}

.indicator__interpretation {
  grid-column: 1 / -1;
  margin: 0;
  font-size: 0.75rem;
  color: var(--p-text-muted-color);
  line-height: 1.5;
}

@media (max-width: 767px) {
  .stock-detail {
    padding: 1rem;
    gap: 1rem;
  }

  .stock-detail__price {
    align-items: flex-start;
  }

  /* 모바일은 지표를 한 줄씩 훑는다. 두 열로 만들면 설명이 잘려 의미가 사라진다. */
  .indicator-group__list {
    grid-template-columns: 1fr;
  }
}
</style>
