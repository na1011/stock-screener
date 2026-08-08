<script setup lang="ts">
import Button from 'primevue/button'
import InputText from 'primevue/inputtext'
import Message from 'primevue/message'
import Tag from 'primevue/tag'
import ToggleSwitch from 'primevue/toggleswitch'
import { useConfirm } from 'primevue/useconfirm'
import { onMounted, ref } from 'vue'

import { ALLOWED_EMAIL_NOTE_MAX_LENGTH } from '@/api/limits'
import type { AllowedEmail } from '@/api/types'
import AdminNav from '@/components/admin/AdminNav.vue'
import AsyncPanel from '@/components/AsyncPanel.vue'
import FormField from '@/components/ui/FormField.vue'
import SectionHeading from '@/components/ui/SectionHeading.vue'
import { useApiToast } from '@/composables/useApiToast'
import { useAdminStore } from '@/stores/admin'
import { useAuthStore } from '@/stores/auth'

/**
 * 계정 관리 (spec §4 "접근이 허용된 이메일 목록을 추가·삭제하고 관리자 권한 부여 여부를 설정").
 *
 * 이 화면이 생기기 전까지 지인을 초대하는 방법이 psql뿐이었다.
 *
 * 마지막 관리자 보호는 **서버가 한다.** 화면이 미리 막지 않는 이유는 판정 근거(관리자 총수)가
 * 화면에 다 있지 않을 수 있고, 무엇보다 규칙이 두 곳에 생기면 한쪽만 고쳐지기 때문이다.
 * 여기서는 서버가 준 409를 그대로 사용자 말로 옮긴다.
 */
const admin = useAdminStore()
const auth = useAuthStore()
const confirm = useConfirm()
const { notifyFailure, notifySuccess } = useApiToast()

const newEmail = ref('')
const newAdmin = ref(false)
const newNote = ref('')
const adding = ref(false)
const busyId = ref<number | null>(null)

onMounted(() => {
  void admin.ensureAccountsLoaded()
})

async function add(): Promise<void> {
  if (newEmail.value.trim() === '') {
    return
  }
  adding.value = true
  try {
    await admin.addAccount({
      email: newEmail.value.trim(),
      admin: newAdmin.value,
      note: newNote.value.trim() === '' ? null : newNote.value.trim(),
    })
    notifySuccess('추가했습니다', newEmail.value.trim())
    newEmail.value = ''
    newAdmin.value = false
    newNote.value = ''
  } catch (caught) {
    notifyFailure('추가하지 못했습니다', caught)
  } finally {
    adding.value = false
  }
}

async function toggleAdmin(account: AllowedEmail, next: boolean): Promise<void> {
  busyId.value = account.id
  try {
    await admin.updateAccount(account.id, { admin: next, note: account.note })
    notifySuccess(next ? '관리자 권한을 주었습니다' : '관리자 권한을 회수했습니다', account.email)
  } catch (caught) {
    // 마지막 관리자면 서버가 409(LAST_ADMIN)를 준다. 목록을 되돌려 화면과 서버를 맞춘다 —
    // 안 되돌리면 스위치만 바뀐 채로 남아 권한이 바뀐 것처럼 보인다.
    await admin.ensureAccountsLoaded()
    notifyFailure('권한을 바꾸지 못했습니다', caught)
  } finally {
    busyId.value = null
  }
}

function confirmRemove(account: AllowedEmail): void {
  confirm.require({
    header: '계정 삭제',
    message: `"${account.email}"의 접근 권한을 지웁니다. 저장한 전략과 관심종목은 남습니다.`,
    icon: 'pi pi-exclamation-triangle',
    acceptLabel: '삭제',
    rejectLabel: '취소',
    acceptProps: { severity: 'danger' },
    accept: () => void remove(account),
  })
}

async function remove(account: AllowedEmail): Promise<void> {
  busyId.value = account.id
  try {
    await admin.removeAccount(account.id)
    notifySuccess('삭제했습니다', account.email)
  } catch (caught) {
    notifyFailure('삭제하지 못했습니다', caught)
  } finally {
    busyId.value = null
  }
}

function formatDate(value: string | null): string {
  if (value === null) {
    return '—'
  }
  return new Date(value).toLocaleDateString('ko-KR', { dateStyle: 'medium' })
}
</script>

