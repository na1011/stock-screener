import type { WatchlistItem } from '@/api/types'

/**
 * 워치리스트 항목의 알림 상태를 화면이 쓰는 형태로 바꾼다.
 *
 * 여기 있는 이유는 `.claude/rules/frontend.md`의 (b) 조건이다 — 목록(`WatchlistView`)과
 * 담기 버튼(`WatchButton`)이 같은 상태를 그린다.
 *
 * IMPORTANT — **네 상태다.** 서버의 `satisfied`가 세 값(`null`/`false`/`true`)이고 여기에
 * "알림 조건이 아예 없음"이 더해진다. 접으면 각각 다른 것이 같아 보인다:
 * 담은 직후와 "조건에 안 맞음"이 같아 보이면 사용자는 배치가 돌았는지 알 수 없고,
 * "알림 없음"과 "판정 전"이 같아 보이면 왜 알림이 안 오는지 알 수 없다.
 */
export type AlertState = 'NONE' | 'PENDING' | 'UNMET' | 'MET'

export function alertStateOf(item: WatchlistItem): AlertState {
  if (item.screenId === null) {
    return 'NONE'
  }
  if (item.satisfied === null) {
    return 'PENDING'
  }
  return item.satisfied ? 'MET' : 'UNMET'
}

interface AlertStatePresentation {
  /** 태그에 들어가는 두 낱말. */
  label: string
  /** 왜 그 상태인지. 태그만으로는 "판정 전"이 무슨 뜻인지 알 수 없다. */
  hint: string
  severity: 'success' | 'secondary' | 'info' | 'warn'
  icon: string
}

/**
 * 상태별 표시. `Record<AlertState, …>`로 두어 상태가 늘면 컴파일이 멈춘다.
 *
 * 손으로 적은 배열이었다면 새 상태가 조용히 빠지고, 그 항목만 태그 없이 그려진다.
 */
export const ALERT_STATE_PRESENTATION: Record<AlertState, AlertStatePresentation> = {
  NONE: {
    label: '알림 없음',
    hint: '추적만 합니다. 조건을 지정하면 충족될 때 알려드립니다.',
    severity: 'secondary',
    icon: 'pi pi-bell-slash',
  },
  PENDING: {
    label: '판정 전',
    hint: '아직 판정하지 않았습니다. 다음 일일 갱신에서 확인합니다.',
    severity: 'info',
    icon: 'pi pi-clock',
  },
  UNMET: {
    label: '미충족',
    hint: '마지막 판정에서 조건에 맞지 않았습니다.',
    severity: 'secondary',
    icon: 'pi pi-minus-circle',
  },
  MET: {
    label: '충족',
    hint: '마지막 판정에서 조건을 충족했습니다.',
    severity: 'success',
    icon: 'pi pi-check-circle',
  },
}

/**
 * 알림이 언제 오는지 한 줄로.
 *
 * 엣지 트리거(ADR-0026)는 화면에서 설명하지 않으면 오해를 부른다 — 조건을 충족 중인데
 * 알림이 안 오는 것을 사용자는 고장으로 읽는다.
 */
export const EDGE_TRIGGER_NOTICE =
  '알림은 조건을 새로 충족한 날에만 갑니다. 충족 상태가 이어지는 동안에는 다시 오지 않습니다.'
