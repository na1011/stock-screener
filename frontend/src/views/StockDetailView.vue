<script setup lang="ts">
import Button from 'primevue/button'
import { computed, onMounted, ref } from 'vue'
import { RouterLink, useRoute } from 'vue-router'

import { api } from '@/api/endpoints'
import type { StockIndicatorValue } from '@/api/types'
import AsyncPanel from '@/components/AsyncPanel.vue'
import AppViewHeader from '@/components/ui/AppViewHeader.vue'
import IndicatorLabel from '@/components/IndicatorLabel.vue'
import PriceReferencePanel from '@/components/stock/PriceReferencePanel.vue'
import StockHeader from '@/components/stock/StockHeader.vue'
import BandMeter from '@/components/ui/BandMeter.vue'
import SectionHeading from '@/components/ui/SectionHeading.vue'
import { useAsync } from '@/composables/useAsync'
import { useBreakpoint } from '@/composables/useBreakpoint'
import { readBand } from '@/lib/band'
import { MISSING, splitIndicatorValue } from '@/lib/format'
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

/**
 * 값이 어느 구간인지.
 *
 * 구간(`bands`)과 백분위 안내는 **카탈로그에만** 있고 종목 상세 응답에는 없다. 코드로 이어
 * 붙이는 것은 `percentileOf`가 이미 하던 방식이며, 요청을 하나 더 만들지 않는다.
 *
 * 카탈로그가 실패하면 `byCode`가 비어 미터가 통째로 사라진다. 그것이 옳다 — 근거 없이
 * "매우 낮음"을 그리는 것보다 말하지 않는 편이 낫고, 값과 설명은 그대로 남는다.
 */
function readingOf(indicator: StockIndicatorValue) {
  const meta = catalog.byCode.get(indicator.code)
  return meta === undefined ? null : readBand(meta, indicator.value)
}

/**
 * 분류의 라벨·배치와 모르는 분류의 처리는 `lib/indicatorCategory`가 정한다. 빌더와 순서가
 * 다른 것은 의도다.
 *
 * 카드가 쓸 것(값의 조각, 구간 읽기)을 여기서 한 번에 만든다. 템플릿에서 함수를 부르면
 * 같은 계산이 카드마다 두세 번 돈다.
 */
const groups = computed(() =>
  groupByCategory(
    detail.data.value?.indicators ?? [],
    (indicator) => indicator.category,
    DETAIL_CATEGORY_RANK,
  ).map((group) => ({
    ...group,
    items: group.items.map((indicator) => ({
      indicator,
      reading: readingOf(indicator),
      parts: splitIndicatorValue(indicator.value, indicator.unit, indicator.decimalScale),
    })),
  })),
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
  <div class="stock-detail">
    <!--
      띠에 종목명을 넣지 않는다. 이 화면의 주어는 `StockHeader`의 `<h1>`이고, 여기 또 두면
      한 문서에 h1이 둘이 된다. 대신 **돌아가는 길**이 여기 선다 — 로딩 중이나 오류일 때도
      보여야 하는데, 결과 안에 있던 동안에는 그때 사라졌다.

      IMPORTANT — 이 화면에는 돌아갈 길이 하나도 없었다. 라우트가 형제 셋(screener·
      screens·stock)이라 부모-자식 관계가 없고, 유일한 이동 수단인 레일은 좁은 폭에서
      햄버거 서랍 안으로 들어간다. 결과는 스토어에 살아 있으므로 돌아가면 그대로다.
    -->
    <AppViewHeader>
      <template #title>
        <RouterLink :to="{ name: 'screener' }" class="stock-detail__back">
          <i class="pi pi-angle-left" aria-hidden="true" />
          <span>스크리너</span>
        </RouterLink>
      </template>
    </AppViewHeader>

    <div class="stock-detail__body">
      <AsyncPanel
        :state="detail.state.value"
        :error="detail.error.value"
        :retry="detail.retry"
        panel="stock"
        :skeleton-rows="5"
      >
        <template v-if="detail.data.value">
          <StockHeader
            :stock="detail.data.value.stock"
            :price="detail.data.value.price"
            :as-of="detail.data.value.asOf"
            :unadjusted-actions="detail.data.value.unadjustedActions"
          />

          <section v-for="group in groups" :key="group.category" class="indicator-group">
            <SectionHeading :title="group.label" :count="group.items.length" />
            <ul class="indicator-group__list">
              <li v-for="row in group.items" :key="row.indicator.code" class="indicator">
                <IndicatorLabel
                  :name="row.indicator.name"
                  :higher-is-better="row.indicator.higherIsBetter"
                  :percentile="percentileOf(row.indicator.code)"
                  :description="row.indicator.description"
                  :interpretation="row.indicator.interpretation"
                />
                <!--
                  숫자와 단위를 나눠 그린다. 붙여 놓으면 '%'가 숫자와 같은 무게로 읽혀,
                  카드 스무 장을 훑을 때 눈이 자릿수 대신 기호에 걸린다.
                -->
                <span
                  class="indicator__value tabular"
                  :class="row.reading ? `indicator__value--${row.reading.tone.toLowerCase()}` : null"
                >
                  {{ row.parts.number
                  }}<span v-if="row.parts.unit" class="indicator__unit">{{ row.parts.unit }}</span>
                </span>
                <!-- 구간을 아는 지표에만 붙는다. 없으면 지어내지 않는다(ADR-0023). -->
                <BandMeter
                  v-if="row.reading"
                  class="indicator__meter"
                  :reading="row.reading"
                  :indicator-name="row.indicator.name"
                />
                <p class="indicator__interpretation">{{ row.indicator.interpretation }}</p>
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
          panel="priceReference"
          :skeleton-rows="3"
        >
          <PriceReferencePanel v-if="priceReference.data.value" :reference="priceReference.data.value" />
        </AsyncPanel>
      </div>
    </div>
  </div>
</template>

<style scoped>
/*
 * 루트는 전폭이다 — 머리 띠가 좌측 레일의 선과 이어지려면 그것을 감싸는 것이 폭에 묶이면
 * 안 된다. 폭은 여기서 선언만 하고, 띠 안쪽과 아래 본문이 그것을 받는다.
 *
 * 90rem(`--app-reading-width`)을 쓰지 않는다. 여기는 훑는 표가 아니라 읽는 화면이라
 * 줄 길이가 길어지면 오히려 안 읽힌다.
 */
.stock-detail {
  --app-view-width: 64rem;
}

.stock-detail__body {
  max-width: var(--app-view-width);
  margin: 0 auto;
  padding: 1.5rem var(--app-gutter) 3rem;
  display: flex;
  flex-direction: column;
  gap: 1.25rem;
}

/*
 * 돌아가는 길. 머리 띠에 선다 — 여백은 띠가 갖는다.
 *
 * 이 화면의 제목 자리를 이것이 대신하는 이유는 종목명이 아래 `StockHeader`의 `<h1>`이기
 * 때문이다. 띠에 또 두면 한 문서에 h1이 둘이 된다.
 */
.stock-detail__back {
  display: inline-flex;
  align-items: center;
  gap: 0.125rem;
  padding: 0.25rem 0.5rem 0.25rem 0.25rem;
  border-radius: var(--p-border-radius-md);
  color: var(--p-text-muted-color);
  font-size: var(--app-text-sm);
  transition:
    background var(--app-motion-fast) var(--app-ease),
    color var(--app-motion-fast) var(--app-ease);
}

.stock-detail__back:hover {
  background: var(--p-surface-800);
  color: var(--p-text-color);
  text-decoration: none;
}

.stock-detail__missing {
  margin: 0;
  font-size: var(--app-text-sm);
  color: var(--p-text-muted-color);
}

/*
 * 첫 구획 제목의 윗여백을 줄이지 않는다.
 *
 * 예전에는 `:first-of-type`에 `margin-top: 0.25rem`을 걸었다. 그때 바로 위가 얇은 시세
 * **줄**이라 28px이 과했기 때문이다. 지금 위에 있는 것은 요약 **카드**라 전제가 뒤집혔다 —
 * 4px면 제목이 카드에 들러붙어 카드의 일부처럼 읽힌다.
 *
 * `SectionHeading`의 기본값(1.75rem)을 그대로 쓴다. 구획 사이 간격이 한 값으로 통일되어
 * 첫 구획만 리듬이 다르지 않다.
 */

.indicator-group__list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(17rem, 1fr));
  gap: 0.625rem;
}

