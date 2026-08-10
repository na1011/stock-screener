import { defineStore } from 'pinia'
import { computed, ref } from 'vue'

import { api } from '@/api/endpoints'
import type {
  AdminJob,
  AllowedEmail,
  AllowedEmailRequest,
  AllowedEmailUpdateRequest,
  BatchExecution,
  BatchExecutionDetail,
  BatchStatus,
  InteractionAction,
  InteractionActor,
  InteractionLogEntry,
  IsoDate,
  JobRunRequest,
} from '@/api/types'
import { useAsync } from '@/composables/useAsync'

/**
 * 관리자 화면의 상태.
 *
 * 영역마다 `useAsync`를 하나씩 둔다 — 배치 목록이 실패해도 계정 목록은 살아 있어야 하고,
 * 그러려면 실패가 영역 안에 갇혀야 한다(spec §8, frontend.md).
 *
 * 필터를 스토어에 두는 이유는 화면을 옮겼다 돌아왔을 때 방금 좁혀 둔 조건이 남아 있어야
 * 하기 때문이다. 배치 상세를 열었다 닫으면 목록으로 돌아오는데, 그때 필터가 초기화되면
 * 실패 하나를 확인할 때마다 조건을 다시 잡아야 한다.
 */
export const useAdminStore = defineStore('admin', () => {
  // ── 배치 ────────────────────────────────────────────────────────
  const batchFilter = ref<{
    from: IsoDate | null
    to: IsoDate | null
    jobName: string | null
    status: BatchStatus | null
  }>({ from: null, to: null, jobName: null, status: null })

  const summaryRequest = useAsync((signal) => api.adminBatchSummary(signal))
  const batchesRequest = useAsync((signal) => api.adminBatches(batchFilter.value, signal))
  const jobsRequest = useAsync((signal) => api.adminJobs(signal))

  const summary = computed(() => summaryRequest.data.value)
  const batches = computed<BatchExecution[]>(() => batchesRequest.data.value ?? [])
  const jobs = computed<AdminJob[]>(() => jobsRequest.data.value ?? [])

  /** 요약·목록·잡 목록을 함께 새로 고친다. 잡을 띄운 뒤 화면이 방금 뜬 실행을 보는 경로다. */
  async function reloadBatches(): Promise<void> {
    await Promise.all([summaryRequest.run(), batchesRequest.run(), jobsRequest.run()])
  }

  async function ensureBatchesLoaded(): Promise<void> {
    if (batchesRequest.state.value === 'idle') {
      await reloadBatches()
    }
  }

  /** 지금 도는 중인 실행이 목록에 있는가. 자동 갱신을 켤지가 여기 달려 있다. */
  const hasRunningBatch = computed(() => batches.value.some((batch) => batch.status === 'RUNNING'))

  /**
   * 배경 자동 갱신이 연속으로 실패한 횟수.
   *
   * 실패를 삼키지 않되 **5초마다 토스트를 띄우지도 않는다.** 그 토스트는 곧 화면을 덮고,
   * 덮이는 것은 마침 지켜보려던 진행률이다. 대신 세어서 화면이 한 번만 말하게 한다.
   */
  const backgroundFailures = ref(0)

  /**
   * 목록과 요약을 조용히 다시 읽는다. 배치가 도는 동안 진행률을 따라가는 경로다.
   *
   * `reloadBatches`와 나눈 이유는 `useAsync.run()`이 매번 `loading`으로 떨어져 `AsyncPanel`이
   * 슬롯을 파괴하기 때문이다 — 그러면 펼쳐 둔 상세가 5초마다 접힌다.
   *
   * 잡 목록(`jobsRequest`)도 함께 읽는다. 거기 `running` 플래그가 있어, 빼면 배치가 끝난 뒤에도
   * 실행 버튼이 계속 잠겨 있다.
   */
  async function refreshBatchesQuietly(): Promise<void> {
    try {
      await Promise.all([summaryRequest.refresh(), batchesRequest.refresh(), jobsRequest.refresh()])
      backgroundFailures.value = 0
    } catch (caught) {
      backgroundFailures.value += 1
      throw caught
    }
  }

  async function openBatch(id: number): Promise<BatchExecutionDetail> {
    return api.adminBatch(id)
  }

  async function retryBatch(id: number): Promise<void> {
    await api.retryBatch(id)
    await reloadBatches()
  }

  async function runJob(jobName: string, body: JobRunRequest): Promise<void> {
    await api.runJob(jobName, body)
    await reloadBatches()
  }

  // ── 계정 ────────────────────────────────────────────────────────
  const accountsRequest = useAsync((signal) => api.allowedEmails(signal))
  const accounts = computed<AllowedEmail[]>(() => accountsRequest.data.value ?? [])

  /**
   * 목록을 다시 읽는다.
   *
   * `ensureAccountsLoaded`와 나눠 둔 이유는 **쓰기가 거절됐을 때 화면과 서버를 맞추는 것**이
   * "아직 안 읽었으면 읽어라"와 다른 요구이기 때문이다. 그 자리에 `ensure`를 부르면 이미
   * 성공 상태라 아무것도 하지 않고, 되돌리기가 조용히 빠진다(2026-08-08 실측).
   */
  async function reloadAccounts(): Promise<void> {
    await accountsRequest.run()
  }

  async function ensureAccountsLoaded(): Promise<void> {
    if (accountsRequest.state.value === 'idle' || accountsRequest.state.value === 'error') {
      await reloadAccounts()
    }
  }

  async function addAccount(body: AllowedEmailRequest): Promise<void> {
    await api.addAllowedEmail(body)
    await accountsRequest.run()
  }

  async function updateAccount(id: number, body: AllowedEmailUpdateRequest): Promise<void> {
    await api.updateAllowedEmail(id, body)
    await accountsRequest.run()
  }

  async function removeAccount(id: number): Promise<void> {
    await api.removeAllowedEmail(id)
    await accountsRequest.run()
  }

  // ── 인터랙션 로그 ────────────────────────────────────────────────
  const logFilter = ref<{
    from: IsoDate | null
    to: IsoDate | null
    userId: number | null
    action: InteractionAction | null
  }>({ from: null, to: null, userId: null, action: null })

  const logsRequest = useAsync((signal) => api.adminInteractions(logFilter.value, signal))
  const actorsRequest = useAsync((signal) => api.adminInteractionActors(signal))

  const logs = computed<InteractionLogEntry[]>(() => logsRequest.data.value ?? [])
  const actors = computed<InteractionActor[]>(() => actorsRequest.data.value ?? [])

  async function reloadLogs(): Promise<void> {
    await logsRequest.run()
  }

  async function ensureLogsLoaded(): Promise<void> {
    if (logsRequest.state.value === 'idle') {
      await Promise.all([logsRequest.run(), actorsRequest.run()])
    }
  }

  return {
    // 배치
    batchFilter,
    summary,
    batches,
    jobs,
    batchesState: batchesRequest.state,
    batchesError: batchesRequest.error,
    // AsyncPanel이 받는 재시도. `retryBatch`(실패분 재실행)와 이름이 헷갈리지 않게 목록임을 밝힌다.
    retryBatchList: reloadBatches,
    summaryState: summaryRequest.state,
    summaryError: summaryRequest.error,
    retrySummary: summaryRequest.retry,
    ensureBatchesLoaded,
    reloadBatches,
    refreshBatchesQuietly,
    hasRunningBatch,
    backgroundFailures,
    openBatch,
    retryBatch,
    runJob,

    // 계정
    accounts,
    accountsState: accountsRequest.state,
    accountsError: accountsRequest.error,
    retryAccounts: accountsRequest.retry,
    ensureAccountsLoaded,
    reloadAccounts,
    addAccount,
    updateAccount,
    removeAccount,

    // 로그
    logFilter,
    logs,
    actors,
    logsState: logsRequest.state,
    logsError: logsRequest.error,
    retryLogs: logsRequest.retry,
    ensureLogsLoaded,
    reloadLogs,
  }
})
