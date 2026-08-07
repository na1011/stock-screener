<script setup lang="ts">
import AutoComplete from 'primevue/autocomplete'
import type { AutoCompleteCompleteEvent } from 'primevue/autocomplete'
import { ref } from 'vue'
import { useRouter } from 'vue-router'

import { api } from '@/api/endpoints'
import { ApiError } from '@/api/http'
import type { StockSearchResult } from '@/api/types'

/**
 * 종목 검색.
 *
 * 실패해도 조용히 빈 목록만 남긴다 — 헤더에 오류 배너를 띄우면 화면 전체가 흔들리는데,
 * 검색은 부가 기능이라 그만한 자리를 차지할 이유가 없다. 다만 **삼키지는 않는다**:
 * 콘솔에 남겨 Phase 6의 Sentry가 붙었을 때 그대로 잡히게 한다.
 */
const router = useRouter()

const selected = ref<StockSearchResult | null>(null)
const suggestions = ref<StockSearchResult[]>([])

let controller: AbortController | null = null

async function complete(event: AutoCompleteCompleteEvent): Promise<void> {
  const query = event.query.trim()
  if (query === '') {
    suggestions.value = []
    return
  }

  // 글자를 칠 때마다 요청이 나가므로 앞선 요청을 취소한다. 취소하지 않으면 늦게 도착한
  // 옛 응답이 새 결과를 덮어써, 방금 친 글자와 무관한 목록이 남는다.
  controller?.abort()
  const current = new AbortController()
  controller = current

  try {
    suggestions.value = await api.searchStocks(query, current.signal)
  } catch (caught) {
    if (current.signal.aborted) {
      return
    }
    if (!(caught instanceof ApiError)) {
      throw caught
    }
    suggestions.value = []
    console.error('종목 검색 실패', caught)
  }
}

async function go(): Promise<void> {
  const stock = selected.value
  if (stock === null) {
    return
  }
  selected.value = null
  await router.push({ name: 'stock', params: { stockId: stock.id } })
}
</script>

<template>
  <AutoComplete
    v-model="selected"
    :suggestions="suggestions"
    option-label="name"
    placeholder="종목 검색"
    :delay="250"
    complete-on-focus
    class="stock-search"
    @complete="complete"
    @option-select="go"
  >
    <template #option="{ option }: { option: StockSearchResult }">
      <div class="stock-search__option">
        <span class="stock-search__name">{{ option.name }}</span>
        <span class="stock-search__meta">{{ option.ticker }} · {{ option.marketCode }}</span>
      </div>
    </template>
    <template #empty>
      <div class="stock-search__empty">일치하는 종목이 없습니다.</div>
    </template>
  </AutoComplete>
</template>

<style scoped>
.stock-search {
  width: 14rem;
}

.stock-search :deep(input) {
  width: 100%;
}

.stock-search__option {
  display: flex;
  align-items: baseline;
  gap: 0.5rem;
}

.stock-search__meta {
  font-size: 0.75rem;
  color: var(--p-text-muted-color);
}

.stock-search__empty {
  padding: 0.5rem 0.75rem;
  font-size: 0.875rem;
  color: var(--p-text-muted-color);
}

@media (max-width: 640px) {
  .stock-search {
    width: 8rem;
  }
}
</style>
