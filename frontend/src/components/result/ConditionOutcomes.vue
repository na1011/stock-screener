<script setup lang="ts">
import Tag from 'primevue/tag'
import { computed } from 'vue'

import type { ConditionInfo, EvaluationMode, ScreeningRow } from '@/api/types'
import RatioBar from '@/components/ui/RatioBar.vue'
import { readBand } from '@/lib/band'
import { conditionRoleLabels, splitCondition } from '@/lib/conditionText'
import { MISSING, formatIndicatorValue, formatNumber, formatRatioAsPercent } from '@/lib/format'
import { outcomeStyle } from '@/lib/outcome'
import { SCORE_DECIMAL_SCALE } from '@/lib/resultRow'
import { laneScale, scoreLane } from '@/lib/scoreLane'
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
 *
 * **조건 하나가 한 줄이고, 그 줄은 일곱 칸이다** — 판정·지표·기준·실제·평가·레인·획득/배점.
 * 기준과 실제를 이웃 칸에 두는 것이 이 배치의 요점이다. 한 문장("PER 백분위 0.3000 이하")과
 * 실제값이 세 칸 떨어져 있으면 사용자가 둘을 눈으로 이어 붙여야 대조가 된다.
 */
const props = defineProps<{
  conditions: ConditionInfo[]
  row: ScreeningRow
  evaluationMode: EvaluationMode
  /** 지표 코드 → 이름. 조건 문구가 코드 대신 이름을 쓰게 한다. */
  indicatorName: (code: string) => string
}>()

const catalog = useCatalogStore()

const isScoring = computed(() => props.evaluationMode === 'SCORING')

/**
 * 레인의 척도(= 최대 배점). null이면 레인을 그리지 않는다.
 *
 * 필수 조건을 빼고 넘긴다 — 총점에 참여하지 않으므로 최대 배점을 정할 자격도 없다.
 * 거르는 기준은 `required`이고 `weight === null`이 아니다(`api/types.ts`가 금지한다).
 */
const laneMax = computed(() =>
  laneScale(
    props.conditions.filter((it) => !it.required).map((it) => it.weight ?? 1),
  ),
)

/**
 * 레인 칸을 두는가.
 *
 * 배점이 전부 같으면 두지 않는다 — 그때 트랙은 전부 같은 길이가 되고 채움은 0 아니면 1이라
 * 옆의 판정 `Tag`와 똑같은 말만 한다. 판단의 정본은 `lib/scoreLane`이고 여기는 그 결과를
 * 칸 개수로 옮기기만 한다.
 */
const showsLane = computed(() => isScoring.value && laneMax.value !== null)

/**
 * 그리드 트랙 수. **한 곳에서만 정한다.**
 *
 * FILTER인데 `required` 조건이 실려 오는 경로가 실재한다 — SCORING 전략을 불러와 판정 방식만
 * 바꾸면 `required`가 그대로 서버로 가고, 서버는 요청의 `evaluationMode`를 우선한다. 칸 수를
 * 판정 방식이나 구획 유무로 따로 유도하면 그때 머리글과 몸이 어긋난다.
 */
const columnCount = computed(() => 5 + (showsLane.value ? 1 : 0) + (isScoring.value ? 1 : 0))

