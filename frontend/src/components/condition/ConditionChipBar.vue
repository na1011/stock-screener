<script setup lang="ts">
import Button from 'primevue/button'
import Popover from 'primevue/popover'
import { useToast } from 'primevue/usetoast'
import { computed, nextTick, onMounted, ref } from 'vue'

import { MAX_CONDITIONS } from '@/api/limits'
import AsyncPanel from '@/components/AsyncPanel.vue'
import ConditionChip from '@/components/condition/ConditionChip.vue'
import ConditionEditor from '@/components/condition/ConditionEditor.vue'
import IndicatorPicker from '@/components/condition/IndicatorPicker.vue'
import ScreenSettingsPopover from '@/components/condition/ScreenSettingsPopover.vue'
import { useFreshDraft } from '@/composables/useFreshDraft'
import { incompleteConditionNotice } from '@/lib/conditionText'
import { evaluationModeLabels } from '@/lib/evaluationMode'
import { ALL_UNIVERSES_LABEL } from '@/lib/universe'
import { useCatalogStore } from '@/stores/catalog'
import { FRESH_DRAFT_TITLE, useScreenDraftStore } from '@/stores/screenDraft'

/**
 * 조건 전부를 한 줄(넘치면 두 줄)로.
 *
 * 예전 `ConditionBuilder` + `ConditionRow`를 대체한다. 그쪽은 조건 하나가 세로 90px에 컨트롤
 * 여섯 개였고, 조건 다섯 개면 화면 절반이 편집기였다. 여기서는 **읽기가 칩, 고치기가 팝오버**다.
 *
 * 이 컴포넌트가 해소하는 배려 문제 넷(전부 예전 코드에 실재했다):
 *   1. 미완성 조건이 조용히 버려졌다 → 칩이 점선 + "값 필요", 실행 버튼 툴팁이 개수를 말한다
 *   2. 삭제에 되돌리기가 없었다 → 토스트의 `실행취소`
 *   3. "새 조건"이 확인 없이 전부 날렸다 → 조건이 둘 이상이면 확인을 받는다
 *   4. 서버가 준 `conditionIndex`를 쓰지 않았다 → 그 칩을 붉게 칠하고 팝오버를 연다
 */
const props = defineProps<{
  retention?: { from: string; to: string } | null
  /**
   * 서버가 거부한 조건의 `key`.
   *
   * 자리(`conditionIndex`)가 아니라 key인 이유는 **보내는 것과 그리는 것이 다르기 때문**이다 —
   * 미완성 조건은 요청에서 빠지는데 칩 바는 그것도 그린다. 자리로 받으면 미완성 조건이 앞에
   * 있을 때 엉뚱한 칩이 붉어진다. 되짚기는 `ScreenerView`가 `draft.lastSent.keys`로 한다.
   */
  invalidKey?: number | null
}>()

const catalog = useCatalogStore()
const draft = useScreenDraftStore()
const { startFresh } = useFreshDraft()
const toast = useToast()

const editor = ref<InstanceType<typeof Popover> | null>(null)
const picker = ref<InstanceType<typeof Popover> | null>(null)
const pickerBody = ref<InstanceType<typeof IndicatorPicker> | null>(null)
const settings = ref<InstanceType<typeof Popover> | null>(null)

/** 지금 팝오버로 고치는 중인 조건의 key. 칩의 눌린 상태가 이 값을 본다. */
const editingKey = ref<number | null>(null)

/** 지표 목록이 닫히면 열어 줄 조건. `openPendingEditor`의 주석에 이유가 있다. */
const pendingEditKey = ref<number | null>(null)

const editing = computed(
  () => draft.conditions.find((it) => it.key === editingKey.value) ?? null,
)

const readonly = computed(() => draft.readOnly)

const incompleteCount = computed(
  () => draft.conditions.length - draft.completeConditions.length,
)

/** 실행 버튼 툴팁과 짝이다(ADR-0024). 문구의 정본은 `lib/conditionText`에 있다. */
const incompleteNotice = computed(() => incompleteConditionNotice(incompleteCount.value))

/**
 * 설정 칩의 라벨. 접어 둔 것이 무엇으로 설정되어 있는지 여기서 답한다.
 *
 * 접기만 하고 요약을 남기지 않으면 사용자는 "이 결과가 코스피200만인지 전체인지"를 팝오버를
 * 열어야 알 수 있다. 그러면 접은 이득이 사라진다.
 */
const settingsLabel = computed(() => {
  const mode = evaluationModeLabels(draft.evaluationMode).short
  const universe =
    draft.universeCode === null
      ? ALL_UNIVERSES_LABEL
      : (catalog.universes.find((it) => it.code === draft.universeCode)?.name ?? draft.universeCode)
  return draft.asOf === null ? `${universe} · ${mode}` : `${universe} · ${mode} · ${draft.asOf}`
})

