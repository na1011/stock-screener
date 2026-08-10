<script setup lang="ts">
import Tag from 'primevue/tag'
import { computed } from 'vue'

import type { ConditionInfo, EvaluationMode, ScreeningRow } from '@/api/types'
import RatioBar from '@/components/ui/RatioBar.vue'
import { readBand } from '@/lib/band'
import { conditionRoleLabels, describeCondition } from '@/lib/conditionText'
import { MISSING, formatIndicatorValue, formatNumber, formatRatioAsPercent } from '@/lib/format'
import { outcomeStyle } from '@/lib/outcome'
import { SCORE_DECIMAL_SCALE } from '@/lib/resultRow'
import { useCatalogStore } from '@/stores/catalog'

/**
 * 이 종목이 왜 이 결과에 있는가.
 *
 * MUST — 조건별 판정을 **세 상태 그대로** 보인다(ADR-0003). 충족·불충족·데이터없음이 화면에서
 * 서로 달라야 사용자가 종목이 왜 빠졌는지 알 수 있다.
 *
 * MUST — SCORING이면 조건별 획득 점수를 보인다(frontend.md). 총점만 주고 내역을 감추면
 * 사용자가 결과를 신뢰할 근거가 없다.
 *
 * IMPORTANT — 이 목록에서 **색을 갖는 것은 판정 `Tag` 하나뿐이다.** 등급("양호", "상위 16%")은
 * 색 없이 낱말로만 적는다. 여기는 판정과 값이 한 줄에 놓이는 자리라, 등급까지 초록·빨강을
 * 쓰면 `✓충족`(초록) 옆에 `높음`(빨강)이 나오는 줄이 생겨 훑는 눈에는 자기모순으로 보인다.
 * 형태로 채널을 가른다는 ADR-0021의 결정이 겨냥한 자리가 정확히 이곳이다.
 */
const props = defineProps<{
  conditions: ConditionInfo[]
  row: ScreeningRow
  evaluationMode: EvaluationMode
  /** 지표 코드 → 이름. 조건 문장이 코드 대신 이름을 쓰게 한다. */
  indicatorName: (code: string) => string
}>()

const catalog = useCatalogStore()

const isScoring = computed(() => props.evaluationMode === 'SCORING')

const items = computed(() =>
  props.conditions.map((condition) => {
    const outcome = props.row.outcomes.find((it) => it.conditionIndex === condition.index) ?? null
    // 가중치는 null이면 1이다. 서버의 `effectiveWeight`와 같은 규칙이어야 획득/가중치가 맞는다.
    const weight = condition.weight ?? 1
    return {
      condition,
      outcome,
      style: outcome === null ? null : outcomeStyle(outcome.status),
      text: describeCondition(condition, props.indicatorName),
      grade: gradeOf(condition, outcome?.actualValue ?? null),
      weight,
      earnedRatio: contributionOf(outcome?.earnedScore ?? null),
    }
  }),
)

/**
 * 필수와 점수를 갈라 보인다.
 *
 * 두 종류는 **묻는 것이 다르다.** 필수는 "이 종목이 여기 있어도 되는가"이고 점수는 "얼마나
 * 좋은가"다. 한 목록에 섞으면 필수 조건이 점수 0점짜리 조건처럼 보여, 총점이 낮은 이유를
 * 필수 조건에서 찾게 된다 — 필수는 애초에 점수에 참여하지 않는다.
 *
 * 필수 조건이 하나도 없으면 나누지 않는다. 기존 전략의 화면이 제목 하나 때문에 달라지지
 * 않게 하는 것이고, 구획이 하나뿐인 목록에 제목을 붙여 봐야 말해 주는 것이 없다.
 */
const sections = computed(() => {
  const required = items.value.filter((item) => item.condition.required)
  if (required.length === 0) {
    return [{ key: 'all', title: null, hint: null, items: items.value }]
  }

  const scored = items.value.filter((item) => !item.condition.required)
  return [
    {
      key: 'required',
      title: conditionRoleLabels.REQUIRED,
      hint: '하나라도 충족하지 못하면 결과에서 빠집니다.',
      items: required,
    },
    ...(scored.length > 0
      ? [{ key: 'scored', title: conditionRoleLabels.SCORED, hint: null, items: scored }]
      : []),
  ]
})

/**
 * 이 조건이 **총점에서 차지한 몫**(0..1).
 *
 * 획득/가중치가 아니다. 서버의 `earnedScore`는 충족이면 가중치 전액, 아니면 0인 **이진값**이라
 * 그것을 막대로 그리면 옆의 판정 `Tag`와 같은 말만 반복한다. 총점 기준으로 재면 가중치가
 * 다른 조건들의 막대 길이가 달라져 "무엇이 이 종목을 끌어올렸나"가 보인다 — 다섯 막대를
 * 합치면 달성률이 된다.
 *
 * @return 총점 상한이 없거나 0이면 null. 나눌 수 없고, 그때는 기여라는 개념도 없다.
 */
