<script setup lang="ts">
import { computed, useId } from 'vue'

import type { IsoDate, StockPrice, StockSummary, UnadjustedActions } from '@/api/types'
import WatchButton from '@/components/watchlist/WatchButton.vue'
import { formatCount, formatDate, formatPrice } from '@/lib/format'
import { formatQuoteChange, quoteBasisLabel, quoteTone } from '@/lib/quote'

/**
 * 종목 상세의 요약 카드.
 *
 * "이 종목이 무엇이고, 얼마이고, 무엇을 할 수 있는가"를 한 덩어리로 답한다.
 *
 * IMPORTANT — **소속 지수와 액션(담기·판정)을 한 줄의 양끝에 둔다.** 예전에는 담기 버튼이
 * 위쪽 줄의 오른쪽 끝, 가격이 아래쪽 줄의 오른쪽 끝이라 **둘이 같은 x에 세로로 쌓여 있었고**,
 * 사이 간격이 음수가 되는 순간 버튼이 가격 위에 얹혔다. 지금은 겹칠 수 있는 배치 자체가 없다.
 *
 * 카드는 새로 만든 것이 아니다. OHLC 띠가 이미 `--app-surface-raised` 카드였고, 그 위에
 * 테두리로 끊은 헤더가 따로 있어 층이 둘이었다. 하나로 합쳐 이 화면의 hero로 만든다.
 *
 * 뒤로가기(`‹ 스크리너`)는 여기 없다. 그것은 이 종목에 관한 것이 아니라 **페이지 이동**이라
 * 호출부에 남는다.
 */