onMounted(() => {
  void catalog.ensureCatalog()
})

/**
 * 칩을 눌렀을 때.
 *
 * IMPORTANT — 열려 있는 팝오버를 닫고 곧바로 다시 여는 것은 **하지 않는다.** Popover 하나를
 * 여러 칩이 돌려 쓰는데, `hide()` 직후 같은 틱에 `show()`를 부르면 나가는 트랜지션이 컨테이너를
 * 걷어내는 중이라 `offsetHeight`를 null에서 읽고 죽는다(실측). 대신 "다음에 열 칩"을 적어 두고
 * 실제로 닫혔다는 신호(`@hide`)를 기다린다.
 */
// 읽기 전용 화면에서도 연다 — 조건의 뜻과 지표 해설은 볼 수 있어야 한다. 편집 컨트롤만 잠긴다.
function openEditor(event: Event, key: number): void {
  /**
   * IMPORTANT — 기준 요소를 **지금** 붙잡는다. 브라우저는 디스패치가 끝나면
   * `event.currentTarget`을 null로 되돌리므로, `nextTick` 뒤에 Popover가 그것을 읽으면
   * null에서 `offsetHeight`를 읽고 죽는다. `.click()`으로 만든 합성 이벤트에서 특히 그렇다.
   */
  const anchor = (event.currentTarget ?? event.target) as HTMLElement | null

  if (editingKey.value === key) {
    closeEditor()
    return
  }
  if (editingKey.value !== null) {
    pendingEditKey.value = key
    editor.value?.hide()
    return
  }
  editingKey.value = key
  void nextTick(() => {
    if (anchor !== null) {
      editor.value?.show(event, anchor)
    }
  })
}

function closeEditor(): void {
  editor.value?.hide()
}

function openPicker(event: Event): void {
  picker.value?.show(event)
  void nextTick(() => void pickerBody.value?.focusSearch())
}

/**
 * 지표를 고르면 조건이 생기고 **곧바로 편집기가 열린다.**
 *
 * 고른 다음 다시 칩을 눌러야 값을 넣을 수 있으면 클릭이 하나 더 늘고, 그 사이에 "값 필요"
 * 상태의 칩만 남아 무엇을 해야 하는지 모르게 된다.
 */
/**
 * 지표를 고르면 조건이 생기고 목록이 닫힌다. 편집기는 **목록이 완전히 닫힌 뒤에** 연다.
 *
 * IMPORTANT — 둘을 같은 틱에 처리하면 편집기가 열리자마자 사라진다. PrimeVue의 Popover는
 * 바깥 클릭을 document에서 듣는데, 목록을 닫은 그 클릭이 아직 전파 중이라 새로 열린 편집기가
 * 자기 "바깥 클릭"으로 받는다. `nextTick`으로는 부족했고(실제로 그렇게 안 열렸다), 목록이
 * 스스로 닫혔다고 알리는 `@hide`가 유일하게 확실한 시점이다.
 */
function pickIndicator(code: string): void {
  draft.addCondition(code)
  const added = draft.conditions[draft.conditions.length - 1]
  pendingEditKey.value = added?.key ?? null
  picker.value?.hide()
}

function onEditorHidden(): void {
  editingKey.value = null
  void openPendingEditor()
}

/**
 * PrimeVue 오버레이가 완전히 걷힐 때까지.
 *
 * `@hide`는 트랜지션이 **시작될 때** 나오고, 바깥 클릭을 듣는 document 리스너는 그 뒤에
 * 걷힌다. 그 사이에 새 팝오버를 열면 방금 그 클릭이 새 팝오버의 "바깥 클릭"으로 잡혀 곧바로
 * 닫힌다. nextTick으로는 넘길 수 없는 구간이라(실측) 트랜지션보다 넉넉한 값을 둔다.
 */
const OVERLAY_TEARDOWN_MS = 160

async function openPendingEditor(): Promise<void> {
  const key = pendingEditKey.value
  pendingEditKey.value = null
  if (key === null) {
    return
  }
  await nextTick()
  // 칩을 **실제로 누른다.** 가짜 이벤트로 `show()`를 부르면 위치 기준이 없다(브라우저가
  // 디스패치 후 currentTarget을 비운다). 사용자가 누른 것과 같은 경로가 확실하다.
  setTimeout(() => {
    document.querySelector<HTMLElement>(`[data-chip-key="${key}"]`)?.click()
  }, OVERLAY_TEARDOWN_MS)
}

/**
 * 삭제하고 되돌릴 기회를 남긴다.
 *
 * 조건 하나에 ConfirmDialog는 과하다 — 흔한 동작이고, 매번 막으면 확인 자체가 무의미해진다.
 * 대신 되돌릴 수 있게 한다.
 */
