import { fileURLToPath, URL } from 'node:url'

import { sentryVitePlugin } from '@sentry/vite-plugin'
import vue from '@vitejs/plugin-vue'
import type { PluginOption } from 'vite'
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

/**
 * 소스맵을 Sentry에 올린다. **셋이 다 있을 때만** 켠다.
 *
 * 슬러그는 시크릿이 아니라 대시보드 주소에 보이는 조회값이고, 토큰만 시크릿이다. 값이 없어도
 * 빌드는 성공해야 한다 — 발급은 사람 손에 달려 있고 그것 때문에 빌드가 멈추면 안 된다.
 *
 * MUST: 건너뛸 때 한 줄 남긴다. 조용히 건너뛰면 "소스맵이 올라간 줄 알았는데 안 올라간"
 * 상태가 되고, 그 사실은 진짜 오류가 나서 이슈를 열어 봤을 때에야 드러난다.
 */
function sourcemapUpload(): PluginOption {
  const org = process.env.SENTRY_ORG
  const project = process.env.SENTRY_PROJECT_FRONTEND
  const authToken = process.env.SENTRY_AUTH_TOKEN
  if (!org || !project || !authToken) {
    const missing = [
      !org && 'SENTRY_ORG',
      !project && 'SENTRY_PROJECT_FRONTEND',
      !authToken && 'SENTRY_AUTH_TOKEN',
    ].filter(Boolean)
    console.warn(
      `[sentry] ${missing.join('·')}이(가) 없어 소스맵을 올리지 않는다. ` +
        '이슈의 스택트레이스는 minify된 채로 남는다.',
    )
    return null
  }
  return sentryVitePlugin({
    org,
    project,
    authToken,
    /**
     * IMPORTANT — 기본 동작은 업로드가 실패해도 **에러만 찍고 빌드를 통과시킨다**
     * (2026-08-09 실측: 없는 프로젝트로 올리면 `Project not found`를 찍고 exit 0).
     * 그러면 슬러그 오타나 회수된 토큰이 초록 빌드 뒤에 숨고, 그 사실은 진짜 오류가 나서
     * 이슈를 열었을 때 minify된 스택트레이스로만 드러난다 — 이 배선이 막으려던 바로 그것이다.
     * 값을 채운 것은 명시적 선택이므로, 채운 뒤 실패하면 시끄럽게 죽는 편이 맞다.
     */
    errorHandler: (error) => {
      throw error
    },
    sourcemaps: {
      // MUST: 올린 뒤 지운다. 남기면 processResources가 배포 jar에 싣고 프론트 소스가
      // 통째로 공개된다. build.gradle의 exclude '**/*.map'이 같은 것을 한 겹 더 막지만,
      // 두 겹인 이유는 어느 한쪽이 사라져도 소스가 새지 않아야 하기 때문이다.
      filesToDeleteAfterUpload: ['./dist/**/*.map'],
    },
  })
}

export default defineConfig(({ command }) => ({
  /**
   * 소스맵 업로드는 `vite build`에만 붙인다.
   *
   * IMPORTANT — 이 config는 `vite build` 말고도 로드된다. 개발 서버(`npm run dev`)와
   * **Vitest**(`npm run test:unit`)가 같은 파일을 읽으므로, 조건 없이 넣으면 단위 테스트를
   * 돌릴 때마다 Sentry에 릴리스가 만들어진다. 실제로 `./gradlew build` 한 번에 경고가 두 번
   * 찍히는 것으로 드러났다 — 하나는 frontendBuild, 다른 하나는 frontendTest였다.
   */
  plugins: [vue(), command === 'build' ? sourcemapUpload() : null],
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
    /**
     * 'hidden'이다 — 맵을 만들되 번들 끝에 `//# sourceMappingURL` 주석을 남기지 않는다.
     *
     * true로 두면 브라우저가 그 주석을 따라 `/assets/index-a3f9.js.map`을 요청하고, 그것이
     * 서빙되면 프론트 소스가 통째로 공개된다. 맵은 Sentry에 올리기 위해서만 만든다.
     * false로 두면 올릴 것이 없어 스택트레이스가 `index-a3f9.js:1:28471`로만 남는다.
     */
    sourcemap: 'hidden',
  },
  test: {
    // jsdom을 쓰지 않는다. 검증 대상이 재시도 판별·서식·상태 매핑 같은 순수 로직이고,
    // document.cookie를 읽는 곳은 테스트가 전역을 직접 대체한다. DOM 런타임을 얹으면
    // 의존성만 늘고 검증되는 것은 같다.
    environment: 'node',
    include: ['src/**/*.spec.ts'],
  },
}))
