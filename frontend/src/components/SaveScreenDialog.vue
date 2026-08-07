<script setup lang="ts">
import Button from 'primevue/button'
import Dialog from 'primevue/dialog'
import InputText from 'primevue/inputtext'
import Message from 'primevue/message'
import SelectButton from 'primevue/selectbutton'
import Textarea from 'primevue/textarea'
import { useToast } from 'primevue/usetoast'
import { computed, ref, watch } from 'vue'

import { ApiError, ProblemType } from '@/api/http'
import { SCREEN_DESCRIPTION_MAX_LENGTH, SCREEN_NAME_MAX_LENGTH } from '@/api/limits'
import type { ScreenRequest } from '@/api/types'
import { BREAKPOINTS } from '@/lib/breakpoints'
import { evaluationModeLabels } from '@/lib/evaluationMode'
import { useScreenDraftStore } from '@/stores/screenDraft'
import { useScreensStore } from '@/stores/screens'

/**
 * 조건 저장.
 *
 * 저장이 없으면 조건 빌더가 일회용 도구가 되고, 사용자는 매번 조건을 다시 만든다 — 그러면
 * spec §3의 커스텀 스크리닝이 실질적으로 성립하지 않는다(design §5 Phase 5).
 *
 * 프리셋은 여기 오지 않는다. 서버가 프리셋 수정에 403을 주므로(ADR-0017) 편집할 수 있는
 * 것처럼 그려 두고 저장할 때 실패하게 만들지 않는다 — 빌더의 "복제해서 편집"이 앞단이다.
 */
const visible = defineModel<boolean>('visible', { required: true })

const draft = useScreenDraftStore()
const screens = useScreensStore()
const toast = useToast()

const name = ref('')
const description = ref('')
const saving = ref(false)
const nameError = ref<string | null>(null)
const otherError = ref<string | null>(null)

/** 저장된 내 화면을 편집 중일 때만 덮어쓰기를 고를 수 있다. */
const existingId = computed(() => (draft.origin.kind === 'screen' ? draft.origin.id : null))
const mode = ref<'create' | 'update'>('create')

const modeOptions = computed(() => [
  { label: '새 화면으로 저장', value: 'create' },
  { label: '이 화면 덮어쓰기', value: 'update' },
])

watch(visible, (open) => {
  if (!open) {
    return
  }
  nameError.value = null
  otherError.value = null
  mode.value = existingId.value === null ? 'create' : 'update'
  name.value = draft.origin.kind === 'custom' ? '' : draft.origin.name
  const source = existingId.value === null ? null : screens.all.find((it) => it.id === existingId.value)
  description.value = source?.description ?? ''
})

function buildRequest(): ScreenRequest {
  return {
    name: name.value.trim(),
    description: description.value.trim() === '' ? null : description.value.trim(),
    evaluationMode: draft.evaluationMode,
    universeCode: draft.universeCode,
    conditions: draft.toConditionGroup(),
  }
}

async function save(): Promise<void> {
  nameError.value = null
  otherError.value = null

  if (name.value.trim() === '') {
    nameError.value = '화면 이름이 필요합니다.'
    return
  }
  if (draft.completeConditions.length === 0) {
    otherError.value = '완성된 조건이 하나도 없습니다.'
    return
  }

  saving.value = true
  try {
    const id = existingId.value
    const saved =
      mode.value === 'update' && id !== null
        ? await screens.update(id, buildRequest())
        : await screens.create(buildRequest())

    // 저장 결과를 다시 불러와 dirty를 내린다. 그래야 다음 실행이 screenId로 나가고,
    // 서버가 저장된 정의를 그대로 실행한다.
    draft.loadFrom(saved)
    toast.add({ severity: 'success', summary: '저장했습니다', detail: saved.name, life: 3000 })
    visible.value = false
  } catch (caught) {
    if (!(caught instanceof ApiError)) {
      throw caught
    }
    if (caught.is(ProblemType.DUPLICATE_SCREEN_NAME)) {
      nameError.value = '같은 이름의 화면이 이미 있습니다.'
      return
    }
    otherError.value = caught.userMessage
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <Dialog
    v-model:visible="visible"
    modal
    header="조건 저장"
    :style="{ width: '28rem' }"
    :breakpoints="{ [`${BREAKPOINTS.COMPACT}px`]: '95vw' }"
  >
    <div class="save-dialog">
      <SelectButton
        v-if="existingId !== null"
        v-model="mode"
        :options="modeOptions"
        option-label="label"
        option-value="value"
        :allow-empty="false"
        size="small"
      />

      <div class="save-dialog__field">
        <label for="screen-name">화면 이름</label>
        <InputText
          id="screen-name"
          v-model="name"
          :maxlength="SCREEN_NAME_MAX_LENGTH"
          :invalid="nameError !== null"
        />
        <small v-if="nameError" class="save-dialog__error">{{ nameError }}</small>
      </div>

      <div class="save-dialog__field">
        <label for="screen-description">설명 (선택)</label>
        <Textarea
          id="screen-description"
          v-model="description"
          rows="3"
          :maxlength="SCREEN_DESCRIPTION_MAX_LENGTH"
          auto-resize
        />
      </div>

      <p class="save-dialog__summary">
        조건 {{ draft.completeConditions.length }}개 ·
        {{ evaluationModeLabels(draft.evaluationMode).noun }}
      </p>

      <Message v-if="otherError" severity="error" :closable="false">{{ otherError }}</Message>
    </div>

    <template #footer>
      <Button label="취소" text :disabled="saving" @click="visible = false" />
      <Button label="저장" icon="pi pi-save" :loading="saving" @click="save" />
    </template>
  </Dialog>
</template>

<style scoped>
.save-dialog {
  display: flex;
  flex-direction: column;
  gap: 1rem;
}

.save-dialog__field {
  display: flex;
  flex-direction: column;
  gap: 0.375rem;
}

.save-dialog__field label {
  font-size: 0.8125rem;
  color: var(--p-text-muted-color);
}

.save-dialog__error {
  /* 다크에서는 500이 어두워 읽히지 않는다. StockSearch의 실패 문구와 같은 단계를 쓴다. */
  color: var(--p-red-400);
}

.save-dialog__summary {
  margin: 0;
  font-size: 0.8125rem;
  color: var(--p-text-muted-color);
}
</style>
