/**
 * 백엔드 record의 거울. 필드 이름과 널 가능성이 서버 계약 그대로여야 한다.
 *
 * IMPORTANT — 값이 없는 지표는 `null`이 아니라 **키 자체가 없다**. 서버가 "계산되지 않았다"와
 * "값이 0이다"를 구분해 보내기 때문이다. 그래서 `values`·`indicators`를 다룰 때 `?? 0`을 쓰면
 * 그 구분이 화면에서 사라진다.
 */

/** ISO-8601 날짜(yyyy-MM-dd). Jackson이 LocalDate를 이 형식으로 직렬화한다. */
export type IsoDate = string

/**
 * 서버 enum을 배열에서 파생시키는 이유.
 *
 * 유니온만 있으면 컴파일 시점 밖에서는 아무것도 할 수 없다. 배열로 두면 선택지를 손으로 적지
 * 않아도 되고(적으면 값이 늘 때 배열만 낡는다), `wire-contract.json`과 실제로 대조할 수 있다.
 *
 * `ComparisonOperatorName`은 일부러 배열로 만들지 않는다 — 연산자 목록의 정본은 서버이고
 * (`/api/indicators`가 라벨과 함께 준다) 배열이 있으면 프론트가 그것을 나열하고 싶어진다.
 */
export const EVALUATION_MODES = ['FILTER', 'SCORING'] as const
export type EvaluationMode = (typeof EVALUATION_MODES)[number]

export const SCREEN_TYPES = ['PRESET', 'CUSTOM'] as const
export type ScreenType = (typeof SCREEN_TYPES)[number]

/** 조건별 판정. boolean으로 접으면 "판정 불가"가 "불충족"과 같아진다(ADR-0003). */
export const OUTCOME_STATUSES = ['MATCHED', 'NOT_MATCHED', 'NOT_EVALUABLE'] as const
export type OutcomeStatus = (typeof OUTCOME_STATUSES)[number]

export const INDICATOR_CATEGORIES = ['TECHNICAL', 'FUNDAMENTAL', 'PRICE', 'DERIVED'] as const
export type IndicatorCategory = (typeof INDICATOR_CATEGORIES)[number]

export const FAVORABLE_ENDS = ['HIGH', 'LOW', 'UNKNOWN'] as const
export type FavorableEnd = (typeof FAVORABLE_ENDS)[number]

export const OPERAND_TYPES = ['LITERAL', 'INDICATOR'] as const
export type OperandType = (typeof OPERAND_TYPES)[number]

/** 응답에만 나오고 화면이 읽지 않는다. 대조 대상이 아니라 계약 문서로 남긴다. */
export type IndicatorValueType = 'RATIO' | 'PERCENT' | 'CURRENCY' | 'COUNT' | 'PRICE'

/**
 * 연산자 이름.
 *
 * IMPORTANT — 배열로 만들지 않는다. 목록의 정본은 서버이고 `/api/indicators`가 한글 라벨과
 * 함께 내려준다. 배열이 있으면 프론트가 그것을 나열하고 싶어지고, 그 순간 연산자가 추가될 때
 * 고칠 곳이 둘이 된다.
 */
export type ComparisonOperatorName = 'GT' | 'GTE' | 'LT' | 'LTE' | 'EQ' | 'NEQ' | 'BETWEEN'

/** v1은 항상 AND다. 구조만 트리라서 남겨 둔다(ADR-0003). */
export type LogicalOperator = 'AND' | 'OR'

// ── 인증 ──────────────────────────────────────────────────────────

export interface MeResponse {
  userId: number
  email: string
  name: string | null
  admin: boolean
}

// ── 지표 카탈로그 ──────────────────────────────────────────────────

/**
 * 백분위 조건의 방향 안내.
 *
 * IMPORTANT — 백분위는 언제나 오름차순인데 "상위 20%"의 부등호는 원본 지표의 유불리에 따라
 * 뒤집힌다. PER은 낮을수록 좋으므로 `<= 0.2`, ROE는 높을수록 좋으므로 `>= 0.8`이다.
 * 화면이 지표 코드로 이것을 판단하면 그것이 프론트로 옮겨간 하드코딩 분기다.
 */
export interface PercentileGuide {
  /** 항상 참. 예외가 없다는 사실 자체가 계약이다. */
  ascending: boolean
  favorableEnd: FavorableEnd
  /** 방향을 모르면 null. */
  topQuintileOperator: 'GTE' | 'LTE' | null
  /** 방향을 모르면 null. */
  topQuintileThreshold: number | null
  label: string
}

