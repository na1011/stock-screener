<script setup lang="ts">
import type { PercentileGuide } from '@/api/types'
import IndicatorLabel from '@/components/IndicatorLabel.vue'
import BandMeter from '@/components/ui/BandMeter.vue'
import type { BandReading } from '@/lib/band'

/**
 * 종목 상세의 지표 카드 하나.
 *
 * 분류 구획과 「내 조건」 구획이 **같은 카드**를 그린다. 둘이 각자 마크업을 들면 값 색조나
 * 설명 클램프가 한쪽에서만 바뀌는데, 사용자에게는 같은 지표가 위아래에서 다르게 보이는
 * 형태로 드러난다.
 *
 * 값이 없는 지표도 그린다 — 「내 조건」에서 그것이 "왜 이 종목이 결과에 없는가"의 답이다.
 */
defineProps<{
  name: string
  higherIsBetter: boolean | null
  /** 숫자와 단위를 나눠 받는다. 붙여 그리면 '%'가 숫자와 같은 무게로 읽힌다. */
  parts: { number: string; unit: string }
  reading: BandReading | null
  percentile?: PercentileGuide | null
  description?: string | null
  interpretation?: string | null
  /**
   * 값이 없는 이유. 서버가 문구를 소유하므로 화면은 그대로 그린다(ADR-0060).
   *
   * IMPORTANT — 이것이 있으면 **해석문 대신** 이것을 보인다. 값이 없는데 "낮을수록 싸다"
   * 같은 일반 해설을 그리면 사용자는 그 지표가 계산된 것으로 읽는다.
   */
  unavailableReason?: string | null
}>()
</script>

<template>
  <li class="indicator">
    <IndicatorLabel
      :name="name"
      :higher-is-better="higherIsBetter"
      :percentile="percentile ?? null"
      :description="description"
      :interpretation="interpretation"
    />
    <span
      class="indicator__value tabular"
      :class="reading ? `indicator__value--${reading.tone.toLowerCase()}` : null"
    >
      {{ parts.number
      }}<span v-if="parts.unit" class="indicator__unit">{{ parts.unit }}</span>
    </span>
    <!-- 구간을 아는 지표에만 붙는다. 없으면 지어내지 않는다(ADR-0023). -->
    <BandMeter v-if="reading" class="indicator__meter" :reading="reading" :indicator-name="name" />
    <!--
      사유가 해석문을 **대체한다.** 둘을 함께 그리면 "값이 없다"와 "낮을수록 좋다"가 나란히
      서서, 값이 계산됐는데 화면이 못 그린 것처럼 읽힌다.
    -->
    <p v-if="unavailableReason" class="indicator__unavailable">{{ unavailableReason }}</p>
    <p v-else-if="interpretation" class="indicator__interpretation">{{ interpretation }}</p>
  </li>
</template>

<style scoped>
/*
 * 사유는 해석문과 다른 색이다 — 같으면 "이 지표는 원래 이런 뜻이다"로 읽힌다.
 * 경고색을 쓰지 않는 것도 의도다: 결손은 사용자가 고칠 수 있는 것이 아니고,
 * 지지·저항의 경고 문구와 같은 무게로 보이면 그쪽 경고가 묽어진다.
 */
.indicator__unavailable {
  margin: 0.35rem 0 0;
  color: var(--text-color-secondary);
  font-size: 0.8rem;
  line-height: 1.5;
  font-style: italic;
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
</style>
