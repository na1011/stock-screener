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
import { incompleteConditionNotice } from '@/lib/conditionText'
import { matchesOrigin, screenTypeLabel, type StaleReason } from '@/lib/screen'
import { useScreenDraftStore } from '@/stores/screenDraft'
import { useScreeningStore } from '@/stores/screening'

const draft = useScreenDraftStore()
const screening = useScreeningStore()
const { isMobile, isBuilderNarrow } = useBreakpoint()

const result = computed(() => screening.result ?? null)
const saveVisible = ref(false)

/**
 * 서버가 수정을 막는 전략은 저장 버튼을 주지 않는다(403이 날 것을 알면서 버튼을 주지 않는다).
 * 복제한 뒤에는 내 것이므로 저장할 수 있다. 판단의 정본은 서버의 `editable`이다.
 */
const canSave = computed(() => draft.editable && draft.completeConditions.length > 0)

/**
 * 결과가 지금 조건과 어긋나 있는가. 그리고 왜.
 *
 * IMPORTANT — 자동 실행을 하지 않는 대신 **화면이 어긋남을 자백한다.** `/api/screening`은
 * 최대 2000행을 통째로 주는 POST라 타이핑마다 부를 수 없고, 그래서 결과는 언제나 "마지막으로
 * 실행한 것"이다. 그 사실을 말하지 않으면 사용자는 눈앞의 목록이 방금 고친 조건의 결과라고
 * 읽는다. 버튼 라벨이 바뀌는 것이 그 자백이다.
 *
 * IMPORTANT — 어긋나는 길이 **둘**이다. 조건을 손으로 고친 것(`dirty`)과 **전략을 통째로
 * 갈아탄 것**이다. 뒤엣것은 `loadFrom`이 `dirty`를 false로 내리므로 예전에는 자백이
 * 꺼졌다 — 제목·조건칩은 새 전략인데 표는 옛 전략의 결과였고 경고가 하나도 없었다.
 *
 * 전략이 같은지는 **서버가 준 `result.screen`**과 대조한다. "무엇을 실행했는가"를 클라이언트가
 * 따로 기억하면 그 거울이 어긋나고, 어긋나도 값이 그럴듯해 드러나지 않는다.
 */
const staleReason = computed<StaleReason>(() => {
  if (!screening.hasResult || result.value === null) {
    return null
  }
  // 손댄 것이 먼저다. 프리셋을 복제하면(`forkAsCustom`) origin은 즉석으로 바뀌지만 조건은
  // 그대로이고 `dirty`만 올라간다 — 그때 "다른 전략"이라고 말하면 사실이 아니다.
  if (draft.dirty) {
    return 'EDITED'
  }
  // "새 조건"(reset)도 여기 걸린다. 즉석 조건은 어느 전략과도 같지 않으므로 조건 칩이
  // 비었는데 옛 결과가 남아 있는 상태도 자백된다.
  return matchesOrigin(result.value.screen, draft.origin) ? null : 'SCREEN'
})

const stale = computed(() => staleReason.value !== null)

const runLabel = computed(() => (stale.value ? '변경 반영' : '스크리닝 실행'))

/**
 * 실행 버튼의 툴팁.
 *
 * 미완성 조건이 조용히 빠지던 것을 여기서도 말한다 — 칩의 점선을 놓친 사용자에게 마지막
 * 기회다. 실행 자체는 막지 않는다. 완성된 조건이 하나라도 있으면 그것으로 거를 수 있고,
 * 막아 버리면 "왜 눌리지 않는지"를 다시 설명해야 한다.
 */
const incompleteCount = computed(() => draft.conditions.length - draft.completeConditions.length)

const runHint = computed(() => incompleteConditionNotice(incompleteCount.value))

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
          <!-- 넓은 폭에서는 좌측 레일이 전략 목록을 상주시킨다. 좁을 때만 Select가 그 자리를 맡는다. -->
          <ScreenPicker v-if="isBuilderNarrow" class="screener__picker" />
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
          <ResultSummary :result="result" :stale-reason="staleReason" />
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
  /*
   * 동작들이 제목 아래 자기 줄로 내려온다. `margin-inline-start: auto`(넓은 폭에서 오른쪽으로
   * 미는 것)를 여기서 풀어야 줄 전체를 쓸 수 있다.
   */
  .screener__actions {
    margin-inline-start: 0;
    flex-basis: 100%;
  }

  /*
   * IMPORTANT — 셀렉트가 남는 폭을 **마진으로** 먹어 버튼 둘을 오른쪽 끝으로 민다.
   *
   * 이 한 줄이 없으면 셋이 전부 왼쪽에 몰리고 오른쪽이 빈다 — 넓은 폭에서는 버튼이 오른쪽
   * 끝에 있는데 좁아지는 순간 왼쪽으로 옮겨가, 같은 버튼이 폭에 따라 다른 자리에 있게 된다.
   *
   * `flex-grow`가 아니라 `margin`인 이유는 셀렉트에 `max-width`(`--app-overlay-md`)가 있어서다.
   * 늘리면 그 상한에서 멈추고 남는 공간이 셀렉트와 버튼 **사이**가 아니라 버튼 **오른쪽**에
   * 남는다. 마진은 상한과 무관하게 빈 공간을 전부 가운데로 몬다.
   */
  .screener__picker {
    margin-inline-end: auto;
  }

  /*
   * 손가락으로 누르는 폭이므로 터치 타깃을 키운다.
   *
   * `size="small"`이 23px을 만드는데 WCAG 2.2의 최소 타깃(24×24)에도 못 미친다. 데스크톱은
   * 커서라 그대로 두고 여기서만 올린다 — 프리셋의 `sm` 치수를 건드리면 칩 바와 결과 표
   * 머리글의 밀도가 함께 무너진다(그쪽은 스무 개가 한 줄에 서는 자리다).
   */
  .screener__actions :deep(.p-button) {
    min-height: 2rem;
    font-size: var(--app-text-sm);
  }
}

/*
 * 모바일에서는 조건바가 따라다니지 않는다.
 *
 * 제목·동작·칩이 줄바꿈으로 쌓여 183px가 되는데, 720px 화면에서 그것이 상시로 붙어 있으면
 * 카드가 세 장밖에 안 남는다. 데스크톱에서 이 바를 sticky로 두는 이유("무엇을 실행했는가"와
 * "다시 실행"이 스크롤 밖으로 나가지 않게)는 여기서 값을 못 한다 — 모바일은 훑어보는
 * 화면이고(frontend.md), 조건을 고치려면 어차피 위로 올라온다.
 */
@media (max-width: 767px) {
  .screener__bar {
    position: static;
  }
}
</style>
