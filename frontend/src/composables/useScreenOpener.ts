import { ref } from 'vue'

import { ApiError } from '@/api/http'
import type { ScreenResponse } from '@/api/types'
import { useApiToast } from '@/composables/useApiToast'
import { useScreenDraftStore } from '@/stores/screenDraft'
import { useScreensStore } from '@/stores/screens'

/**
 * 목록에서 화면 하나를 가리키는 키.
 *
 * 프리셋은 code, 내 화면은 id로 식별한다 — 둘을 한 목록에 담으려면 키가 하나여야 한다.
 *
 * IMPORTANT — 옵션을 만들 때와 고른 것을 되찾을 때가 **반드시 같은 식**이어야 한다. 예전에
 * 두 곳이 각자 유도했고, 어긋나면 `find`가 실패해 사용자가 골라도 아무 일이 일어나지 않았다.
 * 그 무반응에는 오류도 로그도 없다. 이제 레일과 Select 두 곳이 고르므로 식이 **셋**으로
 * 갈릴 수 있었고, 그래서 함수를 여기 하나로 옮겼다.
 */
export function screenKey(screen: ScreenResponse): number | string {
  return screen.type === 'PRESET' ? (screen.code ?? screen.name) : (screen.id ?? screen.name)
}

/**
 * 불러오기 실패 알림의 제목.
 *
 * 세 곳(`AppRail`·`ScreenPicker`·`ScreensView`)이 같은 문구를 각자 적고 있었다. 문구가
 * 흩어지면 한쪽만 고쳐지고, 사용자는 **같은 실패를 화면마다 다른 말로** 듣게 된다.
 */
const OPEN_FAILED = '불러오지 못했습니다'

/**
 * 저장된 화면·프리셋을 편집기로 불러오는 동작.
 *
 * 레일(`AppRail`)·좁은 폭의 Select(`ScreenPicker`)·목록 화면(`ScreensView`) 셋이 같은 것을
 * 한다. 각자 구현하면 한쪽만 고쳐지고, 그 차이는 "어느 쪽으로 골랐느냐"에 따라 다르게
 * 동작하는 형태로 나타난다 — 실제로 `ScreenPicker`가 키 유도부터 실패 문구까지 통째로
 * 복제하고 있었다.
 */
export function useScreenOpener() {
  const screens = useScreensStore()
  const draft = useScreenDraftStore()
  const { notifyFailure } = useApiToast()

  const loading = ref(false)

  /**
   * @return 실제로 불러왔으면 `true`. 실패는 여기서 알림까지 끝내므로 호출부가 다시 잡을
   *   것은 없지만, **성공에만 이어지는 동작**(목록 화면의 스크리너 이동)이 있어 결과가
   *   필요하다. `void`로 두면 실패 토스트를 띄운 채로 화면까지 넘어간다.
   */
  async function openByKey(key: number | string | null): Promise<boolean> {
    if (key === null) {
      // 비운 경우다. 편집 중인 조건은 그대로 둔다.
      return false
    }
    const found = screens.all.find((it) => screenKey(it) === key)
    if (found === undefined || found.id === null) {
      // 여기 오면 목록과 선택이 어긋난 것이다. 조용히 넘기면 사용자는 클릭이 먹지 않는
      // 화면을 보게 되고, 무엇이 잘못됐는지 알 방법이 없다.
      notifyFailure(
        OPEN_FAILED,
        new ApiError(0, null, '고른 화면을 목록에서 찾지 못했습니다. 새로고침 후 다시 시도해 주세요.'),
      )
      return false
    }
    loading.value = true
    try {
      draft.loadFrom(await screens.open(found.id))
      return true
    } catch (caught) {
      notifyFailure(OPEN_FAILED, caught)
      return false
    } finally {
      loading.value = false
    }
  }

  /**
   * 지금 편집 중인 것이 이 화면인가. 레일에서 활성 항목을 표시하는 데 쓴다.
   *
   * `draft.origin`을 보는 이유는 그것이 "무엇을 불러왔는가"의 정본이기 때문이다. 별도의
   * 선택 상태를 두면 복제·초기화 같은 경로에서 둘이 어긋난다.
   */
  function isOpen(screen: ScreenResponse): boolean {
    const origin = draft.origin
    if (origin.kind === 'preset') {
      return screen.type === 'PRESET' && screen.code === origin.code
    }
    if (origin.kind === 'screen') {
      return screen.type === 'CUSTOM' && screen.id === origin.id
    }
    return false
  }

  return { loading, openByKey, isOpen }
}
