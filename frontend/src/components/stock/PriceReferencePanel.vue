<script setup lang="ts">
import Tag from 'primevue/tag'
import { computed } from 'vue'

import type { PriceReference } from '@/api/types'
import { formatDate, formatPrice } from '@/lib/format'
import { priceReferenceMethodLabel, zoneRoleStyle, zoneStrengthLabel } from '@/lib/priceReference'

/**
 * 지지·저항 참고정보.
 *
 * IMPORTANT — 확정된 매매 신호가 아니다. 사용자가 이것을 신호로 오해하면 실제 금전적 손실로
 * 이어진다(frontend.md). 그래서 이 컴포넌트는 세 가지를 반드시 지킨다.
 *
 * 1. **`disclaimer`를 접지 않는다.** 툴팁·아코디언 뒤에 숨기지 않고 항상 펼쳐 둔다.
 * 2. **지표와 시각적으로 분리한다.** 경고 색의 테두리와 별도 머리말을 둬서 지표 목록과 같은
 *    자리에 있지 않다는 것이 한눈에 보이게 한다.
 * 3. **단일 값을 만들어내지 않는다.** 구간의 중앙값·평균처럼 진입가로 읽힐 숫자를 계산하지
 *    않는다(spec §10). 여기 있는 것은 구간과 그 구간이 몇 번 의미를 가졌는지뿐이다.
 */
const props = defineProps<{ reference: PriceReference }>()

/**
 * 위쪽 구간을 먼저 보인다. 가격 축과 같은 순서라야 읽는 방향이 어긋나지 않는다.
 *
 * 역할·강도·산출 방식의 표시는 `lib/priceReference`가 정한다. 모르는 값에 던지지 않는 이유가
 * 거기 적혀 있다 — 이 패널이 사라지면 경고 문구까지 함께 사라진다.
 */
const zones = computed(() =>
  [...props.reference.zones]
    .sort((a, b) => b.upper - a.upper)
    .map((zone) => ({ ...zone, role: zoneRoleStyle(zone.role), strength: zoneStrengthLabel(zone.strength) })),
)
</script>

<template>
  <section class="price-reference" aria-labelledby="price-reference-heading">
    <header class="price-reference__header">
      <h2 id="price-reference-heading" class="price-reference__title">
        <i class="pi pi-exclamation-triangle" aria-hidden="true" />
        참고정보 — 확정된 매매 신호가 아닙니다
      </h2>
      <span class="price-reference__window">
        {{ formatDate(reference.window.from) }} ~ {{ formatDate(reference.window.to) }}
        ({{ reference.window.tradingDays }}거래일)
      </span>
    </header>

    <p class="price-reference__disclaimer">{{ reference.disclaimer }}</p>

    <p v-if="reference.insufficientData" class="price-reference__insufficient">
      분석에 필요한 봉이 부족해 구간을 산출하지 못했습니다.
    </p>

    <template v-else>
      <p class="price-reference__last-close">
        기준 종가 <strong class="tabular">{{ formatPrice(reference.lastClose) }}</strong>
        <span class="price-reference__note">(수정종가 — 실제 매매 가격과 다를 수 있습니다)</span>
      </p>

      <ul v-if="zones.length > 0" class="price-reference__zones">
        <li v-for="zone in zones" :key="`${zone.lower}-${zone.upper}`" class="zone">
          <Tag :value="zone.role.label" :severity="zone.role.severity" />
          <span class="zone__range tabular">
            {{ formatPrice(zone.lower) }} ~ {{ formatPrice(zone.upper) }}
          </span>
          <span class="zone__meta">
            {{ zone.touchCount }}회 · {{ zone.strength }} · 마지막
            {{ formatDate(zone.lastTouchedOn) }}
          </span>
        </li>
      </ul>
      <p v-else class="price-reference__insufficient">
        이 기간에 반복적으로 머무른 구간이 확인되지 않았습니다.
      </p>

      <p class="price-reference__method">
        산출 방식: {{ priceReferenceMethodLabel(reference.method) }} · 연속된 봉은 한 번으로 셉니다.
      </p>
    </template>
  </section>
</template>

<style scoped>
/* 지표 목록과 같은 자리에 있지 않다는 것이 색과 테두리로 먼저 보여야 한다. */
.price-reference {
  border: 2px dashed var(--p-orange-400, #fb923c);
  border-radius: var(--p-border-radius-md);
  padding: 1rem;
  background: color-mix(in srgb, var(--p-orange-400, #fb923c) 6%, transparent);
}

.price-reference__header {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 0.75rem;
  flex-wrap: wrap;
  margin-bottom: 0.5rem;
}

.price-reference__title {
  display: flex;
  align-items: center;
  gap: 0.375rem;
  font-size: 0.9375rem;
  margin: 0;
}

.price-reference__window,
.price-reference__note,
.price-reference__method {
  font-size: 0.75rem;
  color: var(--p-text-muted-color);
}

.price-reference__disclaimer {
  margin: 0 0 0.75rem;
  font-size: 0.8125rem;
  line-height: 1.6;
}

.price-reference__last-close {
  margin: 0 0 0.75rem;
  font-size: 0.875rem;
  display: flex;
  align-items: baseline;
  gap: 0.375rem;
  flex-wrap: wrap;
}

.price-reference__insufficient {
  margin: 0;
  font-size: 0.875rem;
  color: var(--p-text-muted-color);
}

.price-reference__zones {
  list-style: none;
  margin: 0 0 0.75rem;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

.zone {
  display: flex;
  align-items: center;
  gap: 0.625rem;
  flex-wrap: wrap;
}

.zone__range {
  font-weight: 600;
}

.zone__meta {
  font-size: 0.75rem;
  color: var(--p-text-muted-color);
}

.price-reference__method {
  margin: 0;
}
</style>
