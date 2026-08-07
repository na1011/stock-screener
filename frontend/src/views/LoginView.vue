<script setup lang="ts">
import Button from 'primevue/button'
import Message from 'primevue/message'
import { computed, ref } from 'vue'
import { useRoute } from 'vue-router'

import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const route = useRoute()

/**
 * OAuth 실패는 `/login?error`로 돌아온다.
 *
 * IMPORTANT — 사유를 밝히지 않는다. 실패 원인은 대개 "허용목록에 없는 계정"인데, 그것을
 * 알려주면 허용목록의 존재 자체가 드러난다(ADR-0004). 지인이 쓰는 서비스라도 경계는 유지한다.
 */
const loginFailed = computed(() => 'error' in route.query)

/** 로그인 뒤 돌아갈 곳. 가드가 쿼리에 실어 보낸다. */
const redirectTo = computed(() => {
  const value = route.query.redirect
  return typeof value === 'string' ? value : '/'
})

const retrying = ref(false)

async function retryProbe(): Promise<void> {
  retrying.value = true
  try {
    await auth.probe()
  } finally {
    retrying.value = false
  }
}
</script>

<template>
  <main class="login">
    <section class="login__card">
      <h1 class="login__title">주식 스크리너</h1>
      <p class="login__subtitle">코스피200·코스닥150 종목을 기술적·재무적 조건으로 걸러 봅니다.</p>

      <Message v-if="loginFailed" severity="error" :closable="false" class="login__message">
        로그인할 수 없습니다. 접근 권한이 있는 계정인지 확인해 주세요.
      </Message>

      <template v-if="auth.status === 'unavailable'">
        <!-- 서버가 답하지 않은 것과 로그인하지 않은 것은 다르다. 여기서 로그인 버튼만
             보여주면 사용자는 자기 계정 문제로 읽고 로그인을 반복한다. -->
        <Message severity="warn" :closable="false" class="login__message">
          <div>서버에 연결하지 못했습니다.</div>
          <div class="login__detail">{{ auth.error?.userMessage }}</div>
        </Message>
        <Button
          label="다시 시도"
          icon="pi pi-refresh"
          outlined
          :loading="retrying"
          class="login__button"
          @click="retryProbe"
        />
      </template>

      <Button
        v-else
        label="Google 계정으로 로그인"
        icon="pi pi-google"
        class="login__button"
        @click="auth.signIn(redirectTo)"
      />

      <p class="login__note">허가된 계정만 이용할 수 있습니다.</p>
    </section>
  </main>
</template>

<style scoped>
.login {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 100dvh;
  padding: 1.5rem;
}

.login__card {
  width: 100%;
  max-width: 26rem;
  text-align: center;
}

.login__title {
  font-size: 1.5rem;
  margin: 0 0 0.5rem;
}

.login__subtitle {
  color: var(--p-text-muted-color);
  font-size: 0.9375rem;
  margin: 0 0 2rem;
}

.login__message {
  margin-bottom: 1rem;
  text-align: left;
}

.login__detail {
  font-size: 0.8125rem;
  opacity: 0.85;
  margin-top: 0.25rem;
}

.login__button {
  width: 100%;
}

.login__note {
  color: var(--p-text-muted-color);
  font-size: 0.8125rem;
  margin-top: 1.5rem;
}
</style>
