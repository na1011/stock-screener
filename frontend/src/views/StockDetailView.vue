<script setup lang="ts">
import Button from 'primevue/button'
import { computed, onMounted, ref } from 'vue'
import { RouterLink, useRoute } from 'vue-router'

import { api } from '@/api/endpoints'
import type { StockIndicatorValue } from '@/api/types'
import AsyncPanel from '@/components/AsyncPanel.vue'
import AppViewHeader from '@/components/ui/AppViewHeader.vue'
import IndicatorCard from '@/components/stock/IndicatorCard.vue'
import FinancialsPanel from '@/components/stock/FinancialsPanel.vue'
import PriceReferencePanel from '@/components/stock/PriceReferencePanel.vue'
import StockHeader from '@/components/stock/StockHeader.vue'
import SectionHeading from '@/components/ui/SectionHeading.vue'
import { useAsync } from '@/composables/useAsync'
import { useBreakpoint } from '@/composables/useBreakpoint'
import { readBand } from '@/lib/band'
import { conditionIndicatorCodes } from '@/lib/draftIndicators'
import { hiddenCount, isVisibleByDefault, missingCount as countMissing } from '@/lib/detailVisibility'
import { MISSING, splitIndicatorValue } from '@/lib/format'
import { DETAIL_CATEGORY_RANK, groupByCategory } from '@/lib/indicatorCategory'
import { indicatorBriefOf } from '@/lib/resultRow'
import { useCatalogStore } from '@/stores/catalog'
import { useScreenDraftStore } from '@/stores/screenDraft'

const props = defineProps<{ stockId: string }>()

const route = useRoute()
const catalog = useCatalogStore()
const draft = useScreenDraftStore()
const { isMobile } = useBreakpoint()

const stockId = computed(() => Number(props.stockId))
const asOf = computed(() => (typeof route.query.asOf === 'string' ? route.query.asOf : null))

const detail = useAsync((signal) => api.stock(stockId.value, asOf.value, signal))

/**
 * 이 종목의 금액 표기 통화. 시세·재무·지지저항이 전부 이것 하나를 쓴다.
 *
 * 상세 응답이 아직 안 왔으면 `null`이고, 그때는 금액에 통화를 붙이지 않는다 —
 * 기본값으로 원화를 그리면 미국 종목의 첫 렌더가 잠깐 「150원」이 된다.
 */
const currencyCode = computed(() => detail.data.value?.stock.currencyCode ?? null)
/**
 * 지지·저항은 **별도 요청**이다.
 *
 * 한 응답에 합치지 않는 이유가 셋이다 — 한쪽이 실패해도 다른 쪽은 살고(ADR-0014), 지표가
 * 아니라는 사실이 구조로 드러나며, 모바일에서는 눌러서 받을 수 있다.
 */
const priceReference = useAsync((signal) => api.priceReference(stockId.value, asOf.value, signal))
/**
 * 실적도 별도 요청이다(ADR-0059). 지지·저항과 달리 **처음부터 불러온다** — 접어 둘 이유가
 * 없는 값이고, 지연 로드는 계산이 무거운 쪽에 쓰는 수단이다.
 */
const financials = useAsync((signal) => api.stockFinancials(stockId.value, signal))

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
    // 감춘 지표는 여기서만 빠진다. 「내 조건」 구획은 이 필터를 타지 않는다(ADR-0058).
    (detail.data.value?.indicators ?? []).filter(isVisibleByDefault),
    (indicator) => indicator.category,
    DETAIL_CATEGORY_RANK,
  ).map((group) => ({
    ...group,
    items: group.items.map((indicator) => ({
      indicator,
      reading: readingOf(indicator),
      parts: splitIndicatorValue(
        indicator.value,
        indicator.unit,
        indicator.decimalScale,
        currencyCode.value,
      ),
    })),
  })),
)