<template>
  <section class="accounts">
    <AdminNav />

    <!--
      권한 회수가 즉시 반영되지 않는다는 사실을 화면이 말해야 한다. app_user.is_admin이
      로그인 시점의 사본이기 때문이고(ADR-0004), 모르면 "왜 아직 그대로인가"를 버그로 읽는다.
    -->
    <Message severity="secondary" :closable="false" class="accounts__note">
      권한 변경은 그 사람의 <strong>다음 로그인부터</strong> 적용됩니다. 이미 로그인한 세션은
      그대로 유지됩니다.
    </Message>

    <SectionHeading title="계정 추가" />
    <form class="accounts__form" @submit.prevent="add">
      <FormField label="이메일" control-id="new-email" hint="Google 계정 주소를 그대로 넣습니다.">
        <InputText
          id="new-email"
          v-model="newEmail"
          type="email"
          placeholder="friend@example.com"
          autocomplete="off"
        />
      </FormField>
      <FormField label="메모" control-id="new-note" hint="누구인지 나중에 알아볼 수 있게.">
        <InputText
          id="new-note"
          v-model="newNote"
          :maxlength="ALLOWED_EMAIL_NOTE_MAX_LENGTH"
          placeholder="지인"
        />
      </FormField>
      <div class="accounts__admin-toggle">
        <ToggleSwitch v-model="newAdmin" input-id="new-admin" />
        <label for="new-admin">관리자 권한</label>
      </div>
      <Button
        type="submit"
        label="추가"
        icon="pi pi-plus"
        size="small"
        :loading="adding"
        :disabled="newEmail.trim() === ''"
      />
    </form>

    <SectionHeading title="허용된 계정" :count="admin.accounts.length" />
    <AsyncPanel
      :state="admin.accountsState"
      :error="admin.accountsError"
      :retry="admin.retryAccounts"
      panel="accounts"
      :skeleton-rows="3"
    >
      <ul class="accounts__list">
        <li v-for="account in admin.accounts" :key="account.id" class="account">
          <div class="account__body">
            <div class="account__title">
              <strong>{{ account.email }}</strong>
              <Tag v-if="account.admin" value="관리자" severity="info" />
              <Tag v-if="account.email === auth.me?.email" value="나" severity="secondary" />
            </div>
            <p class="account__meta">
              <span v-if="account.note">{{ account.note }} · </span>
              <!--
                아직 로그인하지 않은 사람이 있는 것이 정상 상태다(ADR-0004). 그 둘을 구분해야
                초대장을 다시 보낼 대상을 알 수 있다.
              -->
              <span v-if="account.lastLoginAt === null" class="account__pending">
                아직 로그인한 적 없음
              </span>
              <span v-else>마지막 로그인 {{ formatDate(account.lastLoginAt) }}</span>
            </p>
          </div>
          <div class="account__actions">
            <div class="account__admin-toggle">
              <ToggleSwitch
                :model-value="account.admin"
                :input-id="`admin-${account.id}`"
                :disabled="busyId === account.id"
                @update:model-value="(next: boolean) => toggleAdmin(account, next)"
              />
              <label :for="`admin-${account.id}`" class="account__toggle-label">관리자</label>
            </div>
            <Button
              icon="pi pi-trash"
              severity="danger"
              text
              size="small"
              :aria-label="`${account.email} 삭제`"
              :loading="busyId === account.id"
              @click="confirmRemove(account)"
            />
          </div>
        </li>
      </ul>
      <p v-if="admin.accounts.length === 0" class="accounts__empty">
        허용된 계정이 없습니다.
      </p>
    </AsyncPanel>
  </section>
</template>

<style scoped>
.accounts {
  max-width: 64rem;
  margin: 0 auto;
  padding: 1.5rem var(--app-gutter) 3rem;
}

.accounts__note {
  margin-bottom: 1.5rem;
}

.accounts__form {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-end;
  gap: 0.75rem;
  margin-bottom: 1.5rem;
}

.accounts__admin-toggle,
.account__admin-toggle {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  font-size: var(--app-text-sm);
}

.accounts__list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

.account {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 1rem;
  padding: 0.75rem 0.875rem;
  border: 1px solid var(--p-content-border-color);
  border-radius: var(--p-border-radius-md);
  background: var(--app-surface-raised);
  box-shadow: var(--app-edge-highlight);
}

.account__body {
  min-width: 0;
}

.account__title {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  flex-wrap: wrap;
  overflow-wrap: anywhere;
}

.account__meta {
  margin: 0.25rem 0 0;
  font-size: var(--app-text-sm);
  color: var(--p-text-muted-color);
}

.account__pending {
  color: var(--p-text-subtle-color);
}

.account__actions {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  flex-shrink: 0;
}

.account__toggle-label {
  color: var(--p-text-muted-color);
}

.accounts__empty {
  margin: 0;
  font-size: var(--app-text-sm);
  color: var(--p-text-muted-color);
}

@media (max-width: 640px) {
  .account {
    flex-direction: column;
    align-items: stretch;
  }

  .account__actions {
    justify-content: space-between;
  }
}
</style>
