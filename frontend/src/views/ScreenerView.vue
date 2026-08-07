<script setup lang="ts">
import Button from 'primevue/button'
import { computed } from 'vue'

import AsyncPanel from '@/components/AsyncPanel.vue'
import ConditionBuilder from '@/components/condition/ConditionBuilder.vue'
import ResultCards from '@/components/result/ResultCards.vue'
import ResultSummary from '@/components/result/ResultSummary.vue'
import ResultTable from '@/components/result/ResultTable.vue'
import { useBreakpoint } from '@/composables/useBreakpoint'
import { useScreenDraftStore } from '@/stores/screenDraft'
import { useScreeningStore } from '@/stores/screening'

const draft = useScreenDraftStore()
const screening = useScreeningStore()
const { isMobile } = useBreakpoint()

const result = computed(() => screening.result ?? null)

async function run(): Promise<void> {
  const request = draft.toRequest()
  if (request === null) {
    return
  }
  await screening.run(request)
}
</script>

<template>
  <main class="screener">
    <ConditionBuilder :retention="screening.retention">
      <template #actions>
        <Button
          label="스크리닝 실행"
          icon="pi pi-play"
          :loading="screening.state === 'loading'"
          :disabled="!draft.canRun"
          @click="run"
        />
      </template>
    </ConditionBuilder>

    <section class="screener__result">
      <AsyncPanel
        :state="screening.state"
        :error="screening.error"
        :retry="screening.retry"
        label="스크리닝 결과"
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
  </main>
</template>

<style scoped>
.screener {
  max-width: 90rem;
  margin: 0 auto;
  padding: 1.5rem;
  display: flex;
  flex-direction: column;
  gap: 1.5rem;
}

.screener__idle {
  padding: 2rem 0;
  text-align: center;
  color: var(--p-text-muted-color);
}

.screener__rows {
  margin-top: 1rem;
}

@media (max-width: 900px) {
  .screener {
    padding: 1rem;
  }
}
</style>
