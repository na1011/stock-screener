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
 * 실패해도 헤더에 오류 배너를 띄우지 않는다 — 화면 전체가 흔들리는데 검색은 부가 기능이라
 * 그만한 자리를 차지할 이유가 없다. 콘솔에도 남겨 Phase 6의 Sentry가 붙었을 때 잡히게 한다.
 *
 * IMPORTANT — 그렇다고 **빈 목록만 남기지도 않는다.** 빈 드롭다운은 "일치하는 종목이 없다"와
 * 구별되지 않아서, 사용자는 자기가 친 이름이 없는 종목이라고 믿고 검색을 그만둔다. 실패한
 * 것과 결과가 없는 것은 문구로 갈라야 한다(frontend.md의 재시도 요구가 겨냥하는 지점이다).
 */
const router = useRouter()

const selected = ref<StockSearchResult | null>(null)
const suggestions = ref<StockSearchResult[]>([])
/** 마지막 조회가 실패했는가. 빈 결과와 실패를 문구로 가르는 데만 쓴다. */
const failed = ref(false)

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
    failed.value = false
  } catch (caught) {
    if (current.signal.aborted) {
      return
    }
    if (!(caught instanceof ApiError)) {
      throw caught
    }
    suggestions.value = []
    failed.value = true
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
      <div v-if="failed" class="stock-search__empty stock-search__empty--failed">
        검색에 실패했습니다. 잠시 뒤 다시 입력해 주세요.
      </div>
      <div v-else class="stock-search__empty">일치하는 종목이 없습니다.</div>
    </template>
  </AutoComplete>
</template>

<style scoped>
/*
 * 폭은 **호출부가 정한다.** 여기서 고정하면 헤더가 좁아질 때 줄일 수 없다.
 *
 * 안쪽 input을 래퍼 폭에 맞추는 규칙은 `main.css`의 전역 규칙으로 옮겼다 — 여기만 겪던
 * 문제가 아니라 PrimeVue 폼 래퍼 전반의 문제였다(래퍼가 inline-flex인데 input의
 * `width: 1%`가 fluid 클래스에만 붙는다).
 */
.stock-search {
  width: 100%;
}

.stock-search__option {
  display: flex;
  align-items: baseline;
  gap: 0.5rem;
}

.stock-search__meta {
  font-size: var(--app-text-xs);
  color: var(--p-text-muted-color);
}

.stock-search__empty {
  padding: 0.5rem 0.75rem;
  font-size: var(--app-text-md);
  color: var(--p-text-muted-color);
}

/* 실패는 "결과 없음"과 색으로도 갈린다. 문구만으로는 훑어보는 눈에 같아 보인다.
   다크에서는 500이 어두워 읽히지 않으므로 400을 쓴다. */
.stock-search__empty--failed {
  color: var(--p-red-400);
}
</style>
