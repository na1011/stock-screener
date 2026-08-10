<script setup lang="ts">
import Button from 'primevue/button'
import Column from 'primevue/column'
import DataTable from 'primevue/datatable'
import Message from 'primevue/message'
import Skeleton from 'primevue/skeleton'
import Tag from 'primevue/tag'
import { computed } from 'vue'

import type { BatchExecutionDetail } from '@/api/types'
import RatioBar from '@/components/ui/RatioBar.vue'
import SectionHeading from '@/components/ui/SectionHeading.vue'
import { canRetry } from '@/lib/admin'
import {
  formatCount,
  formatDuration,
  isStalled,
  processedTasks,
  progressRatio,
  remainingSeconds,
  runningSeconds,
  throughputPerSecond,
} from '@/lib/batchProgress'

/**
 * 배치 실행 하나의 상세. 표의 그 행 자리에서 펼쳐진다.
 *
 * IMPORTANT — **잡 이름으로 분기하지 않는다.** 절마다 "서버가 준 것이 있으면 그린다"가
 * 유일한 조건이고, 그래서 잡이 늘어도 이 파일을 고치지 않는다. 예전에 상세가 실패 종목
 * 목록 하나뿐이었던 것은 그 목록만이 모든 잡에 공통이라고 보았기 때문인데, 실제로 공통인
 * 것은 **잡이 무엇을 신고했는가**이지 어떤 잡인가가 아니다.
 *
 * 값이 없는 절을 0으로 채워 그리지 않는다. 단계별 표에 시세·재무·유니버스가 전부 0으로
 * 늘어서면 "이 잡은 이 단계를 돌지 않는다"와 "돌았는데 아무것도 못 받았다"가 같은 모양이 된다.
 */
const props = defineProps<{
  detail: BatchExecutionDetail | null
  loading: boolean
  /** 상세를 못 읽었을 때의 사유. 시트 안에 두어 어느 행이 실패했는지가 드러나게 한다. */
  errorMessage: string | null
  /** 지금 시각(ms). 부모가 자동 갱신마다 밀어 준다 — 여기서 새로 재면 행마다 값이 어긋난다. */
  now: number
}>()

const emit = defineEmits<{ (event: 'retry'): void; (event: 'reload'): void }>()

const execution = computed(() => props.detail?.execution ?? null)

const ratio = computed(() => (execution.value === null ? null : progressRatio(execution.value)))

const processed = computed(() =>
  execution.value === null ? null : processedTasks(execution.value),
)

const throughput = computed(() =>
  execution.value === null ? null : throughputPerSecond(execution.value, props.now),
)

const remaining = computed(() =>
  execution.value === null ? null : remainingSeconds(execution.value, props.now),
)

const elapsed = computed(() =>
  execution.value === null ? null : runningSeconds(execution.value, props.now),
)

const stalled = computed(() => execution.value !== null && isStalled(execution.value, props.now))

/**
 * 분모를 그릴 문자열.
 *
 * 계획이 없으면 분수로 쓰지 않는다. `43400/—`은 분모가 무엇인지 묻게 만들고, 그 물음이
 * 바로 `43400/350`이 만들던 혼란이다. 계량 이전 실행은 처리 건수만 말한다.
 */
const processedLabel = computed(() => {
  if (execution.value === null || processed.value === null) {
    return '—'
  }
  if (execution.value.plannedTaskCount === null) {
    return `${formatCount(processed.value)}건`
  }
  return `${formatCount(processed.value)} / ${formatCount(execution.value.plannedTaskCount)}건`
})

function percentText(value: number | null): string {
  return value === null ? '—' : `${Math.round(value * 100)}%`
}
</script>

