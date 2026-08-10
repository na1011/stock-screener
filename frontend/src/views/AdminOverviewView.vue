<script setup lang="ts">
import Button from 'primevue/button'
import Column from 'primevue/column'
import DataTable from 'primevue/datatable'
import DatePicker from 'primevue/datepicker'
import Message from 'primevue/message'
import Select from 'primevue/select'
import Tag from 'primevue/tag'
import { useConfirm } from 'primevue/useconfirm'
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'

import { ApiError } from '@/api/http'
import { BATCH_STATUSES } from '@/api/types'
import type { AdminJob, BatchExecution, BatchExecutionDetail, IsoDate } from '@/api/types'
import AdminNav from '@/components/admin/AdminNav.vue'
import AsyncPanel from '@/components/AsyncPanel.vue'
import BatchDetailSheet from '@/components/admin/BatchDetailSheet.vue'
import RatioBar from '@/components/ui/RatioBar.vue'
import SectionHeading from '@/components/ui/SectionHeading.vue'
import { useApiToast } from '@/composables/useApiToast'
import { batchStatusStyle, elapsedSeconds, triggeredByLabel } from '@/lib/admin'
import { formatCount, isStalled, processedTasks, progressRatio } from '@/lib/batchProgress'
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

/**
 * 어느 행을 펼쳤는가. PrimeVue의 행 확장이 이 객체를 소유한다(`ResultTable.vue`와 같은 패턴).
 *
 * 화면 아래 단일 슬롯이 아니라 행 확장인 이유는, 표 밖 어딘가에 열린 상세를 사용자가 켜졌는지
 * 확인하기 어려웠기 때문이다. 여러 행을 동시에 펼칠 수 있는 것은 부수적인 이득이 아니라
 * 필요한 것이다 — 어제와 오늘의 같은 잡을 나란히 놓고 보는 것이 이 화면의 주 용도다.
 */
const expandedRows = ref<Record<string, boolean>>({})

/** 펼친 행의 상세. 목록과 별도 요청이라 행마다 따로 든다. */
const details = ref<Record<number, BatchExecutionDetail>>({})
const detailBusy = ref<Record<number, boolean>>({})
const detailError = ref<Record<number, string>>({})
const runningJobName = ref<string | null>(null)

/**
 * 진행률·경과·정체 판정이 쓰는 "지금".
 *
 * 컴포넌트가 하나로 들고 자동 갱신마다 민다. 행마다 `Date.now()`를 부르면 같은 표 안에서
 * 기준 시각이 어긋나고, 무엇보다 값이 반응형이 아니라 화면이 갱신되지 않는다.
 */
const now = ref(Date.now())

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

/**
 * 펼친 행의 상세를 읽는다.
 *
 * 실패를 토스트가 아니라 시트 안에 둔다. 여러 행을 펼칠 수 있으므로, 토스트로만 알리면
 * 어느 행이 실패했는지가 사라진다.
 */
async function loadDetail(id: number): Promise<void> {
  detailBusy.value[id] = true
  delete detailError.value[id]
  try {
    details.value[id] = await admin.openBatch(id)
  } catch (caught) {
    if (!(caught instanceof ApiError)) {
      throw caught
    }
    detailError.value[id] = caught.userMessage
  } finally {
    delete detailBusy.value[id]
  }
}

function onRowExpand(event: { data: BatchExecution }): void {
  void loadDetail(event.data.id)
}

function onRowCollapse(event: { data: BatchExecution }): void {
  // 접으면 버린다. 들고 있으면 다시 펼쳤을 때 옛 진행률이 잠깐 보이는데, 그 몇 백 ms가
  // 하필 "지금 얼마나 갔나"를 확인하려던 순간이다.
  delete details.value[event.data.id]
  delete detailError.value[event.data.id]
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
  detailBusy.value[id] = true
  try {
    await admin.retryBatch(id)
    notifySuccess('재실행을 시작했습니다', '완료되면 목록의 상태가 바뀝니다.')
  } catch (caught) {
    notifyFailure('재실행하지 못했습니다', caught)
  } finally {
    delete detailBusy.value[id]
  }
}

