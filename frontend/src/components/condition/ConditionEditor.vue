<script setup lang="ts">
import Button from 'primevue/button'
import InputNumber from 'primevue/inputnumber'
import Select from 'primevue/select'
import { computed, nextTick, ref } from 'vue'

import { OPERAND_TYPES } from '@/api/types'
import type { ComparisonOperatorName, IndicatorBand, OperandType } from '@/api/types'
import IndicatorPicker from '@/components/condition/IndicatorPicker.vue'
import FormField from '@/components/ui/FormField.vue'
import SegmentedControl from '@/components/ui/SegmentedControl.vue'
import {
  CONDITION_ROLES,
  PICK_INDICATOR,
  SEARCH_INDICATOR,
  conditionRoleHints,
  conditionRoleOf,
} from '@/lib/conditionText'
import type { ConditionRole } from '@/lib/conditionText'
import { formatEnteredNumber } from '@/lib/format'
import { topQuintileCondition } from '@/lib/direction'
import { useCatalogStore } from '@/stores/catalog'
import type { DraftCondition } from '@/stores/screenDraft'
import { useScreenDraftStore } from '@/stores/screenDraft'

/**
 * 조건 하나를 고치는 팝오버.
 *
 * IMPORTANT — **구간 프리셋이 먼저, 숫자가 나중이다.** 이 순서가 이 컴포넌트의 요점이다.
 * 예전에는 값 입력칸 하나뿐이라 "PER에 무슨 숫자를 넣어야 하는가"에 화면이 답하지 않았고,
 * 그것이 `.claude/rules/frontend.md`가 MUST NOT으로 금지한 "값만 나열"이었다. 대상 사용자는
 * 초보~중급 투자자다. 아는 사람은 `직접 입력`을 한 번 더 눌러 내려가면 된다.
 *
 * 구간이 없는 지표(주가 스케일 — SMA·ATR·MACD·종가)는 프리셋 없이 직접 입력만 낸다.
 * 없는 것을 지어내지 않는다. 근거는 ADR-0023에 있다.
 */
const props = defineProps<{
  condition: DraftCondition
  readonly: boolean
  /**
   * 「직접 입력」을 사용자가 이미 접었는가 펼쳤는가. **null이면 아직 정하지 않은 것**이고,
   * 그때만 아래 기본값 규칙을 적용한다.
   *
   * 이 상태가 밖에 있는 이유는 이 컴포넌트가 팝오버와 함께 언마운트되기 때문이다 —
   * 지역 `ref`로 두면 닫을 때마다 사용자의 선택이 사라진다(`ConditionChipBar`의 주석).
   */
  manualOpen: boolean | null
}>()
const emit = defineEmits<{ remove: []; done: []; 'update:manual-open': [boolean] }>()

const catalog = useCatalogStore()
const draft = useScreenDraftStore()

const indicator = computed(() =>
  props.condition.indicatorCode === null
    ? null
    : (catalog.byCode.get(props.condition.indicatorCode) ?? null),
)

const bands = computed<IndicatorBand[]>(() => indicator.value?.bands ?? [])

const conditionRole = computed(() => conditionRoleOf(props.condition.required))

/**
 * 역할을 바꾼다. 필수로 바꾸면 가중치를 함께 비운다 — 둘은 배타이고, 남겨 두면 저장·실행이
 * 400으로 거절된다. 되돌릴 때 옛 가중치를 복원하지 않는 것은 의도다: 복원하면 화면에 없던
 * 값이 되살아나 사용자가 넣은 적 없는 가중치로 점수가 매겨진다.
 */
function setRole(role: ConditionRole) {
  props.condition.required = role === 'REQUIRED'
  if (props.condition.required) {
    props.condition.weight = null
  }
  draft.markDirty()
}

/** 백분위 지표일 때만 나온다. 부등호와 경계값은 서버가 준 것을 그대로 넣는다. */
const topQuintile = computed(() => topQuintileCondition(indicator.value?.percentile))

