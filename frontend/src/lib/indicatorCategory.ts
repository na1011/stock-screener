import type { IndicatorCategory } from '@/api/types'

/**
 * 지표 분류의 표시 이름과 배치.
 *
 * 조건 빌더와 종목 상세가 같은 분류를 각자 묶고 있었다 — 라벨 맵 둘 + 순서 배열 둘이 백엔드
 * `IndicatorCategory` 하나를 거울로 들었고, 그 중 한쪽은 `Record<string, string>`이라 타입이
 * 아무것도 잡아주지 못했다.
 *
 * IMPORTANT — **모르는 분류를 버리지 않는다.** 예전 `stores/catalog.ts`는 순서 배열에 없는
 * 분류를 걸러냈다. 서버가 분류를 하나 추가하면 그 분류의 지표 전체가 조건 빌더에서 선택
 * 불가능해지는데, 사라졌다는 사실이 화면 어디에도 남지 않는다. 던지지도 않는다 — 분류 하나
 * 때문에 빌더 전체가 못 뜨는 것이 더 나쁘다. 낯선 이름을 그대로 보이는 것이 셋 중 가장 덜
 * 나쁘다.
 */
const CATEGORY_LABELS: Record<IndicatorCategory, string> = {
  PRICE: '가격',
  TECHNICAL: '기술적 지표',
  FUNDAMENTAL: '재무 지표',
  DERIVED: '파생·백분위',
}

/**
 * 배치 순위.
 *
 * 배열(`IndicatorCategory[]`)이 아니라 `Record`인 것이 요점이다. 배열은 빠뜨린 분류를 잡지
 * 못하고 Record는 컴파일을 멈춘다. 분류가 늘면 여기서 먼저 걸린다.
 */
export const BUILDER_CATEGORY_RANK: Record<IndicatorCategory, number> = {
  PRICE: 0,
  TECHNICAL: 1,
  FUNDAMENTAL: 2,
  DERIVED: 3,
}

/** 종목 상세의 순위. 빌더와 다른 것은 의도다 — 상세는 재무부터 읽는다. */
export const DETAIL_CATEGORY_RANK: Record<IndicatorCategory, number> = {
  FUNDAMENTAL: 0,
  TECHNICAL: 1,
  DERIVED: 2,
  PRICE: 3,
}

/** 모르는 분류는 알려진 것들 뒤에 온다. 여럿이면 서버가 준 순서를 유지한다(정렬이 안정적이다). */
const UNKNOWN_RANK = Number.MAX_SAFE_INTEGER

export type CategoryRank = Record<IndicatorCategory, number>

/** 모르는 분류는 코드를 그대로 보인다. 빈 문자열로 두면 제목 없는 묶음이 생긴다. */
export function categoryLabel(category: string): string {
  return Object.hasOwn(CATEGORY_LABELS, category)
    ? CATEGORY_LABELS[category as IndicatorCategory]
    : category
}

export interface CategoryGroup<T> {
  category: string
  label: string
  items: T[]
}

/**
 * 분류별로 묶는다.
 *
 * 빈 묶음은 생기지 않는다 — 실제로 들어온 분류만 버킷이 된다. 묶음 안의 순서는 입력 순서
 * 그대로다. `stores/catalog.ts`가 서버의 `sortOrder`로 미리 정렬해 넘기므로 그 배치가
 * 여기서 흐트러지면 안 된다.
 */
export function groupByCategory<T>(
  items: readonly T[],
  categoryOf: (item: T) => string,
  rank: CategoryRank,
): CategoryGroup<T>[] {
  const buckets = new Map<string, T[]>()
  for (const item of items) {
    const category = categoryOf(item)
    const bucket = buckets.get(category)
    if (bucket) {
      bucket.push(item)
    } else {
      buckets.set(category, [item])
    }
  }

  return [...buckets.entries()]
    .map(([category, bucket]) => ({ category, label: categoryLabel(category), items: bucket }))
    .sort((a, b) => rankOf(a.category, rank) - rankOf(b.category, rank))
}

function rankOf(category: string, rank: CategoryRank): number {
  return Object.hasOwn(rank, category) ? rank[category as IndicatorCategory] : UNKNOWN_RANK
}
