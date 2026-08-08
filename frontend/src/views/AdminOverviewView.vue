<script setup lang="ts">
import Button from 'primevue/button'
import Column from 'primevue/column'
import DataTable from 'primevue/datatable'
import DatePicker from 'primevue/datepicker'
import Message from 'primevue/message'
import Select from 'primevue/select'
import Tag from 'primevue/tag'
import { useConfirm } from 'primevue/useconfirm'
import { computed, onMounted, ref } from 'vue'

import { BATCH_STATUSES } from '@/api/types'
import type { AdminJob, BatchExecution, BatchExecutionDetail, IsoDate } from '@/api/types'
import AdminNav from '@/components/admin/AdminNav.vue'
import AsyncPanel from '@/components/AsyncPanel.vue'
import SectionHeading from '@/components/ui/SectionHeading.vue'
import { useApiToast } from '@/composables/useApiToast'
import { batchStatusStyle, canRetry, elapsedSeconds, triggeredByLabel } from '@/lib/admin'
import { useAdminStore } from '@/stores/admin'

/**
 * 운영 현황과 배치 개입 (spec §4, §9의 다섯째 수용 기준).
 *
 * 요약·기동·이력 셋을 한 화면에 두는 이유는 이것이 하나의 판단이기 때문이다 — "어제 배치가
 * 어땠나 → 실패가 남았나 → 다시 돌릴까 / 지금 띄울까"가 이어진다. 화면을 나누면 그 사이마다
 * 이동이 끼어든다.
 */
const admin = useAdminStore()
const confirm = useConfirm()
const { notifyFailure, notifySuccess } = useApiToast()

/** 어느 실행의 상세를 펼쳤는가. 상세는 목록과 별도 요청이라 여기서 들고 있는다. */
const openDetail = ref<BatchExecutionDetail | null>(null)
const detailBusyId = ref<number | null>(null)
const runningJobName = ref<string | null>(null)

/** 잡별 실행 파라미터. 비우면 서버가 잡마다 정한 기본값을 쓴다. */
const jobDates = ref<Record<string, { targetDate: Date | null; from: Date | null; to: Date | null }>>(
  {},
)

/**
 * 이력 필터의 날짜 칸.
 *
 * 스토어는 서버가 받는 `yyyy-MM-dd` 문자열을 들고, 여기는 `DatePicker`가 요구하는 `Date`를
 * 든다. 한쪽으로 통일하지 않는 이유는 변환 지점을 하나로 모으기 위해서다 — 스토어에 `Date`를
 * 두면 요청을 만들 때마다 변환이 필요하고, 화면에 문자열을 두면 달력이 열리지 않는다.
 */
const filterFrom = ref<Date | null>(null)
const filterTo = ref<Date | null>(null)

const statusOptions = computed(() =>
  BATCH_STATUSES.map((status) => ({ value: status, label: batchStatusStyle(status).label })),
)

const jobOptions = computed(() =>
  admin.jobs.map((job) => ({ value: job.name, label: job.label })),
)

onMounted(() => {
  void admin.ensureBatchesLoaded()
})

function dateState(jobName: string): { targetDate: Date | null; from: Date | null; to: Date | null } {
  jobDates.value[jobName] ??= { targetDate: null, from: null, to: null }
  return jobDates.value[jobName]
}

