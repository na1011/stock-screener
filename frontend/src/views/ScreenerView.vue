<script setup lang="ts">
import Button from 'primevue/button'
import Tag from 'primevue/tag'
import { computed, ref } from 'vue'

import AsyncPanel from '@/components/AsyncPanel.vue'
import SaveScreenDialog from '@/components/SaveScreenDialog.vue'
import ScreenPicker from '@/components/ScreenPicker.vue'
import ConditionChipBar from '@/components/condition/ConditionChipBar.vue'
import ResultCards from '@/components/result/ResultCards.vue'
import ResultSummary from '@/components/result/ResultSummary.vue'
import ResultTable from '@/components/result/ResultTable.vue'
import { useBreakpoint } from '@/composables/useBreakpoint'
import { screenTypeLabel } from '@/lib/screen'
import { useScreenDraftStore } from '@/stores/screenDraft'
import { useScreeningStore } from '@/stores/screening'

const draft = useScreenDraftStore()
const screening = useScreeningStore()
const { isMobile, isBuilderNarrow } = useBreakpoint()

const result = computed(() => screening.result ?? null)
const saveVisible = ref(false)

/**
 * 서버가 수정을 막는 화면은 저장 버튼을 주지 않는다(403이 날 것을 알면서 버튼을 주지 않는다).
 * 복제한 뒤에는 내 것이므로 저장할 수 있다. 판단의 정본은 서버의 `editable`이다.
 */
const canSave = computed(() => draft.editable && draft.completeConditions.length > 0)

/**
 * 결과가 지금 조건과 어긋나 있는가.
 *
 * IMPORTANT — 자동 실행을 하지 않는 대신 **화면이 어긋남을 자백한다.** `/api/screening`은
 * 최대 2000행을 통째로 주는 POST라 타이핑마다 부를 수 없고, 그래서 결과는 언제나 "마지막으로
 * 실행한 것"이다. 그 사실을 말하지 않으면 사용자는 눈앞의 목록이 방금 고친 조건의 결과라고
 * 읽는다. 버튼 라벨이 바뀌는 것이 그 자백이다.
 */
const stale = computed(() => screening.hasResult && draft.dirty)

const runLabel = computed(() => (stale.value ? '변경 반영' : '스크리닝 실행'))

/**
 * 실행 버튼의 툴팁.
 *
 * 미완성 조건이 조용히 빠지던 것을 여기서도 말한다 — 칩의 점선을 놓친 사용자에게 마지막
 * 기회다. 실행 자체는 막지 않는다. 완성된 조건이 하나라도 있으면 그것으로 거를 수 있고,
 * 막아 버리면 "왜 눌리지 않는지"를 다시 설명해야 한다.
 */
const incompleteCount = computed(() => draft.conditions.length - draft.completeConditions.length)

const runHint = computed(() =>
  incompleteCount.value > 0
    ? `값을 채우지 않은 조건 ${incompleteCount.value}개는 제외하고 실행합니다.`
    : null,
)

async function run(): Promise<void> {
  const request = draft.toRequest()
  if (request === null) {
    return
  }
  await screening.run(request)
}
</script>