/**
 * 직접 입력을 펼쳐 두는가.
 *
 * 제시할 구간이 없으면 접을 것이 없으므로 처음부터 펼친다. 이미 값이 들어 있는 조건을
 * 다시 열 때도 펼친다 — 고치러 온 사람에게 프리셋 목록을 먼저 보이면 한 번 더 눌러야 한다.
 */
const manualOpen = computed(() => props.manualOpen ?? defaultManualOpen())

/**
 * 사용자가 아직 정하지 않았을 때의 기본값.
 *
 * 규칙을 함수로 둔 이유는 부르는 곳이 **둘**이기 때문이다. 지금 그리는 값과 [초기화]다.
 * 초기화는 `key`를 바꾸지 않으므로(바꾸면 팝오버가 앵커를 잃는다) watch만으로는 재평가되지
 * 않고, 값이 비워졌는데 입력칸이 펼쳐진 채 남는다.
 */
function defaultManualOpen(): boolean {
  return bands.value.length === 0 || props.condition.value !== null
}

/**
 * 지표를 고르는 중인가.
 *
 * IMPORTANT — 목록을 **중첩 팝오버로 열지 않는다.** 이 시트 자체가 팝오버라 그 위에 또 하나를
 * 얹으면 ADR-0024가 적어 둔 재진입 함정(같은 틱에 닫고 열면 컨테이너가 걷히는 중에
 * `offsetHeight`를 읽고 죽는다)과 ADR-0064의 오버레이 클릭 문제를 동시에 불러온다.
 * 시트 안에서 본문을 갈아 끼우면 둘 다 해당 없다.
 */
const picking = ref(false)

const picker = ref<InstanceType<typeof IndicatorPicker> | null>(null)

function startPicking(): void {
  picking.value = true
  void nextTick(() => void picker.value?.focusSearch())
}

/**
 * 지표를 갈아 끼운다.
 *
 * `resetManualOpen()`을 함께 부르는 것이 요점이다. 새 지표는 구간이 다르고 값이 비어 있으므로
 * 접힘 상태를 그 지표에 맞게 다시 정해야 한다 — **여기서는 사용자의 기억보다 규칙이 이긴다.**
 * 값을 비우는 사건이라는 점에서 [초기화]와 같은 자리다.
 */
function pickIndicator(code: string): void {
  draft.setIndicator(props.condition.key, code)
  picking.value = false
  resetManualOpen()
}

/**
 * 사용자의 선택을 지우고 기본값 규칙으로 되돌린다.
 *
 * [초기화]와 **지표 교체**가 부른다. 둘 다 값이 비고 구간이 달라지는 사건이라, 접힘 상태도
 * 그 지표에 맞게 다시 정해져야 한다 — 여기서는 기억보다 규칙이 이긴다.
 */
function resetManualOpen(): void {
  emit('update:manual-open', defaultManualOpen())
}

/** 지표만 남기고 값을 비운다. 되돌리는 것이 아니라 처음 상태로 보내는 것이다. */
function resetValues(): void {
  draft.resetCondition(props.condition.key)
  resetManualOpen()
}

/**
 * BETWEEN은 지표 참조를 우변으로 쓸 수 없다 — 서버가 거부한다("상·하한을 각각 조건으로
 * 나누어라"). 선택지 자체를 없애 400을 만들지 않는다.
 */
const operatorOptions = computed(() =>
  catalog.operators.filter(
    (op) => props.condition.operandType !== 'INDICATOR' || !op.requiresUpperBound,
  ),
)

const requiresUpperBound = computed(
  () =>
    catalog.operators.find((op) => op.name === props.condition.operator)?.requiresUpperBound ?? false,
)

/**
 * 비교 대상의 종류.
 *
 * 유니온에서 파생시킨다 — 손으로 적은 배열은 서버가 종류를 늘려도 그대로 남아, 그 종류를
 * 고를 수 없게 되고(만들 수 없는 조건이 생긴다) 저장된 전략이 그것을 쓰면 LITERAL 분기로
 * 잘못 그려진다.
 */
