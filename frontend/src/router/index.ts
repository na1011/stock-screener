import { createRouter, createWebHistory } from 'vue-router'

import AppLayout from '@/layouts/AppLayout.vue'
import { useAuthStore } from '@/stores/auth'

/**
 * 히스토리 모드를 쓴다. 서버가 SPA 폴백을 하므로 깊은 링크 새로고침이 index.html로 온다
 * (백엔드 {@code WebMvcConfig}). 해시 모드는 그 폴백을 불필요하게 만드는 대신 주소가
 * 지저분해지고, 이 서비스는 종목 상세 주소를 그대로 공유할 일이 있다.
 */
export const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/login',
      name: 'login',
      component: () => import('@/views/LoginView.vue'),
      meta: { public: true },
    },
    {
      path: '/',
      component: AppLayout,
      children: [
        { path: '', name: 'screener', component: () => import('@/views/ScreenerView.vue') },
        { path: 'screens', name: 'screens', component: () => import('@/views/ScreensView.vue') },
        {
          path: 'watchlist',
          name: 'watchlist',
          component: () => import('@/views/WatchlistView.vue'),
        },
        {
          path: 'stocks/:stockId(\\d+)',
          name: 'stock',
          component: () => import('@/views/StockDetailView.vue'),
          props: true,
        },
      ],
    },
    {
      path: '/:pathMatch(.*)*',
      name: 'not-found',
      component: () => import('@/views/NotFoundView.vue'),
      meta: { public: true },
    },
  ],
  scrollBehavior() {
    return { top: 0 }
  },
})

/**
 * 세션 확인과 이동 통제.
 *
 * 서버는 `/api/**`에만 인증을 요구하고 정적 셸은 익명에게 연다(ADR-0018). 그래서 "로그인하지
 * 않은 사용자를 로그인 화면으로 보내는 일"은 전적으로 여기 몫이다 — 없으면 익명 사용자가
 * 스크리너 화면을 보고 모든 패널이 401로 실패하는 것을 보게 된다.
 */
router.beforeEach(async (to) => {
  const auth = useAuthStore()

  if (auth.status === 'unknown') {
    await auth.probe()
  }

  if (to.meta.public === true) {
    // 이미 로그인했는데 로그인 화면으로 가면 되돌린다. 뒤로가기로 여기 오는 경우가 그렇다.
    if (to.name === 'login' && auth.isAuthenticated) {
      return auth.consumeRedirect() ?? '/'
    }
    return true
  }

  if (!auth.isAuthenticated) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }

  // OAuth를 마치면 서버가 항상 '/'로 돌려보낸다. 원래 가려던 곳은 그때만 되살린다 —
  // 아무 이동에서나 되살리면 사용자가 직접 누른 링크를 가로챈다.
  if (to.path === '/') {
    const pending = auth.consumeRedirect()
    if (pending !== null && pending !== to.fullPath) {
      return pending
    }
  }

  return true
})
