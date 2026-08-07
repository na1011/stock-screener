/**
 * 숫자 서식.
 *
 * IMPORTANT — 값이 없을 때 0이나 '-'를 쓰지 않고 {@link MISSING}을 쓴다. 백엔드는 "계산되지
 * 않았다"를 값 없음(키 부재)으로 보내는데, 화면이 0으로 그리면 그 구분이 사라지고 사용자는
 * 부채비율 0%인 회사를 본 것으로 읽는다.
 */

/** 값이 없음. 하이픈(-)이 아니라 em dash(—)인 이유는 음수 부호와 헷갈리지 않기 위해서다. */
export const MISSING = '—'

/** 자릿수가 흔들리면 표에서 비교가 어렵다. 서버가 지표마다 정한 소수 자릿수를 그대로 쓴다. */
export function formatNumber(value: number | null | undefined, decimalScale: number): string {
  if (value === null || value === undefined || Number.isNaN(value)) {
    return MISSING
  }
  return new Intl.NumberFormat('ko-KR', {
    minimumFractionDigits: decimalScale,
    maximumFractionDigits: decimalScale,
  }).format(value)
}

/** 지표 값 + 단위. 단위는 서버가 준 문자열을 그대로 붙인다('%', '배', 없으면 빈 문자열). */
export function formatIndicatorValue(
  value: number | null | undefined,
  unit: string | null | undefined,
  decimalScale: number,
): string {
  const formatted = formatNumber(value, decimalScale)
  if (formatted === MISSING) {
    return MISSING
  }
  return unit ? `${formatted}${unit}` : formatted
}

/** 주가. **원주가**이므로 사용자가 실제로 지불하는 금액이고, 소수점을 보이지 않는다. */
export function formatPrice(value: number | null | undefined): string {
  if (value === null || value === undefined || Number.isNaN(value)) {
    return MISSING
  }
  return `${new Intl.NumberFormat('ko-KR', { maximumFractionDigits: 0 }).format(value)}원`
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
  return new Intl.NumberFormat('ko-KR', { maximumFractionDigits: 0 }).format(value)
}
