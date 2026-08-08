<script setup lang="ts">
import { useId } from 'vue'

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

/**
 * 이 값이 원주가라는 사실.
 *
 * IMPORTANT — 실재하는 구분이다. 백엔드가 **기술적 지표를 예외 없이 수정종가로 계산**하는데
 * (`PriceBar`) 여기 보이는 것은 사용자가 실제로 지불하는 원주가다(`StockReader`). 액면분할이
 * 있었던 종목이면 두 값이 갈리고, 그때 "차트와 숫자가 다르다"가 된다.
 *
 * 예전에는 라벨에 "종가(원주가)"로 붙어 있었다. 그 괄호가 라벨 폭을 155px로 늘려 카드가
 * 접히는 시점을 앞당기고 있어서 뺐고, 사실 자체는 여기로 옮긴다 — 없애는 것이 아니다.
 * 지지·저항 패널의 "(수정종가 — 실제 매매 가격과 다를 수 있습니다)"가 반대쪽 짝이다.
 */
const UNADJUSTED_PRICE_NOTICE =
  '실제로 지불하는 원주가입니다. 지표는 수정종가로 계산하므로 액면분할이 있었다면 두 값이 다를 수 있습니다.'

/**
 * 위 문구를 낭독기에 연결하는 id.
 *
 * IMPORTANT — **PrimeVue 툴팁은 `aria-describedby`를 달지 않는다.** 실측으로 확인했다.
 * 그래서 툴팁만 붙이면 초점은 닿는데 읽히는 것은 숫자뿐이고, `tabindex`가 얻는 것 없이 탭
 * 정지만 하나 늘린다. 숨은 문단을 두고 직접 잇는다.
 */
const noticeId = useId()
</script>

<template>
  <header class="stock-header">
    <!--
      IMPORTANT — 네 요소가 **grid의 직계 자식**이다. 좌우를 각각 상자로 묶지 않는다.

      묶으면 두 번째 줄(티커 / 기준일)이 어긋난다. flex의 `align-items: baseline`은 각
      flex 아이템의 **첫** 베이스라인만 맞추기 때문이다 — 그 안의 둘째 줄은 각 상자의
      line-height가 정하고, 28px 이름과 24px 가격은 그 값이 달라 5.5px 어긋났다.
      grid는 **행마다** 베이스라인 그룹을 만들어 두 줄이 각각 맞는다.

      DOM 순서는 화면 배치와 무관하다(`grid-area`가 정한다). 그래서 낭독 순서를
      "이름 → 티커 → 가격 → 기준일"로 두어 정체와 값이 섞이지 않게 한다.
    -->
    <div class="stock-header__top">
      <h1 class="stock-header__name">{{ stock.name }}</h1>
      <p class="stock-header__meta">
        {{ stock.ticker }} · {{ stock.marketCode }}
        <template v-if="stock.sector"> · {{ stock.sector }}</template>
      </p>
      <!--
        수식자 셋이 각각 필요하다.
        - `bottom` — 카드 안에서 아래로 편다. 위로 펴면 뒤로가기 줄을 덮는다.
        - `focus` — **PrimeVue는 이 수식자가 있을 때만 `focus` 이벤트를 바인딩한다**(소스 확인).
          없으면 `mouseenter`만 듣는 마우스 전용이 되어 `tabindex`가 헛돈다.
        - `tabindex="0"` — 다른 툴팁은 전부 버튼·링크에 붙어 초점이 저절로 오지만 여기는
          숫자를 담은 `<span>`이다.

        그래도 툴팁은 **눈**에만 닿는다. 낭독기 몫은 아래 `aria-describedby`가 맡는다.
      -->
      <span
        v-tooltip.bottom.focus="UNADJUSTED_PRICE_NOTICE"
        class="stock-header__price-value tabular"
        tabindex="0"
        :aria-describedby="noticeId"
      >
        {{ formatPrice(price?.close ?? null) }}
      </span>
      <!-- 툴팁은 눈으로만 읽힌다. 같은 문구를 낭독기 몫으로 한 벌 더 둔다. -->
      <span :id="noticeId" class="stock-header__sr-only">{{ UNADJUSTED_PRICE_NOTICE }}</span>
      <span class="stock-header__price-label">기준일 {{ formatDate(asOf) }} 종가</span>
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
/*
 * IMPORTANT — 이 카드가 자기 **컨테이너 질의 기준**이다. 아래 `@container`가 이것을 잰다.
 *
 * 뷰포트 미디어 쿼리로는 이 판단을 표현할 수 없다. 900px에서 좌측 레일(15rem)이 서랍으로
 * 바뀌므로 **카드 폭이 뷰포트에 단조증가하지 않는다** — 실측으로 뷰포트 890px일 때 카드가
 * 842px이고 950px일 때 662px이다. "화면이 좁은가"와 "카드가 좁은가"가 다른 질문이라,
 * 뷰포트로 접으면 넓은 카드를 좁은 것으로 오인해 이르게 쌓는다(767px에서 그랬다).
 *
 * 그래서 이 경계는 `lib/breakpoints.ts`에 넣지 않는다. 거기 있는 셋은 **뷰포트** 경계이고
 * JS(`useBreakpoint`)와 CSS 양쪽에 걸쳐 있어 어긋날 수 있어서 한 곳에 모은 것인데,
 * 컨테이너 질의는 JS 짝이 없어 어긋날 대상 자체가 없다.
 */