function removeEditing(): void {
  const target = editing.value
  if (target === null) {
    return
  }
  closeEditor()
  draft.removeCondition(target.key)
  toast.add({
    severity: 'secondary',
    summary: '조건을 삭제했습니다',
    detail: '실행취소를 누르면 되돌아옵니다.',
    group: 'undo-condition',
    life: 6000,
  })
}

/*
 * 전부 지우는 것은 되돌리기로 감당할 수 없다. 확인 절차와 이동은 `useFreshDraft`가 갖는다 —
 * 레일도 같은 버튼을 갖는데, 여기는 확인만 있고 이동이 없었으며 레일은 그 반대였다.
 */
</script>

<template>
  <AsyncPanel
    :state="catalog.catalogState"
    :error="catalog.catalogError"
    :retry="catalog.retryCatalog"
    panel="catalog"
    :skeleton-rows="1"
  >
    <div class="bar">
      <div class="bar__chips">
        <ConditionChip
          v-for="condition in draft.conditions"
          :key="condition.key"
          :data-chip-key="condition.key"
          :condition="condition"
          :active="editingKey === condition.key"
          :invalid="props.invalidKey === condition.key"
          :readonly="readonly"
          @click="openEditor($event, condition.key)"
        />

        <Button
          v-if="!readonly"
          type="button"
          size="small"
          text
          icon="pi pi-plus"
          label="조건"
          class="bar__add"
          :disabled="!draft.canAddCondition"
          :aria-label="`조건 추가 (${draft.conditions.length}/${MAX_CONDITIONS})`"
          @click="openPicker"
        />
      </div>

      <div class="bar__tail">
        <!-- 설정은 조건이 아니다. 접어 두되 무엇으로 설정됐는지는 라벨이 말한다. -->
        <button type="button" class="bar__settings" @click="settings?.toggle($event)">
          <i class="pi pi-sliders-h" aria-hidden="true" />
          <span class="bar__settings-label">{{ settingsLabel }}</span>
        </button>

        <Button
          v-if="!readonly"
          type="button"
          size="small"
          text
          icon="pi pi-eraser"
          :label="FRESH_DRAFT_TITLE"
          @click="startFresh()"
        />
      </div>
    </div>

    <!-- 미완성이 있으면 말로 알린다. 조용히 버리지 않는 것이 이 줄의 존재 이유다. -->
    <p v-if="incompleteNotice && !readonly" class="bar__incomplete">
      <i class="pi pi-info-circle" aria-hidden="true" />
      {{ incompleteNotice }}
    </p>

    <!-- 닫힌 뒤에야 다음 칩을 연다. 이유는 `openEditor` 주석에 있다. -->
    <Popover ref="editor" @hide="onEditorHidden">
      <ConditionEditor
        v-if="editing"
        :key="editing.key"
        :condition="editing"
        :readonly="readonly"
        @remove="removeEditing"
        @done="closeEditor"
      />
    </Popover>

    <Popover ref="picker" @hide="openPendingEditor">
      <IndicatorPicker ref="pickerBody" @pick="pickIndicator" />
    </Popover>

    <Popover ref="settings">
      <ScreenSettingsPopover :retention="props.retention" />
    </Popover>
  </AsyncPanel>
</template>

<style scoped>
.bar {
  display: flex;
  align-items: flex-start;
  gap: 0.5rem;
}

/* 조건이 스무 개까지 늘어도 줄바꿈으로 흡수한다. 세로로 쌓이지 않는 것이 요점이다. */
.bar__chips {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 0.25rem;
  flex: 1 1 auto;
  min-width: 0;
}

.bar__add {
  flex: 0 0 auto;
}

.bar__tail {
  display: flex;
  align-items: center;
  gap: 0.25rem;
  flex: 0 0 auto;
}

.bar__settings {
  display: inline-flex;
  align-items: center;
  gap: 0.375rem;
  max-width: 16rem;
  min-height: 1.75rem;
  padding: 0 0.5rem;
  border: 1px solid transparent;
  border-radius: var(--p-border-radius-md);
  background: transparent;
  color: var(--p-text-muted-color);
  font: inherit;
  font-size: var(--app-text-2xs);
  cursor: pointer;
}

.bar__settings:hover {
  border-color: var(--p-content-border-color);
  color: var(--p-text-color);
}

.bar__settings-label {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.bar__incomplete {
  display: flex;
  align-items: center;
  gap: 0.375rem;
  margin: 0.375rem 0 0;
  font-size: var(--app-text-2xs);
  color: var(--p-amber-300);
}

/* 좁아지면 설정·새 조건이 아래로 내려간다. 조건 자체가 먼저 자리를 갖는다. */
@media (max-width: 900px) {
  .bar {
    flex-wrap: wrap;
  }

  .bar__tail {
    width: 100%;
    justify-content: space-between;
  }
}
</style>