/** `Date`를 서버가 받는 `yyyy-MM-dd`로. 시간대 때문에 ISO 문자열을 자르면 하루가 밀린다. */
function toIsoDate(value: Date | null): IsoDate | null {
  if (value === null) {
    return null
  }
  const year = value.getFullYear()
  const month = String(value.getMonth() + 1).padStart(2, '0')
  const day = String(value.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

function confirmRun(job: AdminJob): void {
  confirm.require({
    header: `${job.label} 실행`,
    message:
      job.externalCallEstimate > 0
        ? `외부 API를 약 ${job.externalCallEstimate}회 호출합니다. 실행할까요?`
        : '외부 API를 호출하지 않습니다. 실행할까요?',
    icon: 'pi pi-play',
    acceptLabel: '실행',
    rejectLabel: '취소',
    accept: () => void run(job),
  })
}

async function run(job: AdminJob): Promise<void> {
  const dates = dateState(job.name)
  runningJobName.value = job.name
  try {
    await admin.runJob(job.name, {
      targetDate: toIsoDate(dates.targetDate),
      from: toIsoDate(dates.from),
      to: toIsoDate(dates.to),
    })
    // 202다. "끝났다"가 아니라 "띄웠다"라고 말해야 사용자가 결과를 기다릴 줄 안다.
    notifySuccess('실행을 시작했습니다', `${job.label}이(가) 백그라운드에서 돌고 있습니다.`)
  } catch (caught) {
    notifyFailure('실행하지 못했습니다', caught)
  } finally {
    runningJobName.value = null
  }
}

async function toggleDetail(execution: BatchExecution): Promise<void> {
  if (openDetail.value?.execution.id === execution.id) {
    openDetail.value = null
    return
  }
  detailBusyId.value = execution.id
  try {
    openDetail.value = await admin.openBatch(execution.id)
  } catch (caught) {
    notifyFailure('상세를 불러오지 못했습니다', caught)
  } finally {
    detailBusyId.value = null
  }
}

function confirmRetry(detail: BatchExecutionDetail): void {
  confirm.require({
    header: '실패분 재실행',
    message: `미해결 실패 ${detail.execution.unresolvedFailureCount}건만 다시 처리합니다.`,
    icon: 'pi pi-replay',
    acceptLabel: '재실행',
    rejectLabel: '취소',
    accept: () => void retry(detail.execution.id),
  })
}

async function retry(id: number): Promise<void> {
  detailBusyId.value = id
  try {
    await admin.retryBatch(id)
    openDetail.value = null
    notifySuccess('재실행을 시작했습니다', '완료되면 목록의 상태가 바뀝니다.')
  } catch (caught) {
    notifyFailure('재실행하지 못했습니다', caught)
  } finally {
    detailBusyId.value = null
  }
}

function applyFilter(): void {
  admin.batchFilter.from = toIsoDate(filterFrom.value)
  admin.batchFilter.to = toIsoDate(filterTo.value)
  void admin.reloadBatches()
}

function formatTime(value: string | null): string {
  if (value === null) {
    return '—'
  }
  return new Date(value).toLocaleString('ko-KR', { dateStyle: 'short', timeStyle: 'medium' })
}
</script>

<template>
  <section class="admin">
    <AdminNav />

    <!-- 운영 현황 요약 (spec §4의 첫 항목) -->
    <AsyncPanel
      :state="admin.summaryState"
      :error="admin.summaryError"
      :retry="admin.retrySummary"
      panel="batchSummary"
      :skeleton-rows="2"
    >
      <div v-if="admin.summary" class="admin__summary">
        <!--
          미해결 에러 건수를 가장 크게 둔다. 이 화면에서 조치가 필요한지를 한 눈에 답하는
          숫자가 이것 하나이기 때문이다. 0이면 강조를 걷어 평상시 화면이 조용하게 만든다.
        -->
        <article class="stat" :class="{ 'stat--alert': admin.summary.unresolvedFailureCount > 0 }">
          <span class="stat__label">미해결 실패</span>
          <strong class="stat__value tabular">{{ admin.summary.unresolvedFailureCount }}</strong>
          <span class="stat__unit">건</span>
        </article>

        <article
          v-for="execution in admin.summary.latestByJob"
          :key="execution.jobName"
          class="stat stat--job"
        >
          <span class="stat__label">{{ execution.jobName }}</span>
          <Tag
            :value="batchStatusStyle(execution.status).label"
            :severity="batchStatusStyle(execution.status).severity"
            v-tooltip.bottom="batchStatusStyle(execution.status).tooltip"
          />
          <span class="stat__meta tabular">{{ execution.targetDate }}</span>
        </article>

        <p v-if="admin.summary.latestByJob.length === 0" class="admin__empty">
          아직 실행된 배치가 없습니다.
        </p>
      </div>
    </AsyncPanel>

    <!-- 수동 기동 -->
    <SectionHeading title="배치 실행" />
    <AsyncPanel
      :state="admin.batchesState"
      :error="admin.batchesError"
      :retry="admin.retryBatchList"
      panel="batches"
      :skeleton-rows="3"
    >
      <ul class="jobs">
        <li v-for="job in admin.jobs" :key="job.name" class="job">
          <div class="job__body">
            <div class="job__title">
              <strong>{{ job.label }}</strong>
              <Tag v-if="job.running" value="실행 중" severity="info" />
            </div>
            <p class="job__description">{{ job.description }}</p>
            <!--
              누르면 쿼터가 나간다. 그 사실이 버튼 옆에 있어야 한다 — 값은 서버가 준다.
            -->
            <p class="job__cost">
              {{
                job.externalCallEstimate > 0
                  ? `외부 API 약 ${job.externalCallEstimate}회`
                  : '외부 API 호출 없음'
              }}
            </p>
          </div>
          <div class="job__controls">
            <DatePicker
              v-if="job.parameters === 'TARGET_DATE'"
              v-model="dateState(job.name).targetDate"
              date-format="yy-mm-dd"
              placeholder="대상 일자(기본값)"
              show-icon
              icon-display="input"
              size="small"
            />
            <template v-else>
              <DatePicker
                v-model="dateState(job.name).from"
                date-format="yy-mm-dd"
                placeholder="시작"
                size="small"
              />
              <DatePicker
                v-model="dateState(job.name).to"
                date-format="yy-mm-dd"
                placeholder="종료"
                size="small"
              />
            </template>
            <Button
              label="실행"
              icon="pi pi-play"
              size="small"
              :disabled="job.running"
              :loading="runningJobName === job.name"
              @click="confirmRun(job)"
            />
          </div>
        </li>
      </ul>

      <!-- 실행 이력 -->
      <SectionHeading title="실행 이력" :count="admin.batches.length" />
      <div class="filters">
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
          v-model="admin.batchFilter.jobName"
          :options="jobOptions"
          option-label="label"
          option-value="value"
          placeholder="잡 전체"
          show-clear
          size="small"
          @change="applyFilter"
        />
        <Select
          v-model="admin.batchFilter.status"
          :options="statusOptions"
          option-label="label"
          option-value="value"
          placeholder="상태 전체"
          show-clear
          size="small"
          @change="applyFilter"
        />
        <Button label="새로 고침" icon="pi pi-refresh" size="small" outlined @click="applyFilter" />
      </div>

      <DataTable :value="admin.batches" size="small" data-key="id" class="admin__table">
        <template #empty>
          <p class="admin__empty">조건에 맞는 실행 이력이 없습니다.</p>
        </template>

        <Column field="targetDate" header="대상 일자" />
        <Column field="jobName" header="잡" />
        <Column header="상태">
          <template #body="{ data }: { data: BatchExecution }">
            <Tag
              :value="batchStatusStyle(data.status).label"
              :severity="batchStatusStyle(data.status).severity"
              v-tooltip.bottom="batchStatusStyle(data.status).tooltip"
            />
            <!-- 재실행은 원래 실행과 연결되어 남는다. 없으면 "또 실패"가 안 보인다. -->
            <span v-if="data.retriedFromId !== null" class="admin__retry-of">
              #{{ data.retriedFromId }} 재실행
            </span>
          </template>
        </Column>
        <Column header="처리">
          <template #body="{ data }: { data: BatchExecution }">
            <span class="tabular">{{ data.successCount }}/{{ data.targetCount }}</span>
            <span v-if="data.failureCount > 0" class="admin__failed tabular">
              실패 {{ data.failureCount }}
            </span>
          </template>
        </Column>
        <Column header="시작">
          <template #body="{ data }: { data: BatchExecution }">
            <span class="tabular">{{ formatTime(data.startedAt) }}</span>
          </template>
        </Column>
        <Column header="소요">
          <template #body="{ data }: { data: BatchExecution }">
            <!-- 아직 안 끝났으면 —다. 0으로 그리면 즉시 끝난 것으로 읽힌다. -->
            <span class="tabular">
              {{ elapsedSeconds(data) === null ? '—' : `${elapsedSeconds(data)}초` }}
            </span>
          </template>
        </Column>
        <Column header="실행 주체">
          <template #body="{ data }: { data: BatchExecution }">
            {{ triggeredByLabel(data.triggeredBy) }}
          </template>
        </Column>
        <Column header="">
          <template #body="{ data }: { data: BatchExecution }">
            <Button
              :label="openDetail?.execution.id === data.id ? '닫기' : '상세'"
              size="small"
              text
              :loading="detailBusyId === data.id"
              @click="toggleDetail(data)"
            />
          </template>
        </Column>
      </DataTable>

      <!-- 상세: 실패 종목과 재실행 -->
      <section v-if="openDetail" class="detail">
        <SectionHeading
          :title="`#${openDetail.execution.id} 실패 종목`"
          :count="openDetail.failures.length"
        />

        <Message
          v-if="!openDetail.retrySupported"
          severity="secondary"
          :closable="false"
          class="detail__note"
        >
          이 잡은 부분 재실행을 지원하지 않습니다. 실패 단위가 종목이 아니기 때문입니다.
        </Message>

        <div v-else class="detail__actions">
          <Button
            label="실패분만 재실행"
            icon="pi pi-replay"
            size="small"
            :disabled="!canRetry(openDetail.execution, openDetail.retrySupported)"
            :loading="detailBusyId === openDetail.execution.id"
            @click="confirmRetry(openDetail)"
          />
          <span v-if="openDetail.execution.unresolvedFailureCount === 0" class="detail__hint">
            미해결 실패가 없습니다.
          </span>
        </div>

        <DataTable :value="openDetail.failures" size="small" data-key="id" scrollable
          scroll-height="20rem">
          <template #empty>
            <p class="admin__empty">기록된 실패가 없습니다.</p>
          </template>
          <Column header="종목">
            <template #body="{ data }">
              <span class="tabular">{{ data.ticker }}</span>
              <span class="detail__stock-name">{{ data.stockName }}</span>
            </template>
          </Column>
          <!--
            단계·종류는 유니온으로 좁히지 않고 그대로 보인다. 서버가 값을 늘렸을 때 목록이
            통째로 죽는 것보다 낯선 이름이 섞이는 편이 낫다(frontend.md).
          -->
          <Column field="failedPhase" header="단계" />
          <Column field="failureKind" header="종류" />
          <Column field="sourceName" header="소스" />
          <Column field="message" header="사유" />
          <Column header="해결">
            <template #body="{ data }">
              <Tag
                :value="data.resolved ? '해결됨' : '미해결'"
                :severity="data.resolved ? 'success' : 'warn'"
              />
            </template>
          </Column>
        </DataTable>
      </section>
    </AsyncPanel>
  </section>
</template>

<style scoped>
.admin {
  max-width: var(--app-reading-width);
  margin: 0 auto;
  padding: 1.5rem var(--app-gutter) 3rem;
}

.admin__summary {
  display: flex;
  flex-wrap: wrap;
  gap: 0.75rem;
  margin-bottom: 1.5rem;
}

.stat {
  display: flex;
  align-items: baseline;
  gap: 0.375rem;
  padding: 0.75rem 1rem;
  border: 1px solid var(--p-content-border-color);
  border-radius: var(--p-border-radius-md);
  background: var(--app-surface-raised);
  box-shadow: var(--app-edge-highlight);
}

/* 조치가 필요할 때만 강조한다. 늘 켜져 있으면 그 자리는 곧 배경이 된다. */
.stat--alert {
  border-color: var(--app-tonal-danger);
}

.stat--job {
  align-items: center;
}

.stat__label {
  font-size: var(--app-text-sm);
  color: var(--p-text-muted-color);
}

.stat__value {
  font-size: var(--app-text-2xl);
  font-weight: 700;
}

.stat--alert .stat__value {
  color: var(--p-red-400);
}

.stat__unit,
.stat__meta {
  font-size: var(--app-text-sm);
  color: var(--p-text-muted-color);
}

.jobs {
  list-style: none;
  margin: 0 0 1.5rem;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

.job {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 1rem;
  padding: 0.75rem 0.875rem;
  border: 1px solid var(--p-content-border-color);
  border-radius: var(--p-border-radius-md);
  background: var(--app-surface-raised);
}

.job__body {
  min-width: 0;
}

.job__title {
  display: flex;
  align-items: center;
  gap: 0.5rem;
}

.job__description {
  margin: 0.25rem 0 0;
  font-size: var(--app-text-sm);
  color: var(--p-text-muted-color);
}

.job__cost {
  margin: 0.125rem 0 0;
  font-size: var(--app-text-xs);
  color: var(--p-text-subtle-color);
}

.job__controls {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  flex-shrink: 0;
  flex-wrap: wrap;
  justify-content: flex-end;
}

.filters {
  display: flex;
  flex-wrap: wrap;
  gap: 0.5rem;
  margin-bottom: 0.75rem;
}

.admin__table {
  margin-bottom: 1.5rem;
}

.admin__empty {
  margin: 0;
  padding: 0.75rem 0;
  font-size: var(--app-text-sm);
  color: var(--p-text-muted-color);
}

.admin__retry-of,
.admin__failed {
  margin-inline-start: 0.5rem;
  font-size: var(--app-text-xs);
  color: var(--p-text-subtle-color);
}

.admin__failed {
  color: var(--p-red-400);
}

.detail {
  padding-top: 0.5rem;
  border-top: 1px solid var(--p-content-border-color);
}

.detail__note,
.detail__actions {
  margin-bottom: 0.75rem;
}

.detail__actions {
  display: flex;
  align-items: center;
  gap: 0.75rem;
}

.detail__hint {
  font-size: var(--app-text-sm);
  color: var(--p-text-muted-color);
}

.detail__stock-name {
  margin-inline-start: 0.5rem;
  color: var(--p-text-muted-color);
}

/* COMPACT. 잡 행이 컨트롤 넉 줄을 감당하지 못하는 폭이다(lib/breakpoints.ts). */
@media (max-width: 640px) {
  .job {
    flex-direction: column;
    align-items: stretch;
  }

  .job__controls {
    justify-content: flex-start;
  }
}
</style>
