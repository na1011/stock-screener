<script setup lang="ts">
import Button from 'primevue/button'
import Tag from 'primevue/tag'
import { computed, ref, watch } from 'vue'

import AsyncPanel from '@/components/AsyncPanel.vue'
import SaveScreenDialog from '@/components/SaveScreenDialog.vue'
import ScreenPicker from '@/components/ScreenPicker.vue'
import ConditionBuilder from '@/components/condition/ConditionBuilder.vue'
import ResultCards from '@/components/result/ResultCards.vue'
import ResultSummary from '@/components/result/ResultSummary.vue'
import ResultTable from '@/components/result/ResultTable.vue'
import { useBreakpoint } from '@/composables/useBreakpoint'
import { describeCondition } from '@/lib/conditionText'
import { screenTypeLabel } from '@/lib/screen'
import { useCatalogStore } from '@/stores/catalog'
import { useScreenDraftStore } from '@/stores/screenDraft'
import { useScreeningStore } from '@/stores/screening'

const catalog = useCatalogStore()
const draft = useScreenDraftStore()
const screening = useScreeningStore()
const { isMobile } = useBreakpoint()

const result = computed(() => screening.result ?? null)
const saveVisible = ref(false)

/**
 * 서버가 수정을 막는 화면은 저장 버튼을 주지 않는다(403이 날 것을 알면서 버튼을 주지 않는다).
 * 복제한 뒤에는 내 것이므로 저장할 수 있다. 판단의 정본은 서버의 `editable`이다.
 */
const canSave = computed(() => draft.editable && draft.completeConditions.length > 0)

/**
 * 조건 빌더를 펼쳐 두는가.
 *
 * 실행하고 나면 사용자가 보려는 것은 결과다. 데스크톱에서만 자동으로 접는 이유는 모바일에서
 * 스크롤 한 번이면 지나가는 높이라 접을 이득이 없고, 접힘 상태가 하나 더 생기면 오히려
 * 무엇을 눌러야 하는지 알기 어려워지기 때문이다.
 *
 * IMPORTANT — 접혀도 **무엇으로 걸렀는지는 남는다**(아래 `digest`). 조건이 통째로 사라지면
 * 사용자는 눈앞의 목록이 무슨 기준으로 나온 것인지 알 수 없게 된다.
 */
const builderOpen = ref(true)

watch(
  () => screening.state,
  (state) => {
    if (state === 'ready' && !isMobile.value) {
      builderOpen.value = false
    }
  },
)

// 다른 화면을 불러오거나 새로 만들면 편집하려는 것이므로 다시 펼친다.
watch(
  () => draft.origin,
  () => {
    builderOpen.value = true
  },
  { deep: true },
)

function indicatorName(code: string): string {
  return catalog.byCode.get(code)?.name ?? code
}

/**
 * 접혔을 때 조건바에 남는 한 줄.
 *
 * 편집 중인 초안이 아니라 **응답의 조건**을 쓴다. 초안은 실행 뒤에도 고쳐질 수 있어서,
 * 초안으로 적으면 눈앞의 결과와 다른 조건을 설명하게 된다. 문장 조립은 결과 화면과 같은
 * `describeCondition`을 쓴다 — 두 곳이 각자 적으면 같은 조건이 다르게 읽힌다.
 */
const digest = computed(() => {
  const conditions = result.value?.conditions ?? []
  if (conditions.length === 0) {
    return null
  }
  const head = conditions.slice(0, 2).map((it) => describeCondition(it, indicatorName))
  const rest = conditions.length - head.length
  return rest > 0 ? `${head.join(' · ')} 외 ${rest}건` : head.join(' · ')
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
    <div class="screener__bar">
      <div class="screener__bar-row">
        <Button
          type="button"
          text
          size="small"
          class="screener__toggle"
          :icon="builderOpen ? 'pi pi-chevron-down' : 'pi pi-chevron-right'"
          :aria-expanded="builderOpen"
          aria-controls="condition-builder"
          :aria-label="builderOpen ? '조건 접기' : '조건 펼치기'"
          @click="builderOpen = !builderOpen"
        />

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
          <ScreenPicker />
          <Button
            label="스크리닝 실행"
            icon="pi pi-play"
            :loading="screening.state === 'loading'"
            :disabled="!draft.canRun"
            @click="run"
          />
          <Button
            label="조건 저장"
            icon="pi pi-save"
            outlined
            :disabled="!canSave"
            @click="saveVisible = true"
          />
        </div>
      </div>

      <!-- 접혀도 무엇으로 걸렀는지는 남는다. 눈앞의 결과가 무슨 기준인지 알 수 없으면 안 된다. -->
      <p v-if="!builderOpen && digest" class="screener__digest">
        <i class="pi pi-filter" aria-hidden="true" />
        <span>{{ digest }}</span>
      </p>
    </div>

    <section v-show="builderOpen" id="condition-builder" class="screener__builder">
      <ConditionBuilder :retention="screening.retention" />
    </section>

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
          <ResultSummary :result="result" />
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
  min-height: calc(100dvh - var(--app-header-height));
}

/* 헤더 바로 아래에 붙는다. 스크롤을 내려도 "무엇을 실행했는가"와 "다시 실행"이 남는다. */
.screener__bar {
  position: sticky;
  top: var(--app-header-height);
  z-index: 15;
  padding: 0.5rem var(--app-gutter);
  border-bottom: 1px solid var(--p-content-border-color);
  background: color-mix(in srgb, var(--p-content-background) 92%, transparent);
  backdrop-filter: blur(12px);
}

.screener__bar-row {
  display: flex;
  align-items: center;
  gap: 0.5rem 0.75rem;
  flex-wrap: wrap;
}

.screener__toggle {
  flex: 0 0 auto;
}

.screener__identity {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  flex-wrap: wrap;
  flex: 1 1 12rem;
  min-width: 0;
}

.screener__title {
  margin: 0;
  font-size: 1rem;
  font-weight: 600;
  overflow-wrap: anywhere;
}

.screener__actions {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  flex-wrap: wrap;
  margin-inline-start: auto;
}

.screener__digest {
  display: flex;
  align-items: baseline;
  gap: 0.375rem;
  margin: 0.375rem 0 0 2.25rem;
  font-size: 0.8125rem;
  color: var(--p-text-muted-color);
  overflow-wrap: anywhere;
}

.screener__builder {
  padding: 1.25rem var(--app-gutter);
  border-bottom: 1px solid var(--p-content-border-color);
}

.screener__builder > * {
  max-width: var(--app-reading-width);
  margin: 0 auto;
}

.screener__result {
  flex: 1;
  min-width: 0;
  padding: 1rem var(--app-gutter) 2rem;
}

.screener__idle {
  padding: 4rem 0;
  text-align: center;
  color: var(--p-text-muted-color);
}

.screener__rows {
  margin-top: 0.75rem;
}

@media (max-width: 900px) {
  .screener__actions {
    margin-inline-start: 0;
    flex-basis: 100%;
  }

  .screener__digest {
    margin-inline-start: 0;
  }
}
</style>
