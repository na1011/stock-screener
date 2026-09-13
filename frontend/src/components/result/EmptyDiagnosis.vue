<script setup lang="ts">
import Button from 'primevue/button'
import Tag from 'primevue/tag'
import { computed, ref, watch } from 'vue'

import type { ConditionInfo, ScreeningResponse } from '@/api/types'
import { splitCondition } from '@/lib/conditionText'
import { readDiagnosisCollapsed, writeDiagnosisCollapsed } from '@/lib/detailVisibility'
import { formatCount } from '@/lib/format'
import { outcomeStyle } from '@/lib/outcome'

/**
 * 결과가 0건일 때 **왜 0건인가**를 그린다(ADR-0142 · P27 축④).
 *
 * IMPORTANT — 이 자리는 `ResultSummary` 아래의 **머리말 층**이다. 표의 `#empty` 슬롯에 두면
 * 안 된다 — 그 슬롯은 사용자의 검색어가 전부 걸러냈을 때도 뜨므로, 거기 두면 화면이 지금
 * 보이는 것과 **다른 질문에 답한다.**
 *
 * 서버는 수만 주고 문구는 여기서 정한다. `adsSuspectCount`·`relativePopulations`가 이미 그
 * 형태이고, enum을 만들면 `wire-contract.json`·`WireContractTest`·`contract.spec.ts` 셋을
 * 함께 고쳐야 한다.
 */
const props = defineProps<{
  result: ScreeningResponse
  /** 지표 코드 → 이름. `ConditionOutcomes`와 같은 형태로 **주입받는다** — 화면이 카탈로그를
   * 스스로 들면 그 요청만 실패했을 때 문구가 조용히 망가진다. */
  indicatorName: (code: string) => string
}>()

const rows = computed(() =>
  props.result.conditionTallies
    .flatMap((tally) => {
      const condition: ConditionInfo | undefined = props.result.conditions[tally.conditionIndex]
      // 서버가 길이를 맞춰 주지만(조립자가 단언한다) 화면이 그것을 전제하지는 않는다.
      return condition === undefined ? [] : [{ tally, condition }]
    })
    // 많이 자른 것부터. 사용자가 먼저 손볼 조건이 위에 온다.
    .sort(
      (a, b) =>
        b.tally.notMatched + b.tally.notEvaluable - (a.tally.notMatched + a.tally.notEvaluable),
    ),
)

/** 그 조건 하나만 풀면 열리는가. `wouldPassWithout`이 통과 수보다 클 때만 뜻이 있다. */
const openers = computed(() =>
  rows.value.filter(
    (row) =>
      row.tally.wouldPassWithout !== null && row.tally.wouldPassWithout > props.result.includedCount,
  ),
)

/**
 * 한 조건만 풀어서는 안 열리는 경우의 답.
 *
 * 이것이 없으면 진단이 **조건이 가장 빡빡할 때 정확히 입을 다문다** — 어느 종목도 정확히
 * 하나만 실패하지 않으면 한계 기여가 전 조건에서 0이 되기 때문이다.
 */
const closestNote = computed(() => {
  if (openers.value.length > 0 || props.result.minimumBlocked === null) return null
  return `한 조건만 풀어서는 열리지 않습니다 — 가장 가까운 종목도 ${props.result.minimumBlocked}개를 넘기지 못했습니다.`
})

/**
 * 접혔을 때도 남는 한 줄. **접힌 화면이 침묵하지 않게 하는 것이 이 값의 일이다.**
 *
 * 한 조건만 풀면 열리는 경우가 있으면 **가장 많이 열어 주는 것 하나**를 이름으로 말하고,
 * 없으면 `closestNote`가 대신한다. 목록이 아니라 한 줄이므로 접기의 목적(스크롤)을 안 깬다.
 */
const collapsedNote = computed(() => {
  if (closestNote.value !== null) return null
  const best = [...openers.value].sort(
    (a, b) => (b.tally.wouldPassWithout ?? 0) - (a.tally.wouldPassWithout ?? 0),
  )[0]
  if (best === undefined) return null
  return `${text(best.condition)} 하나만 빼면 ${formatCount(best.tally.wouldPassWithout ?? 0)}종목이 남습니다.`
})

const text = (condition: ConditionInfo): string => {
  // 이어 붙이면 원래 문장 그대로다 — `conditionText`의 계약이 그렇게 적혀 있다.
  const parts = splitCondition(condition, props.indicatorName, props.result.currencyCode)
  return `${parts.subject} ${parts.criterion}`
}

