<script setup lang="ts">
import Message from 'primevue/message'
import { computed } from 'vue'

import type { ScreeningResponse } from '@/api/types'
import { evaluationModeLabels } from '@/lib/evaluationMode'
import { populationNote } from '@/lib/population'
import { screenTypeLabel, staleNotice, type StaleReason } from '@/lib/screen'
import { ALL_UNIVERSES_LABEL, universeLabel } from '@/lib/universe'
import { formatCount, formatDate } from '@/lib/format'

/**
 * 결과의 전제를 드러내는 머리말.
 *
 * 세 가지가 반드시 보여야 한다.
 * - **기준일** — 감추면 배치가 하루 밀렸을 때 사용자가 어제 데이터를 오늘 것으로 읽는다.
 * - **잘림** — `truncated`가 참인데 표시하지 않으면 그 목록을 "조건에 맞는 전부"로 읽는다.
 * - **상대 비교의 모집단** — "상위 50%"가 무엇에 대한 것인지. 모집단이 유니버스와 업종 둘이라
 *   조건마다 뜻이 다른데, 값은 둘 다 0~1이라 표에서는 구분되지 않는다.
 */
const props = defineProps<{
  result: ScreeningResponse
  /**
   * 눈앞의 결과가 지금 조건과 어긋난 이유. 어긋나지 않았으면 null.
   *
   * IMPORTANT — 자동 실행을 하지 않으므로 결과는 언제나 "마지막으로 실행한 것"이다. 그 사실을
   * 말하지 않으면 사용자는 눈앞의 목록을 방금 고친 조건의 결과로 읽는다. 위의 세 가지와
   * 같은 종류의 위험이다 — 화면이 사실과 다른 것을 주장하게 된다.
   *
   * 참/거짓이 아니라 **이유**를 받는다. 어긋나는 길이 둘(조건 편집·화면 전환)이고 무엇을
   * 되돌리면 되는지가 다르다. 문구의 정본은 `lib/screen.staleNotice`다.
   */
  staleReason?: StaleReason
}>()

const staleText = computed(() => staleNotice(props.staleReason ?? null))

/** 서버가 일자를 정했는가. `asOfRequested`가 null이면 그렇다. */
const serverChoseDate = computed(() => props.result.asOfRequested === null)

/** 요청한 일자가 거래일이 아니어서 보정되었는가. */
const adjusted = computed(
  () => props.result.asOfRequested !== null && props.result.asOfRequested !== props.result.asOf,
)

const universeText = computed(() =>
  props.result.universe === null ? ALL_UNIVERSES_LABEL : universeLabel(props.result.universe),
)

/**
 * 이 결과가 쓴 모집단마다 문장 하나.
 *
 * 카탈로그로 판단하지 않는다. 예전에 그렇게 했다가, 이 컴포넌트는 카탈로그에 `AsyncPanel`을
 * 걸지 않아서 **스크리닝은 성공하고 카탈로그 요청만 실패하면** 판단이 조용히 false가 되고
 * 문구가 사라졌다. 지금은 응답의 `relativePopulations`를 그대로 쓴다.
 *
 * 둘이면 둘 다 낸다. 하나로 접으면 조건마다 모집단이 다르다는 사실이 감춰지는데, 값은 둘 다
 * 0~1이라 표에서는 구분되지 않는다.
 */
const populationNotes = computed(() =>
  props.result.relativePopulations.map((population) => ({
    population,
    note: populationNote(population),
  })),
)
</script>

<template>
  <div class="summary">
    <!--
      네 칸짜리 테두리 상자였던 것을 한 줄로 줄였다. 여기서 먹는 높이는 그대로 결과 행 수에서
      빠지는데, 이 넷은 훑어보는 전제이지 읽는 내용이 아니다. 다만 **줄이는 것과 지우는 것은
      다르다** — 기준일·잘림·백분위 모집단 셋은 없으면 화면이 사실과 다른 것을 주장하게 되므로
      그대로 남는다.
    -->
    <dl class="summary__facts">
      <!--
        결과가 **어느 전략 것인지**. 이것이 없으면 제목은 새 전략인데 표는 옛 전략인 상태를
        대조할 단서가 없다. 아래 경고가 그 사실을 말하지만, 무엇의 결과인지는 여기가 답한다.
      -->
      <div class="summary__fact">
        <dt>{{ screenTypeLabel(result.screen.type) }}</dt>
        <dd>
          <strong>{{ result.screen.name }}</strong>
        </dd>
      </div>
      <div class="summary__fact">
        <dt>기준일</dt>
        <dd>
          <strong class="tabular">{{ formatDate(result.asOf) }}</strong>
          <span v-if="serverChoseDate" class="summary__hint">최신 지표 일자</span>
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
          <strong class="tabular">{{ formatCount(result.resultCount) }}</strong
          ><span class="summary__hint"
            >/ 평가 {{ formatCount(result.evaluatedCount) }}종목</span
          >
        </dd>
      </div>
      <div class="summary__fact">
        <dt>판정</dt>
        <dd>{{ evaluationModeLabels(result.evaluationMode).noun }}</dd>
      </div>
      <div v-if="staleText" class="summary__fact summary__fact--stale">
        <i class="pi pi-exclamation-triangle" aria-hidden="true" />
        <span>{{ staleText }}</span>
      </div>
    </dl>

    <Message v-if="result.truncated" severity="warn" :closable="false" class="summary__message">
      결과가 상한에 걸려 잘렸습니다. 아래 목록은 조건에 맞는 종목의 전부가 아닙니다 — 조건을 좁혀
      다시 실행해 주세요.
    </Message>

    <p v-for="entry in populationNotes" :key="entry.population" class="summary__note">
      {{ entry.note }}
    </p>
  </div>
</template>

<style scoped>
.summary {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

/*
 * 상자가 아니라 한 줄이다. 테두리와 칸막이를 없애고 가운뎃점으로 나눈다 — 넷은 서로 대등한
 * 사실이지 비교할 수치가 아니라서 격자에 가둘 이유가 없었다. 59px이 22px이 됐고 그만큼
 * 결과 행이 늘었다.
 */
.summary__facts {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 0.25rem 1rem;
  margin: 0;
}

.summary__fact {
  display: flex;
  align-items: baseline;
  gap: 0.375rem;
  min-width: 0;
}

.summary__fact dt {
  font-size: var(--app-text-2xs);
  letter-spacing: 0.03em;
  color: var(--p-text-subtle-color);
}

.summary__fact dd {
  margin: 0;
  display: flex;
  align-items: baseline;
  gap: 0.25rem;
  font-size: var(--app-text-xs);
}

.summary__hint {
  font-size: var(--app-text-2xs);
  font-weight: 400;
  color: var(--p-text-muted-color);
}

/* 어긋남은 사실이 아니라 경고다. 나머지 넷과 같은 무게로 두면 읽히지 않는다. */
.summary__fact--stale {
  gap: 0.3125rem;
  padding: 0 0.375rem;
  border-radius: var(--p-border-radius-sm);
  background: color-mix(in srgb, var(--p-amber-400) 16%, transparent);
  color: var(--p-amber-300);
  font-size: var(--app-text-2xs);
}

.summary__note {
  margin: 0;
  font-size: var(--app-text-2xs);
  color: var(--p-text-muted-color);
}

.summary__message {
  margin: 0;
}
</style>