export interface IndicatorResponse {
  code: string
  name: string
  description: string
  interpretation: string
  category: IndicatorCategory
  valueType: IndicatorValueType
  /** 단위 없는 지표는 null. */
  unit: string | null
  decimalScale: number
  /** null이면 유불리 방향이 없다(RSI 등). */
  higherIsBetter: boolean | null
  defaultColumn: boolean
  sortOrder: number
  /** 백분위가 아니면 null. */
  percentileOf: string | null
  /** 백분위가 아니면 null. */
  percentile: PercentileGuide | null
}

/** 연산자 한글 라벨의 정본은 서버다. 프론트가 목록을 복제하면 두 곳이 어긋난다. */
export interface OperatorResponse {
  name: ComparisonOperatorName
  label: string
  requiresUpperBound: boolean
}

export interface CatalogResponse {
  indicators: IndicatorResponse[]
  operators: OperatorResponse[]
}

// ── 유니버스 ──────────────────────────────────────────────────────

export interface UniverseResponse {
  code: string
  name: string
  marketCode: string
  memberCount: number
}

// ── 조건 모델 ─────────────────────────────────────────────────────

export interface OperandPayload {
  type: OperandType
  /** LITERAL의 값. BETWEEN이면 하한. */
  value?: number | null
  /** LITERAL + BETWEEN에서만 쓴다. */
  upperBound?: number | null
  /** INDICATOR에서만 쓴다. */
  indicatorCode?: string | null
  /** INDICATOR의 배수. null이면 1.0. */
  multiplier?: number | null
}

export interface ConditionPayload {
  indicatorCode: string
  operator: ComparisonOperatorName
  operand: OperandPayload
  /** SCORING에서만 쓴다. null이면 1로 취급된다. */
  weight?: number | null
}

/** v1은 `childGroups`를 항상 비워 보낸다. 구조만 트리다(ADR-0003). */
export interface ConditionGroupPayload {
  logicalOperator?: LogicalOperator
  conditions: ConditionPayload[]
  childGroups?: ConditionGroupPayload[]
}

// ── 저장된 화면 ────────────────────────────────────────────────────

export interface ScreenResponse {
  id: number | null
  /** 프리셋만 갖는다. */
  code: string | null
  name: string
  description: string | null
  type: ScreenType
  evaluationMode: EvaluationMode
  universeCode: string | null
  active: boolean
  /** 서버가 계산해 준다. 프론트가 type으로 판단하지 않는다. */
  editable: boolean
  /** 목록 응답에서는 null이다. */
  conditions: ConditionGroupPayload | null
}

export interface ScreenRequest {
  name: string
  description?: string | null
  evaluationMode: EvaluationMode
  universeCode?: string | null
  conditions: ConditionGroupPayload
}

// ── 스크리닝 ──────────────────────────────────────────────────────

/** screenId·presetCode·conditions 중 **정확히 하나**를 지정한다. */
export interface ScreeningRequest {
  screenId?: number | null
  presetCode?: string | null
  conditions?: ConditionGroupPayload | null
  evaluationMode?: EvaluationMode | null
  universeCode?: string | null
  asOf?: IsoDate | null
}

/** 결과 표가 컬럼 머리글과 값 서식에 쓰는 최소 정보. */
export interface IndicatorBrief {
  code: string
  name: string
  unit: string | null
  decimalScale: number
  higherIsBetter: boolean | null
  interpretation: string
}

export interface ConditionInfo {
  index: number
  indicator: IndicatorBrief
  operator: ComparisonOperatorName
  operatorLabel: string
  operand: OperandPayload
  weight: number | null
}

export interface Outcome {
  conditionIndex: number
  status: OutcomeStatus
  /** 판정 불가면 null. */
  actualValue: number | null
  earnedScore: number | null
}

export interface ScreeningRow {
  stockId: number
  ticker: string | null
  name: string | null
  marketCode: string | null
  /** 기준일 **원주가** 종가. 사용자가 실제로 지불하는 가격이다. */
  close: number | null
  score: number | null
  maxScore: number | null
  scoreRatio: number | null
  matchedCount: number
  notEvaluableCount: number
  /** 지표 코드 → 값. **계산되지 않은 지표는 키가 없다**(null이 아니라 부재). */
  values: Record<string, number>
  outcomes: Outcome[]
}

export interface ScreeningUniverseInfo {
  code: string
  name: string
  /** 전체 유니버스면 null. */
  memberCount: number | null
}

