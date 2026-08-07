import { defineStore } from 'pinia'
import { computed } from 'vue'

import { api } from '@/api/endpoints'
import type { ScreenRequest, ScreenResponse } from '@/api/types'
import { useAsync } from '@/composables/useAsync'

/**
 * 저장된 화면과 프리셋.
 *
 * 서버는 프리셋과 커스텀 화면을 같은 목록으로 주고 `editable`로 구분한다(ADR-0017). 프론트가
 * `type === 'PRESET'`으로 판단해도 되지만 그러지 않는다 — 규칙이 바뀌면(예: 관리자는 프리셋을
 * 고칠 수 있게) 화면과 서버가 갈린다. 판단의 정본은 인가를 아는 쪽이다.
 *
 * 남의 화면은 서버가 목록에 담지 않으므로 여기서 걸러낼 것이 없다.
 */
export const useScreensStore = defineStore('screens', () => {
  const listRequest = useAsync((signal) => api.screens(signal))

  const all = computed<ScreenResponse[]>(() => listRequest.data.value ?? [])
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
