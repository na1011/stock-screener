<script setup lang="ts">
import Button from 'primevue/button'
import Tag from 'primevue/tag'
import { useConfirm } from 'primevue/useconfirm'
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'

import type { ScreenResponse } from '@/api/types'
import AsyncPanel from '@/components/AsyncPanel.vue'
import { panelSubject } from '@/components/asyncPanel'
import { useApiToast } from '@/composables/useApiToast'
import { evaluationModeLabels } from '@/lib/evaluationMode'
import { screenTypeLabel } from '@/lib/screen'
import { useScreenDraftStore } from '@/stores/screenDraft'
import { useScreensStore } from '@/stores/screens'

/**
 * 저장된 화면과 프리셋 목록.
 *
 * 프리셋은 읽기 전용으로 보이고 남의 화면은 애초에 목록에 오지 않는다(서버가 담지 않는다).
 * "불러오기"는 조건까지 받아 편집기에 올린 뒤 스크리너로 보낸다 — 목록 응답에는 조건이
 * 없으므로(`conditions`가 null) 이 한 번의 추가 요청이 필요하다.
 */
const screens = useScreensStore()
const draft = useScreenDraftStore()
const router = useRouter()
const confirm = useConfirm()
const { notifyFailure, notifySuccess } = useApiToast()

const busyId = ref<number | null>(null)

onMounted(() => {
  void screens.ensureLoaded()
})

async function load(screen: ScreenResponse): Promise<void> {
  if (screen.id === null) {
    return
  }
  busyId.value = screen.id
  try {
    draft.loadFrom(await screens.open(screen.id))
    await router.push({ name: 'screener' })
  } catch (caught) {
    notifyFailure('불러오지 못했습니다', caught)
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
    header: '화면 삭제',
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
    <h1 class="screens__title">{{ panelSubject('screens') }}</h1>

    <AsyncPanel
      :state="screens.state"
      :error="screens.error"
      :retry="screens.retry"
      panel="screens"
      :skeleton-rows="4"
    >
      <section class="screens__group">
        <h2 class="screens__group-title">{{ screenTypeLabel('PRESET') }}</h2>
        <p class="screens__group-note">
          미리 정의된 전략입니다. 고칠 수는 없지만 불러온 뒤 복제해 내 화면으로 저장할 수 있습니다.
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
        <h2 class="screens__group-title">{{ screenTypeLabel('CUSTOM') }}</h2>
        <p v-if="screens.mine.length === 0" class="screens__empty">
          저장한 화면이 없습니다. 스크리너에서 조건을 만든 뒤 저장해 보세요.
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
  font-size: 1.25rem;
  letter-spacing: -0.01em;
  margin: 0 0 1.5rem;
}

.screens__group {
  margin-bottom: 2rem;
}

.screens__group-title {
  font-size: 0.75rem;
  font-weight: 600;
  letter-spacing: 0.04em;
  color: var(--p-text-muted-color);
  margin: 0 0 0.25rem;
}

.screens__group-note,
.screens__empty {
  margin: 0 0 0.75rem;
  font-size: 0.8125rem;
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
  background: var(--p-content-background);
  transition: border-color 0.15s;
}

.screen-item:hover {
  border-color: var(--p-surface-600);
}

.screen-item__body {
  /* flex 아이템의 자동 최소 크기는 내용 기반이다. 긴 화면 이름이 버튼을 밀어내지 않게 한다. */
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
  font-size: 0.8125rem;
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
