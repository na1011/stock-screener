import type { OutcomeStatus } from '@/api/types'

/**
 * 조건별 판정을 화면 표현으로 옮기는 **유일한** 지점.
 *
 * IMPORTANT — 세 상태를 둘로 접지 않는다. "데이터가 없어 판정할 수 없었다"와 "조건에 맞지
 * 않았다"가 같아 보이면 사용자는 종목이 왜 빠졌는지 알 수 없고, 백엔드가 굳이 세 값을 보낸
 * 의미가 사라진다(ADR-0003).
 *
 * 매핑을 한 곳에 두는 이유는 여러 화면(결과 표, 행 확장, 모바일 카드)이 같은 표현을 써야
 * 하기 때문이다. 각자 그리면 어느 화면에서는 회색이고 어느 화면에서는 빨간색이 된다.
 */
export interface OutcomeStyle {
  label: string
  /** PrimeIcons 클래스. */
  icon: string
  /** PrimeVue severity. 색의 정본은 테마 토큰이다. */
  severity: 'success' | 'danger' | 'warn'
  tooltip: string
}

export function outcomeStyle(status: OutcomeStatus): OutcomeStyle {
  switch (status) {
    case 'MATCHED':
      return {
        label: '충족',
        icon: 'pi pi-check',
        severity: 'success',
        tooltip: '이 조건을 만족한다.',
      }
    case 'NOT_MATCHED':
      return {
        label: '불충족',
        icon: 'pi pi-times',
        severity: 'danger',
        tooltip: '지표 값은 있으나 조건을 만족하지 않는다.',
      }
    case 'NOT_EVALUABLE':
      return {
        label: '데이터 없음',
        icon: 'pi pi-question',
        severity: 'warn',
        tooltip: '이 지표 값이 없어 판정하지 못했다. 조건에 맞지 않았다는 뜻이 아니다.',
      }
    default:
      // 상태가 늘어나면 여기서 컴파일이 멈춘다. 런타임까지 가면 그 상태는 화면에서
      // 조용히 사라지고, 사라졌다는 사실 자체가 드러나지 않는다.
      return assertNever(status)
  }
}

function assertNever(value: never): never {
  throw new Error(`처리하지 않은 판정 상태다: ${String(value)}`)
}
