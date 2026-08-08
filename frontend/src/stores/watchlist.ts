import { defineStore } from 'pinia'
import { computed } from 'vue'

import { api } from '@/api/endpoints'
import type { WatchlistItem, WatchlistRequest } from '@/api/types'
import { useAsync } from '@/composables/useAsync'

/**
 * 관심종목.
 *
 * 목록을 한 곳에 두는 이유는 **두 화면이 같은 사실을 물어야 하기 때문**이다 — 워치리스트
 * 화면과 종목 상세의 담기 버튼이다. 각자 조회하면 상세에서 담은 종목이 목록에 없거나,
 * 목록에서 해제한 종목의 버튼이 여전히 "담김"으로 남는다.
 *
 * `stores/screens`와 같은 모양이다. 쓰기 뒤에 목록을 다시 읽어 두 화면이 함께 따라온다 —
 * 응답 하나로 로컬 배열을 손보면 목록의 정렬·조인 결과(종목명·전략명)를 프론트가 다시
 * 만들어야 하고, 그 재구성이 서버와 어긋나기 시작한다.
 */
export const useWatchlistStore = defineStore('watchlist', () => {
  const listRequest = useAsync((signal) => api.watchlist(signal))

  const items = computed<WatchlistItem[]>(() => listRequest.data.value ?? [])

  /** 종목 id → 항목. 담기 버튼이 "이미 담았는가"를 묻는 자리다. */
  const byStockId = computed(() => new Map(items.value.map((it) => [it.stockId, it])))

  /** 마지막 판정에서 조건을 충족한 항목 수. 레일 배지가 쓴다. */
  const satisfiedCount = computed(() => items.value.filter((it) => it.satisfied === true).length)

  async function ensureLoaded(): Promise<void> {
    if (listRequest.state.value === 'idle' || listRequest.state.value === 'error') {
      await listRequest.run()
    }
  }

  async function reload(): Promise<void> {
    await listRequest.run()
  }

  async function add(body: WatchlistRequest): Promise<WatchlistItem> {
    const created = await api.addToWatchlist(body)
    await reload()
    return created
  }

  async function update(id: number, body: WatchlistRequest): Promise<WatchlistItem> {
    const updated = await api.updateWatchlistItem(id, body)
    await reload()
    return updated
  }

  async function remove(id: number): Promise<void> {
    await api.removeFromWatchlist(id)
    await reload()
  }

  return {
    state: listRequest.state,
    error: listRequest.error,
    retry: listRequest.retry,
    items,
    byStockId,
    satisfiedCount,
    ensureLoaded,
    reload,
    add,
    update,
    remove,
  }
})
