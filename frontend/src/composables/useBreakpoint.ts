import { onScopeDispose, readonly, ref } from 'vue'
import type { Ref } from 'vue'

import { BREAKPOINTS, maxWidthQuery } from '@/lib/breakpoints'

/**
 * 화면 폭.
 *
 * 이 값으로 **컴포넌트를 갈아 끼운다**. CSS로 숨기는 방식과 다른 점이 요점이다 —
 * spec §5와 frontend.md가 "같은 화면을 축소해 보여주지 말라"고 요구하는데, DataTable을
 * CSS로 좁히면 결국 축소본이 된다. 모바일에는 표가 아니라 카드 목록을 준다.
 *
 * `matchMedia`를 쓰는 이유는 resize 이벤트가 스크롤·주소창 변화에도 흔들려 모바일에서
 * 불필요하게 자주 발생하기 때문이다.
 */
function useMediaQuery(query: string): Ref<boolean> {
  const matches = ref(false)

  if (typeof window !== 'undefined' && typeof window.matchMedia === 'function') {
    const media = window.matchMedia(query)
    matches.value = media.matches

    const listener = (event: MediaQueryListEvent) => {
      matches.value = event.matches
    }
    media.addEventListener('change', listener)
    onScopeDispose(() => media.removeEventListener('change', listener))
  }

  return matches
}

/**
 * 화면 폭 경계 둘.
 *
 * 경계값을 여기 적지 않는 이유는 CSS도 같은 값을 써야 하기 때문이다 — 정본은
 * `lib/breakpoints`이고, 어긋나면 `breakpoints.spec.ts`가 빌드를 멈춘다.
 *
 * `isMobile`은 **컴포넌트를 갈아 끼우는** 경계이고, `isCompact`는 같은 컴포넌트가 라벨을
 * 접는 경계다. 둘을 하나로 합치지 않는 이유는 서로 다른 질문이기 때문이다 —
 * `lib/breakpoints`의 주석이 그 구분을 이미 적어 두었다.
 */
export function useBreakpoint(): {
  isMobile: Readonly<Ref<boolean>>
  isCompact: Readonly<Ref<boolean>>
} {
  return {
    isMobile: readonly(useMediaQuery(maxWidthQuery(BREAKPOINTS.MOBILE))),
    isCompact: readonly(useMediaQuery(maxWidthQuery(BREAKPOINTS.COMPACT))),
  }
}
