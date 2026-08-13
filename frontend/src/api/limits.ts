/**
 * 서버가 정한 값을 프론트가 거울로 들고 있는 곳.
 *
 * 이런 값이 흩어져 있으면 서버에서 바꿔도 화면은 옛 한계를 계속 강제한다 — 사용자는 조건을
 * 다 만든 뒤에야 400을 만나거나(서버가 조였을 때), 아무 설명 없이 막힌 버튼을 본다(서버가
 * 풀었을 때). 어느 쪽이든 원인이 화면에 드러나지 않는다.
 *
 * IMPORTANT — **여기 있는 값은 전부 `wire-contract.json`이 지킨다.** 손으로 맞춰 둔 값은
 * 어긋나도 아무 데서도 티가 나지 않으므로, 값을 하나 더할 때 픽스처에도 함께 적는다.
 */

/** 조건 개수 상한. `ScreenDefinitionValidator.MAX_CONDITIONS`. */
export const MAX_CONDITIONS = 25

/** `ScreenRequest.name`의 `@Size(max)`. */
export const SCREEN_NAME_MAX_LENGTH = 100

/** `ScreenRequest.description`의 `@Size(max)`. */
export const SCREEN_DESCRIPTION_MAX_LENGTH = 2000

/** `AllowedEmailRequest.note`의 `@Size(max)`. `allowed_email.note` 컬럼 폭과 같다. */
export const ALLOWED_EMAIL_NOTE_MAX_LENGTH = 200

/**
 * 소수 자릿수를 모를 때의 기본값.
 *
 * 카탈로그에서 사라진 지표(저장된 전략이 폐기된 지표를 참조하는 경우)에 쓴다. 서버의
 * `ScreeningResponseAssembler.brief()`가 같은 상황에 쓰는 값과 같아야 표와 카드가 서버가
 * 직접 내려준 값과 다른 자릿수로 그려지지 않는다.
 */
export const DEFAULT_DECIMAL_SCALE = 2
