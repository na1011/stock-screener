import { readdirSync } from 'node:fs'
import { join } from 'node:path'

/**
 * 저장소의 소스를 훑는 스캐너 spec들의 공용 워커.
 *
 * `breakpoints.spec.ts`·`terminology.spec.ts`·`theme/tokens.spec.ts` 셋이 같은 순회를 한다.
 * 사본을 두면 제외 규칙이 한쪽에서만 바뀌는데, 그때 생기는 것은 **아무 spec도 훑지 않는
 * 파일**이고 그 사실은 어디에도 드러나지 않는다.
 *
 * IMPORTANT — 테스트 전용이다. `node:fs`를 부르므로 프로덕션 코드가 import하면 브라우저
 * 번들이 깨진다. 그 파손이 이 파일을 잘못 쓰는 것에 대한 강제 수단이다.
 */
export function walkSource(dir: string, accept: (name: string) => boolean): string[] {
  return readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const path = join(dir, entry.name)
    if (entry.isDirectory()) {
      return walkSource(path, accept)
    }
    return accept(entry.name) ? [path] : []
  })
}
