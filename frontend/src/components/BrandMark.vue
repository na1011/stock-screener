<script setup lang="ts">
/**
 * 브랜드 마크.
 *
 * 값이 다른 막대 셋. 이 화면이 다루는 것이 시세라는 것을 글자보다 먼저 알린다.
 *
 * 헤더와 로그인 화면 둘이 같은 것을 그리므로 컴포넌트로 뺐다 — 각자 그리면 한쪽만
 * 고쳐졌을 때 두 화면의 마크가 달라지고, 그 어긋남은 두 화면을 나란히 놓기 전에는
 * 드러나지 않는다.
 *
 * 색은 `currentColor`가 아니라 accent 토큰이다. 로그인 화면에서는 큰 글자 옆에 놓이는데
 * 본문 색을 따라가면 마크가 아니라 문장 부호처럼 보인다.
 */
withDefaults(defineProps<{ size?: string }>(), { size: '1rem' })
</script>

<template>
  <svg
    class="brand-mark"
    viewBox="0 0 16 16"
    aria-hidden="true"
    focusable="false"
    :style="{ width: size, height: size }"
  >
    <rect x="0" y="9" width="4" height="7" rx="1" />
    <rect x="6" y="5" width="4" height="11" rx="1" />
    <rect x="12" y="0" width="4" height="16" rx="1" />
  </svg>
</template>

<style scoped>
.brand-mark {
  display: block;
  flex: 0 0 auto;
  fill: var(--p-primary-color);
}
</style>
