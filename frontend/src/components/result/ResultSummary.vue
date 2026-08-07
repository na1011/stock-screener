<script setup lang="ts">
import Message from 'primevue/message'
import { computed } from 'vue'

import { PERCENTILE_POPULATION_ALL } from '@/api/types'
import type { ScreeningResponse } from '@/api/types'
import { evaluationModeLabels } from '@/lib/evaluationMode'
import { formatCount, formatDate } from '@/lib/format'

/**
 * 결과의 전제를 드러내는 머리말.
 *
 * 세 가지가 반드시 보여야 한다.
 * - **기준일** — 감추면 배치가 하루 밀렸을 때 사용자가 어제 데이터를 오늘 것으로 읽는다.
 * - **잘림** — `truncated`가 참인데 표시하지 않으면 그 목록을 "조건에 맞는 전부"로 읽는다.
 * - **백분위 모집단** — 유니버스를 골라도 순위는 그 안에서 매긴 것이 아니다. 이것을 적지 않으면
 *   화면이 "코스피 상위 20%"라고 말하게 되는데 사실이 아니다.
 */
const props = defineProps<{ result: ScreeningResponse }>()

/** 서버가 일자를 정했는가. `asOfRequested`가 null이면 그렇다. */
const serverChoseDate = computed(() => props.result.asOfRequested === null)

/** 요청한 일자가 거래일이 아니어서 보정되었는가. */
const adjusted = computed(
  () => props.result.asOfRequested !== null && props.result.asOfRequested !== props.result.asOf,
)

const universeText = computed(() =>
  props.result.universe === null
    ? '전체 (코스피200 + 코스닥150)'
    : `${props.result.universe.name}${
        props.result.universe.memberCount === null ? '' : ` (${props.result.universe.memberCount}종목)`
      }`,
)

/**
 * 백분위 모집단 주의 문구를 보여야 하는가.
 *
 * 예전에는 카탈로그(`byCode.percentileOf`)로 판단했다. 그런데 이 컴포넌트는 카탈로그에
 * `AsyncPanel`을 걸지 않아서, **스크리닝은 성공하고 카탈로그 요청만 실패하면** 판단이
 * 조용히 false가 되고 문구가 사라졌다. 그러면 화면이 "코스피 상위 20%"라고 말하게 되는데
 * 서버는 그것이 사실이 아니라고 명시하고 있다. 지금은 응답의 `usesPercentile`을 쓴다.
 */
const showPercentileNote = computed(() => props.result.usesPercentile)

/**
 * 모집단이 알려진 값과 다른가.
 *
 * 다르다고 해서 문구를 **지우지 않는다.** 주의 문구의 부재가 위험한 상태이지, 조금 어긋난
 * 문구가 위험한 것이 아니다. 모르는 모집단이면 모집단 이름을 그대로 밝힌다.
 */
const knownPopulation = computed(
  () => props.result.percentilePopulation === PERCENTILE_POPULATION_ALL,
)
</script>

<template>
  <div class="summary">
    <dl class="summary__facts">
      <div class="summary__fact">
        <dt>기준일</dt>
        <dd>
          <strong>{{ formatDate(result.asOf) }}</strong>
          <span v-if="serverChoseDate" class="summary__hint">서버가 최신 지표 일자로 지정</span>
          <span v-else-if="adjusted" class="summary__hint">
            요청 {{ formatDate(result.asOfRequested) }} → 거래일로 보정
          </span>
        </dd>
      </div>
      <div class="summary__fact">
        <dt>대상</dt>
        <dd>{{ universeText }}</dd>
      </div>
      <div class="summary__fact">
        <dt>결과</dt>
        <dd>
          <strong>{{ formatCount(result.resultCount) }}</strong
          >종목
          <span class="summary__hint">평가 {{ formatCount(result.evaluatedCount) }}종목</span>
        </dd>
      </div>
      <div class="summary__fact">
        <dt>판정</dt>
        <dd>{{ evaluationModeLabels(result.evaluationMode).noun }}</dd>
      </div>
    </dl>

    <Message v-if="result.truncated" severity="warn" :closable="false" class="summary__message">
      결과가 상한에 걸려 잘렸습니다. 아래 목록은 조건에 맞는 종목의 전부가 아닙니다 — 조건을 좁혀
      다시 실행해 주세요.
    </Message>

    <template v-if="showPercentileNote">
      <p v-if="knownPopulation" class="summary__note">
        백분위는 <strong>현재 편입된 코스피·코스닥 종목 전체</strong>를 기준으로 계산됩니다. 특정
        유니버스를 골라 조회해도 순위는 그 안에서 매긴 것이 아닙니다.
      </p>
      <p v-else class="summary__note">
        백분위는 <strong>{{ result.percentilePopulation }}</strong> 모집단을 기준으로 계산됩니다.
        특정 유니버스를 골라 조회해도 순위는 그 안에서 매긴 것이 아닐 수 있습니다.
      </p>
    </template>
  </div>
</template>

<style scoped>
.summary {
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
}

.summary__facts {
  display: flex;
  flex-wrap: wrap;
  gap: 1.5rem;
  margin: 0;
}

.summary__fact {
  display: flex;
  flex-direction: column;
  gap: 0.125rem;
}

.summary__fact dt {
  font-size: 0.75rem;
  color: var(--p-text-muted-color);
}

.summary__fact dd {
  margin: 0;
  font-size: 0.9375rem;
  display: flex;
  align-items: baseline;
  gap: 0.375rem;
  flex-wrap: wrap;
}

.summary__hint {
  font-size: 0.75rem;
  color: var(--p-text-muted-color);
}

.summary__note {
  margin: 0;
  font-size: 0.8125rem;
  color: var(--p-text-muted-color);
}

.summary__message {
  margin: 0;
}
</style>