export interface ScreeningScreenInfo {
  /** 즉석 조건이면 null. */
  id: number | null
  /** 프리셋만 갖는다. */
  code: string | null
  name: string
  type: ScreenType
}

export interface ScreeningResponse {
  asOf: IsoDate
  /** null이면 서버가 정했다는 뜻이다. */
  asOfRequested: IsoDate | null
  evaluationMode: EvaluationMode
  sortedBy: string
  /** 전체 유니버스면 null. */
  universe: ScreeningUniverseInfo | null
  /**
   * 항상 `ALL_CURRENT_MEMBERS`. 백분위는 현재 어느 유니버스든 편입된 종목 전체
   * (코스피+코스닥)에 대해 계산된다 — 유니버스를 골라도 그 안의 순위가 아니다.
   */
  percentilePopulation: string
  /**
   * 이 결과가 백분위 지표를 하나라도 쓰는가.
   *
   * 서버가 판단해서 준다. 화면이 카탈로그를 따로 받아 판단하면 그 요청만 실패했을 때
   * 위 모집단 주의 문구가 조용히 사라지고, 화면은 사실이 아닌 말을 하게 된다.
   */
  usesPercentile: boolean
  screen: ScreeningScreenInfo
  conditions: ConditionInfo[]
  /** 결과 표의 기본 컬럼 — 카탈로그 기본 컬럼 ∪ 조건이 참조한 지표. */
  columns: string[]
  /** 상한에 걸리기 전 평가한 총 종목 수. */
  evaluatedCount: number
  resultCount: number
  /** 상한에 걸려 잘렸는가. 조용히 자르지 않는다. */
  truncated: boolean
  rows: ScreeningRow[]
}

export const PERCENTILE_POPULATION_ALL = 'ALL_CURRENT_MEMBERS'

// ── 종목 상세 ─────────────────────────────────────────────────────

/** 검색 결과 한 건. 지표 값은 싣지 않는다 — 값이 필요하면 상세로 간다. */
export interface StockSearchResult {
  id: number
  ticker: string
  name: string
  marketCode: string
  universeCodes: string[]
}

export interface StockUniverseRef {
  code: string
  name: string
  since: IsoDate
}

export interface StockSummary {
  id: number
  ticker: string
  name: string
  marketCode: string
  sector: string | null
  universes: StockUniverseRef[]
}

/** 기준일 시세. 원주가다. `tradeValue`·`listedShares`는 소스가 주지 않으면 null. */
export interface StockPrice {
  tradeDate: IsoDate
  open: number
  high: number
  low: number
  close: number
  volume: number | null
  tradeValue: number | null
  listedShares: number | null
}

export interface StockIndicatorValue {
  code: string
  name: string
  value: number
  unit: string | null
  decimalScale: number
  category: IndicatorCategory
  higherIsBetter: boolean | null
  description: string
  interpretation: string
}

export interface StockDetailResponse {
  asOf: IsoDate
  stock: StockSummary
  /** 기준일에 시세 행이 없으면 null. */
  price: StockPrice | null
  /** **값이 없는 지표는 목록에 없다.** */
  indicators: StockIndicatorValue[]
}

// ── 지지·저항 참고정보 ─────────────────────────────────────────────

export const ZONE_ROLES = ['SUPPORT', 'RESISTANCE', 'IN_RANGE'] as const
export type ZoneRole = (typeof ZONE_ROLES)[number]

export const ZONE_STRENGTHS = ['HIGH', 'MEDIUM', 'LOW'] as const
export type ZoneStrength = (typeof ZONE_STRENGTHS)[number]

export interface PriceReferenceWindow {
  from: IsoDate
  to: IsoDate
  tradingDays: number
}

export interface PriceReferenceZone {
  role: ZoneRole
  lower: number
  upper: number
  /** 이 구간이 의미를 가졌던 횟수. 연속된 봉은 한 번으로 센다. */
  touchCount: number
  lastTouchedOn: IsoDate
  strength: ZoneStrength
}

/**
 * IMPORTANT — 확정된 매매 신호가 아니다. `disclaimer`를 감추면 안 되고, 진입가·손절가로
 * 읽힐 단일 값을 화면에서 만들어내서도 안 된다(spec §10, frontend.md).
 */
export interface PriceReference {
  kind: string
  method: string
  disclaimer: string
  window: PriceReferenceWindow
  /** 봉이 모자라 산출하지 못했다. 이때 `zones`는 비어 있다. */
  insufficientData: boolean
  lastClose: number | null
  zones: PriceReferenceZone[]
}
