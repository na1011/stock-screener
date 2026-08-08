<script setup lang="ts">
import type { IsoDate, StockPrice, StockSummary } from '@/api/types'
import WatchButton from '@/components/watchlist/WatchButton.vue'
import { formatCount, formatDate, formatPrice } from '@/lib/format'

/**
 * 종목 상세의 요약 카드.
 *
 * "이 종목이 무엇이고, 얼마이고, 무엇을 할 수 있는가"를 한 덩어리로 답한다.
 *
 * IMPORTANT — **소속(유니버스)과 액션(담기·판정)을 한 줄의 양끝에 둔다.** 예전에는 담기 버튼이
 * 위쪽 줄의 오른쪽 끝, 가격이 아래쪽 줄의 오른쪽 끝이라 **둘이 같은 x에 세로로 쌓여 있었고**,
 * 사이 간격이 음수가 되는 순간 버튼이 가격 위에 얹혔다. 지금은 겹칠 수 있는 배치 자체가 없다.
 *
 * 카드는 새로 만든 것이 아니다. OHLC 띠가 이미 `--app-surface-raised` 카드였고, 그 위에
 * 테두리로 끊은 헤더가 따로 있어 층이 둘이었다. 하나로 합쳐 이 화면의 hero로 만든다.
 *
 * 뒤로가기(`‹ 스크리너`)는 여기 없다. 그것은 이 종목에 관한 것이 아니라 **페이지 이동**이라
 * 호출부에 남는다.
 */
defineProps<{
  stock: StockSummary
  /** 기준일에 시세 행이 없으면 null. 그때는 OHLC 띠를 통째로 그리지 않는다. */
  price: StockPrice | null
  asOf: IsoDate
}>()
</script>

<template>
  <header class="stock-header">
    <div class="stock-header__top">
      <div class="stock-header__identity">
        <h1 class="stock-header__name">{{ stock.name }}</h1>
        <p class="stock-header__meta">
          {{ stock.ticker }} · {{ stock.marketCode }}
          <template v-if="stock.sector"> · {{ stock.sector }}</template>
        </p>
      </div>
      <div class="stock-header__price">
        <span class="stock-header__price-value tabular">
          {{ formatPrice(price?.close ?? null) }}
        </span>
        <span class="stock-header__price-label">
          기준일 {{ formatDate(asOf) }} 종가(원주가)
        </span>
      </div>
    </div>

    <div class="stock-header__base">
      <!--
        유니버스는 판정이 아니라 소속이다. `Tag`를 쓰지 않는 이유가 그것이다 — 이 화면의
        `Tag`는 판정 3상태가 쓰는 형태이고(ADR-0021), 소속 배지가 같은 모양이면 "충족"과
        한 종류로 읽힌다. accent를 옅게 깔아 머리글에 색을 하나 준다.
      -->
      <div class="stock-header__universes">
        <span
          v-for="universe in stock.universes"
          :key="universe.code"
          class="stock-header__universe"
        >
          {{ universe.name }}
        </span>
      </div>
      <!--
        담기를 여기 두는 이유는 spec §5의 흐름이다 — 종목을 깊이 들여다본 그 자리에서
        추적을 시작할 수 있어야 한다. 별도 화면으로 빼면 흐름이 여기서 끊긴다.
      -->
      <WatchButton :stock-id="stock.id" :stock-name="stock.name" />
    </div>

    <!-- 시세가 없으면 띠를 그리지 않는다. 빈 칸 다섯 개를 남기면 0인 것으로 읽힌다. -->
    <section v-if="price" class="stock-header__ohlc">
      <span>
        <span class="stock-header__ohlc-label">시가</span>
        <span class="tabular">{{ formatPrice(price.open) }}</span>
      </span>
      <span>
        <span class="stock-header__ohlc-label">고가</span>
        <span class="tabular">{{ formatPrice(price.high) }}</span>
      </span>
      <span>
        <span class="stock-header__ohlc-label">저가</span>
        <span class="tabular">{{ formatPrice(price.low) }}</span>
      </span>
      <span>
        <span class="stock-header__ohlc-label">거래량</span>
        <span class="tabular">{{ formatCount(price.volume) }}</span>
      </span>
      <span>
        <span class="stock-header__ohlc-label">상장주식수</span>
        <span class="tabular">{{ formatCount(price.listedShares) }}</span>
      </span>
    </section>
    <p v-else class="stock-header__no-price">이 날짜의 시세가 없습니다.</p>
  </header>
</template>

<style scoped>
.stock-header {
  border: 1px solid var(--p-content-border-color);
  border-radius: var(--p-border-radius-md);
  background: var(--app-surface-raised);
  box-shadow: var(--app-edge-highlight);
}

.stock-header__top {
  display: flex;
  /*
   * `flex-start`가 아니라 `baseline`이다. 앞의 것은 두 덩어리의 **윗변**을 맞추는데, 28px
   * 이름과 24px 가격은 글자 크기가 달라 윗변이 같아도 글자가 6px 어긋나 앉는다. 크기가
   * 다른 두 글자를 나란히 두는 자리에서는 baseline이 유일하게 맞는 기준선이다.
   */
  align-items: baseline;
  justify-content: space-between;
  gap: 1rem;
  flex-wrap: wrap;
  padding: 0.875rem 1rem 0;
}

.stock-header__identity {
  /* 긴 종목명이 오른쪽 가격 블록을 밀어내지 않게 한다. */
  min-width: 0;
}

