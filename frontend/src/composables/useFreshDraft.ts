import { useConfirm } from 'primevue/useconfirm'
import { useRouter } from 'vue-router'

import { useScreenDraftStore } from '@/stores/screenDraft'

/**
 * 조건을 전부 비우고 새 전략을 시작하는 동작.
 *
 * 레일(`AppRail`)과 칩 바(`ConditionChipBar`) 둘이 같은 버튼을 갖는데 **각자 구현해서 서로
 * 다른 절반씩만 옳았다.** 칩 바는 확인을 받되 이동하지 않았고, 레일은 이동하되 확인 없이
 * 즉시 날렸다. 어느 쪽으로 눌렀느냐에 따라 잃는 것이 달랐다.
 *
 * `useScreenOpener`가 같은 이유로 존재한다 — 거기서도 레일만 `router.push`를 빠뜨려, 종목
 * 상세를 보는 중에 누르면 조건만 조용히 바뀌고 본문은 그대로였다.
 *
 * IMPORTANT — 이동이 확인 **뒤에** 와야 한다. 확인을 띄우기 전에 스크리너로 보내면 사용자가
 * 취소해도 이미 보던 자리를 잃는다.
 */
export function useFreshDraft(): { startFresh: (onDone?: () => void) => void } {
  const draft = useScreenDraftStore()
  const router = useRouter()
  const confirm = useConfirm()

  async function run(onDone?: () => void): Promise<void> {
    draft.reset()
    // 종목 상세에서 눌렀을 때 조건만 조용히 비워지고 본문은 그대로이던 것을 막는다.
    // 이미 스크리너면 vue-router가 중복 이동으로 조용히 끝낸다.
    await router.push({ name: 'screener' })
    onDone?.()
  }

  /**
   * @param onDone 실제로 비우고 이동한 **뒤에만** 부른다. 취소하면 부르지 않는다 — 좁은 폭에서
   *   레일은 서랍이고, 취소했는데 서랍이 닫히면 사용자는 아무 일도 안 일어난 채 서랍만 잃는다.
   */
  function startFresh(onDone?: () => void): void {
    // 조건이 하나뿐이면(=빈 조건 하나) 잃을 것이 없으므로 묻지 않는다. 의미 없는 확인이
    // 쌓이면 사용자는 읽지 않고 누르게 된다.
    if (draft.conditions.length <= 1 && draft.completeConditions.length === 0) {
      void run(onDone)
      return
    }
    confirm.require({
      // 여기서 세는 것은 칩이므로 "조건"이 맞다. 만들려는 것이 "전략"인 것과 층위가 다르다.
      message: `조건 ${draft.conditions.length}개를 지우고 새로 시작합니다.`,
      header: '새 전략으로 시작할까요?',
      icon: 'pi pi-exclamation-triangle',
      rejectProps: { label: '취소', severity: 'secondary', outlined: true, size: 'small' },
      acceptProps: { label: '새로 시작', severity: 'danger', size: 'small' },
      accept: () => void run(onDone),
    })
  }

  return { startFresh }
}