<template>
  <div class="sheet">
    <div v-if="loading" class="sheet__loading">
      <Skeleton height="1.5rem" class="sheet__skeleton" />
      <Skeleton height="1.5rem" width="70%" />
    </div>

    <!--
      실패를 시트 안에 둔다. 토스트로만 알리면 어느 행이 실패했는지가 사라지고, 여러 행을
      펼쳐 둔 상태에서는 특히 그렇다.
    -->
    <Message v-else-if="errorMessage !== null" severity="error" :closable="false">
      {{ errorMessage }}
      <Button label="다시 시도" size="small" text @click="emit('reload')" />
    </Message>

    <template v-else-if="detail !== null && execution !== null">
      <!-- 진행 요약 -->
      <section class="summary">
        <div class="summary__bar">
          <RatioBar :value="ratio" :aria-label="`진행률 ${percentText(ratio)}`" />
        </div>
        <dl class="facts">
          <div class="fact">
            <dt>처리</dt>
            <dd class="tabular">
              {{ processedLabel }}
              <span v-if="ratio !== null" class="fact__aside">{{ percentText(ratio) }}</span>
            </dd>
          </div>
          <div class="fact">
            <dt>대상 종목</dt>
            <dd class="tabular">{{ formatCount(execution.targetCount) }}개</dd>
          </div>
          <div class="fact">
            <dt>저장 행</dt>
            <dd class="tabular">{{ formatCount(execution.writtenRowCount) }}</dd>
          </div>
          <div class="fact">
            <dt>경과</dt>
            <dd class="tabular">{{ formatDuration(elapsed) }}</dd>
          </div>
          <div class="fact">
            <dt>처리 속도</dt>
            <!-- 못 재는 것과 느린 것은 다르다. 0으로 그리면 둘이 같아진다. -->
            <dd class="tabular">
              {{ throughput === null ? '—' : `${throughput.toFixed(1)}건/초` }}
            </dd>
          </div>
          <div v-if="remaining !== null" class="fact">
            <dt>남은 시간(어림)</dt>
            <dd class="tabular">{{ formatDuration(remaining) }}</dd>
          </div>
          <div v-if="execution.failureCount > 0" class="fact fact--alert">
            <dt>실패</dt>
            <dd class="tabular">
              {{ formatCount(execution.failureCount) }}건
              <span class="fact__aside">미해결 {{ execution.unresolvedFailureCount }}</span>
            </dd>
          </div>
        </dl>

        <!-- 서버가 준 문자열을 해석 없이 보인다. 형식은 잡이 정한다. -->
        <p v-if="execution.stageLabel !== null" class="stage">
          <i class="pi pi-spin pi-spinner stage__icon" aria-hidden="true" />
          {{ execution.stageLabel }}
        </p>

        <!--
          RUNNING만으로는 도는 것과 프로세스가 죽어 행만 남은 것이 구분되지 않는다.
          실제로 그렇게 남은 고아 행 10건이 전부 "실행 중"으로 보였다.
        -->
        <Message v-if="stalled" severity="warn" :closable="false" class="stage__stalled">
          1분 넘게 진척이 없습니다. 프로세스가 내려갔을 수 있습니다.
        </Message>

        <p v-if="execution.note !== null" class="note">{{ execution.note }}</p>
      </section>

      <!-- 단계별: 합계로는 어느 단계가 비었는지 알 수 없다 -->
      <template v-if="detail.phases.length > 0">
        <SectionHeading title="단계별" />
        <DataTable :value="detail.phases" size="small" data-key="phase" class="sheet__table">
          <Column field="phase" header="단계" />
          <Column header="처리">
            <template #body="{ data }">
              <span class="tabular">
                {{ formatCount(data.successCount + data.failureCount) }} /
                {{ formatCount(data.plannedCount) }}
              </span>
            </template>
          </Column>
          <Column header="실패">
            <template #body="{ data }">
              <span class="tabular" :class="{ 'sheet__alert': data.failureCount > 0 }">
                {{ formatCount(data.failureCount) }}
              </span>
            </template>
          </Column>
          <Column header="저장 행">
            <template #body="{ data }">
              <span class="tabular">{{ formatCount(data.writtenRowCount) }}</span>
            </template>
          </Column>
        </DataTable>
        <!--
          지표 0행은 결손이 아니다. 이 문구가 없으면 0을 보고 배치가 안 돈 것으로 읽는다.
        -->
        <p class="sheet__hint">
          저장 행의 뜻은 단계마다 다릅니다. 시세는 저장한 행이고, 지표는 값이 달라진 행입니다 —
          재계산만 한 실행의 지표 0행은 “바뀐 것이 없다”는 뜻입니다.
        </p>
      </template>

      <!-- 실패 요약: 대응이 종류마다 다르므로 이 구분이 곧 다음 행동이다 -->
      <template v-if="detail.failureSummary.length > 0">
        <SectionHeading title="실패 요약" />
        <!-- 고유 id가 없다. 묶음의 정체성은 종류+소스이고 그 조합이 곧 키다. -->
        <DataTable :value="detail.failureSummary" size="small" class="sheet__table">
          <Column field="failureKind" header="종류" />
          <Column header="소스">
            <template #body="{ data }">{{ data.sourceName ?? '—' }}</template>
          </Column>
          <Column header="건수">
            <template #body="{ data }">
              <span class="tabular">{{ formatCount(data.totalCount) }}</span>
            </template>
          </Column>
          <Column header="미해결">
            <template #body="{ data }">
              <span class="tabular" :class="{ 'sheet__alert': data.unresolvedCount > 0 }">
                {{ formatCount(data.unresolvedCount) }}
              </span>
            </template>
          </Column>
        </DataTable>
      </template>

      <!-- 실패 종목과 재실행 -->
      <template v-if="detail.failures.length > 0 || execution.failureCount > 0">
        <SectionHeading title="실패 종목" :count="detail.failures.length" />

        <Message
          v-if="!detail.retrySupported"
          severity="secondary"
          :closable="false"
          class="sheet__note"
        >
          이 잡은 부분 재실행을 지원하지 않습니다. 실패 단위가 종목이 아니기 때문입니다.
        </Message>
        <div v-else class="sheet__actions">
          <Button
            label="실패분만 재실행"
            icon="pi pi-replay"
            size="small"
            :disabled="!canRetry(execution, detail.retrySupported)"
            @click="emit('retry')"
          />
          <span v-if="execution.unresolvedFailureCount === 0" class="sheet__hint">
            미해결 실패가 없습니다.
          </span>
        </div>

        <DataTable
          :value="detail.failures"
          size="small"
          data-key="id"
          scrollable
          scroll-height="18rem"
          class="sheet__table"
        >
          <template #empty>
            <p class="sheet__hint">
              기록된 실패가 없습니다. 실패 단위가 종목이 아닌 잡은 건수만 남습니다.
            </p>
          </template>
          <Column header="종목">
            <template #body="{ data }">
              <span class="tabular">{{ data.ticker }}</span>
              <span class="sheet__stock-name">{{ data.stockName }}</span>
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
      </template>
    </template>
  </div>
