<script setup lang="ts">
import Button from 'primevue/button'
import Column from 'primevue/column'
import DataTable from 'primevue/datatable'
import DatePicker from 'primevue/datepicker'
import Select from 'primevue/select'
import Tag from 'primevue/tag'
import { computed, onMounted, ref } from 'vue'

import { INTERACTION_ACTIONS } from '@/api/types'
import type { InteractionLogEntry, IsoDate } from '@/api/types'
import AdminNav from '@/components/admin/AdminNav.vue'
import AsyncPanel from '@/components/AsyncPanel.vue'
import SectionHeading from '@/components/ui/SectionHeading.vue'
import { interactionActionLabel } from '@/lib/admin'
import { useAdminStore } from '@/stores/admin'

/**
 * 인터랙션 기록 조회 (spec §4 "누가 언제 어떤 주요 행위를 했는지 조회·필터링").
 *
 * 사용자 선택지를 **기록에서** 받는다. 허용목록에서 만들면 권한이 회수된 사람의 과거 행위를
 * 영영 못 찾는다 — 권한 회수는 기록을 지우지 않는다.
 */
const admin = useAdminStore()

const filterFrom = ref<Date | null>(null)
const filterTo = ref<Date | null>(null)

const actionOptions = computed(() =>
  INTERACTION_ACTIONS.map((action) => ({ value: action, label: interactionActionLabel(action) })),
)

const actorOptions = computed(() =>
  admin.actors.map((actor) => ({ value: actor.userId, label: actor.email })),
)

onMounted(() => {
  void admin.ensureLogsLoaded()
})

function toIsoDate(value: Date | null): IsoDate | null {
  if (value === null) {
    return null
  }
  const year = value.getFullYear()
  const month = String(value.getMonth() + 1).padStart(2, '0')
  const day = String(value.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

function applyFilter(): void {
  admin.logFilter.from = toIsoDate(filterFrom.value)
  admin.logFilter.to = toIsoDate(filterTo.value)
  void admin.reloadLogs()
}

function formatTime(value: string): string {
  return new Date(value).toLocaleString('ko-KR', { dateStyle: 'short', timeStyle: 'medium' })
}

/** `{"id":"7"}` → `id=7`. 빈 상세는 빈 문자열이라 셀이 비어 보인다. */
function formatDetail(entry: InteractionLogEntry): string {
  return Object.entries(entry.detail)
    .map(([key, value]) => `${key}=${value}`)
    .join(', ')
}

/** 2xx가 아니면 강조한다. 실패한 시도가 목록에서 성공과 같아 보이면 남길 이유가 없다. */
function isFailure(entry: InteractionLogEntry): boolean {
  return entry.statusCode !== null && entry.statusCode >= 400
}
</script>

<template>
  <div class="logs">
    <AdminNav />

    <div class="logs__body">
      <SectionHeading title="인터랙션 기록" :count="admin.logs.length" />

      <div class="logs__filters">
        <DatePicker
          v-model="filterFrom"
          date-format="yy-mm-dd"
          placeholder="시작 일자"
          size="small"
          @date-select="applyFilter"
        />
        <DatePicker
          v-model="filterTo"
          date-format="yy-mm-dd"
          placeholder="종료 일자"
          size="small"
          @date-select="applyFilter"
        />
        <Select
          v-model="admin.logFilter.userId"
          :options="actorOptions"
          option-label="label"
          option-value="value"
          placeholder="사용자 전체"
          show-clear
          size="small"
          @change="applyFilter"
        />
        <Select
          v-model="admin.logFilter.action"
          :options="actionOptions"
          option-label="label"
          option-value="value"
          placeholder="행위 전체"
          show-clear
          size="small"
          @change="applyFilter"
        />
        <Button label="새로 고침" icon="pi pi-refresh" size="small" outlined @click="applyFilter" />
      </div>

      <AsyncPanel
        :state="admin.logsState"
        :error="admin.logsError"
        :retry="admin.retryLogs"
        panel="interactions"
        :skeleton-rows="6"
      >
        <DataTable :value="admin.logs" size="small" data-key="id" scrollable scroll-height="60vh">
          <template #empty>
            <p class="logs__empty">조건에 맞는 기록이 없습니다.</p>
          </template>

          <Column header="시각">
            <template #body="{ data }: { data: InteractionLogEntry }">
              <span class="tabular">{{ formatTime(data.occurredAt) }}</span>
            </template>
          </Column>
          <Column header="사용자">
            <template #body="{ data }: { data: InteractionLogEntry }">
              <!-- 사용자를 특정하지 못한 기록이 있을 수 있다. 행위 자체는 남는다(ADR-0028). -->
              <span v-if="data.userEmail">{{ data.userEmail }}</span>
              <span v-else class="logs__unknown">알 수 없음</span>
            </template>
          </Column>
          <Column header="행위">
            <template #body="{ data }: { data: InteractionLogEntry }">
              {{ interactionActionLabel(data.action) }}
            </template>
          </Column>
          <Column header="대상">
            <template #body="{ data }: { data: InteractionLogEntry }">
              <span class="logs__detail">{{ formatDetail(data) }}</span>
            </template>
          </Column>
          <Column header="결과">
            <template #body="{ data }: { data: InteractionLogEntry }">
              <Tag
                v-if="data.statusCode !== null"
                :value="String(data.statusCode)"
                :severity="isFailure(data) ? 'danger' : 'secondary'"
              />
              <span v-else class="logs__unknown">—</span>
            </template>
          </Column>
          <Column header="소요">
            <template #body="{ data }: { data: InteractionLogEntry }">
              <span class="tabular">
                {{ data.durationMs === null ? '—' : `${data.durationMs}ms` }}
              </span>
            </template>
          </Column>
        </DataTable>
      </AsyncPanel>
    </div>
  </div>
</template>

<style scoped>
/*
 * 루트는 전폭이다 — 머리 띠가 좌측 레일의 선과 이어지려면 그것을 감싸는 것이 폭에
 * 묶이면 안 된다. 폭은 여기서 선언만 하고, 띠 안쪽과 아래 본문이 그것을 받는다.
 */
.logs {
  --app-view-width: var(--app-reading-width);
}

.logs__body {
  max-width: var(--app-view-width);
  margin: 0 auto;
  padding: 1.5rem var(--app-gutter) 3rem;
}

.logs__filters {
  display: flex;
  flex-wrap: wrap;
  gap: 0.5rem;
  margin-bottom: 0.75rem;
}

.logs__empty {
  margin: 0;
  padding: 0.75rem 0;
  font-size: var(--app-text-sm);
  color: var(--p-text-muted-color);
}

.logs__unknown {
  color: var(--p-text-subtle-color);
}

.logs__detail {
  font-size: var(--app-text-sm);
  color: var(--p-text-muted-color);
  overflow-wrap: anywhere;
}
</style>