/*
 * 카드가 캔버스 위 평면 하나였다. 위쪽을 미세하게 들어 올리고 상단 1px에 빛을 걸어야
 * 스물몇 장이 같은 깊이에 눕지 않는다 — 합성값의 정본은 `main.css`다.
 */
.indicator {
  border: 1px solid var(--p-content-border-color);
  border-radius: var(--p-border-radius-md);
  background: var(--app-surface-raised);
  box-shadow: var(--app-edge-highlight);
  padding: 0.625rem 0.75rem;
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 0.25rem 0.5rem;
  align-items: baseline;
  transition: border-color var(--app-motion-fast) var(--app-ease);
}

.indicator:hover {
  border-color: var(--p-surface-600);
}

.indicator__value {
  font-size: var(--app-text-lg);
  font-weight: 700;
  justify-self: end;
  white-space: nowrap;
  color: var(--p-tone-neutral-text);
}

/*
 * 값의 색조. 판정 3상태와 같은 초록·빨강이되 **알약이 아니다** — 형태로 채널을 가른 것이고
 * 그래서 판정 Tag가 함께 나오는 결과 화면에는 이 색조를 두지 않는다(ADR-0021).
 */
.indicator__value--favorable {
  color: var(--p-tone-favorable-text);
}

.indicator__value--unfavorable {
  color: var(--p-tone-unfavorable-text);
}

/* 단위는 숫자를 읽고 난 다음이다. 같은 무게면 자릿수 대신 기호에 눈이 걸린다. */
.indicator__unit {
  margin-inline-start: 0.0625rem;
  font-size: var(--app-text-xs);
  font-weight: 600;
  color: var(--p-text-muted-color);
}

.indicator__meter {
  grid-column: 1 / -1;
  margin-top: 0.125rem;
}

/*
 * 설명은 두 줄까지만. 전문은 이름 옆 `ⓘ`가 **이미 같은 문장을 들고 있다**(`IndicatorLabel`).
 *
 * 자르는 이유는 자리 때문만이 아니다. 잘리지 않으면 문단이 카드 잉크의 절반을 넘어, 값을
 * 훑으려는 눈이 카드마다 산문을 먼저 만난다. 높이가 들쭉날쭉해 그리드에 구멍이 생기는 것도
 * 그 부작용이다.
 */
.indicator__interpretation {
  grid-column: 1 / -1;
  display: -webkit-box;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  line-clamp: 2;
  overflow: hidden;
  margin: 0;
  font-size: var(--app-text-xs);
  color: var(--p-text-subtle-color);
  line-height: 1.5;
}

@media (max-width: 767px) {
  .stock-detail {
    padding: 1rem;
    gap: 1rem;
  }

  /* 모바일은 지표를 한 줄씩 훑는다. 두 열로 만들면 설명이 잘려 의미가 사라진다. */
  .indicator-group__list {
    grid-template-columns: 1fr;
  }
}
</style>