</template>

<style scoped>
.sheet {
  padding: 0.75rem 0.25rem 1rem;
}

.sheet__loading {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

.sheet__skeleton {
  max-width: 24rem;
}

.summary {
  margin-bottom: 1rem;
}

.summary__bar {
  margin-bottom: 0.75rem;
}

.facts {
  display: flex;
  flex-wrap: wrap;
  gap: 0.5rem 1.75rem;
  margin: 0;
}

.fact {
  display: flex;
  flex-direction: column;
  gap: 0.125rem;
}

.fact dt {
  font-size: var(--app-text-xs);
  color: var(--p-text-muted-color);
}

.fact dd {
  margin: 0;
  font-size: var(--app-text-sm);
  font-weight: 600;
}

.fact--alert dd {
  color: var(--p-red-400);
}

.fact__aside {
  margin-inline-start: 0.375rem;
  font-weight: 400;
  color: var(--p-text-subtle-color);
}

.stage {
  display: flex;
  align-items: center;
  gap: 0.375rem;
  margin: 0.75rem 0 0;
  font-size: var(--app-text-sm);
  color: var(--p-text-muted-color);
}

.stage__icon {
  font-size: var(--app-text-xs);
}

.stage__stalled,
.sheet__note {
  margin-top: 0.75rem;
}

.note {
  margin: 0.5rem 0 0;
  font-size: var(--app-text-sm);
  color: var(--p-text-muted-color);
}

.sheet__table {
  margin-bottom: 0.75rem;
}

.sheet__actions {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  margin-bottom: 0.75rem;
}

.sheet__hint {
  margin: 0 0 0.75rem;
  font-size: var(--app-text-xs);
  color: var(--p-text-subtle-color);
}

.sheet__alert {
  color: var(--p-red-400);
}

.sheet__stock-name {
  margin-inline-start: 0.5rem;
  color: var(--p-text-muted-color);
}
</style>
