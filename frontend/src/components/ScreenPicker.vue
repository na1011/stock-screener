<script setup lang="ts">
import Select from 'primevue/select'
import { computed, onMounted, ref } from 'vue'

import { ApiError } from '@/api/http'
import type { ScreenResponse } from '@/api/types'
import AsyncPanel from '@/components/AsyncPanel.vue'
import { useApiToast } from '@/composables/useApiToast'
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
const { notifyFailure } = useApiToast()

const selected = ref<number | string | null>(null)
const loading = ref(false)

/**
 * 목록에서 화면 하나를 가리키는 키.
 *
 * 프리셋은 code, 내 화면은 id로 식별한다 — 둘을 한 Select에 담으려면 키가 하나여야 한다.
 *
 * IMPORTANT — 옵션을 만들 때와 고른 것을 되찾을 때가 **반드시 같은 식**이어야 한다. 예전에는
 * 두 곳이 각자 유도했고, 어긋나면 `find`가 실패해 사용자가 골라도 아무 일이 일어나지 않았다.
 * 그 무반응에는 오류도 로그도 없다.
 */
function screenKey(screen: ScreenResponse): number | string {
  return screen.type === 'PRESET' ? (screen.code ?? screen.name) : (screen.id ?? screen.name)
}

const options = computed(() => [
  {
    label: '프리셋',
    items: screens.presets.map((it) => ({ key: screenKey(it), name: it.name })),
  },
  {
    label: '내 화면',
    items: screens.mine.map((it) => ({ key: screenKey(it), name: it.name })),
  },
])

onMounted(() => {
  void screens.ensureLoaded()
})

async function pick(key: number | string | null): Promise<void> {
  if (key === null) {
    // show-clear로 비운 경우다. 편집 중인 조건은 그대로 둔다.
    return
  }
  const found = screens.all.find((it) => screenKey(it) === key)
  if (found === undefined || found.id === null) {
    // 여기 오면 목록과 선택이 어긋난 것이다. 조용히 넘기면 사용자는 클릭이 먹지 않는
    // 화면을 보게 되고, 무엇이 잘못됐는지 알 방법이 없다.
    notifyFailure(
      '불러오지 못했습니다',
      new ApiError(0, null, '고른 화면을 목록에서 찾지 못했습니다. 새로고침 후 다시 시도해 주세요.'),
    )
    return
  }
  loading.value = true
  try {
    draft.loadFrom(await screens.open(found.id))
  } catch (caught) {
    notifyFailure('불러오지 못했습니다', caught)
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