const OPERAND_TYPE_LABELS: Record<OperandType, string> = {
  LITERAL: '값',
  INDICATOR: '지표',
}

const operandTypeOptions: { label: string; value: OperandType }[] = OPERAND_TYPES.map((value) => ({
  label: OPERAND_TYPE_LABELS[value],
  value,
}))

/** 값 입력칸 뒤에 붙는 단위. 서버가 준 문자열을 그대로 쓴다. */
const unitSuffix = computed(() => indicator.value?.unit ?? '')

const indicatorOptions = computed(() =>
  catalog.grouped.map((group) => ({
    label: group.label,
    items: group.items.map((it) => ({ label: it.name, value: it.code })),
  })),
)

function bandLabel(band: IndicatorBand): string {
  const scale = indicator.value?.decimalScale ?? 2
  const unit = unitSuffix.value
  const operatorLabel = catalog.operators.find((op) => op.name === band.operator)?.label ?? ''
  if (band.upperBound !== null) {
    return `${formatEnteredNumber(band.value, scale)}~${formatEnteredNumber(band.upperBound, scale)}${unit}`
  }
  return `${formatEnteredNumber(band.value, scale)}${unit} ${operatorLabel}`.trim()
}

/**
 * 구간 하나를 조건으로 만든다.
 *
 * IMPORTANT — 부등호를 여기서 정하지 않는다. 서버가 `operator`·`value`·`upperBound`를 이미
 * 번역해 내려준다(ADR-0023). `band.from`/`to`를 보고 화면이 유도하면 그 규칙이 두 곳에 생기고,
 * 어긋나도 값이 그럴듯해 드러나지 않는다.
 */
function applyBand(band: IndicatorBand): void {
  props.condition.operandType = 'LITERAL'
  props.condition.operator = band.operator
  props.condition.value = band.value
  props.condition.upperBound = band.upperBound
  draft.markDirty()
  // 고르면 끝이다. 한 번 더 닫게 하면 프리셋의 이점이 사라진다.
  emit('done')
}

function applyTopQuintile(): void {
  const condition = topQuintile.value
  if (condition === null) {
    return
  }
  props.condition.operandType = 'LITERAL'
  props.condition.operator = condition.operator as ComparisonOperatorName
  props.condition.value = condition.value
  props.condition.upperBound = null
  draft.markDirty()
  emit('done')
}

function onOperandTypeChange(value: OperandType | null): void {
  if (value === null) {
    return
  }
  props.condition.operandType = value
  if (value === 'INDICATOR' && props.condition.operator === 'BETWEEN') {
    props.condition.operator = 'GT'
  }
  draft.markDirty()
}
</script>

