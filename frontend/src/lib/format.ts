/**
 * 숫자 서식.
 *
 * IMPORTANT — 값이 없을 때 0이나 '-'를 쓰지 않고 {@link MISSING}을 쓴다. 백엔드는 "계산되지
 * 않았다"를 값 없음(키 부재)으로 보내는데, 화면이 0으로 그리면 그 구분이 사라지고 사용자는
 * 부채비율 0%인 회사를 본 것으로 읽는다.
 */

/** 값이 없음. 하이픈(-)이 아니라 em dash(—)인 이유는 음수 부호와 헷갈리지 않기 위해서다. */
export const MISSING = '—'

/**
 * 숫자·정렬의 로케일.
 *
 * 다국어를 하려는 것이 아니다. 이 문자열이 서식에도 `localeCompare`에도 쓰이는데 화면마다
 * 흩어져 있으면, 한 곳만 고쳤을 때 표는 새 규칙으로 그리고 정렬은 옛 규칙으로 도는 상태가
 * 된다. 그 어긋남은 자릿수나 순서가 미묘하게 다른 형태로만 드러난다.
 */
export const LOCALE = 'ko-KR'

/** 자릿수가 흔들리면 표에서 비교가 어렵다. 서버가 지표마다 정한 소수 자릿수를 그대로 쓴다. */
export function formatNumber(value: number | null | undefined, decimalScale: number): string {
  if (value === null || value === undefined || Number.isNaN(value)) {
    return MISSING
  }
  return new Intl.NumberFormat(LOCALE, {
    minimumFractionDigits: decimalScale,
    maximumFractionDigits: decimalScale,
  }).format(value)
}

/**
 * 자릿수를 **채우지 않는** 서식. 사용자가 직접 넣은 값에 쓴다.
 *
 * {@link formatNumber}와 나누는 기준은 "누가 그 숫자를 정했는가"다. 표의 지표 값은 서버가
 * 계산한 것이라 자릿수가 고정되어야 세로로 비교가 되지만, 조건 칩의 `10`은 사용자가 그렇게
 * 넣은 것이다. `10.00`으로 채우면 자기가 넣지 않은 정밀도를 넣은 것처럼 읽힌다.
 *
 * `decimalScale`은 상한으로만 쓴다 — 서버가 정한 자릿수보다 더 자세히 보이지는 않는다.
 */
export function formatEnteredNumber(
  value: number | null | undefined,
  decimalScale: number,
): string {
  if (value === null || value === undefined || Number.isNaN(value)) {
    return MISSING
  }
  return new Intl.NumberFormat(LOCALE, { maximumFractionDigits: decimalScale }).format(value)
}

/**
 * 지표 값과 단위를 **나눠서**.
 *
 * 종목 상세의 카드가 숫자를 크게, 단위를 작고 흐리게 그린다. 붙여 놓으면 `11.34%`에서
 * `%`가 숫자와 같은 무게로 읽혀, 카드 스무 장을 훑을 때 눈이 자릿수 대신 기호에 걸린다.
 *
 * 값이 없으면 `unit`은 빈 문자열이다 — `—%`는 없는 값에 단위를 붙인 것이라 거짓이다.
 */
export function splitIndicatorValue(
  value: number | null | undefined,
  unit: string | null | undefined,
  decimalScale: number,
  currency: string | null | undefined,
): { number: string; unit: string } {
  const formatted = formatNumber(value, decimalScale)
  if (formatted === MISSING) {
    return { number: MISSING, unit: '' }
  }
  const { prefix, suffix } = unitAffixes(unit, currency)
  return { number: `${prefix}${formatted}`, unit: suffix }
}

/**
 * 서버가 준 단위 문자열을 **그 통화의 표기**로 옮긴다.
 *
 * 금액 지표만 통화를 탄다 — 나머지 단위('%'·'배'·'%p')는 시장 중립이라 그대로 쓴다.
 * 사용자가 직접 넣은 값(조건 칩)과 서버가 계산한 값(결과 표)이 같은 규칙을 쓰게 하려고
 * 뽑아 두었다. 한쪽만 고치면 같은 임계가 칩에서는 「10000000000원」, 표에서는 「$10,000,000,000」이 된다.
 */
export function unitAffixes(
  unit: string | null | undefined,
  currency: string | null | undefined,
): { prefix: string; suffix: string } {
  if (unit === MONEY_UNIT) {
    const style = styleOf(currency)
    return { prefix: style.prefix, suffix: style.suffix }
  }
  return { prefix: '', suffix: unit ?? '' }
}

