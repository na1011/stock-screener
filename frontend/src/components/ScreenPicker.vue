<script setup lang="ts">
import Select from 'primevue/select'
import { useToast } from 'primevue/usetoast'
import { computed, onMounted, ref } from 'vue'

import { ApiError } from '@/api/http'
import AsyncPanel from '@/components/AsyncPanel.vue'
import { useScreenDraftStore } from '@/stores/screenDraft'
import { useScreensStore } from '@/stores/screens'

/**
 * 스크리너 안에서 프리셋·저장된 화면을 바로 고른다.
 *
 * 목록 화면으로 나갔다 오게 하지 않는 이유는 spec §5가 "스크리닝 실행 → 조건 설정·저장 →
 * 개별 종목 심층 확인"을 하나로 이어지는 흐름으로 요구하기 때문이다. 화면을 바꿔야 조건을
 * 고를 수 있으면 그 흐름이 끊긴다.
 */
const screens = useScreensStore()
const draft = useScreenDraftStore()
const toast = useToast()

const selected = ref<number | string | null>(null)
const loading = ref(false)

/** 프리셋은 code, 내 화면은 id로 식별한다. 둘을 한 목록에 담으려면 키가 하나여야 한다. */
const options = computed(() => [
  {
    label: '프리셋',
    items: screens.presets.map((it) => ({ key: it.code ?? it.name, id: it.id, name: it.name })),
  },
  {
    label: '내 화면',
    items: screens.mine.map((it) => ({ key: it.id ?? it.name, id: it.id, name: it.name })),
  },
])

onMounted(() => {
  void screens.ensureLoaded()
})

async function pick(key: number | string | null): Promise<void> {
  const found = screens.all.find((it) => (it.type === 'PRESET' ? (it.code ?? it.name) : (it.id ?? it.name)) === key)
  if (!found || found.id === null) {
    return
  }
  loading.value = true
  try {
    draft.loadFrom(await screens.open(found.id))
  } catch (caught) {
    if (!(caught instanceof ApiError)) {
      throw caught
    }
    toast.add({
      severity: 'error',
      summary: '불러오지 못했습니다',
      detail: caught.userMessage,
      life: 5000,
    })
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <AsyncPanel
    :state="screens.state"
    :error="screens.error"
    :retry="screens.retry"
    label="화면 목록"
    :skeleton-rows="1"
  >
    <Select
      v-model="selected"
      :options="options"
      option-group-label="label"
      option-group-children="items"
      option-label="name"
      option-value="key"
      placeholder="프리셋·저장된 화면 불러오기"
      show-clear
      :loading="loading"
      class="screen-picker"
      @update:model-value="pick"
    />
  </AsyncPanel>
</template>

<style scoped>
.screen-picker {
  width: 20rem;
  max-width: 100%;
}
</style>
