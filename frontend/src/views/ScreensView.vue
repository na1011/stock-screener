<script setup lang="ts">
import Button from 'primevue/button'
import Tag from 'primevue/tag'
import { useConfirm } from 'primevue/useconfirm'
import { onMounted, ref } from 'vue'

import type { ScreenResponse } from '@/api/types'
import AsyncPanel from '@/components/AsyncPanel.vue'
import { panelSubject } from '@/components/asyncPanel'
import SectionHeading from '@/components/ui/SectionHeading.vue'
import { useApiToast } from '@/composables/useApiToast'
import { screenKey, useScreenOpener } from '@/composables/useScreenOpener'
import { evaluationModeLabels } from '@/lib/evaluationMode'
import { screenTypeLabel } from '@/lib/screen'
import { useScreensStore } from '@/stores/screens'

/**
 * 저장된 전략과 프리셋 목록.
 *
 * 프리셋은 읽기 전용으로 보이고 남의 전략은 애초에 목록에 오지 않는다(서버가 담지 않는다).
 * "불러오기"는 조건까지 받아 편집기에 올린 뒤 스크리너로 보낸다 — 목록 응답에는 조건이
 * 없으므로(`conditions`가 null) 이 한 번의 추가 요청이 필요하다.
 */
const screens = useScreensStore()
const confirm = useConfirm()
const { notifyFailure, notifySuccess } = useApiToast()
const { openByKey } = useScreenOpener()

const busyId = ref<number | null>(null)

onMounted(() => {
  void screens.ensureLoaded()
})

/**
 * 불러오기도 스크리너로 보내는 것도 `useScreenOpener`가 한다. 여기가 더하는 것은 **어느 행이
 * 도는 중인지 표시하는 것**뿐이다.
 *
 * 이동을 여기서 따로 하지 않는다. 예전에는 여기만 `router.push`를 갖고 레일은 갖지 않아,
 * 같은 동작이 고른 자리에 따라 다르게 굴었다.
 */
async function load(screen: ScreenResponse): Promise<void> {
  if (screen.id === null) {
    return
  }
  busyId.value = screen.id
  try {
    await openByKey(screenKey(screen))
  } finally {
    busyId.value = null
  }
}

function confirmRemove(screen: ScreenResponse): void {
  if (screen.id === null) {
    return
  }
  const id = screen.id
  confirm.require({
    header: '전략 삭제',
    message: `"${screen.name}"을(를) 삭제합니다. 되돌릴 수 없습니다.`,
    icon: 'pi pi-exclamation-triangle',
    acceptLabel: '삭제',
    rejectLabel: '취소',
    acceptProps: { severity: 'danger' },
    accept: () => void remove(id, screen.name),
  })
}

async function remove(id: number, name: string): Promise<void> {
  busyId.value = id
  try {
    await screens.remove(id)
    notifySuccess('삭제했습니다', name)
  } catch (caught) {
    // 워치리스트가 참조 중이면 서버가 409를 준다(SCREEN_IN_USE). 조용히 넘기면 사용자는
    // 삭제된 줄 알고 목록에 그대로 남은 것을 보게 된다.
    notifyFailure('삭제하지 못했습니다', caught)
  } finally {
    busyId.value = null
  }
}
</script>