<template>
  <div class="editor" :class="{ 'editor--picking': picking }">
    <header class="editor__head">
      <div class="editor__title">
        <h3 class="editor__name">{{ indicator?.name ?? '지표' }}</h3>
        <span v-if="unitSuffix" class="editor__unit">{{ unitSuffix }}</span>
        <!--
          지표를 바꾸는 길. 없으면 사용자는 지우고 다시 만들어야 하고, 그러면 값·역할·가중치를
          전부 다시 넣게 된다. `readonly`에서는 제목이 글자로만 남는다.
        -->
        <Button
          v-if="!readonly"
          type="button"
          text
          size="small"
          class="editor__change"
          :label="picking ? '취소' : '변경'"
          :aria-expanded="picking"
          v-tooltip.top="picking ? '지표 바꾸기를 그만둔다' : '이 조건의 지표를 바꾼다'"
          @click="picking ? (picking = false) : startPicking()"
        />
      </div>
      <div class="editor__head-actions">
        <!--
          `v-if="!readonly"` 밖에 있다. 제외는 전략을 고치는 것이 아니라 이번 실행에서
          빼는 것이고, "프리셋에서 조건 하나 빼고 돌려보기"가 이 기능이 있는 이유다.
        -->
        <Button
          type="button"
          text
          rounded
          size="small"
          :severity="condition.enabled ? 'secondary' : undefined"
          :icon="condition.enabled ? 'pi pi-eye' : 'pi pi-eye-slash'"
          :aria-label="condition.enabled ? '이 조건을 실행에서 빼기' : '이 조건을 다시 넣기'"
          v-tooltip.top="condition.enabled ? '실행에서 빼기' : '다시 넣기'"
          @click="draft.setEnabled(condition.key, !condition.enabled)"
        />
        <Button
          v-if="!readonly"
          type="button"
          text
          rounded
          size="small"
          severity="danger"
          icon="pi pi-trash"
          aria-label="이 조건 삭제"
          v-tooltip.top="'이 조건 삭제'"
          @click="emit('remove')"
        />
      </div>
    </header>

    <!--
      지표를 고르는 중에는 본문을 통째로 갈아 끼운다. 설명·구간·입력칸을 아래 남겨 두면
      **바꾸기 전 지표의 구간**이 목록 옆에 함께 떠서 어느 쪽이 지금 조건인지 알 수 없다.
    -->
    <IndicatorPicker
      v-if="picking"
      ref="picker"
      compact
      class="editor__picker"
      @pick="pickIndicator"
    />

    <template v-else>
      <!-- 무엇을 뜻하는 지표인지. 이름만으로는 값을 정할 수 없다. -->
      <p v-if="indicator?.interpretation" class="editor__interpretation">
        {{ indicator.interpretation }}
      </p>
      <p v-if="indicator?.percentile" class="editor__interpretation">
        {{ indicator.percentile.label }}
      </p>

      <template v-if="!readonly">
        <!-- 구간 프리셋. 숫자를 모르는 사용자가 먼저 만나는 것. -->
        <p v-if="bands.length > 0" class="editor__bands-title">구간에서 고르기</p>
        <div v-if="bands.length > 0" class="editor__bands">
          <button
            v-for="band in bands"
            :key="band.label"
            type="button"
            class="app-list-button editor__band"
            @click="applyBand(band)"
          >
            <span class="editor__band-range tabular">{{ bandLabel(band) }}</span>
            <span class="editor__band-label">{{ band.label }}</span>
          </button>
        </div>

        <Button
          v-if="topQuintile"
          label="상위 20% 바로 적용"
          icon="pi pi-bolt"
          size="small"
          text
          class="editor__quintile"
          @click="applyTopQuintile"
        />

        <button
          v-if="bands.length > 0"
          type="button"
          class="app-list-button editor__manual-toggle"
          :aria-expanded="manualOpen"
          @click="emit('update:manual-open', !manualOpen)"
        >
          <i :class="manualOpen ? 'pi pi-chevron-down' : 'pi pi-chevron-right'" aria-hidden="true" />
          <span>직접 입력</span>
        </button>

        <div v-show="manualOpen" class="editor__manual">
          <FormField label="비교 대상">
            <SegmentedControl
              :model-value="condition.operandType"
              :options="operandTypeOptions"
              aria-label="비교 대상"
              @update:model-value="onOperandTypeChange"
            />
          </FormField>

          <FormField label="조건">
            <Select
              :model-value="condition.operator"
              :options="operatorOptions"
              option-label="label"
              option-value="name"
              fluid
              @update:model-value="
                (value) => {
                  condition.operator = value
                  draft.markDirty()
                }
              "
            />
          </FormField>

          <template v-if="condition.operandType === 'LITERAL'">
            <FormField :label="requiresUpperBound ? '하한' : '값'">
              <InputNumber
                :model-value="condition.value"
                :max-fraction-digits="4"
                :suffix="unitSuffix"
                fluid
                @update:model-value="
                  (value) => {
                    condition.value = value
                    draft.markDirty()
                  }
                "
              />
            </FormField>
            <FormField v-if="requiresUpperBound" label="상한">
              <InputNumber
                :model-value="condition.upperBound"
                :max-fraction-digits="4"
                :suffix="unitSuffix"
                fluid
                @update:model-value="
                  (value) => {
                    condition.upperBound = value
                    draft.markDirty()
                  }
                "
              />
            </FormField>
          </template>

          <template v-else>
            <FormField label="비교할 지표">
              <Select
                :model-value="condition.operandIndicatorCode"
                :options="indicatorOptions"
                option-group-label="label"
                option-group-children="items"
                option-label="label"
                option-value="value"
                :placeholder="PICK_INDICATOR"
                filter
                :filter-placeholder="SEARCH_INDICATOR"
                fluid
                @update:model-value="
                  (value) => {
                    condition.operandIndicatorCode = value
                    draft.markDirty()
                  }
                "
              />
            </FormField>
            <FormField label="배수">
              <InputNumber
                :model-value="condition.multiplier"
                :max-fraction-digits="4"
                placeholder="1"
                fluid
                @update:model-value="
                  (value) => {
                    condition.multiplier = value
                    draft.markDirty()
                  }
                "
              />
            </FormField>
          </template>

          <!--
            역할과 가중치는 점수 방식에서만 뜻이 있다. 거르기에서는 모든 조건이 이미 필수이고
            점수도 매기지 않으므로 자리를 차지할 이유가 없다.

            토글로 두는 것이 요점이다 — 필수와 가중치는 배타이고(서버의 ck_cond_required_weight),
            체크박스 + 별도 입력칸으로 두면 둘 다 채워 놓고 400을 맞는 조합이 만들어진다.
          -->
          <template v-if="draft.evaluationMode === 'SCORING'">
            <FormField label="역할" :hint="conditionRoleHints[conditionRole]">
              <SegmentedControl
                :model-value="conditionRole"
                :options="CONDITION_ROLES"
                aria-label="조건의 역할"
                @update:model-value="setRole"
              />
            </FormField>

            <FormField v-if="conditionRole === 'SCORED'" label="가중치">
              <InputNumber
                :model-value="condition.weight"
                :min="0"
                :max-fraction-digits="2"
                placeholder="1"
                fluid
                @update:model-value="
                  (value) => {
                    condition.weight = value
                    draft.markDirty()
                  }
                "
              />
            </FormField>
          </template>
        </div>
      </template>

      <p v-else class="editor__readonly">읽기 전용 전략입니다. 복제하면 고칠 수 있습니다.</p>
    </template>

    <!--
      footer가 `v-if="!readonly"` **바깥**에 있다. 읽기 전용 팝오버는 닫는 수단이 바깥
      클릭뿐이었고, 그것이 닫을 수 있다는 사실 자체가 화면에 없었다.

      [확인]이 값을 확정하는 것은 아니다 — 입력은 이미 반영되어 있다. 이 버튼이 하는 일은
      "여기서 끝났다"를 말할 자리를 주는 것이다. 구간 프리셋을 고르면 저절로 닫히는 것과
      달리 직접 입력에는 끝나는 지점이 없어, 사용자가 언제 확정됐는지 알 수 없었다.
    -->
    <footer v-if="!picking" class="editor__foot">
      <Button
        v-if="!readonly"
        type="button"
        label="초기화"
        severity="secondary"
        size="small"
        text
        @click="resetValues"
      />
      <Button type="button" label="확인" size="small" outlined @click="emit('done')" />
    </footer>
  </div>
