<script setup lang="ts">
import Button from 'primevue/button'
import DatePicker from 'primevue/datepicker'
import Message from 'primevue/message'
import Select from 'primevue/select'
import SelectButton from 'primevue/selectbutton'
import Tag from 'primevue/tag'
import { computed, onMounted } from 'vue'

import { MAX_CONDITIONS } from '@/api/limits'
import AsyncPanel from '@/components/AsyncPanel.vue'
import ConditionRow from '@/components/condition/ConditionRow.vue'
import { evaluationModeOptions } from '@/lib/evaluationMode'
import { parseIsoDate, toIsoDate } from '@/lib/format'
import { screenTypeLabel } from '@/lib/screen'
import { ALL_UNIVERSES_LABEL, universeLabel } from '@/lib/universe'
import { useCatalogStore } from '@/stores/catalog'
import { useScreenDraftStore } from '@/stores/screenDraft'

/**
 * 조건 빌더.
 *
 * 편집할 수 없는 화면은 읽기 전용으로 보인다 — 서버가 수정에 403을 주므로(ADR-0017) 편집할
 * 수 있는 것처럼 그려 놓고 저장할 때 실패하게 두지 않는다. 대신 "복제해서 편집"을 준다.
 * 그것이 없으면 사용자는 프리셋 조건을 손으로 다시 옮겨 적게 된다.
 *
 * 잠금 여부의 정본은 서버의 `editable`이다(`stores/screenDraft`의 같은 이름 참조). "프리셋인가"로
 * 판단하지 않는다 — 그것은 출처이지 권한이 아니다.
 */
const props = defineProps<{
  /** 보존 기간 밖 요청이 오류로 돌아왔을 때 서버가 알려준 구간. 날짜 선택 범위를 좁힌다. */
  retention?: { from: string; to: string } | null
}>()

const catalog = useCatalogStore()
const draft = useScreenDraftStore()

const readonly = computed(() => draft.readOnly)

const universeOptions = computed(() => [
  { code: null as string | null, name: ALL_UNIVERSES_LABEL },
  ...catalog.universes.map((u) => ({ code: u.code as string | null, name: universeLabel(u) })),
])

const asOfDate = computed<Date | null>({
  get: () => parseIsoDate(draft.asOf),
  set: (value) => {
    draft.asOf = toIsoDate(value)
  },
})

const minDate = computed(() => parseIsoDate(props.retention?.from ?? null) ?? undefined)
const maxDate = computed(() => parseIsoDate(props.retention?.to ?? null) ?? new Date())

onMounted(() => {
  void catalog.ensureCatalog()
  void catalog.ensureUniverses()
})
</script>

<template>
  <section class="builder">
    <header class="builder__header">
      <div class="builder__title-row">
        <h2 class="builder__title">{{ draft.title }}</h2>
        <Tag v-if="draft.isPreset" :value="screenTypeLabel('PRESET')" severity="info" />
        <Tag v-if="readonly" value="읽기 전용" severity="secondary" />
        <Tag v-else-if="draft.dirty && draft.origin.kind !== 'custom'" value="수정됨" severity="warn" />
      </div>
      <div class="builder__header-actions">
        <Button
          v-if="readonly"
          label="복제해서 편집"
          icon="pi pi-copy"
          size="small"
          outlined
          @click="draft.forkAsCustom()"
        />
        <Button label="새 조건" icon="pi pi-plus-circle" size="small" text @click="draft.reset()" />
      </div>
    </header>

    <div class="builder__controls">
      <div class="builder__control">
        <label class="builder__label">판정 방식</label>
        <SelectButton
          :model-value="draft.evaluationMode"
          :options="evaluationModeOptions"
          option-label="label"
          option-value="value"
          :allow-empty="false"
          :disabled="readonly"
          @update:model-value="draft.setEvaluationMode($event)"
        />
      </div>

      <div class="builder__control">
        <label class="builder__label" for="universe">대상 유니버스</label>
        <AsyncPanel
          :state="catalog.universeState"
          :error="catalog.universeError"
          :retry="catalog.retryUniverses"
          label="유니버스 목록"
          :skeleton-rows="1"
        >
          <Select
            id="universe"
            v-model="draft.universeCode"
            :options="universeOptions"
            option-label="name"
            option-value="code"
            class="builder__select"
          />
        </AsyncPanel>
      </div>

      <div class="builder__control">
        <label class="builder__label" for="as-of">기준일</label>
        <DatePicker
          id="as-of"
          v-model="asOfDate"
          date-format="yy-mm-dd"
          placeholder="최신 (서버가 정함)"
          show-button-bar
          :min-date="minDate"
          :max-date="maxDate"
          class="builder__select"
        />
      </div>
    </div>

    <AsyncPanel
      :state="catalog.catalogState"
      :error="catalog.catalogError"
      :retry="catalog.retryCatalog"
      label="지표 카탈로그"
      :skeleton-rows="4"
    >
      <div class="builder__conditions">
        <ConditionRow
          v-for="(condition, index) in draft.conditions"
          :key="condition.key"
          :condition="condition"
          :index="index"
          :readonly="readonly"
        />
      </div>

      <div class="builder__footer">
        <Button
          label="조건 추가"
          icon="pi pi-plus"
          size="small"
          outlined
          :disabled="readonly || !draft.canAddCondition"
          @click="draft.addCondition()"
        />
        <span class="builder__count">{{ draft.conditions.length }} / {{ MAX_CONDITIONS }}</span>
        <slot name="actions" />
      </div>

      <Message
        v-if="draft.completeConditions.length === 0 && !readonly"
        severity="secondary"
        :closable="false"
        class="builder__notice"
      >
        지표와 비교값을 채우면 실행할 수 있습니다.
      </Message>

      <Message
        v-if="draft.evaluationMode === 'SCORING'"
        severity="secondary"
        :closable="false"
        class="builder__notice"
      >
        점수 방식은 조건에 맞지 않는 종목도 결과에 남습니다. 조건별 가중치의 합에서 얼마를
        얻었는지로 줄을 세웁니다.
      </Message>
    </AsyncPanel>
  </section>
</template>

<style scoped>
.builder {
  display: flex;
  flex-direction: column;
  gap: 1rem;
}

.builder__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 1rem;
  flex-wrap: wrap;
}

.builder__title-row {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  flex-wrap: wrap;
}

.builder__title {
  font-size: 1.125rem;
  margin: 0;
}

.builder__header-actions {
  display: flex;
  gap: 0.5rem;
}

.builder__controls {
  display: flex;
  gap: 1rem;
  flex-wrap: wrap;
}

.builder__control {
  display: flex;
  flex-direction: column;
  gap: 0.375rem;
  min-width: 14rem;
}

.builder__label {
  font-size: 0.75rem;
  color: var(--p-text-muted-color);
}

.builder__select {
  width: 100%;
}

.builder__conditions {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

.builder__footer {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  flex-wrap: wrap;
  margin-top: 0.75rem;
}

.builder__count {
  font-size: 0.8125rem;
  color: var(--p-text-muted-color);
}

.builder__notice {
  margin-top: 0.75rem;
}

@media (max-width: 900px) {
  .builder__control {
    min-width: 0;
    width: 100%;
  }
}
</style>
