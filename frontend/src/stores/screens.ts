import { defineStore } from 'pinia'
import { computed } from 'vue'

import { api } from '@/api/endpoints'
import type { ScreenRequest, ScreenResponse } from '@/api/types'
import { useAsync } from '@/composables/useAsync'

/**
 * 저장된 화면과 프리셋.
 *
 * 서버는 둘을 같은 목록으로 주고 `type`(출처)과 `editable`(권한)을 함께 싣는다(ADR-0017).
 * 둘은 다른 질문이고 이 파일이 쓰는 것은 **출처**다 — 아래 `presets`/`mine`은 목록을 두 섹션으로
 * 나누기 위한 것이지 무엇을 고칠 수 있는지를 정하는 것이 아니다.
 *
 * IMPORTANT — 편집 가능 여부를 여기서 유도하지 않는다. 그 판단은 서버의 `editable`이고
 * `stores/screenDraft`가 그것을 받아 든다. 규칙이 바뀌면(예: 관리자는 프리셋을 고칠 수 있게)
 * 서버만 고치면 되도록 만들어 둔 값이라, 프론트가 `type`으로 다시 유도하면 그 설계가
 * 무의미해진다.
 *
 * 남의 화면은 서버가 목록에 담지 않으므로 여기서 걸러낼 것이 없다.
 */
export const useScreensStore = defineStore('screens', () => {
  const listRequest = useAsync((signal) => api.screens(signal))

  const all = computed<ScreenResponse[]>(() => listRequest.data.value ?? [])
  // 출처로 나눈다. 목록의 두 섹션이 "미리 정의된 것"과 "내가 만든 것"이기 때문이다.
  const presets = computed(() => all.value.filter((it) => it.type === 'PRESET'))
  const mine = computed(() => all.value.filter((it) => it.type === 'CUSTOM'))

  async function ensureLoaded(): Promise<void> {
    if (listRequest.state.value === 'idle' || listRequest.state.value === 'error') {
      await listRequest.run()
    }
  }

  async function reload(): Promise<void> {
    await listRequest.run()
  }

  /** 조건까지 포함된 정의. 목록 응답은 `conditions`가 null이라 실행·편집에 쓸 수 없다. */
  async function open(id: number): Promise<ScreenResponse> {
    return api.screen(id)
  }

  async function create(body: ScreenRequest): Promise<ScreenResponse> {
    const created = await api.createScreen(body)
    await reload()
    return created
  }

  async function update(id: number, body: ScreenRequest): Promise<ScreenResponse> {
    const updated = await api.updateScreen(id, body)
    await reload()
    return updated
  }

  async function remove(id: number): Promise<void> {
    await api.deleteScreen(id)
    await reload()
  }

  return {
    state: listRequest.state,
    error: listRequest.error,
    retry: listRequest.retry,
    all,
    presets,
    mine,
    ensureLoaded,
    reload,
    open,
    create,
    update,
    remove,
  }
})
