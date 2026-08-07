<script setup lang="ts">
import IconField from 'primevue/iconfield'
import InputIcon from 'primevue/inputicon'
import InputText from 'primevue/inputtext'
import { computed, nextTick, ref } from 'vue'

import type { IndicatorResponse } from '@/api/types'
import { SEARCH_INDICATOR } from '@/lib/conditionText'
import { directionStyle, indicatorDirection } from '@/lib/direction'
import { BUILDER_CATEGORY_RANK, categoryLabel, groupByCategory } from '@/lib/indicatorCategory'
import { useCatalogStore } from '@/stores/catalog'

/**
 * 지표 고르기.
 *
 * 예전에는 조건 행 안의 Select 하나였다. 17개가 한 열로 흐르고 그룹 머리글이 항목과 거의
 * 같게 보여서, 무엇이 분류이고 무엇이 고를 수 있는 것인지 구별되지 않았다. 설명도 없어서
 * 이름을 모르는 지표는 고를 수가 없었다.
 *
 * 그래서 셋을 바꿨다 — 분류를 개수와 함께 머리글로 세우고, 항목마다 한 줄 설명을 붙이고,
 * 검색이 이름과 설명 **양쪽**에 걸리게 했다. "성장"으로 검색하면 매출 성장률이 나와야 한다.
 */
const emit = defineEmits<{ pick: [code: string] }>()

const catalog = useCatalogStore()
const query = ref('')
/**
 * `InstanceType<typeof InputText>`로 두면 `$el`에 타입이 없다. PrimeVue의 InputText는
 * 루트가 `<input>` 그 자체라 DOM 참조로 받는 편이 정확하다.
 */
const searchInput = ref<{ $el?: HTMLInputElement } | null>(null)

/**
 * 분류별 묶음. 순서와 라벨의 정본은 `lib/indicatorCategory`다.
 *
 * IMPORTANT — 모르는 분류를 걸러내지 않는다. 예전에 필터링 때문에 분류 하나가 통째로
 * 선택 불가가 된 적이 있고, 그 사실은 어디에도 드러나지 않았다(frontend.md).
 */
const groups = computed(() => {
  const needle = query.value.trim().toLowerCase()
  // 묶음 안의 정렬은 서버의 `sortOrder`다. 프론트가 자기 기준으로 정렬하면 카탈로그가
  // 의도한 배치가 사라진다 — `stores/catalog`의 `grouped`와 같은 규칙이다.
  const sorted = [...catalog.indicators].sort((a, b) => a.sortOrder - b.sortOrder)
  const matched =
    needle === ''
      ? sorted
      : sorted.filter(
          (it) =>
            it.name.toLowerCase().includes(needle) ||
            it.code.toLowerCase().includes(needle) ||
            (it.description ?? '').toLowerCase().includes(needle),
        )
  return groupByCategory(matched, (it) => it.category, BUILDER_CATEGORY_RANK)
})

const total = computed(() => groups.value.reduce((sum, group) => sum + group.items.length, 0))

function markerOf(indicator: IndicatorResponse): string {
  return directionStyle(
    indicatorDirection({
      higherIsBetter: indicator.higherIsBetter,
      percentile: indicator.percentile,
    }),
  ).marker
}

/** 팝오버가 열릴 때 부모가 부른다. 검색으로 바로 타이핑할 수 있어야 한다. */
async function focusSearch(): Promise<void> {
  query.value = ''
  await nextTick()
  searchInput.value?.$el?.focus()
}

defineExpose({ focusSearch })
</script>

<template>
  <div class="picker">
    <IconField class="picker__search">
      <InputIcon class="pi pi-search" />
      <InputText
        ref="searchInput"
        v-model="query"
        :placeholder="SEARCH_INDICATOR"
        :aria-label="SEARCH_INDICATOR"
        fluid
        @keydown.esc.stop="query = ''"
      />
    </IconField>

    <div class="picker__scroll">
      <section v-for="group in groups" :key="group.category" class="picker__group">
        <h3 class="picker__group-title">
          {{ categoryLabel(group.category) }}
          <span class="picker__count tabular">{{ group.items.length }}</span>
        </h3>
        <button
          v-for="indicator in group.items"
          :key="indicator.code"
          type="button"
          class="app-list-button picker__item"
          @click="emit('pick', indicator.code)"
        >
          <span class="picker__item-head">
            <span class="picker__item-name">{{ indicator.name }}</span>
            <span class="picker__item-marker" aria-hidden="true">{{ markerOf(indicator) }}</span>
            <span v-if="indicator.unit" class="picker__item-unit">{{ indicator.unit }}</span>
          </span>
          <!-- 이름만으로는 고를 수 없다. frontend.md가 "값만 나열하지 않는다"로 요구하는 것. -->
          <span v-if="indicator.description" class="picker__item-desc">
            {{ indicator.description }}
          </span>
        </button>
      </section>

      <p v-if="total === 0" class="picker__empty">
        "{{ query }}"에 해당하는 지표가 없습니다.
      </p>
    </div>
  </div>
</template>

<style scoped>
.picker {
  display: flex;
  flex-direction: column;
  width: var(--app-overlay-md);
  max-width: 100%;
}

.picker__search {
  margin-bottom: 0.375rem;
}

.picker__scroll {
  max-height: 22rem;
  overflow-y: auto;
  margin: 0 -0.25rem;
  padding: 0 0.25rem;
}

.picker__group + .picker__group {
  margin-top: 0.5rem;
}

/* 분류 머리글이 항목과 같아 보이던 것이 예전 Select의 문제였다. 크기·색·자간을 모두 벌린다. */
.picker__group-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  position: sticky;
  top: 0;
  z-index: 1;
  margin: 0 0 0.125rem;
  padding: 0.25rem 0.375rem;
  background: var(--p-overlay-popover-background);
  font-size: var(--app-text-2xs);
  font-weight: 600;
  letter-spacing: 0.04em;
  color: var(--p-surface-500);
}

.picker__count {
  color: var(--p-surface-600);
}

.picker__item {
  display: block;
  width: 100%;
  padding: 0.3125rem 0.375rem;
}

.picker__item-head {
  display: flex;
  align-items: baseline;
  gap: 0.25rem;
}

.picker__item-name {
  font-size: var(--app-text-sm);
  font-weight: 600;
}

.picker__item-marker,
.picker__item-unit {
  font-size: var(--app-text-2xs);
  color: var(--p-text-muted-color);
}

.picker__item-desc {
  display: -webkit-box;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  line-clamp: 2;
  overflow: hidden;
  margin-top: 0.0625rem;
  font-size: var(--app-text-2xs);
  line-height: 1.4;
  color: var(--p-surface-500);
}

.picker__empty {
  margin: 0;
  padding: 1rem 0.375rem;
  font-size: var(--app-text-xs);
  color: var(--p-text-muted-color);
}
</style>
