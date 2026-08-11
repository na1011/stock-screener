<script setup lang="ts">
import Button from 'primevue/button'
import Tag from 'primevue/tag'
import { useConfirm } from 'primevue/useconfirm'
import { computed, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'

import type { WatchlistItem } from '@/api/types'
import AsyncPanel from '@/components/AsyncPanel.vue'
import AppViewHeader from '@/components/ui/AppViewHeader.vue'
import SectionHeading from '@/components/ui/SectionHeading.vue'
import { useApiToast } from '@/composables/useApiToast'
import { useBreakpoint } from '@/composables/useBreakpoint'
import { formatDate } from '@/lib/format'
import { ALERT_STATE_PRESENTATION, EDGE_TRIGGER_NOTICE, alertStateOf } from '@/lib/watchlist'
import { useWatchlistStore } from '@/stores/watchlist'

/**
 * 관심종목 목록.
 *
 * 데스크톱과 모바일이 같은 화면의 축소가 아니다(`frontend.md`). 넓은 화면은 표로 밀도를
 * 올리고, 좁은 화면은 카드로 한 종목씩 훑는다 — 결과 표(`ResultTable`/`ResultCards`)가
 * 같은 판단을 하고 경계값도 같은 곳(`lib/breakpoints`)에서 온다.
 *
 * 알림 조건 편집은 여기 없다. 종목 상세의 담기 버튼이 그 자리이고, 두 곳에 두면 같은 폼이
 * 두 벌이 된다. 여기서는 상태를 보고 해제하는 것까지다.
 */
const watchlist = useWatchlistStore()
const confirm = useConfirm()
const { notifyFailure, notifySuccess } = useApiToast()
const { isMobile } = useBreakpoint()

const busyId = ref<number | null>(null)

onMounted(() => {
  void watchlist.ensureLoaded()
})

const rows = computed(() =>
  watchlist.items.map((item) => ({
    item,
    presentation: ALERT_STATE_PRESENTATION[alertStateOf(item)],
  })),
)

function confirmRemove(item: WatchlistItem): void {
  confirm.require({
    header: '관심종목 해제',
    message: `"${item.stockName}"을(를) 관심종목에서 뺍니다.`,
    icon: 'pi pi-exclamation-triangle',
    acceptLabel: '빼기',
    rejectLabel: '취소',
    acceptProps: { severity: 'danger' },
    accept: () => void remove(item),
  })
}

async function remove(item: WatchlistItem): Promise<void> {
  busyId.value = item.id
  try {
    await watchlist.remove(item.id)
    notifySuccess('관심종목에서 뺐습니다', item.stockName)
  } catch (caught) {
    notifyFailure('빼지 못했습니다', caught)
  } finally {
    busyId.value = null
  }
}
</script>

<template>
  <div class="watchlist">
    <AppViewHeader>
      <template #title>
        <h1 class="watchlist__title">관심종목</h1>
      </template>
    </AppViewHeader>

    <div class="watchlist__body">
      <!-- 공지는 크롬이 아니라 본문이다. 알림이 언제 오는지는 이 화면을 읽는 사람이
           알아야 할 내용이지, 화면의 정체를 말하는 머리글이 아니다. -->
      <p class="watchlist__notice">{{ EDGE_TRIGGER_NOTICE }}</p>

      <AsyncPanel
        :state="watchlist.state"
        :error="watchlist.error"
        :retry="watchlist.retry"
        panel="watchlist"
        :skeleton-rows="4"
      >
        <p v-if="rows.length === 0" class="watchlist__empty">
          담아 둔 종목이 없습니다. 종목 상세에서 <strong>관심종목</strong>을 누르면 여기 쌓입니다.
        </p>

        <template v-else>
          <SectionHeading title="추적 중" :count="rows.length" />

          <!-- 좁은 화면: 카드. 한 종목씩 훑는 데 맞춘다. -->
          <ul v-if="isMobile" class="watchlist__cards">
            <li v-for="row in rows" :key="row.item.id" class="watch-card">
              <div class="watch-card__head">
                <RouterLink :to="`/stocks/${row.item.stockId}`" class="watch-card__name">
                  {{ row.item.stockName }}
                </RouterLink>
                <Tag
                  :value="row.presentation.label"
                  :severity="row.presentation.severity"
                  :icon="row.presentation.icon"
                />
              </div>
              <p class="watch-card__meta">
                {{ row.item.ticker }} · {{ row.item.marketCode }}
              </p>
              <p class="watch-card__condition">
                알림 조건: {{ row.item.screenName ?? '없음' }}
              </p>
              <p v-if="row.item.memo" class="watch-card__memo">{{ row.item.memo }}</p>
              <div class="watch-card__actions">
                <Button
                  icon="pi pi-trash"
                  severity="danger"
                  text
                  size="small"
                  :aria-label="`${row.item.stockName} 관심종목 해제`"
                  :loading="busyId === row.item.id"
                  @click="confirmRemove(row.item)"
                />
              </div>
            </li>
          </ul>

          <!-- 넓은 화면: 표. 여러 종목의 상태를 한눈에 견준다. -->
          <div v-else class="watchlist__table-wrap">
            <table class="watchlist__table">
              <thead>
                <tr>
                  <th scope="col">종목</th>
                  <th scope="col">알림 조건</th>
                  <th scope="col">상태</th>
                  <th scope="col">마지막 알림</th>
                  <th scope="col">메모</th>
                  <th scope="col"><span class="sr-only">동작</span></th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="row in rows" :key="row.item.id">
                  <td>
                    <RouterLink :to="`/stocks/${row.item.stockId}`" class="watchlist__link">
                      {{ row.item.stockName }}
                    </RouterLink>
                    <span class="watchlist__ticker">{{ row.item.ticker }}</span>
                  </td>
                  <td>{{ row.item.screenName ?? '없음' }}</td>
                  <td>
                    <Tag
                      :value="row.presentation.label"
                      :severity="row.presentation.severity"
                      :icon="row.presentation.icon"
                    />
                  </td>
                  <!-- 알린 적이 없으면 '—'다. 빈 칸으로 두면 값이 안 온 것과 구분되지 않는다. -->
                  <td>{{ row.item.notifiedAt ? formatDate(row.item.notifiedAt.slice(0, 10)) : '—' }}</td>
                  <td class="watchlist__memo">{{ row.item.memo ?? '' }}</td>
                  <td>
                    <Button
                      icon="pi pi-trash"
                      severity="danger"
                      text
                      size="small"
                      :aria-label="`${row.item.stockName} 관심종목 해제`"
                      :loading="busyId === row.item.id"
                      @click="confirmRemove(row.item)"
                    />
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </template>
      </AsyncPanel>
    </div>
  </div>
</template>

<style scoped>
/*
 * 루트는 전폭이다. 머리 띠가 좌측 레일의 선과 이어지려면 그 띠를 감싸는 것이
 * 폭에 묶이면 안 된다. 폭은 여기서 **선언만** 하고, 그것을 받는 것은 띠 안쪽
 * (`AppViewHeader`가 `--app-view-width`로 읽는다)과 아래 본문이다.
 */
.watchlist {
  --app-view-width: 72rem;
}

.watchlist__body {
  max-width: var(--app-view-width);
  margin: 0 auto;
  padding: 1.5rem var(--app-gutter) 3rem;
}

/* 레일의 브랜드·다른 화면 제목과 같은 크기다. 아래 여백은 띠가 갖는다. */
.watchlist__title {
  margin: 0;
  font-size: var(--app-text-xl);
  font-weight: 700;
  letter-spacing: -0.02em;
}

.watchlist__notice,
.watchlist__empty {
  margin: 0 0 1.5rem;
  font-size: var(--app-text-sm);
  color: var(--p-text-muted-color);
}

.watchlist__table-wrap {
  /* 넓은 표는 자기 안에서 가로로 스크롤한다. 본문이 가로로 밀리면 전 구간 오버플로가 된다. */
  overflow-x: auto;
}

.watchlist__table {
  width: 100%;
  border-collapse: collapse;
  font-size: var(--app-text-sm);
}

.watchlist__table th,
.watchlist__table td {
  text-align: left;
  padding: 0.625rem 0.75rem;
  border-bottom: 1px solid var(--p-content-border-color);
  vertical-align: middle;
}

.watchlist__table th {
  font-size: var(--app-text-2xs);
  font-weight: 600;
  letter-spacing: 0.02em;
  color: var(--p-text-muted-color);
}

.watchlist__link {
  color: inherit;
  font-weight: 600;
}

.watchlist__ticker {
  margin-left: 0.5rem;
  font-size: var(--app-text-2xs);
  color: var(--p-text-muted-color);
}

.watchlist__memo {
  max-width: 20rem;
  overflow-wrap: anywhere;
}

.watchlist__cards {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

.watch-card {
  padding: 0.75rem 0.875rem;
  border: 1px solid var(--p-content-border-color);
  border-radius: var(--p-border-radius-md);
  background: var(--app-surface-raised);
  box-shadow: var(--app-edge-highlight);
}

.watch-card__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 0.5rem;
  flex-wrap: wrap;
}

.watch-card__name {
  color: inherit;
  font-weight: 600;
  overflow-wrap: anywhere;
}

.watch-card__meta,
.watch-card__condition,
.watch-card__memo {
  margin: 0.25rem 0 0;
  font-size: var(--app-text-xs);
  color: var(--p-text-muted-color);
  overflow-wrap: anywhere;
}

.watch-card__actions {
  display: flex;
  justify-content: flex-end;
}

.sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  padding: 0;
  margin: -1px;
  overflow: hidden;
  clip: rect(0, 0, 0, 0);
  white-space: nowrap;
  border: 0;
}
</style>