/*
 * 이 화면의 **주어**다. 예전에는 20px이라 24px 가격보다 작았고, 그래서 무엇에 관한
 * 페이지인지보다 얼마인지가 먼저 읽혔다. 주어가 값보다 커야 한다.
 *
 * IMPORTANT — 크기만 키우면 절반만 고쳐진다. **밝기도 가격보다 위여야 한다.** 이름이
 * `--p-text-color`(#dce3ed), 가격이 `--p-text-hover-color`(#f2f5f9)였을 때는 크기를
 * 뒤집어도 눈은 여전히 가격을 먼저 봤다. 램프의 가장 밝은 칸은 주어가 가져간다.
 *
 * 굵기 800은 이 앱에서 여기뿐이다(나머지는 600·700). h1이 하나뿐인 화면이라 정당하고,
 * Pretendard Variable이 45~920이라 별도 파일을 받지 않는다(ADR-0022).
 */
.stock-header__name {
  margin: 0;
  font-size: var(--app-text-3xl);
  font-weight: 800;
  line-height: 1.2;
  letter-spacing: -0.02em;
  color: var(--p-text-hover-color);
  overflow-wrap: anywhere;
}

/* 티커·시장은 있어야 하지만 읽을 차례가 마지막이다. */
.stock-header__meta {
  margin: 0.25rem 0 0;
  color: var(--p-text-subtle-color);
  font-size: var(--app-text-xs);
}

.stock-header__price {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  /* 가격은 줄바꿈되면 자릿수가 끊겨 읽히지 않는다. */
  flex: 0 0 auto;
}

.stock-header__price-value {
  font-size: var(--app-text-2xl);
  font-weight: 700;
  line-height: 1.15;
  letter-spacing: -0.02em;
  color: var(--p-text-hover-color);
}

.stock-header__price-label {
  font-size: var(--app-text-2xs);
  color: var(--p-text-subtle-color);
}

/*
 * 소속과 액션이 한 줄의 양끝에 선다. 이것이 이 컴포넌트의 요점이다 — 겹칠 수 있는
 * 세로 스택이 아니라 같은 flex 라인의 두 끝이라, 좁아져 wrap돼도 서로 침범하지 않는다.
 */
.stock-header__base {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 0.75rem;
  flex-wrap: wrap;
  padding: 0.75rem 1rem;
}

.stock-header__universes {
  display: flex;
  gap: 0.375rem;
  flex-wrap: wrap;
  min-width: 0;
}

/*
 * IMPORTANT — **윤곽형이다. 채우지 않는다.**
 *
 * 소속 배지와 판정 `Tag`가 이제 같은 줄에 선다(위 `__base`). 예전에는 다른 줄이라 둘 다
 * 채워진 알약이어도 헷갈리지 않았지만, 나란히 놓이는 순간 ADR-0021이 막은 상태가 된다 —
 * "소속 배지가 판정과 한 종류로 읽히면 안 된다".
 *
 * 색으로 가르지 않고 **형태로** 가른다. 판정은 채워진 알약, 소속은 윤곽. ADR-0021이
 * 초록·빨강의 두 뜻을 가를 때 쓴 것과 같은 수법이고, 새 색을 쓰지 않는다.
 */
.stock-header__universe {
  padding: 0.1875rem 0.4375rem;
  border: 1px solid var(--app-accent-border);
  border-radius: var(--p-border-radius-sm);
  color: var(--p-primary-300);
  font-size: var(--app-text-2xs);
  font-weight: 600;
}

/*
 * 시가·고가·저가·거래량은 값의 나열이라 칸으로 나눈다. 나누지 않으면 어디까지가 한
 * 항목인지 눈으로 끊기지 않는다.
 *
 * 자기 테두리·면·반경을 갖지 않는다 — 카드 **안**이라 위쪽 선 하나면 충분하고, 겹쳐 두면
 * 카드 안에 카드가 있는 것으로 보인다.
 */
.stock-header__ohlc {
  display: flex;
  flex-wrap: wrap;
  font-size: var(--app-text-sm);
  border-top: 1px solid var(--p-content-border-color);
}

.stock-header__ohlc > span {
  display: flex;
  align-items: baseline;
  gap: 0.375rem;
  flex: 1 1 auto;
  min-width: 0;
  padding: 0.4375rem 1rem;
  border-inline-start: 1px solid var(--p-content-border-color);
  white-space: nowrap;
}

.stock-header__ohlc > span:first-child {
  border-inline-start: 0;
}

/* 라벨은 짚는 것이고 값은 읽는 것이다. 둘이 같은 크기면 다섯 칸이 열 개의 낱말로 보인다. */
.stock-header__ohlc-label {
  font-size: var(--app-text-2xs);
  color: var(--p-text-subtle-color);
}

.stock-header__ohlc .tabular {
  color: var(--p-text-hover-color);
  font-weight: 600;
}

.stock-header__no-price {
  margin: 0;
  padding: 0.5rem 1rem 0.75rem;
  border-top: 1px solid var(--p-content-border-color);
  font-size: var(--app-text-sm);
  color: var(--p-text-muted-color);
}

@media (max-width: 767px) {
  /* 가격이 종목명 아래로 내려오면 우측 정렬이 어색하다 — 왼쪽 기준선에 맞춘다. */
  .stock-header__price {
    align-items: flex-start;
  }

  /*
   * 좁은 폭에서는 한 단계씩 내린다. 28px 이름은 360px 폭에서 두 줄이 되기 쉽고, 그러면
   * 카드 절반이 제목이 된다. **내려도 이름 > 가격 관계는 유지한다** — 그것이 이 화면의
   * 주어이기 때문이고, 순서가 뒤집히면 좁은 폭에서만 주어가 달라진다.
   */
  .stock-header__name {
    font-size: var(--app-text-2xl);
  }

  .stock-header__price-value {
    font-size: var(--app-text-xl);
  }
}
</style>