.stock-header {
  container-type: inline-size;
  container-name: stock-header;
  border: 1px solid var(--p-content-border-color);
  border-radius: var(--p-border-radius-md);
  background: var(--app-surface-raised);
  box-shadow: var(--app-edge-highlight);
}

/*
 * 2행 × 2열 grid. flex가 아닌 이유는 위 템플릿 주석에 있다 — **두 줄을 각각 맞춰야 한다.**
 *
 * `minmax(0, 1fr)`의 0이 핵심이다. grid 트랙의 기본 최소값은 `auto`(=내용 크기)라, 긴
 * 종목명이 트랙을 밀어 가격을 카드 밖으로 내보낸다. 0으로 내려야 이름이 줄어든다.
 */
.stock-header__top {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  grid-template-areas:
    'name  price'
    'meta  asof';
  align-items: baseline;
  column-gap: 1rem;
  row-gap: 0.25rem;
  padding: 0.875rem 1rem 0;
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
  grid-area: name;
  margin: 0;
  font-size: var(--app-text-3xl);
  font-weight: 800;
  line-height: 1.2;
  letter-spacing: -0.02em;
  color: var(--p-text-hover-color);
  overflow-wrap: anywhere;
}

/* 티커·시장은 있어야 하지만 읽을 차례가 마지막이다. 간격은 grid의 row-gap이 준다. */
.stock-header__meta {
  grid-area: meta;
  margin: 0;
  color: var(--p-text-subtle-color);
  font-size: var(--app-text-xs);
}

.stock-header__price-value {
  grid-area: price;
  justify-self: end;
  font-size: var(--app-text-2xl);
  font-weight: 700;
  line-height: 1.15;
  letter-spacing: -0.02em;
  color: var(--p-text-hover-color);
  /*
   * 툴팁이 있다는 것을 알려야 한다. 아무 표시가 없으면 우연히 마우스를 올린 사람만 읽는다.
   *
   * 점선 밑줄은 "설명이 붙어 있다"의 오래된 관용이고, 실선과 달리 링크로 오해되지 않는다.
   * 값 자체는 건드리지 않으므로 숫자 읽기를 방해하지 않는다 — `text-underline-offset`으로
   * 자릿수에서 떨어뜨린다.
   */
  cursor: help;
  text-decoration: underline dotted var(--p-text-subtle-color);
  text-underline-offset: 0.3em;
  text-decoration-thickness: 1px;
}

/*
 * 낭독기에만 보이는 문단. `display: none`이나 `visibility: hidden`은 낭독기에서도 사라지므로
 * 쓸 수 없다 — 1px로 잘라 화면에서만 지운다.
 *
 * 전역 유틸리티로 올리지 않는다. 지금 소비처가 여기 하나뿐이고, 이 저장소는 둘 이상이
 * 같은 것을 그릴 때만 밖으로 뺀다(`.claude/rules/frontend.md`).
 */
.stock-header__sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  margin: -1px;
  padding: 0;
  overflow: hidden;
  clip-path: inset(50%);
  white-space: nowrap;
  border: 0;
}

/* 초점은 반드시 보여야 한다 — `tabindex`로 닿을 수 있게 만든 요소이므로 더욱 그렇다. */
.stock-header__price-value:focus-visible {
  outline: var(--p-focus-ring-width) var(--p-focus-ring-style) var(--p-focus-ring-color);
  outline-offset: 2px;
  border-radius: var(--p-border-radius-sm);
}

.stock-header__price-label {
  grid-area: asof;
  justify-self: end;
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

/*
 * **카드가** 좁을 때만 한 열로 쌓는다. 뷰포트가 아니라 카드를 재는 이유는 위 `.stock-header`
 * 주석에 있다.
 *
 * 24rem(384px)인 근거: 오른쪽 열이 실제로 차지하는 폭이 "기준일 …" 라벨의 ~130px이고,
 * 열 간격 1rem을 빼면 이름에 ~240px이 남는다. 그 아래로 내려가야 28px 이름이 서너 줄이
 * 되기 시작한다. 실측으로 카드 486px일 때 이름 트랙이 283px였다 — 767px 뷰포트에서
 * 쌓던 예전 규칙은 그 여유를 버리고 있었다.
 */
@container stock-header (max-width: 24rem) {
  .stock-header__top {
    grid-template-columns: minmax(0, 1fr);
    grid-template-areas:
      'name'
      'meta'
      'price'
      'asof';
  }

  /* 쌓이면 우측 정렬이 어색하다 — 전부 왼쪽 기준선에 맞춘다. */
  .stock-header__price-value,
  .stock-header__price-label {
    justify-self: start;
  }

  /*
   * 한 단계씩 내린다. **내려도 이름 > 가격 관계는 유지한다** — 그것이 이 화면의 주어이고,
   * 순서가 뒤집히면 좁은 카드에서만 주어가 달라진다.
   */
  .stock-header__name {
    font-size: var(--app-text-2xl);
  }

  .stock-header__price-value {
    font-size: var(--app-text-xl);
  }
}
</style>