/**
 * 지표 값 + 단위. 단위는 서버가 준 문자열을 그대로 붙인다('%', '배', 없으면 빈 문자열).
 *
 * {@link splitIndicatorValue}를 조립한다 — 서식 규칙이 둘로 갈리면 표와 카드에서 같은 값이
 * 다르게 보이기 시작한다.
 */
export function formatIndicatorValue(
  value: number | null | undefined,
  unit: string | null | undefined,
  decimalScale: number,
  currency: string | null | undefined,
): string {
  const parts = splitIndicatorValue(value, unit, decimalScale, currency)
  return `${parts.number}${parts.unit}`
}

/**
 * 통화별 표기 규칙. **시장을 추가하면 여기에 줄을 더한다.**
 *
 * 정본은 `market.currency_code`이고 서버가 응답마다 실어 준다 — 화면이 시장 코드에서
 * 유도하지 않는 이유가 그것이다. 모르는 코드는 **원화로 떨어지지 않고** ISO 코드를 그대로
 * 붙인다(`1,234 JPY`). 못 그리는 것보다 나쁜 것은 **틀린 통화로 그려 놓고 조용한 것**이다.
 *
 * `scales`는 큰 쪽부터다. 접는 자리가 통화마다 다른 것이 의도다 — 한국어권은 만진법이라
 * 조·억이고, 달러는 천진법이라 T·B·M이다. 한쪽 규칙을 다른 쪽에 쓰면 자릿수가 안 읽힌다.
 */
interface CurrencyStyle {
  prefix: string
  suffix: string
  scales: ReadonlyArray<readonly [number, string]>
}

const CURRENCIES: Readonly<Record<string, CurrencyStyle>> = {
  KRW: { prefix: '', suffix: '원', scales: [[1e12, '조'], [1e8, '억']] },
  USD: { prefix: '$', suffix: '', scales: [[1e12, 'T'], [1e9, 'B'], [1e6, 'M']] },
}

function styleOf(currency: string | null | undefined): CurrencyStyle {
  if (!currency) {
    // 서버가 통화를 못 정한 응답. 숫자만 낸다 — 아무 통화나 붙이는 것보다 낫다.
    return { prefix: '', suffix: '', scales: [] }
  }
  return CURRENCIES[currency] ?? { prefix: '', suffix: ` ${currency}`, scales: [] }
}

/**
 * 서버 지표 카탈로그가 **금액 지표**에 쓰는 단위 문자열.
 *
 * 카탈로그는 시장 중립이라 통화를 모르고, 금액이라는 사실만 이 문자열로 말한다. 화면이
 * 그것을 행의 통화로 갈아끼운다 — 안 갈아끼우면 미국 시가총액이 「원」으로 그려진다.
 * 정본은 `IndicatorMeta.unit`이고 지금 이 값을 쓰는 지표는 넷이다
 * (`MARKET_CAP`·`TURNOVER_60`·`EPS_TTM`·`OPERATING_CASH_FLOW_5Y_SUM`).
 */
export const MONEY_UNIT = '원'

/**
 * 주가. **원주가**이므로 사용자가 실제로 지불하는 금액이고, 소수점을 보이지 않는다.
 *
 * `currency`가 **필수 인자다.** 기본값을 주면 새 호출부가 아무것도 안 넘겨도 컴파일되고,
 * 그 화면만 조용히 원화로 그린다 — $150이 「150원」이 되는 경로가 정확히 그것이다.
 */
export function formatPrice(
  value: number | null | undefined,
  currency: string | null | undefined,
): string {
  if (value === null || value === undefined || Number.isNaN(value)) {
    return MISSING
  }
  const style = styleOf(currency)
  const digits = currency === 'KRW' || !currency ? 0 : 2
  const number = new Intl.NumberFormat(LOCALE, {
    minimumFractionDigits: digits,
    maximumFractionDigits: digits,
  }).format(Math.abs(value))
  // 부호는 접두 통화 기호보다 **앞**이다. `$-150`이 아니라 `-$150`이라야 음수로 읽힌다.
  const sign = value < 0 ? '-' : ''
  return `${sign}${style.prefix}${number}${style.suffix}`
}

/**
 * 등락폭. {@link formatSignedPercent}의 금액 짝이다 — 오른 것에 `+`를 붙인다.
 *
 * MUST NOT: 여기서 다시 계산하지 않는다. 서버가 **수정종가의 비**로 낸 값이라(액면분할이
 * 있으면 원주가 뺄셈과 다르다) 화면이 두 종가를 빼서 만들면 같은 사실이 두 곳에 다른
 * 숫자로 나온다. {@link formatSignedPercent}의 같은 규율을 그대로 따른다.
 */
