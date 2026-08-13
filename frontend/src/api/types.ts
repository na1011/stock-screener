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

/**
 * 상대 비교 지표가 무엇과 견주어 나온 값인가.
 *
 * MUST NOT: 지표 코드 접미사로 유추하지 않는다. 서버가 카탈로그와 스크리닝 응답 양쪽에
 * 실어 주며, 코드로 분기하는 순간 지표가 추가될 때마다 화면을 고쳐야 한다.
 */
export const INDICATOR_POPULATIONS = ['UNIVERSE', 'SECTOR'] as const
export type IndicatorPopulation = (typeof INDICATOR_POPULATIONS)[number]

export const FAVORABLE_ENDS = ['HIGH', 'LOW', 'UNKNOWN'] as const
export type FavorableEnd = (typeof FAVORABLE_ENDS)[number]

export const OPERAND_TYPES = ['LITERAL', 'INDICATOR'] as const
export type OperandType = (typeof OPERAND_TYPES)[number]

/**
 * 기록되는 사용자 행위. 관리자 로그 화면의 필터 선택지가 여기서 파생된다.
 *
 * 서버가 값을 더했는데 여기가 낡으면 새 행위가 **필터에서 조용히 빠지고**, 그 행위의 로그는
 * 조회할 방법이 없어진다. `contract.spec.ts`가 그것을 잡는다.
 */
export const INTERACTION_ACTIONS = [
  'LOGIN',
  'SCREENING_RUN',
  'SCREEN_CREATE',
  'SCREEN_UPDATE',
  'SCREEN_DELETE',
  'WATCHLIST_ADD',
  'WATCHLIST_UPDATE',
  'WATCHLIST_REMOVE',
  'ADMIN_JOB_RUN',
  'ADMIN_BATCH_RETRY',
  'ADMIN_ALLOWLIST_ADD',
  'ADMIN_ALLOWLIST_UPDATE',
  'ADMIN_ALLOWLIST_REMOVE',
  'ADMIN_CORPORATE_ACTION_JUDGE',
] as const
export type InteractionAction = (typeof INTERACTION_ACTIONS)[number]

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

/**
 * 값 구간 프리셋. "ROE 20% 이상 = 매우 높음"의 한 줄이다.
 *
 * IMPORTANT — 부등호를 화면이 정하지 않는다. 서버가 구간을 **그대로 적용 가능한 조건**으로
 * 번역해 내려준다(`PercentileGuide`와 같은 방식). `from`/`to`를 받아 화면이 연산자를
 * 유도하면 그 유도 규칙이 두 곳에 생기고, 어긋나도 값이 그럴듯해 드러나지 않는다.
 */
export interface IndicatorBand {
  label: string
  operator: ComparisonOperatorName
  /** BETWEEN이면 하한. */
  value: number
  /** BETWEEN일 때만 채워진다. */
  upperBound: number | null
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
  /**
   * 무엇과 견주어 나온 값인가. 상대 지표가 아니면 null.
   *
   * `percentile`이 있는 것과 **다르다** — 업종 중앙값 배수는 모집단이 있지만 백분위가
   * 아니라 "상위 20%" 안내가 붙지 않는다(1.0이 중앙이라 0.2가 뜻을 잃는다).
   */
  population: IndicatorPopulation | null
  /**
   * 제시할 구간이 없으면 빈 배열이다. 주가 스케일 지표(SMA·ATR·MACD·종가)가 그렇다 —
   * "3만원 이상 = 높음"은 종목마다 뜻이 달라 거짓말이 된다. 그때 화면은 직접 입력만 낸다.
   */
  bands: IndicatorBand[]
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

// ── 조회 대상 ──────────────────────────────────────────────────────

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
  /**
   * SCORING에서 미충족 종목을 결과에서 뺀다. `weight`와 함께 보낼 수 없다 — 서버가 400으로 거절한다.
   * 생략하면 거짓이다.
   */
  required?: boolean | null
}