/**
 * 도는 배치가 있는 동안만 자동 갱신한다.
 *
 * `reloadBatches`가 아니라 조용한 경로를 쓴다 — `useAsync.run()`은 매번 `loading`으로 떨어지고
 * `AsyncPanel`이 그때 슬롯을 파괴하므로, 5초마다 펼쳐 둔 행이 접힌다. 지켜보려고 켠 갱신이
 * 보려던 것을 닫는 셈이 된다.
 */
const POLL_INTERVAL_MS = 5_000

/** 이만큼 연속 실패하면 멈춘다. 5초마다 실패하는 요청을 무한히 보내지 않는다. */
const MAX_BACKGROUND_FAILURES = 3

let pollTimer: ReturnType<typeof setInterval> | null = null

const autoRefreshStopped = computed(
  () => admin.hasRunningBatch && admin.backgroundFailures >= MAX_BACKGROUND_FAILURES,
)

async function pollOnce(): Promise<void> {
  // 탭이 뒤에 있으면 건너뛴다. 안 보는 화면 때문에 계속 요청할 이유가 없다.
  if (document.hidden || admin.backgroundFailures >= MAX_BACKGROUND_FAILURES) {
    return
  }
  try {
    await admin.refreshBatchesQuietly()
    now.value = Date.now()
    await Promise.all(Object.keys(details.value).map((id) => loadDetail(Number(id))))
  } catch (caught) {
    // 삼키지 않는다. 다만 5초마다 토스트를 띄우지도 않는다 — 그 토스트가 곧 진행률을 덮는다.
    if (!(caught instanceof ApiError)) {
      throw caught
    }
  }
}

