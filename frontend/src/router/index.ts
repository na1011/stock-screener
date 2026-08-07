import { createRouter, createWebHistory } from 'vue-router'

/**
 * 히스토리 모드를 쓴다. 서버가 SPA 폴백을 하므로 깊은 링크 새로고침이 index.html로 온다
 * (백엔드 {@code WebMvcConfig}). 해시 모드는 그 폴백을 불필요하게 만드는 대신 주소가
 * 지저분해지고, 이 서비스는 종목 상세 주소를 그대로 공유할 일이 있다.
 */
export const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/',
      name: 'screener',
      component: () => import('@/views/ScreenerView.vue'),
    },
    {
      path: '/:pathMatch(.*)*',
      name: 'not-found',
      component: () => import('@/views/NotFoundView.vue'),
    },
  ],
  scrollBehavior() {
    return { top: 0 }
  },
})