const items = computed(() =>
  props.conditions.map((condition) => {
    const outcome = props.row.outcomes.find((it) => it.conditionIndex === condition.index) ?? null
    // 가중치는 null이면 1이다. 서버의 `effectiveWeight`와 같은 규칙이어야 획득/배점이 맞는다.
    const weight = condition.weight ?? 1
    const parts = splitCondition(condition, props.indicatorName)
    return {
      condition,
      outcome,
      style: outcome === null ? null : outcomeStyle(outcome.status),
      subject: parts.subject,
      criterion: parts.criterion,
      grade: gradeOf(condition, outcome?.actualValue ?? null),
      weight,
      lane:
        laneMax.value === null || condition.required
          ? null
          : scoreLane(weight, outcome?.earnedScore ?? null, laneMax.value),
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
 *
 * IMPORTANT — 구획이 둘이어도 **`<ul>`은 하나다.** 나누면 `max-content` 트랙이 구획마다 따로
 * 계산되어 위아래 구획의 컬럼이 어긋난다. 제목은 전 폭을 차지하는 항목으로 목록 안에 든다.
 *
 * IMPORTANT — **FILTER에서는 나누지 않는다.** 서버가 FILTER에서 `required`를 무시하기 때문이다
 * (ADR-0038: 저장 시점에 거절하지 않고 무시할 뿐이다). 그래서 SCORING 전략을 불러와 판정
 * 방식만 바꾸면 `required=true`인 조건이 그대로 실려 오는데, 그것을 "필수" 구획으로 그리면
 * **화면이 서버와 다른 말을 한다** — FILTER에서는 모든 조건이 이미 결과를 좁히고 있고,
 * 나머지를 "점수"라 부르면 있지도 않은 점수를 가리키게 된다.
 */
const sections = computed(() => {
  const required = isScoring.value
    ? items.value.filter((item) => item.condition.required)
    : []
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

/** 낭독기와 툴팁이 읽을 문장. 숫자 두 개만으로는 무엇의 몇 점인지 알 수 없다. */
function scoreSentence(earnedScore: number | null, weight: number): string {
  return `배점 ${formatNumber(weight, SCORE_DECIMAL_SCALE)} 중 ${formatNumber(earnedScore, SCORE_DECIMAL_SCALE)} 획득`
}

const LANE_HINT =
  '막대의 길이가 그 조건의 배점입니다. 배점이 큰 조건일수록 깁니다. 채워지지 않은 막대는 그 점수를 받지 못했다는 뜻입니다.'
</script>

<template>
  <div class="outcomes">
    <div v-if="isScoring" class="outcomes__total">
      <span class="outcomes__total-label">총점</span>
      <strong class="tabular">{{ formatNumber(row.score, SCORE_DECIMAL_SCALE) }}</strong>
      <span class="outcomes__total-max tabular">/ {{ formatNumber(row.maxScore, SCORE_DECIMAL_SCALE) }}</span>
      <span class="outcomes__total-ratio">({{ formatRatioAsPercent(row.scoreRatio) }})</span>
      <!--
        범례는 머리글과 겹치지 않는다. 머리글은 스크롤과 함께 사라지고 좁은 상자에서는 아예
        접히므로, 인코딩의 설명을 그쪽 툴팁 하나에 걸어 두면 정작 필요한 순간에 없다.
      -->
      <span v-if="showsLane" class="outcomes__legend">막대는 길이가 배점, 채운 만큼이 획득입니다.</span>
    </div>

    <ul class="outcomes__list" :class="`outcomes__list--cols-${columnCount}`">
      <!--
        머리글. 낭독기에는 감춘다 — 좁은 상자에서 접히고 스크롤하면 사라지므로 이것에 뜻을
        기대게 하면 안 된다. 각 칸의 뜻은 셀 자신이 진다(점수 칸의 감춘 문장, 총점 줄의 범례).
      -->
      <li class="outcomes__head" aria-hidden="true">
        <span>판정</span>
        <span>지표</span>
        <span>기준</span>
        <span class="outcomes__head-num">실제</span>
        <span>평가</span>
        <span
          v-if="isScoring"
          class="outcomes__head-score"
          :class="{ 'outcomes__head-score--wide': showsLane }"
        >
          획득 / 배점
          <i v-if="showsLane" v-tooltip.top="LANE_HINT" class="pi pi-info-circle" />
        </span>
      </li>

      <template v-for="section in sections" :key="section.key">
        <li v-if="section.title" class="outcomes__group-title">
          <h4 class="outcomes__group-heading">
            {{ section.title }}
            <span v-if="section.hint" class="outcomes__group-hint">{{ section.hint }}</span>
          </h4>
        </li>

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

          <!-- 잘릴 수 있는 두 칸에는 `title`을 준다. 좁은 상자에서 말줄임이 되면 그 글자를
               되찾을 방법이 달리 없다. -->
          <span class="outcomes__subject" :title="item.subject">{{ item.subject }}</span>
          <span class="outcomes__criterion" :title="item.criterion">{{ item.criterion }}</span>

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
            필수 조건에는 레인도 점수 칸도 없다. 서버가 총점에서 뺐으므로 "0 / 1"을 그리면 못
            받은 점수처럼 읽힌다 — 받을 점수가 애초에 없다. 두 칸을 통째로 비우면 자동 배치가
            앞의 다섯 칸만 채우므로 아래위 행의 실제값·평가가 계속 같은 자리에 선다.
          -->
          <template v-if="isScoring && !item.condition.required">
            <!--
              레인 자리는 값이 없어도 남긴다. `RatioBar`는 값이 없으면 아무것도 그리지 않는데,
              칸까지 사라지면 뒤의 점수가 레인 트랙으로 밀려 들어간다.
            -->
            <span v-if="showsLane" class="outcomes__lane">
              <RatioBar
                v-if="item.lane"
                :value="item.lane.fill"
                :track-ratio="item.lane.trackRatio"
                :floor="false"
                aria-hidden="true"
              />
            </span>
            <span
              class="outcomes__score tabular"
              :title="scoreSentence(item.outcome?.earnedScore ?? null, item.weight)"
            >
              <span class="outcomes__sr-only">
                {{ scoreSentence(item.outcome?.earnedScore ?? null, item.weight) }}
              </span>
              <span aria-hidden="true">
                {{ formatNumber(item.outcome?.earnedScore ?? null, SCORE_DECIMAL_SCALE) }} /
                {{ formatNumber(item.weight, SCORE_DECIMAL_SCALE) }}
              </span>
            </span>
          </template>
        </li>
      </template>
    </ul>

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
  /*
   * IMPORTANT — "이 상자가 좁은가"를 뷰포트가 아니라 상자에게 묻는다(frontend.md).
   *
   * 이 패널은 데스크톱 표의 확장 행과 모바일 카드 **양쪽에** 들어가므로, 접힘 경계가
   * 표↔카드 교체 경계와 같지 않다. 게다가 확장 영역의 폭은 좌측 레일 때문에 뷰포트에
   * 단조증가하지 않는다 — 뷰포트 890px일 때가 950px일 때보다 넓다. 뷰포트로 물으면 넓은
   * 상자를 좁은 것으로 오인한다.
   */
  container-type: inline-size;
  container-name: outcomes;
}

/* 총점은 아래 목록의 합이다. hairline으로 머리와 몸을 가른다. */
.outcomes__total {
  display: flex;
  flex-wrap: wrap;
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

/* 범례는 총점을 읽는 것을 방해하면 안 된다. 한 단 더 내리고 남는 폭으로 민다. */
.outcomes__legend {
  margin-inline-start: auto;
  color: var(--p-text-subtle-color);
  font-size: var(--app-text-2xs);
}

/*
 * 목록 전체가 하나의 그리드다. 행은 subgrid로 이 트랙을 **공유한다.**
 *
 * IMPORTANT — 행마다 자기 그리드를 두지 않는다. 그러면 `max-content` 트랙이 행마다 따로
 * 계산되어 실제값·점수의 좌측 경계가 행마다 어긋나고, 세로로 읽어 내려가는 목록에서 그
 * 들쭉날쭉함이 가장 먼저 보인다. 예전에는 그것을 막으려 트랙을 전부 고정폭으로 두었는데,
 * 그 대가로 **조건문 트랙이 남는 폭을 전부 먹어** 오른쪽에 큰 공백이 생겼다. subgrid는
 * 원인을 없앤다 — 내용에 맞는 폭을 쓰면서 행끼리 정렬된다.
 *
 * 신축을 갖는 것은 **레인 하나**다. 글자 두 칸은 `minmax(0, …)`이라 좁아지면 줄고, 숫자 칸은
 * 내용 폭이 유계라 `max-content`로 두어도 넘치지 않는다. 그리드는 비신축 트랙을 먼저
 * 채우고(§12.6) 신축 트랙을 나중에 펴므로(§12.7), 좁을 때 레인이 글자를 굶기지 않는다.
 */
.outcomes__list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: grid;
  row-gap: 0.125rem;
  column-gap: 0.625rem;
}

.outcomes__list--cols-7 {
  grid-template-columns:
    max-content minmax(0, max-content) minmax(0, max-content)
    max-content max-content minmax(4rem, 1fr) max-content;
}

/*
 * 레인이 없으면(배점이 전부 같거나 FILTER다) **신축 트랙을 두지 않는다.**
 *
 * 남는 폭을 어느 글자 칸엔가 주면 그 칸이 늘어나며 뒤따르는 숫자를 오른쪽 끝으로 밀어낸다 —
 * 브라우저에서 재 보니 FILTER의 기준↔실제 사이가 780px 벌어졌다. 이 개편이 없애려던 바로
 * 그 공백이다. 표는 자기 내용만큼만 넓으면 되고, 남는 폭은 표 **밖**에 두면 된다.
 * 신축이 없으므로 그리드는 저절로 왼쪽으로 몰린다.
 */
.outcomes__list--cols-6 {
  grid-template-columns:
    max-content minmax(0, max-content) minmax(0, max-content)
    max-content max-content max-content;
}

.outcomes__list--cols-5 {
  grid-template-columns:
    max-content minmax(0, max-content) minmax(0, max-content)
    max-content max-content;
}

.outcomes__item,
.outcomes__head {
  grid-column: 1 / -1;
  display: grid;
  grid-template-columns: subgrid;
  align-items: center;
  /* 좌우 패딩은 머리글과 항목이 **같아야** 한다. subgrid의 양끝 트랙이 그만큼 줄어들므로,
     한쪽만 주면 머리글과 몸의 컬럼이 어긋난다. */
  padding: 0.1875rem 0.375rem;
  border-radius: var(--p-border-radius-md);
  font-size: var(--app-text-sm);
}

/*
 * 머리글은 sticky가 아니다. `position: sticky; top: 0`을 붙여 봤는데 **아무 효과가 없었다** —
 * 스크롤포트가 `top: 240`인 지점에서 머리글이 `top: -424`로 그냥 흘러 나갔다(브라우저 실측).
 * 확장 영역이 표의 `<td>` 안에 있고 그 상자 자신이 `position: sticky`라 붙을 자리가 없다.
 *
 * 그래서 **머리글에 뜻을 기대지 않는다.** 스크롤하면 사라지는 것을 전제로, 각 칸의 뜻은
 * 셀 자신이 진다 — 점수 칸의 감춘 문장과 총점 줄의 범례가 그 몫이다.
 */
.outcomes__head {
  color: var(--p-text-subtle-color);
  font-size: var(--app-text-2xs);
  letter-spacing: 0.02em;
}

.outcomes__head-num {
  text-align: right;
}

.outcomes__head-score {
  text-align: right;
}

/* 레인과 숫자는 한 이야기다. 머리글 하나가 두 칸을 함께 덮어 그것을 말한다. */
.outcomes__head-score--wide {
  grid-column: span 2;
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

/*
 * 잘릴 수 있는 두 칸. `min-width: 0`이 없으면 그리드 아이템의 자동 최소 크기가 min-content라
 * 트랙이 줄어도 아이템이 버티며 **옆 칸을 침범한다.** 말줄임도 걸리지 않는다.
 */
.outcomes__subject,
.outcomes__criterion {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 실제값은 기준과 대조하는 것이라 본문 밝기를 유지한다. 나머지 둘은 곁들이다. */
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

/* 트랙은 왼쪽 끝에서 자란다. 길이가 배점이므로 시작점이 같아야 서로 견줄 수 있다. */
.outcomes__lane {
  display: block;
  min-width: 0;
}

.outcomes__sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip-path: inset(50%);
  white-space: nowrap;
}

/* 구획 제목. 목록 안에 들지만 전 폭을 차지한다 — 트랙을 몸과 공유하기 위해서다. */
.outcomes__group-title {
  grid-column: 1 / -1;
  padding: 0.375rem 0.375rem 0;
}

/*
 * 판정 Tag보다 약하게 둔다 — 이 목록에서 눈이 먼저 가야 하는 것은 줄마다의 충족·불충족이고,
 * 제목은 그 줄들이 무엇을 묻는지만 말한다.
 */
.outcomes__group-heading {
  display: flex;
  align-items: baseline;
  gap: 0.375rem;
  margin: 0;
  font-size: var(--app-text-2xs);
  font-weight: 600;
  letter-spacing: 0.02em;
  color: var(--p-text-muted-color);
}

.outcomes__group-hint {
  font-weight: 400;
  color: var(--p-text-subtle-color);
}

.outcomes__note {
  margin: 0;
  font-size: var(--app-text-sm);
  color: var(--p-text-muted-color);
}

/*
 * 좁은 상자에서는 조건이 세 줄이 된다 — 조건 한 줄, 값 한 줄, 레인 한 줄.
 *
 * 48rem은 일곱 칸의 내용 폭 합(약 44rem)에 칸 사이 여백을 더한 값이다. 그 아래에서는 글자
 * 두 칸이 말줄임에 기대기 시작하므로, 잘라 읽히느니 접는 편이 낫다.
 *
 * 첫 칸이 `minmax(4.5rem, max-content)`인 것이 요점이다. 판정 Tag와 실제값이 같은 칸을
 * 위아래로 쓰므로 둘 중 넓은 쪽에 맞아야 하고, subgrid라 그 폭이 **모든 행에서 같다.**
 * 행마다 자기 그리드였을 때는 값의 자릿수가 다르면 조건문이 행마다 다른 자리에서 시작했다.
 */
@container outcomes (max-width: 48rem) {
  .outcomes__head {
    display: none;
  }

  /* 막대가 없으면 막대를 설명하는 문장도 없어야 한다. 아래에서 레인을 감춘다. */
  .outcomes__legend {
    display: none;
  }

  .outcomes__list--cols-7,
  .outcomes__list--cols-6,
  .outcomes__list--cols-5 {
    grid-template-columns: minmax(4.5rem, max-content) max-content minmax(0, 1fr);
  }

  .outcomes__item {
    row-gap: 0.125rem;
    column-gap: 0.5rem;
    padding: 0.25rem 0.375rem;
  }

  .outcomes__status {
    grid-row: 1;
    grid-column: 1;
  }

  /* 지표와 기준은 붙어야 한 문장으로 읽힌다("PER 백분위 0.3000 이하"). */
  .outcomes__subject {
    grid-row: 1;
    grid-column: 2;
  }

  .outcomes__criterion {
    grid-row: 1;
    grid-column: 3;
    text-align: start;
  }

  .outcomes__actual {
    grid-row: 2;
    grid-column: 1;
    text-align: start;
  }

  .outcomes__grade {
    grid-row: 2;
    grid-column: 2;
  }

  .outcomes__score {
    grid-row: 2;
    grid-column: 3;
    justify-self: end;
  }

  /*
   * 레인은 접힌 폭에서 그리지 않는다.
   *
   * 세 줄째에 전폭으로 깔아 봤는데(브라우저 실측), 조건 하나가 세 줄이 되면서 그 막대가
   * **조건 사이의 구분선으로 읽혔다.** 길이가 배점이라 줄마다 길이가 다른데, 구분선으로
   * 읽히는 순간 그 차이가 뜻이 아니라 흠으로 보인다. 넓은 폭에서는 좌우를 잇는 리더 라인이라
   * 같은 그래픽이 정반대로 작동한다.
   *
   * 잃는 정보는 없다 — 옆의 `획득 / 배점`이 같은 것을 숫자로 말한다.
   */
  .outcomes__lane {
    display: none;
  }
}
</style>