const missing = outcomeStyle('NOT_EVALUABLE')
const unmatched = outcomeStyle('NOT_MATCHED')

/**
 * **기본은 접힘이다**(2026-09-14 사용자 결정) — 조건이 많으면 목록만으로 화면이 길어진다.
 *
 * **그래서 접힌 상태가 무엇을 말하는지가 이 컴포넌트의 계약이다.** 판정 대상 수와 요약
 * 한 줄이 남고 버튼이 갯수를 밝힌다. P27 축④가 요구하는 것은 「0건일 때 사유에 **닿을 수
 * 있다**」이므로 클릭 하나 뒤는 충족이지만, **접힌 화면이 아무 말도 안 하면 그때는 못 닿는
 * 것**이 된다. 그 경계를 시험이 잠근다.
 */
const collapsed = ref(readDiagnosisCollapsed())
watch(collapsed, writeDiagnosisCollapsed)
</script>

<template>
  <section v-if="rows.length > 0" class="diagnosis">
    <div class="diagnosis__head">
      <h3 class="diagnosis__title">조건별로 몇 종목이 걸렸는지</h3>
      <Button
        :label="collapsed ? `조건 ${rows.length}개 펼치기` : '접기'"
        :icon="collapsed ? 'pi pi-chevron-down' : 'pi pi-chevron-up'"
        size="small"
        text
        :aria-expanded="!collapsed"
        @click="collapsed = !collapsed"
      />
    </div>
    <p class="diagnosis__lead">
      판정 대상 <strong class="tabular">{{ formatCount(result.populationCount) }}</strong
      >종목에 조건을 하나씩 대 본 결과입니다.
    </p>

    <ul v-if="!collapsed" class="diagnosis__list">
      <li v-for="row in rows" :key="row.tally.conditionIndex" class="diagnosis__item">
        <span class="diagnosis__condition">{{ text(row.condition) }}</span>
        <span class="diagnosis__counts">
          <Tag v-if="row.tally.notMatched > 0" :severity="unmatched.severity" :value="`${unmatched.label} ${formatCount(row.tally.notMatched)}`" />
          <!--
            MUST: 「불충족」과 「데이터 없음」을 갈라 그린다(P27 축④). 합치면 사용자가
            데이터 문제를 전략 문제로 읽고, 조건을 고치러 간다.
          -->
          <Tag v-if="row.tally.notEvaluable > 0" :severity="missing.severity" :value="`${missing.label} ${formatCount(row.tally.notEvaluable)}`" />
        </span>
        <span
          v-if="row.tally.wouldPassWithout !== null && row.tally.wouldPassWithout > result.includedCount"
          class="diagnosis__opener"
        >
          이 조건만 빼면 {{ formatCount(row.tally.wouldPassWithout) }}종목
        </span>
      </li>
    </ul>

    <p v-if="closestNote" class="diagnosis__note">{{ closestNote }}</p>
    <p v-if="collapsed && collapsedNote" class="diagnosis__note">{{ collapsedNote }}</p>
    <p v-if="!collapsed" class="diagnosis__caveat">
      「데이터 없음」은 그 회사에 대한 주장이 아니라 <strong>우리 데이터에 그 값이 없다</strong>는
      뜻입니다. 조건에 맞지 않았다는 뜻이 아닙니다.
    </p>
  </section>
</template>

<style scoped>
.diagnosis {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
  padding: 1rem;
  border: 1px solid var(--p-content-border-color);
  border-radius: var(--p-content-border-radius);
}

.diagnosis__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 0.5rem;
}

.diagnosis__title {
  margin: 0;
  font-size: var(--app-text-md);
}

.diagnosis__lead,
.diagnosis__note,
.diagnosis__caveat {
  margin: 0;
  font-size: var(--app-text-sm);
  color: var(--p-text-muted-color);
}

.diagnosis__note {
  font-weight: 600;
  color: var(--p-text-color);
}

.diagnosis__list {
  display: flex;
  flex-direction: column;
  gap: 0.375rem;
  margin: 0;
  padding: 0;
  list-style: none;
}

.diagnosis__item {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 0.5rem;
}

.diagnosis__condition {
  flex: 1 1 14rem;
}

.diagnosis__counts {
  display: flex;
  gap: 0.375rem;
}

.diagnosis__opener {
  font-size: var(--app-text-xs);
  color: var(--p-text-muted-color);
}
</style>