</template>

<style scoped>
/*
 * IMPORTANT — 높이 상한이 필요하다. PrimeVue의 `absolutePosition`은 아래가 모자라면 위로
 * 뒤집고 그래도 넘치면 **위쪽만** 0으로 클램프한다. 아래는 클램프하지 않는다. 그래서 구간이
 * 많은 지표를 SCORING에서 화면 하단의 칩으로 열면 아래쪽(가중치 입력·이 footer)이 뷰포트
 * 밖으로 나간다. 문서가 스크롤하던 동안에는 내려서 볼 수라도 있었다.
 *
 * `IndicatorPicker`가 같은 이유로 이미 `max-height`를 갖고 있다 — `.p-popover` 자체에는 없다.
 */
.editor {
  display: flex;
  flex-direction: column;
  width: var(--app-overlay-md);
  max-width: 100%;
  max-height: calc(100dvh - 6rem);
  overflow-y: auto;
}

.editor__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 0.5rem;
}

.editor__title {
  display: flex;
  align-items: baseline;
  gap: 0.25rem;
  min-width: 0;
}

.editor__head-actions {
  display: flex;
  align-items: center;
  flex: 0 0 auto;
  gap: 0.125rem;
}

/*
 * 지표를 고르는 중에는 스크롤을 픽커에 맡긴다.
 *
 * `.editor`가 스크롤 컨테이너인 채로 두면 픽커의 검색창이 `.picker__scroll` **밖**이라
 * 함께 밀려 올라가 사라진다 — 검색하려고 연 화면에서 검색창이 안 보인다.
 */
