import { fileURLToPath, URL } from 'node:url'

import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vitest/config'

/**
 * 개발 서버는 :5173, 백엔드는 :8080이고 그 사이를 프록시가 잇는다.
 *
 * 인증이 세션 쿠키 기반이라 오리진이 갈리면 로그인 자체가 성립하지 않는다. 쿠키는 포트를
 * 구분하지 않으므로 :8080이 내려보낸 JSESSIONID·XSRF-TOKEN이 :5173에서도 그대로 실린다.
 *
 * IMPORTANT — changeOrigin을 켜지 않는다. 켜면 Host가 localhost:8080으로 바뀌고,
 * Spring이 그 Host로 redirect_uri를 만들어 Google에 보낸다. 그러면 인증 후 브라우저가
 * :8080으로 튕겨 나가 개발 서버를 벗어난다. Host를 그대로 두어야 redirect_uri가
 * http://localhost:5173/login/oauth2/code/google이 되고 :5173 안에서 흐름이 닫힌다.
 * 그 주소는 Google Cloud Console의 승인된 리디렉션 URI에 등록되어 있어야 한다.
 *
 * MUST NOT: '/login'을 통째로 프록시하지 않는다. '/login'은 SPA 라우트이기도 해서
 * 프록시하면 새로고침이 백엔드의 (개발 중에는 낡았거나 없는) 정적 번들로 간다.
 * 백엔드가 실제로 처리하는 것은 OAuth 콜백인 '/login/oauth2/**'뿐이다.
 */
const BACKEND = 'http://localhost:8080'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    port: 5173,
    strictPort: true,
    proxy: {
      '/api': { target: BACKEND, changeOrigin: false },
      '/oauth2': { target: BACKEND, changeOrigin: false },
      '/login/oauth2': { target: BACKEND, changeOrigin: false },
      '/logout': { target: BACKEND, changeOrigin: false },
    },
  },
  build: {
    // Gradle의 processResources가 이 디렉터리를 classpath의 static/으로 복사한다.
    outDir: 'dist',
    emptyOutDir: true,
  },
  test: {
    // jsdom을 쓰지 않는다. 검증 대상이 재시도 판별·서식·상태 매핑 같은 순수 로직이고,
    // document.cookie를 읽는 곳은 테스트가 전역을 직접 대체한다. DOM 런타임을 얹으면
    // 의존성만 늘고 검증되는 것은 같다.
    environment: 'node',
    include: ['src/**/*.spec.ts'],
  },
})