/**
 * 스크리너에 세워 둔 조건이 이 종목에서 얼마인가.
 *
 * 분류 구획보다 **위**에 온다. 조건을 세우고 종목을 눌러 들어온 사용자가 가장 먼저 확인할
 * 것이 그 값이기 때문이다.
 *
 * IMPORTANT — **값이 없는 지표도 그린다.** 결과 표에서 빠진 종목이라면 "왜 없는가"의 답이
 * 여기밖에 없다(ADR-0055). 값이 없다는 것과 조건에 안 맞았다는 것은 다른 사실인데, 카드를
 * 지우면 둘이 같아 보인다.
 *
 * 아래 분류 구획과 **중복을 허용한다.** 아래에서 빼면 "재무 지표" 구획이 조건 편집 상태에
 * 따라 PER을 잃는다 — 분류가 초안의 함수가 되어 사용자가 기대한 자리에서 지표가 조용히
 * 사라진다. 위는 핀 보드이고 아래는 안정된 분류다.
 *
 * 새로고침하면 초안이 비어 구획이 통째로 사라진다. "조건 없음"을 그리지 않는다 — 그러면
 * "조건이 없다"와 "초안을 잃었다"를 구별 못 하는 채로 앞의 것을 단정하게 된다.
 */
const conditionRows = computed(() => {
  const codes = conditionIndicatorCodes(draft.conditions)
  if (codes.length === 0) {
    return []
  }
  const values = new Map(
    (detail.data.value?.indicators ?? []).map((indicator) => [indicator.code, indicator]),
  )
  return codes.map((code) => {
    const indicator = values.get(code)
    if (indicator !== undefined) {
      return {
        code,
        name: indicator.name,
        higherIsBetter: indicator.higherIsBetter,
        description: indicator.description,
        interpretation: indicator.interpretation,
        parts: splitIndicatorValue(
        indicator.value,
        indicator.unit,
        indicator.decimalScale,
        currencyCode.value,
      ),
        reading: readingOf(indicator),
        // MUST: 아래 분류 구획과 **같은 사유**를 보인다. 이 가지가 카드 객체를 따로 만들기
        // 때문에 빠뜨리기 쉬운데, 빠뜨리면 같은 화면 위아래에 사유 있는 `—`와 없는 `—`가
        // 함께 뜬다. ADR-0058이 허용한 중복은 같은 내용의 중복이다.
        unavailableReason: indicator.unavailableReason,
      }
    }
    // 값이 없다. 이름·단위는 카탈로그에서 오고, 카탈로그에도 없으면(폐기된 지표를 조건에
    // 들고 있는 전략) `indicatorBriefOf`가 코드를 그대로 이름 자리에 둔다.
    const brief = indicatorBriefOf(code, [], catalog.byCode)
    return {
      code,
      name: brief.name,
      higherIsBetter: brief.higherIsBetter,
      description: catalog.byCode.get(code)?.description ?? null,
      interpretation: brief.interpretation,
      parts: splitIndicatorValue(null, brief.unit, brief.decimalScale, currencyCode.value),
      reading: null,
      // 응답에 아예 없는 지표다. 서버가 사유를 말할 수 있었다면 위 가지로 왔을 것이므로
      // 여기서는 지어내지 않는다.
      unavailableReason: null,
    }
  })
})

/** 분류 구획에 실제로 그린 카드 수. 아래 두 고지가 이것을 기준으로 센다. */
const shownCount = computed(() =>
  groups.value.reduce((total, group) => total + group.items.length, 0),
)

/**
 * 값이 없어 목록에 오르지 못한 지표 수.
 *
 * 서버는 값이 없는 지표의 행 자체를 보내지 않는다. 그것을 그냥 두면 화면에는 "지표가 원래
 * 그것뿐"인 것처럼 보인다. 몇 개가 왜 빠졌는지 한 줄로 알린다.
 */
const missingCount = computed(() =>
  detail.data.value === null ? 0 : countMissing(catalog.indicators, shownCount.value),
)

/**
 * 기본 화면에서 감춘 지표 수.
 *
 * 사라졌다는 사실이 화면에 남아야 한다 — 걸러내고 아무 말도 하지 않는 것이 던지는 것보다
 * 나쁘다(`.claude/rules/frontend.md`).
 */
