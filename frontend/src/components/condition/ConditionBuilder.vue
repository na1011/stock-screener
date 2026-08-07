<script setup lang="ts">
import Button from 'primevue/button'
import DatePicker from 'primevue/datepicker'
import Message from 'primevue/message'
import Select from 'primevue/select'
import SelectButton from 'primevue/selectbutton'
import { computed, onMounted } from 'vue'

import { MAX_CONDITIONS } from '@/api/limits'
import AsyncPanel from '@/components/AsyncPanel.vue'
import ConditionRow from '@/components/condition/ConditionRow.vue'
import { evaluationModeOptions } from '@/lib/evaluationMode'
import { parseIsoDate, toIsoDate } from '@/lib/format'
import { ALL_UNIVERSES_LABEL, universeLabel } from '@/lib/universe'
import { useCatalogStore } from '@/stores/catalog'
import { useScreenDraftStore } from '@/stores/screenDraft'

/**
 * 조건 빌더의 **본문**. 화면 이름·배지·실행·저장은 위의 조건바(`ScreenerView`)가 갖는다.
 *
 * 나눈 이유는 빌더가 접히기 때문이다. 접힌 뒤에도 "무엇을 실행했는가"와 "다시 실행"은
 * 남아 있어야 하는데, 그것이 접히는 상자 안에 있으면 함께 사라진다.
 *
 * 편집할 수 없는 화면은 읽기 전용으로 보인다 — 서버가 수정에 403을 주므로(ADR-0017) 편집할
 * 수 있는 것처럼 그려 놓고 저장할 때 실패하게 두지 않는다. 잠금 여부의 정본은 서버의
 * `editable`이다(`stores/screenDraft`의 같은 이름 참조). "프리셋인가"로 판단하지 않는다 —
 * 그것은 출처이지 권한이 아니다.
 */
const props = defineProps<{
  /** 보존 기간 밖 요청이 오류로 돌아왔을 때 서버가 알려준 구간. 날짜 선택 범위를 좁힌다. */
  retention?: { from: string; to: string } | null
}>()

const catalog = useCatalogStore()
const draft = useScreenDraftStore()

const readonly = computed(() => draft.readOnly)

/**
 * "전체"를 나타내는 화면 전용 값.
 *
 * IMPORTANT — 서버가 원하는 값은 `null`이지만 `Select`는 modelValue가 `null`이면 **고른 것이
 * 없다**고 보고 placeholder를 그린다. 그래서 `{ code: null }` 옵션은 골라도 칸이 비어 있었다.
 * 화면에서만 센티넬을 쓰고 경계(아래 setter)에서 `null`로 되돌린다.
 */
const ALL_UNIVERSES = '__ALL__'

const universeOptions = computed(() => [
  { code: ALL_UNIVERSES, name: ALL_UNIVERSES_LABEL },
  ...catalog.universes.map((u) => ({ code: u.code, name: universeLabel(u) })),
])

const universeSelection = computed<string>({
  get: () => draft.universeCode ?? ALL_UNIVERSES,
  set: (value) => {
    draft.universeCode = value === ALL_UNIVERSES ? null : value
  },
})

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
          panel="universes"
          :skeleton-rows="1"
        >
          <Select
            id="universe"
            v-model="universeSelection"
            :options="universeOptions"
            option-label="name"
            option-value="code"
            fluid
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
          show-icon
          icon-display="input"
          fluid
          :min-date="minDate"
          :max-date="maxDate"
        />
      </div>
    </div>

    <AsyncPanel
      :state="catalog.catalogState"
      :error="catalog.catalogError"
      :retry="catalog.retryCatalog"
      panel="catalog"
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
        <Button
          label="새 조건"
          icon="pi pi-plus-circle"
          size="small"
          text
          class="builder__reset"
          @click="draft.reset()"
        />
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

.builder__controls {
  display: flex;
  gap: 1rem;
  flex-wrap: wrap;
}

.builder__control {
  display: flex;
  flex-direction: column;
  gap: 0.375rem;
  /* flex-basis로 폭을 주고 하한을 0으로 내린다. min-width로 주면 좁은 폭에서 줄바꿈
     대신 넘쳐 나간다 — flex 아이템의 자동 최소 크기는 내용 기반이라 그 아래로 안 줄어든다. */
  flex: 1 1 14rem;
  min-width: 0;
}

.builder__label {
  font-size: 0.75rem;
  color: var(--p-text-muted-color);
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

.builder__reset {
  margin-inline-start: auto;
}

.builder__notice {
  margin-top: 0.75rem;
}

@media (max-width: 900px) {
  .builder__control {
    flex-basis: 100%;
  }
}
</style>
