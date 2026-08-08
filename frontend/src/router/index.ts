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
        // 관리자 영역. `meta.admin`이 아래 가드의 축이고, 권한이 없으면 조용히 되돌린다.
        {
          path: 'admin',
          name: 'admin',
          component: () => import('@/views/AdminOverviewView.vue'),
          meta: { admin: true },
        },
        {
          path: 'admin/logs',
          name: 'admin-logs',
          component: () => import('@/views/AdminInteractionsView.vue'),
          meta: { admin: true },
        },
        {
          path: 'admin/accounts',
          name: 'admin-accounts',
          component: () => import('@/views/AdminAccountsView.vue'),
          meta: { admin: true },
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

  /*
   * IMPORTANT — 권한 없는 사용자를 403 화면이나 404로 보내지 않고 **조용히 되돌린다.**
   *
   * spec §4와 frontend.md가 요구하는 것은 "권한이 없는 사용자에게 노출되지 않는다"이고,
   * 거절 화면은 그 자체로 "여기 무언가 있다"를 알려준다. 레일에서 항목을 감추는 것과 같은
   * 이유이며, 주소를 직접 쳐 보는 경로에서 그 노력이 무의미해지면 안 된다.
   *
   * 인가의 실제 강제는 서버다(`/api/admin/**`는 ROLE_ADMIN). 여기서 하는 것은 존재를
   * 드러내지 않는 것뿐이고, 이 가드를 지워도 데이터는 새지 않는다.
   */
  if (to.meta.admin === true && !auth.isAdmin) {
    return { name: 'screener', replace: true }
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