function contributionOf(earnedScore: number | null): number | null {
  const max = props.row.maxScore
  if (earnedScore === null || max === null || max === 0) {
    return null
  }
  return earnedScore / max
}

/**
 * 실제값이 어느 구간인가.
 *
 * 구간과 백분위 안내는 **카탈로그에만** 있고 스크리닝 응답의 `IndicatorBrief`에는 없다.
 * 코드로 이어 붙이는 것은 `ResultTable`이 `percentileOf`로 이미 하던 방식이며 요청을 하나 더
 * 만들지 않는다. 카탈로그가 실패하면 등급만 사라지고 판정·실제값·점수는 그대로 남는다 —
 * 근거 없이 "매우 낮음"을 그리는 것보다 말하지 않는 편이 낫다.
 */
function gradeOf(condition: ConditionInfo, actualValue: number | null): string | null {
  const meta = catalog.byCode.get(condition.indicator.code)
  return meta === undefined ? null : (readBand(meta, actualValue)?.label ?? null)
}
</script>

<template>
  <div class="outcomes">
    <div v-if="isScoring" class="outcomes__total">
      <span class="outcomes__total-label">총점</span>
      <strong class="tabular">{{ formatNumber(row.score, SCORE_DECIMAL_SCALE) }}</strong>
      <span class="outcomes__total-max tabular">/ {{ formatNumber(row.maxScore, SCORE_DECIMAL_SCALE) }}</span>
      <span class="outcomes__total-ratio">({{ formatRatioAsPercent(row.scoreRatio) }})</span>
    </div>

    <section v-for="section in sections" :key="section.key" class="outcomes__group">
      <h4 v-if="section.title" class="outcomes__group-title">
        {{ section.title }}
        <span v-if="section.hint" class="outcomes__group-hint">{{ section.hint }}</span>
      </h4>

      <ul class="outcomes__list">
        <li v-for="item in section.items" :key="item.condition.index" class="outcomes__item">
          <Tag
            v-if="item.style"
            v-tooltip.top="item.style.tooltip"
            :value="item.style.label"
            :severity="item.style.severity"
            :icon="item.style.icon"
            class="outcomes__status"
          />
          <span v-else class="outcomes__status outcomes__status--absent">판정 없음</span>

          <span class="outcomes__text">{{ item.text }}</span>

          <span class="outcomes__actual tabular">
            {{
              item.outcome === null || item.outcome.actualValue === null
                ? MISSING
                : formatIndicatorValue(
                    item.outcome.actualValue,
                    item.condition.indicator.unit,
                    item.condition.indicator.decimalScale,
                  )
            }}
          </span>

          <!--
            등급. 색을 주지 않는다 — 이 줄에서 색을 갖는 것은 판정 Tag 하나뿐이다(ADR-0021).
            서버가 구간을 모르는 지표(주가 스케일)는 빈칸이다. 지어내지 않는다(ADR-0023).
          -->
          <span class="outcomes__grade">{{ item.grade }}</span>

          <!--
            필수 조건에는 점수 칸이 없다. 서버가 총점에서 뺐으므로 "0 / 1"을 그리면 못 받은
            점수처럼 읽힌다 — 받을 점수가 애초에 없다. 빈 칸으로 두면 그리드 트랙이 유지되어
            아래위 행의 실제값·등급이 계속 같은 자리에 선다.
          -->
          <template v-if="isScoring && !item.condition.required">
            <!-- 총점에서 차지한 몫. 막대를 전부 합치면 달성률이 된다. -->
            <RatioBar
              :value="item.earnedRatio"
              class="outcomes__bar"
              :aria-label="`총점 기여 ${formatRatioAsPercent(item.earnedRatio)}`"
            />
            <span class="outcomes__score tabular">
              {{ formatNumber(item.outcome?.earnedScore ?? null, SCORE_DECIMAL_SCALE) }} /
              {{ formatNumber(item.weight, SCORE_DECIMAL_SCALE) }}
            </span>
          </template>
        </li>
      </ul>
    </section>

    <p v-if="row.notEvaluableCount > 0" class="outcomes__note">
      {{ row.notEvaluableCount }}개 조건은 지표 값이 없어 판정하지 못했습니다. 조건에 맞지 않았다는
      뜻이 아닙니다.
    </p>
  </div>
</template>

