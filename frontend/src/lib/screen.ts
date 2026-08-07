import type { ScreenType } from '@/api/types'

/**
 * 저장된 화면의 **출처** 표시.
 *
 * IMPORTANT — 출처이지 권한이 아니다. "고칠 수 있는가"는 서버가 주는 `editable`이 답하고
 * `stores/screenDraft`가 그것을 든다. 이 파일은 목록 섹션 제목과 태그에만 쓴다.
 *
 * 세 화면(화면 목록, 스크리너의 선택기, 조건 빌더의 태그)이 같은 두 낱말을 각자 적고 있었다.
 */
export function screenTypeLabel(type: ScreenType): string {
  switch (type) {
    case 'PRESET':
      return '프리셋'
    case 'CUSTOM':
      return '내 화면'
    default:
      // 화면 종류가 늘면 여기서 컴파일이 멈춘다. 목록의 섹션 구성이 함께 바뀌어야 하므로
      // 낯선 이름을 흘려보내는 것으로는 부족하다.
      return assertNever(type)
  }
}

function assertNever(value: never): never {
  throw new Error(`처리하지 않은 화면 종류다: ${String(value)}`)
}
