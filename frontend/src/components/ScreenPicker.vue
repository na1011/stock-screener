<script setup lang="ts">
import Select from 'primevue/select'
import { computed, onMounted, ref } from 'vue'

import AsyncPanel from '@/components/AsyncPanel.vue'
import { screenKey, useScreenOpener } from '@/composables/useScreenOpener'
import { screenTypeLabel } from '@/lib/screen'
import { useScreensStore } from '@/stores/screens'

/**
 * 스크리너 안에서 프리셋·저장된 전략을 바로 고른다.
 *
 * 목록 화면으로 나갔다 오게 하지 않는 이유는 spec §5가 "스크리닝 실행 → 조건 설정·저장 →
 * 개별 종목 심층 확인"을 하나로 이어지는 흐름으로 요구하기 때문이다. 화면을 바꿔야 조건을
 * 고를 수 있으면 그 흐름이 끊긴다.
 *
 * IMPORTANT — 고르는 **동작**은 `useScreenOpener`가 갖는다. 여기와 `AppRail`이 각자 구현하면
 * 한쪽만 고쳐지고, 그 차이는 "어느 쪽으로 골랐느냐"에 따라 다르게 동작하는 형태로 나타난다.
 * 실제로 키 유도·실패 문구·불러오기 흐름이 두 벌로 복제돼 있었다.
 */
const screens = useScreensStore()
const { loading, openByKey } = useScreenOpener()

const selected = ref<number | string | null>(null)

const options = computed(() => [
  {
    label: screenTypeLabel('PRESET'),
    items: screens.presets.map((it) => ({ key: screenKey(it), name: it.name })),
  },
  {
    label: screenTypeLabel('CUSTOM'),
    items: screens.mine.map((it) => ({ key: screenKey(it), name: it.name })),
  },
])

onMounted(() => {
  void screens.ensureLoaded()
})

/**
 * IMPORTANT — 결과를 보고 **되돌린다.** `openByKey`를 `@update:model-value`에 바로 물리면
 * 실패하거나 사용자가 확인을 취소했을 때 **셀렉트에는 새 이름이 뜨는데 실제로 열린 것은 옛
 * 전략**이 된다. 화면이 사실과 다른 것을 말하는 상태이고, 되돌릴 단서도 없다.
 */
async function pick(key: number | string | null): Promise<void> {
  const previous = selected.value
  selected.value = key
  if (!(await openByKey(key))) {
    selected.value = previous
  }
}
</script>

<template>
  <AsyncPanel
    :state="screens.state"
    :error="screens.error"
    :retry="screens.retry"
    panel="screens"
    :skeleton-rows="1"
  >
    <!-- `v-model`이 아닌 이유: 그러면 셀렉트가 먼저 갱신되어 `pick`이 옛 값을 잡지 못한다. -->
    <Select
      :model-value="selected"
      :options="options"
      option-group-label="label"
      option-group-children="items"
      option-label="name"
      option-value="key"
      placeholder="프리셋·저장된 전략 불러오기"
      show-clear
      fluid
      :loading="loading"
      class="screen-picker"
      @update:model-value="pick"
    />
  </AsyncPanel>
</template>

<style scoped>
/* fluid가 width: 100%를 주므로 여기서는 상한만 정한다. 좁아지면 그대로 줄어든다. */
.screen-picker {
  max-width: var(--app-overlay-md);
}
</style>
