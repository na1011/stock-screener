<script setup lang="ts">
/**
 * 화면의 머리 띠.
 *
 * IMPORTANT — 존재 이유는 **좌측 레일의 브랜드 띠와 같은 높이에 선을 긋는 것**이다. 레일은
 * 모든 화면에 있으므로 오른쪽에 대응하는 띠가 없으면 그 선이 허공에서 끝난다. 두 띠가
 * `--app-shell-header-height`를 함께 보는 것이 이 컴포넌트의 계약이다.
 *
 * **선을 하나만 긋는다.** 아래 둘째 줄(스크리너의 조건 칩 바)은 내용에 따라 줄바꿈으로
 * 늘어나므로 레일의 검색 띠와 절대 같은 높이가 될 수 없다. 안 맞는 선 둘은 맞는 선 하나보다
 * 나쁘다 — 그래서 레일 검색 띠에는 선이 없고, 여기 둘째 줄의 선은 **오른쪽 열 안쪽의 부차
 * 구분선**이라는 다른 위계로 읽힌다.
 *
 * 폭은 뷰가 정한다. 띠 자체(면·선)는 전폭이어야 레일 선과 이어지고, 내용은 그 화면의
 * 본문과 같은 폭이어야 1920px에서 제목만 왼쪽 끝에 붙지 않는다. 뷰 루트가
 * `--app-view-width`를 선언하면 그 값이 여기로 캐스케이드된다 — prop을 두지 않는 이유이고,
 * 선언하지 않으면 전폭이다(스크리너가 그렇다).
 */
defineSlots<{
  /** 제목 자리. `<h1>`을 여기 넣는다. */
  title?: () => unknown
  /** 제목 오른쪽, 같은 줄. 실행·저장 같은 화면 단위 액션. */
  actions?: () => unknown
  /** 띠 아래 둘째 줄. 자연 높이로 늘어난다. */
  default?: () => unknown
}>()
</script>

<template>
  <header class="view-header">
    <!-- 전폭이라 레일의 선과 이어진다. 안쪽에서 폭을 다시 묶는다. -->
    <div class="view-header__top">
      <div class="view-header__row">
        <div class="view-header__title"><slot name="title" /></div>
        <div v-if="$slots.actions" class="view-header__actions"><slot name="actions" /></div>
      </div>
    </div>

    <div v-if="$slots.default" class="view-header__band">
      <div class="view-header__row view-header__row--band"><slot /></div>
    </div>
  </header>
</template>

<style scoped>
/*
 * `backdrop-filter`를 쓰지 않는다. 왼쪽 레일이 불투명(`content.background`)인데 오른쪽만
 * 반투명하면 선은 맞아도 **면이 어긋난다** — 같은 띠로 읽히지 않는다. 넓은 폭에서는 본문이
 * 스크롤 컨테이너라(ADR-0047) 뒤로 지나가는 내용을 비출 이유도 없다.
 */
.view-header {
  position: sticky;
  top: 0;
  z-index: 15;
  background: var(--p-content-background);
}

/*
 * IMPORTANT — 높이가 **여기** 있어야 한다. 안쪽 `__row`에 주면 그 값에 선 1px이 더해져
 * 띠 전체가 53px이 되고, 선을 `min-height` 안에 갖는 레일(52px)과 **1px 어긋난다.**
 * 1px이어도 선은 이어지지 않는다 — 실제로 그렇게 났다.
 *
 * 규칙은 하나다: **`--app-shell-header-height`는 선을 포함한 띠의 총 높이다.**
 * `box-sizing: border-box`(main.css의 `*`)가 그것을 보장한다.
 */
.view-header__top {
  display: flex;
  min-height: var(--app-shell-header-height);
  border-bottom: 1px solid var(--p-content-border-color);
}

.view-header__row {
  display: flex;
  align-items: center;
  gap: 0.5rem 0.75rem;
  flex-wrap: wrap;
  /* 위 상자가 정한 높이를 채운다. 자기 높이를 따로 갖지 않는다. */
  flex: 1 1 auto;
  min-width: 0;
  max-width: var(--app-view-width, none);
  margin-inline: auto;
  padding-inline: var(--app-gutter);
}

/* 둘째 줄은 자기 내용만큼만 높다. 첫 줄의 고정 높이를 물려받으면 칩 한 줄이 52px을 먹는다. */
.view-header__row--band {
  padding-block: 0.5rem;
}

.view-header__band {
  border-bottom: 1px solid var(--p-content-border-color);
}

.view-header__title {
  display: flex;
  align-items: center;
  gap: 0.375rem;
  flex-wrap: wrap;
  flex: 1 1 12rem;
  min-width: 0;
}

.view-header__actions {
  display: flex;
  align-items: center;
  gap: 0.375rem;
  flex-wrap: wrap;
  margin-inline-start: auto;
}

/*
 * 좁으면 동작이 제목 아래 자기 줄로 내려간다. `margin-inline-start: auto`를 풀어야 줄
 * 전체를 쓴다. 고정 높이도 푼다 — 두 줄이 되면 52px 안에 들어가지 않는다.
 *
 * **띠가 따라다니지 않는다.** 이유가 둘이다. 제목·동작·칩이 줄바꿈으로 쌓이면 720px 화면에서
 * 카드가 세 장밖에 안 남고(모바일은 훑어보는 화면이다 — frontend.md), 더 중요하게는 좁은
 * 폭에서 **문서가 여전히 스크롤하므로**(ADR-0047) sticky인 채로 두면 그 안의 오버레이가
 * 앵커에서 떨어져 흘러간다. 900은 `BREAKPOINTS.BUILDER`이고 곧 "레일이 서랍이 되는 폭"이다.
 */
@media (max-width: 900px) {
  .view-header {
    position: static;
  }

  .view-header__top {
    min-height: 0;
  }

  .view-header__row {
    padding-block: 0.5rem;
  }

  .view-header__actions {
    margin-inline-start: 0;
    flex-basis: 100%;
  }
}
</style>