/** v1은 `childGroups`를 항상 비워 보낸다. 구조만 트리다(ADR-0003). */
export interface ConditionGroupPayload {
  logicalOperator?: LogicalOperator
  conditions: ConditionPayload[]
  childGroups?: ConditionGroupPayload[]
}

// ── 저장된 전략 ────────────────────────────────────────────────────

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
  /**
   * 필수 조건인가. **`weight === null`로 유도하지 않는다** — FILTER 전략은 둘 다 null이라
   * 구분이 서지 않는다. 서버가 판단해서 주는 값을 다른 필드로 되짚는 것은 금지다.
   */
  required: boolean
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
  /** 대상을 좁히지 않았으면 null. */
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
  /** 대상을 좁히지 않았으면 null. */
  universe: ScreeningUniverseInfo | null
  /**
   * 이 결과가 **실제로 쓴** 상대 비교의 모집단. 비면 상대 지표를 하나도 쓰지 않은 것이다.
   *
   * 서버가 판단해서 준다. 화면이 카탈로그를 따로 받아 판단하면 그 요청만 실패했을 때
   * 모집단 주의 문구가 조용히 사라지고, 화면은 모집단을 밝히지 않은 채 "상위 20%"라고
   * 말하게 된다.
   *
   * 둘 이상이면 **둘 다 설명한다.** 하나로 접으면 조건마다 모집단이 다르다는 사실이
   * 감춰지는데, 값은 둘 다 0~1이라 표에서는 구분되지 않는다.
   */
  relativePopulations: IndicatorPopulation[]
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

/**
 * 수정계수를 확정하지 못한 기업 행위(ADR-0055).
 *
 * **`notice`는 서버가 소유하고 언제나 실린다** — 화면이 자기 문구를 들면 두 곳이 갈리는데,
 * 갈린 쪽은 어디에서도 드러나지 않는다. `PriceReference.disclaimer`와 같은 형태다.
 */
export interface UnadjustedActions {
  notice: string
  /** 권리락일 오름차순. **비어 있으면 이 종목에는 그런 사건이 없다.** */
  effectiveDates: IsoDate[]
}

