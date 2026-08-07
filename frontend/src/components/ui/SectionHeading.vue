<script setup lang="ts">
/**
 * 화면 안의 구획 제목.
 *
 * IMPORTANT — 이것이 "재무 지표가 본문과 구별되지 않던" 자리다. 제목이 `--app-text-xs`(12px)
 * muted에 `margin-top: 0`이라 **본문(13px)보다 작았고**, 위 여백이 없어 앞 그리드의 캡션처럼
 * 읽혔다. 크기만 올리면 값(16px)과 다투므로 크기는 그 사이(14px)에 두고 **색·여백·표식**으로
 * 구획임을 말한다.
 *
 * 좌측 레일의 제목(`--app-text-2xs`)은 여기 묶지 않는다. 사이드바 캡션은 훑는 대상이 아니라
 * 목록을 가르는 선에 가깝고, 억지로 합치면 변형 prop만 늘어난다.
 */
defineProps<{
  title: string
  /** 항목 개수. 있으면 제목 옆에 붙는다. 제목 혼자면 여전히 캡션처럼 보인다. */
  count?: number | null
}>()
</script>

<template>
  <div class="section-heading">
    <span class="section-heading__mark" aria-hidden="true" />
    <h2 class="section-heading__title">{{ title }}</h2>
    <span v-if="count !== null && count !== undefined" class="section-heading__count tabular">
      {{ count }}
    </span>
    <span class="section-heading__rule" aria-hidden="true" />
  </div>
</template>

<style scoped>
.section-heading {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  /*
   * 위 여백이 아래보다 훨씬 커야 제목이 **아래 것의 머리**로 읽힌다. 같으면 앞 블록과
   * 뒤 블록 중 어디에 속하는지 알 수 없다. 첫 구획은 소비처가 `:first-of-type`으로 지운다.
   */
  margin: 1.75rem 0 0.625rem;
}

/* 코발트 표식. 화면에서 accent가 링크·버튼에만 붙어 있어 구조에는 색이 하나도 없었다. */
.section-heading__mark {
  flex: 0 0 auto;
  width: 3px;
  height: 0.875em;
  border-radius: 2px;
  background: var(--p-primary-color);
}

.section-heading__title {
  margin: 0;
  font-size: var(--app-text-md);
  font-weight: 700;
  letter-spacing: 0.01em;
  color: var(--p-text-color);
  white-space: nowrap;
}

.section-heading__count {
  font-size: var(--app-text-2xs);
  font-weight: 600;
  color: var(--p-text-subtle-color);
}

/* 남는 폭을 hairline이 채운다. 제목이 어디까지 다스리는지가 선으로 보인다. */
.section-heading__rule {
  flex: 1 1 auto;
  height: 1px;
  min-width: 0;
  background: linear-gradient(90deg, var(--p-content-border-color), transparent);
}
</style>