function syncPolling(running: boolean): void {
  if (running && pollTimer === null) {
    pollTimer = setInterval(() => void pollOnce(), POLL_INTERVAL_MS)
    return
  }
  if (!running && pollTimer !== null) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

async function resumeAutoRefresh(): Promise<void> {
  admin.backgroundFailures = 0
  await pollOnce()
}

watch(() => admin.hasRunningBatch, syncPolling, { immediate: true })

onUnmounted(() => syncPolling(false))

function applyFilter(): void {
  admin.batchFilter.from = toIsoDate(filterFrom.value)
  admin.batchFilter.to = toIsoDate(filterTo.value)
  now.value = Date.now()
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

      <!--
        배경 갱신이 연속으로 실패하면 한 번만 말하고 멈춘다. 5초마다 토스트를 띄우면 그것이
        곧 화면을 덮고, 덮이는 것은 마침 지켜보려던 진행률이다.
      -->
      <Message v-if="autoRefreshStopped" severity="warn" :closable="false" class="admin__stopped">
        자동 갱신이 멈췄습니다. 서버에 연결하지 못했습니다.
        <Button label="다시 시작" size="small" text @click="void resumeAutoRefresh()" />
      </Message>

      <DataTable
        v-model:expanded-rows="expandedRows"
        :value="admin.batches"
        size="small"
        data-key="id"
        class="admin__table"
        @row-expand="onRowExpand"
        @row-collapse="onRowCollapse"
      >
        <template #empty>
          <p class="admin__empty">조건에 맞는 실행 이력이 없습니다.</p>
        </template>

        <!--
          화면 아래 단일 슬롯이 아니라 행 확장이다. 표 밖에서 열리면 켜졌는지 확인하기 어렵고,
          두 실행을 나란히 비교할 수도 없다.
        -->
        <Column expander style="width: 3rem" />
        <!--
          실행 id다. 화면에 붙이는 순번이 아닌 이유는 그것이 답해야 할 질문이 "위에서 몇
          번째인가"가 아니라 "이게 #26인가"이기 때문이다 — 순번은 필터를 바꾸면 같은 실행이
          다른 번호가 되고, 알림·로그·DB가 부르는 이름과도 어긋난다. `#26 재실행` 표기와 같은 값이다.
        -->
        <Column header="실행" style="width: 5rem">
          <template #body="{ data }: { data: BatchExecution }">
            <span class="tabular admin__id">#{{ data.id }}</span>
          </template>
        </Column>
        <Column field="targetDate" header="대상 일자" />
        <Column field="jobName" header="잡" />
        <Column header="상태">
          <template #body="{ data }: { data: BatchExecution }">
            <Tag
              :value="batchStatusStyle(data.status).label"
              :severity="batchStatusStyle(data.status).severity"
              v-tooltip.bottom="batchStatusStyle(data.status).tooltip"
            />
            <!-- RUNNING만으로는 도는 것과 죽어서 행만 남은 것이 구분되지 않는다. -->
            <Tag
              v-if="isStalled(data, now)"
              value="정체"
              severity="warn"
              v-tooltip.bottom="'1분 넘게 진척이 없다. 프로세스가 내려갔을 수 있다.'"
              class="admin__stalled"
            />
            <!-- 재실행은 원래 실행과 연결되어 남는다. 없으면 "또 실패"가 안 보인다. -->
            <span v-if="data.retriedFromId !== null" class="admin__retry-of">
              #{{ data.retriedFromId }} 재실행
            </span>
          </template>
        </Column>
        <Column header="진행">
          <template #body="{ data }: { data: BatchExecution }">
            <!--
              분모는 대상 종목 수가 아니라 계획된 작업 단위 수다. 종목 수로 나누던 시절에
              지표 백필이 43400/350으로 보였다(ADR-0037).
            -->
            <span class="tabular">
              {{ formatCount(processedTasks(data)) }}
              <template v-if="data.plannedTaskCount !== null">
                / {{ formatCount(data.plannedTaskCount) }}
              </template>
            </span>
            <span
              v-if="data.plannedTaskCount === null"
              class="admin__unmeasured"
              v-tooltip.bottom="'계획 수가 기록되기 전의 실행이다'"
            >
              계획 미기록
            </span>
            <span v-if="data.failureCount > 0" class="admin__failed tabular">
              실패 {{ formatCount(data.failureCount) }}
            </span>
            <RatioBar
              :value="progressRatio(data)"
              :aria-label="`진행 ${formatCount(processedTasks(data))}건`"
              class="admin__progress"
            />
            <span v-if="data.stageLabel !== null" class="admin__stage">{{ data.stageLabel }}</span>
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

        <template #expansion="{ data }: { data: BatchExecution }">
          <BatchDetailSheet
            :detail="details[data.id] ?? null"
            :loading="detailBusy[data.id] === true"
            :error-message="detailError[data.id] ?? null"
            :now="now"
            @retry="confirmRetry(details[data.id]!)"
            @reload="void loadDetail(data.id)"
          />
        </template>
      </DataTable>
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
.admin__failed,
.admin__unmeasured {
  margin-inline-start: 0.5rem;
  font-size: var(--app-text-xs);
  color: var(--p-text-subtle-color);
}

.admin__failed {
  color: var(--p-red-400);
}

.admin__stalled {
  margin-inline-start: 0.375rem;
}

/* 식별자이지 읽을 값이 아니다. 눈에 먼저 들어오면 옆의 일자·상태를 가린다. */
.admin__id {
  color: var(--p-text-muted-color);
}

.admin__stopped {
  margin-bottom: 0.75rem;
}

/* 진행 막대는 숫자 아래 얇게 깐다. 숫자가 정본이고 막대는 크기 감각만 준다. */
.admin__progress {
  margin-top: 0.25rem;
  max-width: 9rem;
}

/* 서버가 준 구간 문자열. 길어질 수 있어 줄바꿈을 막지 않는다. */
.admin__stage {
  display: block;
  margin-top: 0.125rem;
  font-size: var(--app-text-xs);
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