<style scoped>
.outcomes {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

/* 총점은 아래 목록의 합이다. hairline으로 머리와 몸을 가른다. */
.outcomes__total {
  display: flex;
  align-items: baseline;
  gap: 0.375rem;
  padding: 0 0.375rem 0.375rem;
  border-bottom: 1px solid var(--p-content-border-color);
  font-size: var(--app-text-md);
}

.outcomes__total-label {
  color: var(--p-text-muted-color);
  font-size: var(--app-text-sm);
}

.outcomes__total-max,
.outcomes__total-ratio {
  color: var(--p-text-muted-color);
  font-size: var(--app-text-sm);
}

.outcomes__group {
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
}

/*
 * 구획 제목. 판정 Tag보다 약하게 둔다 — 이 목록에서 눈이 먼저 가야 하는 것은 줄마다의
 * 충족·불충족이고, 제목은 그 줄들이 무엇을 묻는지만 말한다.
 */
.outcomes__group-title {
  display: flex;
  align-items: baseline;
  gap: 0.375rem;
  margin: 0;
  padding: 0 0.375rem;
  font-size: var(--app-text-2xs);
  font-weight: 600;
  letter-spacing: 0.02em;
  color: var(--p-text-muted-color);
}

.outcomes__group-hint {
  font-weight: 400;
  color: var(--p-text-subtle-color);
}

.outcomes__list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 0.125rem;
}

/*
 * IMPORTANT — 마지막 칸들을 `auto`로 두지 않는다. `<li>`마다 자기 그리드라 `auto`는 그 행의
 * 내용 폭이 되고, 자릿수가 다르면 실제값·점수의 **좌측 경계가 행마다 어긋난다.** 세로로 읽어
 * 내려가는 목록에서 그 들쭉날쭉함이 가장 먼저 보인다. 고정 트랙이면 저절로 맞는다.
 * (아래 모바일 주석이 첫 칸에 대해 같은 이야기를 하고 있었고, 나머지 칸이 빠져 있었다.)
 */
.outcomes__item {
  display: grid;
  grid-template-columns: 7rem minmax(0, 1fr) 7rem 5rem 4rem 6rem;
  gap: 0.625rem;
  align-items: center;
  padding: 0.1875rem 0.375rem;
  border-radius: var(--p-border-radius-md);
  font-size: var(--app-text-sm);
}

.outcomes__item:hover {
  background: var(--p-surface-900);
}

.outcomes__status {
  justify-self: start;
}

.outcomes__status--absent {
  color: var(--p-text-muted-color);
  font-size: var(--app-text-xs);
}

.outcomes__text {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 실제값은 조건과 대조하는 것이라 본문 밝기를 유지한다. 나머지 둘은 곁들이다. */
.outcomes__actual {
  color: var(--p-text-color);
  text-align: right;
  white-space: nowrap;
}

.outcomes__grade {
  color: var(--p-text-muted-color);
  font-size: var(--app-text-xs);
  white-space: nowrap;
}

.outcomes__score {
  color: var(--p-text-subtle-color);
  font-size: var(--app-text-xs);
  text-align: right;
  white-space: nowrap;
}

.outcomes__note {
  margin: 0;
  font-size: var(--app-text-sm);
  color: var(--p-text-muted-color);
}

/*
 * 모바일에서는 조건이 두 줄이 된다 — 첫 줄에 판정과 조건문, 둘째 줄에 실제값과 점수.
 *
 * 첫 칸을 `auto`가 아니라 **고정 폭**으로 두는 것이 요점이다. `auto`면 각 `<li>`가 자기만의
 * 그리드라 첫 칸이 그 행의 `실제 …` 길이에 맞춰지고, 값의 자릿수가 다르면 조건문이 행마다
 * 다른 자리에서 시작한다. 세로로 읽어 내려가는 목록에서 그 들쭉날쭉함이 가장 먼저 보인다.
 */
@media (max-width: 767px) {
  .outcomes__item {
    grid-template-columns: 4.5rem minmax(0, 1fr) auto;
    grid-template-areas:
      'status text  text'
      'actual grade score';
    row-gap: 0.125rem;
    column-gap: 0.5rem;
    padding: 0.25rem 0.375rem;
  }

  .outcomes__status {
    grid-area: status;
  }

  .outcomes__text {
    grid-area: text;
    /* 좁은 폭에서는 조건문이 두 줄이 되는 편이 잘리는 것보다 낫다. */
    white-space: normal;
  }

  .outcomes__actual {
    grid-area: actual;
    text-align: start;
  }

  .outcomes__grade {
    grid-area: grade;
  }

  .outcomes__score {
    grid-area: score;
    justify-self: end;
  }

  /* 막대는 자리를 얻지 못한다. 숫자가 같은 것을 말하므로 잃는 정보가 없다. */
  .outcomes__bar {
    display: none;
  }
}
</style>
