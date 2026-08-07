<script setup lang="ts">
import Button from 'primevue/button'
import Message from 'primevue/message'
import { computed, ref } from 'vue'
import { useRoute } from 'vue-router'

import { NETWORK_ERROR_MESSAGE } from '@/api/http'
import BrandMark from '@/components/BrandMark.vue'
import { APP_NAME } from '@/lib/appInfo'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const route = useRoute()

/**
 * 머리글 아래 덧붙일 상세.
 *
 * 네트워크 오류면 `userMessage`가 머리글과 **같은 문장**이라 두 줄이 겹쳐 보였다. 다를 때만
 * 낸다 — 5xx나 타임아웃이면 서버가 준 말이 붙는다.
 */
const unavailableDetail = computed(() => {
  const message = auth.error?.userMessage ?? null
  return message === NETWORK_ERROR_MESSAGE ? null : message
})

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
      <BrandMark size="1.75rem" class="login__mark" />
      <h1 class="login__title">{{ APP_NAME }}</h1>
      <p class="login__subtitle">코스피200·코스닥150 종목을 기술적·재무적 조건으로 걸러 봅니다.</p>

      <Message v-if="loginFailed" severity="error" :closable="false" class="login__message">
        로그인할 수 없습니다. 접근 권한이 있는 계정인지 확인해 주세요.
      </Message>

      <template v-if="auth.status === 'unavailable'">
        <!-- 서버가 답하지 않은 것과 로그인하지 않은 것은 다르다. 여기서 로그인 버튼만
             보여주면 사용자는 자기 계정 문제로 읽고 로그인을 반복한다. -->
        <Message severity="warn" :closable="false" class="login__message">
          <div>{{ NETWORK_ERROR_MESSAGE }}</div>
          <div v-if="unavailableDetail" class="login__detail">{{ unavailableDetail }}</div>
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
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 100dvh;
  padding: 1.5rem;
  overflow: hidden;
}

/* 이 화면은 카드 하나뿐이라 여백이 그대로 허전함이 된다. 위에서 내려오는 옅은 광원
   하나로 공간에 방향을 준다. 장식이 아니라 시선을 카드로 모으는 장치다. */
.login::before {
  content: '';
  position: absolute;
  inset: 0;
  pointer-events: none;
  background: radial-gradient(
    60rem 34rem at 50% -8%,
    color-mix(in srgb, var(--p-primary-color) 14%, transparent),
    transparent 68%
  );
}

.login__card {
  position: relative;
  width: 100%;
  max-width: 24rem;
  padding: 2.25rem 2rem;
  text-align: center;
  border: 1px solid var(--p-content-border-color);
  border-radius: var(--p-border-radius-lg);
  background: var(--p-content-background);
  box-shadow: 0 24px 56px -24px rgb(0 0 0 / 70%);
}

.login__mark {
  margin: 0 auto 0.875rem;
}

.login__title {
  font-size: var(--app-text-xl);
  letter-spacing: -0.01em;
  margin: 0 0 0.5rem;
}

.login__subtitle {
  color: var(--p-text-muted-color);
  font-size: var(--app-text-md);
  line-height: 1.6;
  margin: 0 0 1.75rem;
}

.login__message {
  margin-bottom: 1rem;
  text-align: left;
}

.login__detail {
  font-size: var(--app-text-sm);
  opacity: 0.85;
  margin-top: 0.25rem;
}

.login__button {
  width: 100%;
}

.login__note {
  color: var(--p-text-muted-color);
  font-size: var(--app-text-sm);
  margin-top: 1.5rem;
}
</style>
