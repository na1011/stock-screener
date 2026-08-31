import { watch } from 'vue'

import { useCatalogStore } from '@/stores/catalog'
import { useScreenDraftStore } from '@/stores/screenDraft'

/**
 * 「전체」를 실행할 수 없는 상태이면 조회 대상을 하나 골라 둔다.
 *
 * <p>활성 조회 대상의 통화가 갈리면 서버가 「전체」 실행을 거절한다(ADR-0107 §규칙).
 * 기본값이 `null`(전체)이라 채워 두지 않으면 **사용자가 아무것도 안 했는데 첫 실행이
 * 4xx**다. 고른 대상은 설정 칩에 그대로 보이므로 조용한 선택이 아니다.
 *
 * IMPORTANT — **`catalog.universes`를 반드시 감시 대상에 넣는다.** 이것이 컴포넌트 안에
 * 있던 시절 `[allowsAllUniverses, universeCode]` 둘만 보다가 **한 번도 발화하지 못했다**:
 * `immediate` 시점에는 목록이 아직 안 와 `universes[0]`이 없어 아무 일도 안 하고, 목록이
 * 도착해도 두 값이 **둘 다 안 바뀌므로**(`false → false`, `null → null`) 재발화하지 않는다.
 * 함수는 맞는데 부르는 자리가 영영 안 도는 형태였고, 그래서 미국을 켠 뒤 **프리셋을 열고
 * 실행하면 매번 4xx**였다.
 *
 * <p>컴포넌트에서 뽑아낸 이유가 그것이다 — 이 배선은 **시간에 따라 값이 도착하는 것**이
 * 요점이라 한 번 그리고 끝나는 렌더 시험으로는 못 잡는다. 여기 있으면 스토어 둘만 세워
 * 직접 잰다(`useSelectableUniverse.spec.ts`).
 */
export function useSelectableUniverse(): void {
  const draft = useScreenDraftStore()
  const catalog = useCatalogStore()

  watch(
    // `universeCode`도 본다 — 저장된 전략을 불러오면 `loadFrom`이 그 값을 그대로 넣는데,
    // 그것이 null이면(대상을 안 정한 전략) 다시 「전체」가 되어 같은 4xx로 돌아간다.
    () => [catalog.allowsAllUniverses, draft.universeCode, catalog.universes[0]?.code] as const,
    () =>
      draft.ensureSelectableUniverse(
        catalog.allowsAllUniverses,
        catalog.universes[0]?.code ?? null,
      ),
    { immediate: true },
  )
}
