import { useConfirm } from 'primevue/useconfirm'
import { ref } from 'vue'
import { useRouter } from 'vue-router'

import { ApiError } from '@/api/http'
import type { ScreenResponse } from '@/api/types'
import { useApiToast } from '@/composables/useApiToast'
import { matchesOrigin } from '@/lib/screen'
import { useScreenDraftStore } from '@/stores/screenDraft'
import { useScreensStore } from '@/stores/screens'

/**
 * 목록에서 전략 하나를 가리키는 키.
 *
 * 프리셋은 code, 내 전략은 id로 식별한다 — 둘을 한 목록에 담으려면 키가 하나여야 한다.
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
 * 저장된 전략·프리셋을 편집기로 불러오는 동작.
 *
 * 레일(`AppRail`)·좁은 폭의 Select(`ScreenPicker`)·목록 화면(`ScreensView`) 셋이 같은 것을
 * 한다. 각자 구현하면 한쪽만 고쳐지고, 그 차이는 "어느 쪽으로 골랐느냐"에 따라 다르게
 * 동작하는 형태로 나타난다 — 실제로 `ScreenPicker`가 키 유도부터 실패 문구까지 통째로
 * 복제하고 있었다.
 */
export function useScreenOpener() {
  const screens = useScreensStore()
  const draft = useScreenDraftStore()
  const router = useRouter()
  const confirm = useConfirm()
  const { notifyFailure } = useApiToast()

  const loading = ref(false)

  /**
   * 저장하지 않은 편집을 버려도 되는지 묻는다.
   *
   * `loadFrom`은 조건을 통째로 갈아끼운다. 프리셋을 하나 잘못 누르면 만들던 조건 스무 개가
   * 확인 없이 사라졌고, 되돌릴 방법도 없었다(`undoRemove`는 마지막 하나만 되살린다).
   *
   * IMPORTANT — 가드가 `conditions`이지 `completeConditions`가 아니다. 지표만 고르고 값을
   * 아직 안 넣은 칩도 사용자가 만든 것이다. 완성된 것만 세면 "지표 다섯 개를 고르는 중"이
   * 무방비가 되는데, 그 상태가 조건을 만드는 과정의 대부분이다. ADR-0024가 미완성 조건을
   * 조용히 버리지 않기로 한 것과 같은 판단이다.
   *
   * 손대지 않았으면(`!dirty`) 서버 정의 그대로라 잃을 것이 없다 — 묻지 않는다. 의미 없는
   * 확인이 쌓이면 사용자는 읽지 않고 누르게 된다(`useFreshDraft`가 같은 이유로 가드를 둔다).
   */
  function confirmDiscard(): Promise<boolean> {
    if (!draft.dirty || draft.conditions.length === 0) {
      return Promise.resolve(true)
    }
    return new Promise((resolve) => {
      confirm.require({
        header: '저장하지 않은 편집이 있습니다',
        // 세는 것은 칩이므로 "조건"이 맞다. 여는 것이 "전략"인 것과 층위가 다르다.
        message: `조건 ${draft.conditions.length}개의 편집 내용이 사라집니다.`,
        icon: 'pi pi-exclamation-triangle',
        rejectProps: { label: '취소', severity: 'secondary', outlined: true, size: 'small' },
        acceptProps: { label: '버리고 열기', severity: 'danger', size: 'small' },
        accept: () => resolve(true),
        // `reject`와 `onHide`를 **둘 다** 건다. ESC와 마스크 클릭은 reject를 거치지 않고,
        // 하나라도 새면 이 Promise가 영영 settle되지 않아 호출부가 매달린다.
        reject: () => resolve(false),
        onHide: () => resolve(false),
      })
    })
  }

  /**
   * 전략 하나를 편집기에 올리고 **스크리너로 보낸다.**
   *
   * IMPORTANT — 이동이 여기 있는 것이 요점이다. 예전에는 호출부 셋 중 `ScreensView`만
   * `router.push`를 갖고 있었고, 그래서 **레일에서 프리셋을 눌러도 아무 일이 일어나지
   * 않았다** — 종목 상세를 보는 중이면 요청만 나가고 본문은 그대로였다. 좁은 폭에서는
   * 서랍까지 닫혀 완전한 무반응으로 읽혔다. 위 주석이 예고한 "어느 쪽으로 골랐느냐에 따라
   * 다르게 동작하는 형태"가 정확히 그것이었다.
   *
   * 이미 스크리너 위에 있으면 같은 라우트로의 push라 아무 일도 하지 않는다.
   *
   * @return 실제로 불러왔으면 `true`. 실패는 여기서 알림까지 끝내므로 호출부가 다시 잡을
   *   것은 없지만, **성공에만 이어지는 동작**(서랍 닫기 등)이 있어 결과가 필요하다.
   */
  async function openByKey(key: number | string | null): Promise<boolean> {
    if (key === null) {
      // 비운 경우다. 편집 중인 조건은 그대로 둔다.
      return false
    }
    const found = screens.all.find((it) => screenKey(it) === key)
    if (found === undefined || found.id === null) {
      // 여기 오면 목록과 선택이 어긋난 것이다. 조용히 넘기면 사용자는 클릭이 먹지 않는
      // 전략을 보게 되고, 무엇이 잘못됐는지 알 방법이 없다.
      notifyFailure(
        OPEN_FAILED,
        new ApiError(0, null, '고른 전략을 목록에서 찾지 못했습니다. 새로고침 후 다시 시도해 주세요.'),
      )
      return false
    }
    // 확인은 **찾은 뒤에** 묻는다. 열 수도 없는 것 때문에 편집을 버릴지 묻는 것은 무의미하다.
    // 그리고 `loading`보다 앞이어야 한다 — 대화상자를 띄운 채 스피너가 돌면 호출부(전략
    // 관리 화면의 행)는 이미 무언가 진행 중인 것처럼 보인다.
    if (!(await confirmDiscard())) {
      return false
    }
    loading.value = true
    try {
      draft.loadFrom(await screens.open(found.id))
      await router.push({ name: 'screener' })
      return true
    } catch (caught) {
      notifyFailure(OPEN_FAILED, caught)
      return false
    } finally {
      loading.value = false
    }
  }

  /**
   * 지금 편집 중인 것이 이 전략인가. 레일에서 활성 항목을 표시하는 데 쓴다.
   *
   * `draft.origin`을 보는 이유는 그것이 "무엇을 불러왔는가"의 정본이기 때문이다. 별도의
   * 선택 상태를 두면 복제·초기화 같은 경로에서 둘이 어긋난다.
   *
   * 대조 규칙 자체는 `lib/screen`의 {@link matchesOrigin}이 갖는다 — 결과가 낡았는지
   * 판정하는 쪽이 같은 규칙을 쓴다.
   */
  function isOpen(screen: ScreenResponse): boolean {
    return matchesOrigin(screen, draft.origin)
  }

  return { loading, openByKey, isOpen }
}
