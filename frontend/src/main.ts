import { createApp } from 'vue'
import { createPinia } from 'pinia'
import PrimeVue from 'primevue/config'
import Aura from '@primeuix/themes/aura'
import 'primeicons/primeicons.css'

import App from './App.vue'
import { router } from './router'
import './assets/main.css'

const app = createApp(App)

app.use(createPinia())
app.use(router)
app.use(PrimeVue, {
  theme: {
    preset: Aura,
    options: {
      // 기본값은 'system'이라 OS 설정만으로 다크 모드가 켜진다. v1은 라이트 한 벌만
      // 손보므로, 존재하지 않는 선택자를 지정해 의도치 않은 전환을 막는다.
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

app.mount('#app')