<template>
  <!-- 루트가 <main>이 아닌 이유: AppLayout이 이미 <main>을 두고 있다. 중첩하면 문서에
       주 영역이 둘이 되어 스크린 리더의 랜드마크 이동이 어긋난다. -->
  <div class="screener">
    <div class="screener__bar">
      <div class="screener__row">
        <div class="screener__identity">
          <h1 class="screener__title">{{ draft.title }}</h1>
          <Tag v-if="draft.isPreset" :value="screenTypeLabel('PRESET')" severity="info" />
          <Tag v-if="draft.readOnly" value="읽기 전용" severity="secondary" />
          <Tag
            v-else-if="draft.dirty && draft.origin.kind !== 'custom'"
            value="수정됨"
            severity="warn"
          />
          <Button
            v-if="draft.readOnly"
            label="복제해서 편집"
            icon="pi pi-copy"
            size="small"
            text
            @click="draft.forkAsCustom()"
          />
        </div>

        <div class="screener__actions">
          <!-- 넓은 폭에서는 좌측 레일이 화면 목록을 상주시킨다. 좁을 때만 Select가 그 자리를 맡는다. -->
          <ScreenPicker v-if="isBuilderNarrow" />
          <Button
            v-tooltip.bottom="runHint"
            :label="runLabel"
            icon="pi pi-play"
            size="small"
            :severity="stale ? 'warn' : undefined"
            :loading="screening.state === 'loading'"
            :disabled="!draft.canRun"
            @click="run"
          />
          <Button
            label="저장"
            icon="pi pi-save"
            size="small"
            outlined
            :disabled="!canSave"
            @click="saveVisible = true"
          />
        </div>
      </div>

      <ConditionChipBar
        class="screener__chips"
        :retention="screening.retention"
        :invalid-index="screening.invalidIndex"
      />
    </div>

    <SaveScreenDialog v-model:visible="saveVisible" />

    <section class="screener__result">
      <AsyncPanel
        :state="screening.state"
        :error="screening.error"
        :retry="screening.retry"
        panel="screening"
        :skeleton-rows="6"
      >
        <template #idle>
          <p class="screener__idle">
            조건을 만들고 <strong>스크리닝 실행</strong>을 누르면 결과가 여기에 나옵니다.
          </p>
        </template>

        <template v-if="result">
          <ResultSummary :result="result" :stale="stale" />
          <!-- 모바일은 표를 좁힌 것이 아니라 카드 목록이다. 자세한 이유는 useBreakpoint에 있다. -->
          <ResultCards v-if="isMobile" :result="result" :as-of="result.asOf" class="screener__rows" />
          <ResultTable v-else :result="result" :as-of="result.asOf" class="screener__rows" />
        </template>
      </AsyncPanel>
    </section>
  </div>
</template>

<style scoped>
/*
 * 결과가 화면의 대부분을 차지해야 한다. 그래서 셸은 폭을 묶지 않고(표는 컬럼이 열 개를
 * 넘는다) 읽는 것만 `--app-reading-width`로 묶는다. 예전에는 표까지 90rem에 갇혀
 * 넓은 화면에서 좌우가 비는데 정작 컬럼은 잘렸다.
 */
.screener {
  display: flex;
  flex-direction: column;
  min-height: 100dvh;
}

/* 스크롤을 내려도 "무엇을 실행했는가"와 "다시 실행"이 남는다. */
.screener__bar {
  position: sticky;
  top: 0;
  z-index: 15;
  padding: 0.5rem var(--app-gutter);
  border-bottom: 1px solid var(--p-content-border-color);
  background: color-mix(in srgb, var(--p-content-background) 92%, transparent);
  backdrop-filter: blur(12px);
}

.screener__row {
  display: flex;
  align-items: center;
  gap: 0.5rem 0.75rem;
  flex-wrap: wrap;
  margin-bottom: 0.5rem;
}

.screener__identity {
  display: flex;
  align-items: center;
  gap: 0.375rem;
  flex-wrap: wrap;
  flex: 1 1 12rem;
  min-width: 0;
}

.screener__title {
  margin: 0;
  font-size: var(--app-text-lg);
  font-weight: 700;
  letter-spacing: -0.01em;
  overflow-wrap: anywhere;
}

.screener__actions {
  display: flex;
  align-items: center;
  gap: 0.375rem;
  flex-wrap: wrap;
  margin-inline-start: auto;
}

.screener__result {
  flex: 1;
  min-width: 0;
  padding: 0.625rem var(--app-gutter) 1.5rem;
}

.screener__idle {
  padding: 4rem 0;
  text-align: center;
  color: var(--p-text-muted-color);
}

.screener__rows {
  margin-top: 0.5rem;
}

/* 좁으면 제목과 동작이 한 줄에 들어가지 않는다. 동작을 아래로 내려 제목이 잘리지 않게 한다. */
@media (max-width: 900px) {
  .screener__actions {
    margin-inline-start: 0;
    flex-basis: 100%;
  }
}
</style>
