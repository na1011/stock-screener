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
import AppViewHeader from '@/components/ui/AppViewHeader.vue'
import { useBreakpoint } from '@/composables/useBreakpoint'
import { disabledConditionNotice, incompleteConditionNotice } from '@/lib/conditionText'
import { screenTypeLabel, staleReasonOf } from '@/lib/screen'
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
 * 판정 규칙은 `lib/screen`의 {@link staleReasonOf}에 있다 — 이 저장소는 컴포넌트를
 * 테스트하지 않으므로(`vite.config.ts`) 뷰에 두면 검증할 길이 없다. 그 함수의 주석이
 * **왜 `dirty`가 아니라 "무엇을 보냈는가"를 보는지**를 설명한다.
 *
 * 전략이 같은지는 **서버가 준 `result.screen`**과 대조한다. "무엇을 실행했는가"를 클라이언트가
 * 따로 기억하면 그 거울이 어긋나고, 어긋나도 값이 그럴듯해 드러나지 않는다.
 */
const staleReason = computed(() =>
  staleReasonOf({
    hasResult: screening.hasResult,
    resultScreen: result.value?.screen ?? null,
    origin: draft.origin,
    matchesLastSent: draft.matchesLastSent,
    canRun: draft.canRun,
  }),
)

/**
 * 서버가 거부한 조건의 **칩**. 자리가 아니라 key로 가리킨다.
 *
 * IMPORTANT — 자리로 되짚으면 밀린다. 보내는 것은 완성된 조건뿐인데(`toRequest`) 칩 바는
 * 미완성까지 그리므로, 미완성 조건이 앞에 있으면 서버가 말한 자리와 화면의 자리가 어긋나
 * **엉뚱한 칩이 붉어진다.** 무엇을 보냈는지는 `draft.lastSent.keys`가 안다.
 */
const invalidKey = computed(() => {
  const index = screening.invalidIndex
  if (index === null) {
    return null
  }
  return draft.lastSent?.keys[index] ?? null
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

/**
 * 실행에서 빠지는 것이 있으면 여기서도 말한다. 칩 바의 안내 줄과 짝이다(ADR-0024).
 *
 * 두 문장을 개수로 합치지 않고 나란히 잇는다 — 덜 만든 것과 일부러 뺀 것은 사용자가 해야
 * 할 일이 정반대라, 합친 숫자는 무엇을 하라는 말인지 알려주지 못한다.
 */
const runHint = computed(() => {
  const notices = [
    incompleteConditionNotice(incompleteCount.value),
    disabledConditionNotice(draft.completeConditions.length - draft.runnableConditions.length),
  ].filter((it): it is string => it !== null)
  return notices.length === 0 ? null : notices.join(' ')
})

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
    <AppViewHeader>
      <template #title>
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
      </template>

      <template #actions>
        <!-- 넓은 폭에서는 좌측 레일이 전략 목록을 상주시킨다. 좁을 때만 Select가 그 자리를 맡는다. -->
        <ScreenPicker v-if="isBuilderNarrow" class="screener__picker" />
        <!-- 화면의 주 액션 둘이다. `size="small"`을 주지 않아 기본 높이(36px)로 선다. -->
        <Button
          v-tooltip.bottom="runHint"
          :label="runLabel"
          icon="pi pi-play"
          :severity="stale ? 'warn' : undefined"
          :loading="screening.state === 'loading'"
          :disabled="!draft.canRun"
          @click="run"
        />
        <Button
          label="저장"
          icon="pi pi-save"
          outlined
          :disabled="!canSave"
          @click="saveVisible = true"
        />
      </template>

      <!--
        프리셋의 설명은 **소개가 아니라 결손 고지**다(ADR-0039) — 원본 명세에서 무엇을
        치환했고 무엇을 버렸는지를 말한다. 목록에만 뜨고 불러오면 사라지던 것을 조건을 보는
        자리로 들고 온다. MUST NOT: 이 값을 저장 요청에 싣지 않는다(screenDraft의 DraftOrigin 주석).
      -->
      <p v-if="draft.presetDescription" class="screener__preset-note">
        {{ draft.presetDescription }}
      </p>

      <ConditionChipBar :retention="screening.retention" :invalid-key="invalidKey" />
    </AppViewHeader>

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
  /*
   * 결과가 적어도 조건 바 아래가 캔버스로 이어지게 한다.
   *
   * IMPORTANT — `100dvh`가 아니라 `100%`다. 넓은 폭에서 스크롤하는 것은 문서가 아니라
   * `.app-layout__body`이고(ADR-0047) 그 상자의 높이가 정확히 100dvh다. 여기에 다시 100dvh를
   * 요구하면 여유가 0이라 렌더 오류 배너 한 줄이나 서브픽셀 반올림만으로 스크롤바가 생긴다.
   * 좁은 폭에서는 부모가 그리드 행(`minmax(0,1fr)`)이라 100%도 뷰포트를 채운다.
   */
  min-height: 100%;
}

/*
 * 띠·선·sticky·폭은 전부 `AppViewHeader`가 갖는다. 여기 남은 것은 이 화면 고유의 것뿐이다.
 * 나눠 놓지 않으면 화면마다 머리 띠가 조금씩 달라지고, 그 차이가 좌측 레일의 선과 만나는
 * 자리에서 어긋남으로 드러난다.
 */
.screener__preset-note {
  margin: 0 0 0.75rem;
  color: var(--p-text-muted-color);
  font-size: var(--app-text-sm);
  line-height: 1.6;
  /* 시더가 줄바꿈으로 절을 나눈다. pre-line이 없으면 한 문단으로 뭉친다. */
  white-space: pre-line;
}

.screener__title {
  margin: 0;
  /* 레일의 브랜드와 같은 크기다. 두 낱말이 같은 높이·같은 크기로 마주 보아야 한 화면이 된다. */
  font-size: var(--app-text-xl);
  font-weight: 700;
  letter-spacing: -0.02em;
  overflow-wrap: anywhere;
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

@media (max-width: 900px) {
  /*
   * IMPORTANT — 셀렉트가 남는 폭을 **마진으로** 먹어 버튼 둘을 오른쪽 끝으로 민다.
   *
   * 이 한 줄이 없으면 셋이 전부 왼쪽에 몰리고 오른쪽이 빈다 — 넓은 폭에서는 버튼이 오른쪽
   * 끝에 있는데 좁아지는 순간 왼쪽으로 옮겨가, 같은 버튼이 폭에 따라 다른 자리에 있게 된다.
   *
   * `flex-grow`가 아니라 `margin`인 이유는 셀렉트에 `max-width`(`--app-overlay-md`)가 있어서다.
   * 늘리면 그 상한에서 멈추고 남는 공간이 셀렉트와 버튼 **사이**가 아니라 버튼 **오른쪽**에
   * 남는다. 마진은 상한과 무관하게 빈 공간을 전부 가운데로 몬다.
   *
   * 슬롯 안에 있어도 이 규칙이 닿는다 — Vue의 scoped 속성은 슬롯 **내용을 넘긴 쪽**에 붙는다.
   */
  .screener__picker {
    margin-inline-end: auto;
  }
}
</style>
