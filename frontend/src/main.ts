import { createApp } from 'vue'
import { createPinia } from 'pinia'
import PrimeVue from 'primevue/config'
import ConfirmationService from 'primevue/confirmationservice'
import ToastService from 'primevue/toastservice'
import Tooltip from 'primevue/tooltip'
import 'primeicons/primeicons.css'

import App from './App.vue'
import { initMonitoring } from './lib/monitoring'
import { router } from './router'
import { installUnauthorizedHandler } from './stores/auth'
import { ScreenerPreset } from './theme/preset'
import './assets/main.css'

const app = createApp(App)

// 라우터·플러그인보다 먼저 건다. 초기화 중에 던진 오류도 잡아야 하고, Sentry의 Vue
// 통합은 app의 errorHandler를 감싸는 방식이라 나중에 걸면 그 사이에 등록된 것을 놓친다.
if (!initMonitoring(app)) {
  // 조용히 안 켜지는 것이 이 배선의 유일한 실패 방식이다. 한 줄 남겨 "DSN을 채웠는데
  // 이슈가 안 뜬다"와 "애초에 안 켜졌다"를 콘솔에서 가를 수 있게 한다.
  console.info('[monitoring] VITE_SENTRY_DSN이 없어 오류 수집을 켜지 않았다.')
}

app.use(createPinia())
// 라우터보다 먼저 등록한다. 첫 이동에서 이미 401이 날 수 있다.
installUnauthorizedHandler()
app.use(router)
app.directive('tooltip', Tooltip)
app.use(PrimeVue, {
  theme: {
    // 색·간격·반경의 정본은 `theme/preset.ts`다. Aura는 그 바탕일 뿐이다.
    preset: ScreenerPreset,
    options: {
      /**
       * 다크 한 벌만 만든다(ADR-0021). `index.html`의 `<html class="app-dark">`가 항상 켜 둔다.
       *
       * 기본값 `'system'`을 쓰지 않는 이유는 그러면 OS 설정에 따라 **손보지 않은 라이트
       * 팔레트**로 떨어지기 때문이다. 선택자를 고정하면 OS가 무엇이든 화면은 하나다.
       */
      darkModeSelector: '.app-dark',
    },
  },
  // 서식·라벨의 정본은 서버다(지표 라벨, 연산자 한글명). 여기서는 컴포넌트가 자체적으로
  // 그리는 문구만 한국어로 맞춘다.
  locale: {
    emptyMessage: '결과가 없습니다',
    emptyFilterMessage: '조건에 맞는 항목이 없습니다',
    emptySelectionMessage: '선택된 항목이 없습니다',
    clear: '초기화',
    apply: '적용',
    choose: '선택',
    cancel: '취소',
  },
})

app.use(ToastService)
app.use(ConfirmationService)

app.mount('#app')