.editor--picking {
  overflow-y: hidden;
}

.editor__change {
  flex: 0 0 auto;
  margin-inline-start: 0.125rem;
}

.editor__picker {
  margin-top: 0.5rem;
}

.editor__name {
  margin: 0;
  font-size: var(--app-text-md);
  font-weight: 600;
}

.editor__unit {
  font-size: var(--app-text-2xs);
  color: var(--p-text-muted-color);
}

.editor__interpretation {
  margin: 0.25rem 0 0;
  font-size: var(--app-text-2xs);
  line-height: 1.45;
  color: var(--p-text-muted-color);
}

/*
 * 목록이 무엇인지 말한다. 없으면 설명문 바로 아래 다섯 줄이 근거 없이 떠 있고, "직접 입력"
 * 토글만 이름을 가져 두 선택지가 대등해 보이지 않는다.
 */
.editor__bands-title {
  margin: 0.625rem 0 0.125rem;
  font-size: var(--app-text-2xs);
  font-weight: 600;
  color: var(--p-text-muted-color);
}

.editor__bands {
  display: flex;
  flex-direction: column;
  gap: 1px;
  margin: 0 -0.25rem;
}

/*
 * 구간 한 줄. 값과 뜻을 좌우로 벌린다 — TradingView가 "15 to 25 / Moderate"를 위아래로 두는
 * 자리인데, 한글 라벨이 짧아 한 줄에 들어간다. 세로로 두면 목록이 두 배로 길어진다.
 */
.editor__band {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 0.5rem;
  width: 100%;
  padding: 0.3125rem 0.375rem;
  font-size: var(--app-text-xs);
}

.editor__band-range {
  color: var(--p-text-color);
  white-space: nowrap;
}

.editor__band-label {
  color: var(--p-text-muted-color);
  white-space: nowrap;
}

.editor__quintile {
  margin-top: 0.25rem;
}

/* 구획을 가르는 선이라 hover 배경 대신 글자 색만 밝아진다. `.app-list-button`의 배경을 덮는다. */
.editor__manual-toggle {
  display: flex;
  align-items: center;
  gap: 0.375rem;
  width: 100%;
  margin-top: 0.375rem;
  padding: 0.3125rem 0.375rem;
  border-top: 1px solid var(--p-content-border-color);
  border-radius: 0;
  color: var(--p-text-muted-color);
  font-size: var(--app-text-2xs);
  font-weight: 600;
}

.editor__manual-toggle:hover,
.editor__manual-toggle:focus-visible {
  background: transparent;
  color: var(--p-text-color);
}

.editor__manual {
  display: flex;
  flex-direction: column;
  gap: 0.375rem;
  margin-top: 0.25rem;
}

.editor__readonly {
  margin: 0.5rem 0 0;
  font-size: var(--app-text-2xs);
  color: var(--p-text-muted-color);
}

/*
 * 맺는 줄. 위 선이 "여기까지가 입력"을 가른다.
 *
 * `margin-top: auto`는 내용이 짧을 때 footer를 바닥에 붙이는 것이 아니라, `.editor`가
 * flex column이 되면서 생긴 여백을 footer 위로 몰아 주는 역할이다.
 */
.editor__foot {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 0.375rem;
  margin-top: 0.625rem;
  padding-top: 0.5rem;
  border-top: 1px solid var(--p-content-border-color);
}
</style>
