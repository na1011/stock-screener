<script setup lang="ts">
import DatePicker from 'primevue/datepicker'
import Select from 'primevue/select'
import SelectButton from 'primevue/selectbutton'
import { computed, onMounted } from 'vue'

import type { EvaluationMode } from '@/api/types'
import AsyncPanel from '@/components/AsyncPanel.vue'
import { evaluationModeOptions } from '@/lib/evaluationMode'
import { parseIsoDate, toIsoDate } from '@/lib/format'
import { ALL_UNIVERSES_LABEL, universeLabel } from '@/lib/universe'
import { useCatalogStore } from '@/stores/catalog'
import { useScreenDraftStore } from '@/stores/screenDraft'

/**
 * 판정 방식·대상 유니버스·기준일.
 *
 * 조건이 아니라 **실행 설정**이다. 예전에는 조건과 같은 시각적 무게로 한 줄을 통째로
 * 차지했는데, 한 번 정하고 잘 바꾸지 않는 값 셋이 매번 화면 폭을 다 먹었다. 접어 두고
 * 지금 설정이 무엇인지는 이것을 여는 칩의 라벨이 답한다.
 */
const props = defineProps<{
  /** 보존 기간 밖 요청이 오류로 돌아왔을 때 서버가 알려준 구간. 날짜 선택 범위를 좁힌다. */
  retention?: { from: string; to: string } | null
}>()

const catalog = useCatalogStore()
const draft = useScreenDraftStore()

onMounted(() => {
  void catalog.ensureUniverses()
})

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
</script>

<template>
  <div class="settings">
    <div class="settings__field">
      <span class="settings__label">판정 방식</span>
      <SelectButton
        :model-value="draft.evaluationMode"
        :options="evaluationModeOptions"
        option-label="label"
        option-value="value"
        :allow-empty="false"
        size="small"
        @update:model-value="(mode: EvaluationMode) => draft.setEvaluationMode(mode)"
      />
    </div>

    <div class="settings__field">
      <span class="settings__label">대상 유니버스</span>
      <AsyncPanel
        :state="catalog.universeState"
        :error="catalog.universeError"
        :retry="catalog.retryUniverses"
        panel="universes"
        :skeleton-rows="1"
      >
        <Select
          v-model="universeSelection"
          :options="universeOptions"
          option-label="name"
          option-value="code"
          size="small"
          fluid
        />
      </AsyncPanel>
    </div>

    <div class="settings__field">
      <span class="settings__label">기준일</span>
      <DatePicker
        v-model="asOfDate"
        date-format="yy-mm-dd"
        placeholder="최신 (서버가 정함)"
        show-icon
        show-button-bar
        :min-date="minDate"
        :max-date="maxDate"
        size="small"
        fluid
      />
      <!-- 보존 구간은 서버가 오류로 알려준 것이다. 있을 때만 적는다 — 지어내지 않는다. -->
      <span v-if="props.retention" class="settings__hint">
        {{ props.retention.from }} ~ {{ props.retention.to }} 사이만 조회할 수 있습니다.
      </span>
    </div>
  </div>
</template>

<style scoped>
.settings {
  display: flex;
  flex-direction: column;
  gap: 0.625rem;
  width: 16rem;
  max-width: 100%;
}

.settings__field {
  display: flex;
  flex-direction: column;
  gap: 0.1875rem;
}

.settings__label {
  font-size: var(--app-text-2xs);
  color: var(--p-text-muted-color);
}

.settings__hint {
  font-size: var(--app-text-2xs);
  color: var(--p-surface-500);
}
</style>