const hiddenIndicatorCount = computed(() => hiddenCount(detail.data.value?.indicators ?? []))

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
  void financials.run()
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

          <!--
            스크리너에 세워 둔 조건이 맨 위에 온다. 아래 분류 구획에도 같은 지표가 다시
            나오는 것은 의도다 — 여기서 빼면 분류가 초안에 따라 흔들린다.
          -->
          <section v-if="conditionRows.length > 0" class="indicator-group">
            <SectionHeading title="내 조건" :count="conditionRows.length" />
            <p class="indicator-group__note">
              스크리너에 세워 둔 조건이 이 종목에서 얼마인지 보여 줍니다. 아래 분류에 없는
              세부 지표도 여기에는 나옵니다.
            </p>
            <ul class="indicator-group__list">
              <IndicatorCard
                v-for="row in conditionRows"
                :key="row.code"
                :name="row.name"
                :higher-is-better="row.higherIsBetter"
                :percentile="percentileOf(row.code)"
                :description="row.description"
                :interpretation="row.interpretation"
                :parts="row.parts"
                :reading="row.reading"
                :unavailable-reason="row.unavailableReason"
              />
            </ul>
          </section>

          <section v-for="group in groups" :key="group.category" class="indicator-group">
            <SectionHeading :title="group.label" :count="group.items.length" />
            <ul class="indicator-group__list">
              <IndicatorCard
                v-for="row in group.items"
                :key="row.indicator.code"
                :name="row.indicator.name"
                :higher-is-better="row.indicator.higherIsBetter"
                :percentile="percentileOf(row.indicator.code)"
                :description="row.indicator.description"
                :interpretation="row.indicator.interpretation"
                :parts="row.parts"
                :reading="row.reading"
                :unavailable-reason="row.indicator.unavailableReason"
              />
            </ul>
          </section>

          <p v-if="hiddenIndicatorCount > 0" class="stock-detail__missing">
            세부 지표 {{ hiddenIndicatorCount }}개는 기본 화면에서 감췄습니다. 스크리너에서 조건에
            추가하면 맨 위 「내 조건」에 값이 나옵니다.
          </p>

          <!--
            문구가 이번에 바뀌었다. 예전에는 "값이 —라는 뜻이 아닙니다"였는데, 이제 사유를
            말할 수 있는 지표는 실제로 `—` 카드로 남는다(ADR-0060). 두 상태를 화면이 가른다 —
            **이유를 아는 것은 카드로, 모르는 것은 이 수로.**
          -->
          <p v-if="missingCount > 0" class="stock-detail__missing">
            값이 없어 목록에 넣지 못한 지표가 {{ missingCount }}개 있습니다. 계산에 필요한 이력이나
            공시가 아직 없다는 뜻입니다. 이유를 말할 수 있는 지표는 위에 {{ MISSING }}로 남아
            그 이유를 함께 보여 줍니다.
          </p>

          <!--
            업종 지표는 표본이 모자라면 **행 자체가 없다.** 위 `missingCount`는 "값이 없다"를 세는데
            그 지표들은 애초에 응답에 오지 않아 거기서도 빠진다. 그래서 이유를 여기서 따로 말한다.
            문구는 서버가 소유한다 — 화면이 자기 문장을 만들면 두 곳이 갈린다(ADR-0044).
          -->
          <p
            v-if="detail.data.value?.sectorContext?.notice"
            class="stock-detail__missing"
          >
            {{ detail.data.value.sectorContext.notice }}
          </p>
        </template>
      </AsyncPanel>

      <!-- 실적도 별도 요청이다. 지표 구획이 실패해도 여기는 살고, 반대도 마찬가지다(ADR-0059). -->
      <AsyncPanel
        :state="financials.state.value"
        :error="financials.error.value"
        :retry="financials.retry"
        panel="financials"
        :skeleton-rows="4"
      >
        <FinancialsPanel
          v-if="financials.data.value"
          :financials="financials.data.value"
          :currency-code="currencyCode"
        />
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
          <PriceReferencePanel
            v-if="priceReference.data.value"
            :reference="priceReference.data.value"
            :currency-code="currencyCode"
          />
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

/* 구획 머리 아래 한 줄. 이 구획이 왜 여기 있는지를 설명한다. */
.indicator-group__note {
  margin: 0 0 0.5rem;
  font-size: var(--app-text-xs);
  color: var(--p-text-subtle-color);
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
