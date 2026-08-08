<script setup lang="ts">
import Button from 'primevue/button'
import Popover from 'primevue/popover'
import Select from 'primevue/select'
import Tag from 'primevue/tag'
import Textarea from 'primevue/textarea'
import { computed, onMounted, ref } from 'vue'

import { WATCHLIST_MEMO_MAX_LENGTH } from '@/api/types'
import FormField from '@/components/ui/FormField.vue'
import { useApiToast } from '@/composables/useApiToast'
import { screenTypeLabel } from '@/lib/screen'
import { ALERT_STATE_PRESENTATION, EDGE_TRIGGER_NOTICE, alertStateOf } from '@/lib/watchlist'
import { useScreensStore } from '@/stores/screens'
import { useWatchlistStore } from '@/stores/watchlist'

/**
 * 종목 상세에서 관심종목에 담고 알림 조건을 정한다.
 *
 * spec §5가 "스크리닝 실행 → 조건 설정·저장 → 관심종목 추적 → 개별 종목 심층 확인"을 하나로
 * 이어지는 흐름으로 요구한다. 담기가 별도 화면에 있으면 그 흐름이 여기서 끊긴다.
 *
 * 담긴 뒤에는 같은 자리가 **상태와 조건 편집**이 된다. 담기와 편집을 다른 자리에 두면
 * 사용자는 방금 담은 종목의 알림 조건을 어디서 정하는지 찾아다니게 된다.
 */
const props = defineProps<{ stockId: number; stockName: string }>()

const watchlist = useWatchlistStore()
const screens = useScreensStore()
const { notifyFailure, notifySuccess } = useApiToast()

const panel = ref<InstanceType<typeof Popover> | null>(null)
const busy = ref(false)
const draftScreenId = ref<number | null>(null)
const draftMemo = ref('')

const item = computed(() => watchlist.byStockId.get(props.stockId) ?? null)
const watched = computed(() => item.value !== null)
const presentation = computed(() =>
  item.value === null ? null : ALERT_STATE_PRESENTATION[alertStateOf(item.value)],
)

/**
 * 알림 조건 후보.
 *
 * 서버가 프리셋과 내 전략만 목록에 담으므로 여기서 걸러낼 것이 없다. **조건이 없는 전략은
 * 서버가 거절한다** — 목록 응답에는 조건이 없어(`conditions`가 null) 여기서 미리 가릴 수 없다.
 */
const screenOptions = computed(() => [
  { label: screenTypeLabel('PRESET'), items: screens.presets.filter((it) => it.id !== null) },
  { label: screenTypeLabel('CUSTOM'), items: screens.mine.filter((it) => it.id !== null) },
])

onMounted(() => {
  void watchlist.ensureLoaded()
  void screens.ensureLoaded()
})

function openPanel(event: Event): void {
  draftScreenId.value = item.value?.screenId ?? null
  draftMemo.value = item.value?.memo ?? ''
  panel.value?.toggle(event)
}

async function watch(): Promise<void> {
  busy.value = true
  try {
    await watchlist.add({ stockId: props.stockId })
    notifySuccess('관심종목에 담았습니다', props.stockName)
  } catch (caught) {
    // 이미 담겨 있으면 서버가 409를 준다. 조용히 넘기면 사용자는 담긴 줄 알고 목록에서
    // 찾지 못한다.
    notifyFailure('담지 못했습니다', caught)
  } finally {
    busy.value = false
  }
}

async function save(): Promise<void> {
  if (item.value === null) {
    return
  }
  busy.value = true
  try {
    await watchlist.update(item.value.id, {
      stockId: props.stockId,
      screenId: draftScreenId.value,
      memo: draftMemo.value.trim() === '' ? null : draftMemo.value,
    })
    notifySuccess('저장했습니다', props.stockName)
    panel.value?.hide()
  } catch (caught) {
    notifyFailure('저장하지 못했습니다', caught)
  } finally {
    busy.value = false
  }
}

async function unwatch(): Promise<void> {
  if (item.value === null) {
    return
  }
  busy.value = true
  try {
    await watchlist.remove(item.value.id)
    notifySuccess('관심종목에서 뺐습니다', props.stockName)
    panel.value?.hide()
  } catch (caught) {
    notifyFailure('빼지 못했습니다', caught)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div class="watch-button">
    <Button
      v-if="!watched"
      label="관심종목"
      icon="pi pi-star"
      size="small"
      outlined
      :loading="busy"
      @click="watch"
    />
    <template v-else>
      <Button
        label="관심종목"
        icon="pi pi-star-fill"
        size="small"
        :loading="busy"
        aria-haspopup="true"
        @click="openPanel"
      />
      <Tag
        v-if="presentation"
        :value="presentation.label"
        :severity="presentation.severity"
        :icon="presentation.icon"
      />
    </template>

    <Popover ref="panel">
      <div class="watch-panel">
        <p v-if="presentation" class="watch-panel__state">{{ presentation.hint }}</p>

        <FormField label="알림 조건" hint="비우면 알림 없이 추적만 합니다.">
          <Select
            v-model="draftScreenId"
            :options="screenOptions"
            option-group-label="label"
            option-group-children="items"
            option-label="name"
            option-value="id"
            placeholder="전략 선택"
            show-clear
            fluid
          />
        </FormField>

        <FormField label="메모">
          <Textarea
            v-model="draftMemo"
            rows="2"
            auto-resize
            :maxlength="WATCHLIST_MEMO_MAX_LENGTH"
            placeholder="왜 담았는지"
            fluid
          />
        </FormField>

        <p class="watch-panel__notice">{{ EDGE_TRIGGER_NOTICE }}</p>

        <div class="watch-panel__actions">
          <Button
            label="관심종목에서 빼기"
            icon="pi pi-trash"
            severity="danger"
            text
            size="small"
            :loading="busy"
            @click="unwatch"
          />
          <Button label="저장" icon="pi pi-check" size="small" :loading="busy" @click="save" />
        </div>
      </div>
    </Popover>
  </div>
</template>

<style scoped>
.watch-button {
  display: inline-flex;
  align-items: center;
  gap: 0.5rem;
}

.watch-panel {
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
  /* 팝오버가 좁은 화면을 넘지 않게 한다. 넘으면 가로 스크롤이 생긴다. */
  width: min(20rem, calc(100vw - 2 * var(--app-gutter)));
}

.watch-panel__state,
.watch-panel__notice {
  margin: 0;
  font-size: var(--app-text-xs);
  color: var(--p-text-muted-color);
}

.watch-panel__actions {
  display: flex;
  justify-content: space-between;
  gap: 0.5rem;
}
</style>
