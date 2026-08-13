/**
 * 편집 중인 조건이 참조하는 지표 코드.
 *
 * 종목 상세가 "내 조건" 구획을 그릴 때 쓴다. 스크리너에서 조건을 세우고 종목을 눌러 들어오면
 * **그 조건에 걸린 지표가 이 종목에서 얼마인지**를 맨 위에서 볼 수 있어야 한다 — 결과 표에서
 * 빠진 종목이라면 "왜 없는가"의 답이 여기밖에 없다.
 *
 * 스토어가 아니라 여기 두는 이유는 순수 함수라 테스트가 붙기 때문이다. 인자를 `DraftCondition`
 * 대신 최소 모양으로 받는 것도 같은 이유다 — 스토어를 import하면 이 파일이 pinia에 묶인다.
 */
export interface IndicatorRef {
  indicatorCode: string | null
  operandIndicatorCode: string | null
}

/**
 * 좌변과 우변을 **나온 순서대로** 모으고 중복을 없앤다.
 *
 * 우변까지 세는 이유는 `ADJ_CLOSE > SMA_200 × 0.9` 같은 조건에서 사용자가 실제로 궁금한 것이
 * 양쪽 값이기 때문이다. 순서를 유지하는 것은 조건을 세운 순서가 곧 사용자가 생각한 순서라서다.
 *
 * MUST NOT: 카탈로그에 없는 코드를 걸러내지 않는다. 폐기된 지표를 조건에 들고 있는 전략이
 * 있을 수 있고, 그때 화면에서 조용히 사라지면 **그 전략이 왜 실행되지 않는지** 알 길이 없다.
 * 이름을 못 찾으면 코드를 그대로 보이는 것이 `resultRow`의 `indicatorBriefOf`가 하는 일이다.
 */
export function conditionIndicatorCodes(conditions: readonly IndicatorRef[]): string[] {
  const codes: string[] = []
  for (const condition of conditions) {
    for (const code of [condition.indicatorCode, condition.operandIndicatorCode]) {
      if (code !== null && code !== '' && !codes.includes(code)) {
        codes.push(code)
      }
    }
  }
  return codes
}
