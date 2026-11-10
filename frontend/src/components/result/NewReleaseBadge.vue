<script setup lang="ts">
import { computed } from 'vue'

import type { FinancialBasis } from '@/api/types'
import { financialBasisStyle, rowBadgeText } from '@/lib/financialBasis'

/**
 * 결과 행의 「새 실적」 · 「잠정」 표시(ADR-0195) — 지표가 쓴 재무보다 새로운 실적 발표가 있을 때만 뜬다(잠정실적으로 손익 지표를
 * 계산했으면 「잠정」). 문구는 서버의 `notice`다.
 * 표와 모바일 카드가 함께 쓴다.
 *
 * `Tag`를 쓰지 않는다 — 결과 화면의 `Tag`는 판정 3상태(충족 · 불충족 · 데이터 없음)의 모양이라(ADR-0021), 같은 경고색 `Tag`면
 * 「데이터 없음」과 한 종류로 읽힌다. 미보정 기업 행위 안내(`StockHeader`)와 같은 호박색 글자로 그린다.
 */
const props = defineProps<{ basis: FinancialBasis | null }>()

const shown = computed(() => props.basis !== null && financialBasisStyle(props.basis.status).flagsRow)
const text = computed(() => (props.basis === null ? '' : rowBadgeText(props.basis.status)))
</script>

<template>
  <span
    v-if="shown && basis"
    v-tooltip.top="basis.notice ?? undefined"
    class="new-release"
    :aria-label="basis.notice ?? text"
  >
    <i class="pi pi-clock" aria-hidden="true" />
    {{ text }}
  </span>
</template>

<style scoped>
.new-release {
  display: inline-flex;
  align-items: center;
  gap: 0.25rem;
  margin-left: 0.375rem;
  font-size: var(--app-text-2xs);
  color: var(--p-amber-300);
  white-space: nowrap;
  vertical-align: middle;
}

.new-release .pi {
  font-size: var(--app-text-2xs);
}
</style>
