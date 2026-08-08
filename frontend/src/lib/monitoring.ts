import * as Sentry from '@sentry/vue'
import type { App } from 'vue'

/**
 * 프론트 오류를 Sentry로 보낸다.
 *
 * `main.ts`에 인라인하지 않은 이유는 **검증 때문**이다. Vitest는 `spec.ts` 파일만 돌고
 * `main.ts`는 그 밖이라, 거기 둔 판정은 아무도 확인하지 않는다. 그런데 여기서 틀리는 방식이
 * 하필 조용하다 — DSN이 없는데 초기화를 시도하면 콘솔 경고 하나로 끝나고, 반대로 DSN이
 * 있는데 건너뛰면 **아무 일도 일어나지 않는다.**
 */

/** 값이 비었거나 자리표시자면 수집을 켜지 않는다. */
export function shouldEnableMonitoring(dsn: string | undefined): boolean {
  return typeof dsn === 'string' && dsn.trim().length > 0
}

/**
 * @return 실제로 켰는지. 호출부가 로그로 남길 수 있게 돌려준다 —
 *   조용히 안 켜지는 것이 이 배선의 유일한 실패 방식이다.
 */
export function initMonitoring(app: App): boolean {
  const dsn = import.meta.env.VITE_SENTRY_DSN
  if (!shouldEnableMonitoring(dsn)) {
    return false
  }

  Sentry.init({
    app,
    dsn,
    // 백엔드의 sentry.environment와 같은 값 체계를 쓴다. 로컬 개발 오류가 운영 이슈에
    // 섞이면 "언제부터 나던 것인지"를 알 수 없게 된다.
    environment: import.meta.env.PROD ? 'production' : 'local',
    /**
     * MUST: false로 둔다. 켜면 요청 헤더·쿠키와 함께 로그인 이메일이 Sentry로 나간다.
     * 기본값이지만 명시한다 — 기본값에 기대면 SDK 업그레이드가 조용히 뒤집을 수 있다.
     */
    sendDefaultPii: false,
    // MUST NOT: `integrations`에 `browserTracingIntegration`·`replayIntegration`을 더하지
    // 않는다. 무료 티어의 제약은 프로젝트 수가 아니라 **월 이벤트 총량**이고, 트레이싱과
    // 리플레이는 오류보다 훨씬 많은 이벤트를 만든다. 여기서 필요한 것은 예상 못 한 오류이지
    // 성능 프로파일이 아니다. 지정하지 않으면 기본 통합(전역 오류 핸들러·브레드크럼·중복
    // 제거)만 붙는데, 그것이 정확히 우리가 원하는 집합이다.
    //
    // release도 지정하지 않는다. @sentry/vite-plugin이 번들에 주입한 값을 SDK가 읽고,
    // 소스맵도 같은 값으로 올라간다. 여기서 손으로 적으면 둘이 어긋나 스택트레이스가
    // 매핑되지 않는데, 그 어긋남은 이슈를 열어 보고 나서야 드러난다.
  })
  return true
}