<template>
  <section class="screens">
    <!-- 레일의 진입점이 "전략 관리"라 제목도 같은 말이어야 한다. `panelSubject`는 오류
         문구("전략 목록을 불러오지 못했습니다")에 쓰이는 말이라 여기와 역할이 다르다. -->
    <h1 class="screens__title">전략 관리</h1>

    <AsyncPanel
      :state="screens.state"
      :error="screens.error"
      :retry="screens.retry"
      panel="screens"
      :skeleton-rows="4"
    >
      <section class="screens__group">
        <SectionHeading :title="screenTypeLabel('PRESET')" :count="screens.presets.length" />
        <p class="screens__group-note">
          미리 정의된 전략입니다. 고칠 수는 없지만 불러온 뒤 복제해 내 전략으로 저장할 수 있습니다.
        </p>
        <ul class="screens__list">
          <li v-for="screen in screens.presets" :key="screen.code ?? screen.name" class="screen-item">
            <div class="screen-item__body">
              <div class="screen-item__title">
                <strong>{{ screen.name }}</strong>
                <Tag :value="screenTypeLabel('PRESET')" severity="info" />
                <Tag
                  :value="evaluationModeLabels(screen.evaluationMode).short"
                  severity="secondary"
                />
              </div>
              <p v-if="screen.description" class="screen-item__description">{{ screen.description }}</p>
            </div>
            <div class="screen-item__actions">
              <Button
                label="불러오기"
                icon="pi pi-download"
                size="small"
                outlined
                :loading="busyId === screen.id"
                @click="load(screen)"
              />
            </div>
          </li>
        </ul>
      </section>

      <section class="screens__group">
        <SectionHeading :title="screenTypeLabel('CUSTOM')" :count="screens.mine.length" />
        <p v-if="screens.mine.length === 0" class="screens__empty">
          저장한 전략이 없습니다. 스크리너에서 조건을 만든 뒤 저장해 보세요.
        </p>
        <ul v-else class="screens__list">
          <li v-for="screen in screens.mine" :key="screen.id ?? screen.name" class="screen-item">
            <div class="screen-item__body">
              <div class="screen-item__title">
                <strong>{{ screen.name }}</strong>
                <Tag
                  :value="evaluationModeLabels(screen.evaluationMode).short"
                  severity="secondary"
                />
              </div>
              <p v-if="screen.description" class="screen-item__description">{{ screen.description }}</p>
            </div>
            <div class="screen-item__actions">
              <Button
                label="불러오기"
                icon="pi pi-download"
                size="small"
                outlined
                :loading="busyId === screen.id"
                @click="load(screen)"
              />
              <Button
                v-if="screen.editable"
                icon="pi pi-trash"
                severity="danger"
                text
                size="small"
                :aria-label="`${screen.name} 삭제`"
                :loading="busyId === screen.id"
                @click="confirmRemove(screen)"
              />
            </div>
          </li>
        </ul>
      </section>
    </AsyncPanel>
  </section>
</template>

<style scoped>
.screens {
  max-width: 64rem;
  margin: 0 auto;
  padding: 1.5rem var(--app-gutter) 3rem;
}

.screens__title {
  font-size: var(--app-text-xl);
  letter-spacing: -0.01em;
  margin: 0 0 1.5rem;
}

.screens__group {
  margin-bottom: 2rem;
}

/* 첫 구획은 화면 제목 바로 아래다. 제목의 기본 윗여백을 여기서만 지운다. */
.screens__group:first-of-type :deep(.section-heading) {
  margin-top: 0;
}

.screens__group-note,
.screens__empty {
  margin: 0 0 0.75rem;
  font-size: var(--app-text-sm);
  color: var(--p-text-muted-color);
}

.screens__list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

.screen-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 1rem;
  padding: 0.75rem 0.875rem;
  border: 1px solid var(--p-content-border-color);
  border-radius: var(--p-border-radius-md);
  background: var(--app-surface-raised);
  box-shadow: var(--app-edge-highlight);
  transition: border-color var(--app-motion-fast) var(--app-ease);
}

.screen-item:hover {
  border-color: var(--p-surface-600);
}

.screen-item__body {
  /* flex 아이템의 자동 최소 크기는 내용 기반이다. 긴 전략 이름이 버튼을 밀어내지 않게 한다. */
  min-width: 0;
}

.screen-item__title {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  flex-wrap: wrap;
  overflow-wrap: anywhere;
}

.screen-item__description {
  margin: 0.25rem 0 0;
  font-size: var(--app-text-sm);
  color: var(--p-text-muted-color);
}

.screen-item__actions {
  display: flex;
  gap: 0.25rem;
  flex-shrink: 0;
}

@media (max-width: 640px) {
  .screen-item {
    flex-direction: column;
    align-items: stretch;
  }

  .screen-item__actions {
    justify-content: flex-end;
  }
}
</style>