export interface StockDetailResponse {
  asOf: IsoDate
  stock: StockSummary
  /** 기준일에 시세 행이 없으면 null. */
  price: StockPrice | null
  /** **값이 없는 지표는 목록에 없다.** */
  indicators: StockIndicatorValue[]
  /**
   * **지표가 왜 비었는지에 답하는 유일한 자리다.** 억제된 종목은 FILTER 프리셋 결과에 아예
   * 없고, SCORING이라도 필수 조건이면 통째로 빠져 조건별 "데이터 없음"이 이유를 말하지 못한다.
   */
  unadjustedActions: UnadjustedActions
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

// ── 워치리스트 ────────────────────────────────────────────────────

/**
 * 관심종목 한 줄.
 *
 * IMPORTANT — `satisfied`는 **세 값**이다. `null`은 "아직 판정한 적 없음"(담은 직후,
 * 또는 알림 조건을 방금 바꿈)이고 `false`는 "판정했고 조건에 안 맞음"이다. 둘을 접으면
 * 사용자는 배치가 돌았는지조차 알 수 없다 — 판정 3상태를 화면이 접지 않는 것과 같은 규칙이다.
 *
 * `screenId`가 null이면 알림 없이 추적만 한다. 그때 `screenName`·`screenType`도 null이다.
 */
export interface WatchlistItem {
  id: number
  stockId: number
  ticker: string
  stockName: string
  marketCode: string
  screenId: number | null
  screenName: string | null
  screenType: ScreenType | null
  memo: string | null
  /** null = 아직 판정 전. false = 판정했고 불충족. */
  satisfied: boolean | null
  /** 마지막으로 알림이 나간 시각(ISO). 아직 없으면 null. */
  notifiedAt: string | null
  createdAt: string
}

export interface WatchlistRequest {
  stockId: number
  screenId?: number | null
  memo?: string | null
}

/** 서버의 `@Size(max = 500)`을 거울로 든다(ADR-0020, wire-contract의 watchlistMemoMaxLength). */
export const WATCHLIST_MEMO_MAX_LENGTH = 500

// ── 관리자 ────────────────────────────────────────────────────────

/**
 * 배치 실행 상태.
 *
 * `PARTIAL_SUCCESS`가 별도인 것이 핵심이다 — 848종목 성공에 2종목 실패한 배치를 "실패"로
 * 접으면 매일 빨간불이 뜨고, 곧 아무도 그 불을 보지 않게 된다(spec §4).
 */
export const BATCH_STATUSES = [
  'RUNNING',
  'SUCCESS',
  'PARTIAL_SUCCESS',
  'FAILED',
  'ABORTED',
] as const
export type BatchStatus = (typeof BATCH_STATUSES)[number]

/** 누가 띄웠는가. 쿼터를 태우는 실행이라 출처가 남아야 한다. */
export const TRIGGERED_BY = ['SCHEDULER', 'ADMIN', 'MANUAL'] as const
export type TriggeredBy = (typeof TRIGGERED_BY)[number]

/** 잡이 받는 입력. 화면이 날짜 칸을 몇 개 그릴지 정한다. */
export const JOB_PARAMETERS = ['TARGET_DATE', 'DATE_RANGE'] as const
export type JobParameters = (typeof JOB_PARAMETERS)[number]

/**
 * 배치 실행 한 건.
 *
 * `targetDate`와 `startedAt`이 둘 다 있는 것이 의도다. 새벽에 도는 배치의 대상은 전일이고,
 * 재실행하면 실행 시각은 바뀌지만 대상 일자는 그대로다.
 */
export interface BatchExecution {
  id: number
  jobName: string
  targetDate: IsoDate
  status: BatchStatus
  startedAt: string
  finishedAt: string | null
  /** 대상 **종목** 수. 진행률의 분모가 아니다 — 그건 `plannedTaskCount`다(ADR-0037). */
  targetCount: number
  successCount: number
  failureCount: number
  writtenRowCount: number
  /**
   * 계획된 **작업 단위** 수. 진행률의 분모다.
   *
   * 한 종목이 여러 단계·여러 날짜로 처리되므로 종목 수와 배수만큼 다르다. 분모를 종목 수로
   * 쓰던 시절에 지표 백필이 `43400/350`으로 보였다.
   *
   * 계량이 없는 실행은 `null`이다. **`?? 0`으로 메우지 않는다** — 0으로 그리면 분모 0의
   * 진행률이 되고, "안 재봤다"가 "0건 계획"으로 읽힌다.
   */
  plannedTaskCount: number | null
  /** 마지막 진척 반영 시각. `RUNNING`인데 오래됐으면 멎은 것이다. */
  progressAt: string | null
  /** 지금 어느 구간인가. 서버가 준 문자열을 해석 없이 보인다. 마감하면 null이다. */
  stageLabel: string | null
  /** 어느 실행을 다시 돌린 것인가. null이면 최초 실행이다. */
  retriedFromId: number | null
  triggeredBy: TriggeredBy
  note: string | null
  /** 0이면 재실행 버튼을 켜지 않는다. */
  unresolvedFailureCount: number
}

/**
 * 실패한 종목 하나.
 *
 * `stackTrace`가 없는 것이 의도다 — 깊은 진단은 Sentry가 전담한다(spec §4). 여기 필요한 것은
 * 재실행할지 판단할 근거뿐이다.
 *
 * `failedPhase`·`failureKind`를 유니온으로 좁히지 않는다. 서버가 값을 늘렸을 때 목록이
 * 통째로 죽는 것보다 낯선 이름을 그대로 보이는 편이 낫다(frontend.md).
 */
export interface BatchFailure {
  id: number
  stockId: number
  ticker: string
  stockName: string
  failedPhase: string
  sourceName: string | null
  failureKind: string
  message: string
  resolved: boolean
  occurredAt: string
}

/**
 * 한 단계의 집계.
 *
 * `phase`를 유니온으로 좁히지 않는 것은 `failedPhase`와 같은 판단이다 — 서버가 단계를 하나
 * 더했을 때 표가 통째로 죽는 것보다 낯선 이름 한 줄이 섞이는 편이 낫다.
 */
export interface BatchPhase {
  phase: string
  plannedCount: number
  successCount: number
  failureCount: number
  /**
   * 저장한 행 수. **단계마다 뜻이 다르다** — 시세는 저장한 행이고 지표는 값이 달라진 행이다.
   * 재계산만 한 실행의 지표 0행은 결손이 아니라 "바뀐 것이 없다"는 뜻이다.
   */
  writtenRowCount: number
}

/** 같은 종류·같은 소스의 실패를 묶은 것. 350행을 눈으로 세지 않고 "무엇이 깨졌나"에 답한다. */
export interface BatchFailureGroup {
  failureKind: string
  sourceName: string | null
  totalCount: number
  unresolvedCount: number
}

/**
 * 실행 하나의 상세.
 *
 * 화면은 여기 실린 것만 그리고 잡 이름으로 분기하지 않는다 — `phases`가 비면 단계별 표를,
 * `failureSummary`가 비면 요약을 안 그린다. 그래야 잡이 늘어도 화면을 고치지 않는다.
 */
export interface BatchExecutionDetail {
  execution: BatchExecution
  phases: BatchPhase[]
  failureSummary: BatchFailureGroup[]
  failures: BatchFailure[]
  /** 이 잡이 부분 재실행을 지원하는가. 화면이 잡 이름으로 판단하지 않는다. */
  retrySupported: boolean
}

export interface BatchSummary {
  /** 잡별 가장 최근 실행 한 건씩. 전체 목록의 앞부분이 아니다. */
  latestByJob: BatchExecution[]
  unresolvedFailureCount: number
  runningJobs: string[]
}

/**
 * 화면에서 띄울 수 있는 잡.
 *
 * 목록을 서버가 주므로 프론트는 잡 이름을 하드코딩하지 않는다. 초기 적재가 여기 없는 것은
 * 의도다 — 버튼 하나가 DART 일일 한도의 절반을 태운다(ADR-0029).
 */
export interface AdminJob {
  name: string
  label: string
  description: string
  parameters: JobParameters
  /** 이 버튼이 태우는 외부 API 콜의 어림값. 누르기 전에 보여야 한다. */
  externalCallEstimate: number
  running: boolean
}

export interface JobRunRequest {
  targetDate?: IsoDate | null
  from?: IsoDate | null
  to?: IsoDate | null
}

export interface JobLaunchResponse {
  jobName: string
  targetDate: IsoDate | null
}

/**
 * 허용목록 한 줄.
 *
 * `lastLoginAt`이 null이면 **아직 로그인하지 않은 사람**이고 그것이 정상 상태다(ADR-0004).
 * 화면이 그 둘을 구분해야 초대장을 다시 보낼 대상을 알 수 있다.
 */
export interface AllowedEmail {
  id: number
  email: string
  admin: boolean
  note: string | null
  createdAt: string
  updatedAt: string
  lastLoginAt: string | null
}

export interface AllowedEmailRequest {
  email: string
  admin: boolean
  note?: string | null
}

export interface AllowedEmailUpdateRequest {
  admin: boolean
  note?: string | null
}

/**
 * 인터랙션 기록 한 줄.
 *
 * `userId`가 null인 기록이 있을 수 있다 — 사용자를 특정하지 못해도 행위 자체는 남는다.
 * `statusCode`는 실패한 시도도 남기기 때문에 필요하다.
 */
export interface InteractionLogEntry {
  id: number
  userId: number | null
  userEmail: string | null
  userName: string | null
  action: InteractionAction
  httpMethod: string | null
  path: string | null
  statusCode: number | null
  durationMs: number | null
  /** URI 경로 변수와 지정한 쿼리 파라미터. 요청 본문은 담기지 않는다(ADR-0028). */
  detail: Record<string, string>
  occurredAt: string
}

export interface InteractionActor {
  userId: number
  email: string
  name: string | null
}
