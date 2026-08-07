import { defineStore } from 'pinia'
import { computed, ref, shallowRef } from 'vue'

import { api } from '@/api/endpoints'
import { ApiError, readCookie, setUnauthorizedHandler } from '@/api/http'
import type { MeResponse } from '@/api/types'

/**
 * 로그인하려던 경로를 담아두는 자리.
 *
 * OAuth는 전체 페이지 이동이라 앱이 통째로 다시 뜬다. 메모리에 담으면 그 시점에 사라지고,
 * 사용자는 종목 상세 링크를 눌렀다가 로그인 뒤 홈으로 떨어진다.
 */
const REDIRECT_KEY = 'stock-screener:redirect-after-login'

/**
 * 세션 상태.
 *
 * - `unknown` — 아직 물어보지 않았다.
 * - `authenticated` — 로그인되어 있다.
 * - `anonymous` — 로그인되어 있지 않다(서버가 401을 줬다).
 * - `unavailable` — 물어봤는데 서버가 답하지 못했다.
 *
 * IMPORTANT — `anonymous`와 `unavailable`을 합치지 않는다. 백엔드가 죽었을 때 "로그인하세요"를
 * 띄우면 사용자는 로그인을 반복하다 포기한다. 원인이 자기 계정에 있다고 읽기 때문이다.
 */
export type SessionStatus = 'unknown' | 'authenticated' | 'anonymous' | 'unavailable'

export const useAuthStore = defineStore('auth', () => {
  const status = ref<SessionStatus>('unknown')
  const me = shallowRef<MeResponse | null>(null)
  const error = shallowRef<ApiError | null>(null)

  const isAuthenticated = computed(() => status.value === 'authenticated')
  const isAdmin = computed(() => me.value?.admin === true)
  /** 이름을 주지 않는 계정이 있다. 그때는 이메일이 곧 표시 이름이다. */
  const displayName = computed(() => me.value?.name ?? me.value?.email ?? '')

  async function probe(): Promise<void> {
    try {
      me.value = await api.me()
      error.value = null
      status.value = 'authenticated'
    } catch (caught) {
      if (!(caught instanceof ApiError)) {
        status.value = 'unavailable'
        throw caught
      }
      me.value = null
      if (caught.status === 401) {
        error.value = null
        status.value = 'anonymous'
        return
      }
      error.value = caught
      status.value = 'unavailable'
    }
  }

  /** 어느 요청에서든 401이 나면 불린다. 상태만 바꾸고 이동은 라우터 가드가 한다. */
  function markSignedOut(): void {
    me.value = null
    error.value = null
    status.value = 'anonymous'
  }

  /**
   * Google 로그인으로 보낸다. SPA 내부 이동이 아니라 **전체 페이지 이동**이다 —
   * OAuth 흐름은 서버 리다이렉트로 진행되므로 fetch로는 성립하지 않는다.
   */
  function signIn(redirectTo?: string): void {
    if (redirectTo && redirectTo !== '/login') {
      sessionStorage.setItem(REDIRECT_KEY, redirectTo)
    }
    window.location.assign('/oauth2/authorization/google')
  }

  function consumeRedirect(): string | null {
    const stored = sessionStorage.getItem(REDIRECT_KEY)
    sessionStorage.removeItem(REDIRECT_KEY)
    return stored
  }

  /**
   * 로그아웃.
   *
   * fetch가 아니라 폼 POST인 이유는 서버가 302로 응답하기 때문이다. fetch는 그 리다이렉트를
   * 따라가 HTML을 받아오고, 브라우저 주소는 그대로 남아 화면이 로그인된 것처럼 보인다.
   * 폼으로 보내면 브라우저가 이동까지 처리한다. CSRF 토큰은 파라미터로 싣는다.
   */
  function signOut(): void {
    const form = document.createElement('form')
    form.method = 'POST'
    form.action = '/logout'

    const token = readCookie('XSRF-TOKEN')
    if (token !== null) {
      const input = document.createElement('input')
      input.type = 'hidden'
      input.name = '_csrf'
      input.value = token
      form.appendChild(input)
    }

    document.body.appendChild(form)
    form.submit()
  }

  return {
    status,
    me,
    error,
    isAuthenticated,
    isAdmin,
    displayName,
    probe,
    markSignedOut,
    signIn,
    signOut,
    consumeRedirect,
  }
})

/**
 * 401 처리기를 등록한다. 앱 기동 시 한 번만 부른다.
 *
 * 전역 하나로 두는 이유는 401이 어느 요청에서든 나올 수 있어서다. 호출부마다 처리하면
 * 한 곳을 빠뜨렸을 때 그 화면만 만료된 세션으로 빈 채 남는다.
 */
export function installUnauthorizedHandler(): void {
  const auth = useAuthStore()
  setUnauthorizedHandler(() => auth.markSignedOut())
}