export function formatSignedPrice(
  value: number | null | undefined,
  currency: string | null | undefined,
): string {
  const formatted = formatPrice(value, currency)
  if (formatted === MISSING) {
    return MISSING
  }
  // `formatPrice`가 음수에 이미 `-`를 붙인다. 양수에만 부호를 더한다.
  return (value as number) > 0 ? `+${formatted}` : formatted
}

/**
 * 재무제표 금액을 통화의 큰 단위로 줄여 쓴다(원화면 조·억, 달러면 T·B·M).
 *
 * {@link formatPrice}와 나누는 기준은 **자릿수**다. 주가는 만 단위지만 재무는 조 단위라
 * 원 단위로 그리면 한 칸에 15자리가 들어가 표에서 비교가 불가능하다.
 *
 * 반올림 자리를 소수 둘로 고정한다 — 조와 억이 섞인 열에서 자릿수가 흔들리면 눈으로
 * 크기를 견주지 못한다. 음수는 그대로 부호를 붙인다(적자를 감추지 않는다).
 */
export function formatAmount(
  value: number | null | undefined,
  currency: string | null | undefined,
): string {
  if (value === null || value === undefined || Number.isNaN(value)) {
    return MISSING
  }
  const style = styleOf(currency)
  const abs = Math.abs(value)
  for (const [factor, symbol] of style.scales) {
    if (abs >= factor) {
      const sign = value < 0 ? '-' : ''
      return `${sign}${style.prefix}${formatNumber(abs / factor, 2)}${symbol}${style.suffix}`
    }
  }
  return formatPrice(value, currency)
}

/**
 * 증감률(%). 서버가 이미 계산한 값이라 부호만 붙인다.
 *
 * MUST NOT: 여기서 다시 계산하지 않는다. 분모의 절댓값 처리가 서버(`YearOverYear`)에 있고,
 * 화면이 자기 식을 쓰면 적자 기업에서 같은 사실이 두 곳에 반대 부호로 나온다.
 */
export function formatSignedPercent(value: number | null | undefined): string {
  if (value === null || value === undefined || Number.isNaN(value)) {
    return MISSING
  }
  const sign = value > 0 ? '+' : ''
  return `${sign}${formatNumber(value, 1)}%`
}

/** 0..1 비율을 백분율로. 백분위 값과 점수 비율에 쓴다. */
export function formatRatioAsPercent(value: number | null | undefined, decimalScale = 1): string {
  if (value === null || value === undefined || Number.isNaN(value)) {
    return MISSING
  }
  return `${formatNumber(value * 100, decimalScale)}%`
}

/** ISO 날짜를 화면 표기로. 잘못된 값이면 원문을 그대로 보인다 — 조용히 감추지 않는다. */
export function formatDate(iso: string | null | undefined): string {
  if (!iso) {
    return MISSING
  }
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(iso)
  if (!match) {
    return iso
  }
  return `${match[1]}. ${match[2]}. ${match[3]}.`
}

/**
 * ISO 날짜 문자열 → Date.
 *
 * IMPORTANT — `new Date('2026-08-05')`를 쓰지 않는다. 그 형식은 **UTC 자정**으로 파싱되어
 * KST에서는 같은 날 09시가 되고, 날짜 선택기가 다른 날을 표시하는 경우가 생긴다. 부품을
 * 쪼개 지역 시간으로 만들면 그 어긋남이 없다.
 */
export function parseIsoDate(iso: string | null | undefined): Date | null {
  if (!iso) {
    return null
  }
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(iso)
  if (!match) {
    return null
  }
  return new Date(Number(match[1]), Number(match[2]) - 1, Number(match[3]))
}

/** Date → ISO 날짜 문자열. 지역 시간 기준이라 `toISOString()`의 UTC 밀림이 없다. */
export function toIsoDate(date: Date | null | undefined): string | null {
  if (!date) {
    return null
  }
  const year = date.getFullYear()
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

/** 큰 정수(거래량·발행주식수)를 천 단위로. */
export function formatCount(value: number | null | undefined): string {
  if (value === null || value === undefined || Number.isNaN(value)) {
    return MISSING
  }
  return new Intl.NumberFormat(LOCALE, { maximumFractionDigits: 0 }).format(value)
}