const props = defineProps<{
  stock: StockSummary
  /** 기준일에 시세 행이 없으면 null. 그때는 OHLC 띠를 통째로 그리지 않는다. */
  price: StockPrice | null
  asOf: IsoDate
  /** 수정계수를 확정하지 못한 기업 행위. `effectiveDates`가 비면 아무것도 그리지 않는다. */
  unadjustedActions: UnadjustedActions
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

/**
 * 등락의 방향. 색만 이것으로 고른다.
 *
 * IMPORTANT — 템플릿에서 `changeRate > 0`으로 가르지 않는다. 그러면 `null`(값 없음)과
 * `0`(보합)이 같은 분기로 떨어진다. 판정의 정본은 `lib/quote.ts`다.
 */
const tone = computed(() => quoteTone(props.price?.changeRate))

/**
 * 무엇에 견준 값인가. 기준일 캡션 뒤에 붙는다.
 *
 * 툴팁이 아니라 **글자로** 둔다. 툴팁으로 두면 탭 정지가 하나 늘고, 그러고도 마우스를 올린
 * 사람만 읽는다 — 이 화면에서 가장 자주 묻는 것("무엇에 견준 값인가")을 그렇게 감출 이유가
 * 없다. 기준일이 이미 캡션으로 있으므로 그 옆이 제자리다.
 */
const basis = computed(() => quoteBasisLabel(props.price?.previousTradeDate))
</script>

<template>
  <header class="stock-header">
    <!--
      읽는 순서가 곧 배치다 — **정체(이름·티커·소속) → 값(가격·등락) → 그 값이 언제 것인가.**
      셋이 왼쪽 기준선 하나를 따라 세로로 흐르므로 눈이 가로로 튀지 않는다.

      예전에는 가격이 오른쪽 열에 있었다. 이름과 가격이 카드의 양 끝으로 벌어져 시선이 두
      번 왕복했고, 등락까지 옆에 붙자 오른쪽 위 구석에 네 요소가 뭉쳤다.

      오른쪽에 남는 것은 **액션뿐이다.** 담기·판정은 값이 아니라 할 수 있는 일이라 읽는
      흐름 바깥에 서야 한다. spec §5가 요구하는 "깊이 들여다본 그 자리에서 추적 시작"은
      그대로다 — 자리만 위로 올라왔다.
    -->
    <div class="stock-header__top">
      <div class="stock-header__identity">
        <h1 class="stock-header__name">{{ stock.name }}</h1>
        <p class="stock-header__meta">
          <!--
            텍스트를 span으로 감싼다. `display: flex`에서는 **텍스트 노드 하나하나가 익명 flex
            항목**이 되어, 감싸지 않으면 `· 반도체`가 별도 항목으로 떨어져 나가 gap이 두 번
            들어간다.
          -->
          <span>
            {{ stock.ticker }} · {{ stock.marketCode }}
            <template v-if="stock.sector"> · {{ stock.sector }}</template>
          </span>
          <!--
            이 배지는 판정이 아니라 소속이다. `Tag`를 쓰지 않는 이유가 그것이다 — 이 화면의
            `Tag`는 판정 3상태가 쓰는 형태이고(ADR-0021), 소속 배지가 같은 모양이면 "충족"과
            한 종류로 읽힌다. 채우지 않고 윤곽만 준다.
          -->
          <span
            v-for="universe in stock.universes"
            :key="universe.code"
            class="stock-header__universe"
          >
            {{ universe.name }}
          </span>
        </p>
      </div>

      <div class="stock-header__actions">
        <WatchButton :stock-id="stock.id" :stock-name="stock.name" />
      </div>

      <!--
        시세가 없으면 값 자리를 통째로 비운다. `—` 하나를 크게 띄우고 아래에서 "시세가
        없습니다"를 또 말하면 같은 사실이 두 번 나오는데, 큰 쪽이 먼저 읽혀 무슨 뜻인지
        모르는 채로 지나간다. 대신 아래 문구가 **어느 날짜인지**까지 말한다.
      -->
      <div v-if="price" class="stock-header__quote">
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

        <!--
          등락. **`tone`이 null이면 통째로 그리지 않는다** — 전일 봉이 없거나 미보정 기업
          행위를 지나면 서버가 값을 내지 않는데, 그때 `0원 (0.00%)`를 그리면 "보합"이라는
          없는 사실이 화면에 생긴다.
        -->
        <span
          v-if="tone"
          class="stock-header__change tabular"
          :class="`stock-header__change--${tone.toLowerCase()}`"
        >
          {{ formatQuoteChange(price?.change, price?.changeRate) }}
        </span>
      </div>

      <!-- 값이 언제 것인가. 견준 상대도 여기서 말한다 — 툴팁에 감추지 않는 이유는 위에 있다. -->
      <p v-if="price" class="stock-header__basis">
        기준일 {{ formatDate(asOf) }} 종가<template v-if="basis"> · {{ basis }}</template>
      </p>
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
    <p v-else class="stock-header__no-price">
      기준일 {{ formatDate(asOf) }}의 시세가 없습니다.
    </p>

    <!--
      IMPORTANT — **툴팁이나 아코디언 뒤에 숨기지 않는다.** 이 문구는 "일부 지표가 왜
      비었는가"의 유일한 답이다. 억제된 종목은 FILTER 프리셋 결과에 아예 없고 SCORING이라도
      필수 조건이면 통째로 빠지므로, 조건별 "데이터 없음"이 그 이유를 말해 주지 못한다
      (ADR-0055). 지지·저항 패널의 한계 문구와 같은 형태로 항상 펼쳐 둔다.

      `Tag`를 쓰지 않는다 — 이 화면의 `Tag`는 판정 3상태가 쓰는 형태라(ADR-0021) 같은
      모양이면 "불충족"과 한 종류로 읽힌다.

      문구는 서버가 소유한다(`unadjustedActions.notice`). 여기서 다시 쓰면 두 곳이 갈리고,
      갈린 쪽은 화면 어디에서도 드러나지 않는다.
    -->
    <p v-if="unadjustedActions.effectiveDates.length > 0" class="stock-header__unadjusted">
      {{ unadjustedActions.notice }}
      <span class="stock-header__unadjusted-dates">
        해당 일자: {{ unadjustedActions.effectiveDates.map(formatDate).join(', ') }}
      </span>
    </p>
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
 * 왼쪽 열이 읽는 흐름(정체 → 값 → 기준)이고 오른쪽은 액션뿐이다.
 *
 * `minmax(0, 1fr)`의 0이 핵심이다. grid 트랙의 기본 최소값은 `auto`(=내용 크기)라, 긴
 * 종목명이 트랙을 밀어 액션을 카드 밖으로 내보낸다. 0으로 내려야 이름이 줄어든다.
 *
 * 값과 기준일은 두 열을 **가로지른다** — 액션 아래까지 쓸 수 있어야 긴 등락 문자열이
 * 좁은 폭에서 이르게 접히지 않는다.
 */
.stock-header__top {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  grid-template-areas:
    'identity actions'
    'quote    quote'
    'basis    basis';
  align-items: start;
  column-gap: 1rem;
  padding: 0.875rem 1rem;
}

.stock-header__identity {
  grid-area: identity;
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

/*
 * 티커·시장·업종·소속이 한 줄이다. 넷 다 "이 종목이 무엇인가"에 답하므로 한 덩어리이고,
 * 읽을 차례는 이름 다음이다. 배지가 섞이므로 `inline-flex`로 세로 눈금을 맞춘다.
 */
.stock-header__meta {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 0.375rem;
  margin: 0.25rem 0 0;
  color: var(--p-text-subtle-color);
  font-size: var(--app-text-xs);
}

/*
 * IMPORTANT — **윤곽형이다. 채우지 않는다.** 판정 `Tag`가 같은 카드에 있어(오른쪽 액션),
 * 둘 다 채워진 알약이면 ADR-0021이 막은 상태가 된다 — "소속 배지가 판정과 한 종류로 읽히면
 * 안 된다". 색으로 가르지 않고 **형태로** 가른다.
 */
.stock-header__universe {
  padding: 0.0625rem 0.375rem;
  border: 1px solid var(--app-accent-border);
  border-radius: var(--p-border-radius-sm);
  color: var(--p-primary-300);
  font-size: var(--app-text-2xs);
  font-weight: 600;
  white-space: nowrap;
}

/* 값이 아니라 할 수 있는 일이다. 읽는 흐름 바깥, 오른쪽 위. */
.stock-header__actions {
  grid-area: actions;
  display: flex;
  align-items: center;
  gap: 0.375rem;
  flex: 0 0 auto;
}

/*
 * 가격과 등락이 **한 줄의 한 덩어리**다. 이것이 이 카드의 값이다.
 *
 * `align-items: baseline`이라 크기가 다른 둘이 같은 글줄에 앉는다. 좁아지면 등락이 아래로
 * 내려가되(`flex-wrap`) 왼쪽 기준선은 유지된다.
 */
.stock-header__quote {
  grid-area: quote;
  display: flex;
  align-items: baseline;
  flex-wrap: wrap;
  gap: 0 0.625rem;
  min-width: 0;
  margin-top: 0.875rem;
}

.stock-header__price-value {
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

/* 초점은 반드시 보여야 한다 — `tabindex`로 닿을 수 있게 만든 요소이므로 더욱 그렇다. */
.stock-header__price-value:focus-visible {
  outline: var(--p-focus-ring-width) var(--p-focus-ring-style) var(--p-focus-ring-color);
  outline-offset: 2px;
  border-radius: var(--p-border-radius-sm);
}

/*
 * 등락.
 *
 * IMPORTANT — **지표 카드와 같은 값 색조를 쓴다**(`--p-tone-*`). 상승이 초록, 하락이 빨강이라
 * 이 화면에서 초록·빨강이 여전히 한 뜻씩만 갖는다. **국내 증권앱 관례(상승 빨강·하락 파랑)를
 * 쓰지 않기로 한 것이고**, 그러면 같은 화면에서 빨강이 "상승"과 "불리" 둘을 말하게 되는데
 * 형태로 가를 길이 없다 — ADR-0021이 `▲`/`▼`에 색 주는 것을 금지하고, 그 글리프는 이 화면의
 * 지표 카드가 **반대 극성**으로 이미 쓴다(`direction.ts`의 `▼`는 「낮을수록 유리」다).
 * 경위와 대가는 ADR-0065에 있다.
 *
 * 가격보다 한 단계 작다 — 이 카드의 값은 종가이고 등락은 그것을 설명하는 것이다. 그래도
 * 굵기는 같이 준다. 작고 옅게 두면 덧붙인 주석처럼 보여 눈에 걸리지 않는다.
 */
.stock-header__change {
  font-size: var(--app-text-xl);
  font-weight: 700;
  line-height: 1.15;
  letter-spacing: -0.01em;
  white-space: nowrap;
  color: var(--p-tone-neutral-text);
}

.stock-header__change--rise {
  color: var(--p-tone-favorable-text);
}

.stock-header__change--fall {
  color: var(--p-tone-unfavorable-text);
}

/* 값이 언제 것인가. 값 바로 밑에 붙어야 그 값에 관한 말로 읽힌다. */
.stock-header__basis {
  grid-area: basis;
  margin: 0.25rem 0 0;
  font-size: var(--app-text-2xs);
  color: var(--p-text-subtle-color);
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
 * 주의 문구. `--p-orange-*` 계열을 직접 쓰지 않고 `Tag`의 `warn` 글자색과 같은 amber를 쓴다 —
 * 판정 3상태의 `warn`과 같은 뜻("값이 없다")이라 색이 갈리면 두 자리가 다른 사실을 말하는
 * 것처럼 읽힌다. 정본은 `preset.ts`의 `tag.warn.color`(`{amber.300}`)다.
 *
 * **semantic에 `warn`은 없다.** Aura는 그것을 컴포넌트 스코프에만 두므로 `--p-warn-color`는
 * 어디에서도 선언되지 않는다. 폴백을 달아 두면 무효가 아니라 **본문색으로 조용히 떨어져**
 * 이 주석이 지키려는 것이 죽는다.
 */
.stock-header__unadjusted {
  margin: 0;
  padding: 0.625rem 1rem 0.75rem;
  border-top: 1px solid var(--p-content-border-color);
  font-size: var(--app-text-sm);
  line-height: 1.5;
  color: var(--p-amber-300);
}

.stock-header__unadjusted-dates {
  display: block;
  margin-top: 0.25rem;
  color: var(--p-text-muted-color);
}

/*
 * **카드가** 좁을 때만 한 열로 쌓는다. 뷰포트가 아니라 카드를 재는 이유는 위 `.stock-header`
 * 주석에 있다.
 *
 * 24rem(384px)인 근거: 이름과 액션이 한 줄에 설 수 있는 하한이다. 그 아래로 내려가면 담기
 * 버튼이 이름 트랙을 밀어 28px 이름이 서너 줄이 되기 시작한다.
 *
 * 액션이 **맨 아래**로 간다. 값과 기준일 사이에 끼면 읽는 흐름이 끊기고, 값 위에 두면
 * 이 카드에서 가장 중요한 숫자가 버튼 밑으로 밀린다.
 */
@container stock-header (max-width: 24rem) {
  .stock-header__top {
    grid-template-columns: minmax(0, 1fr);
    grid-template-areas:
      'identity'
      'quote'
      'basis'
      'actions';
  }

  .stock-header__actions {
    margin-top: 0.875rem;
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

  .stock-header__change {
    font-size: var(--app-text-md);
  }
}
</style>
