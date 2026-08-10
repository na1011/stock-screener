---
id: ADR-0041
status: 채택됨
date: 2026-08-10
applies_to:
  - "docs/external/screener-spec-midterm-swing.md"
  - "src/main/java/com/stockscreener/screening/preset/PresetCatalog.java"
---

# ADR-0041. 스윙 명세를 프리셋 한정 정본으로 올리고, 도달 경로를 셋으로 늘린다

## 결정

사람이 쓴 문서가 둘이 됐다. **프로젝트 기획 정본은 `docs/stock-screener-project-spec.md`,
중장기 스윙 프리셋 조건셋의 정본은 `docs/external/screener-spec-midterm-swing.md`이고,
둘이 충돌하면 spec이 이긴다.** 사본 하나(`docs/중장기-스윙.md`)는 지웠다.

지위 선언만으로는 같은 일이 다시 일어난다. **그 파일을 가리키는 곳을 하나에서 셋으로
늘리는 것이 이 결정의 나머지 절반**이다(`CLAUDE.md` · `docs/phase-criteria.md` Phase 7 ·
`api-sources` skill).

## 규칙

- MUST: 프리셋의 조건·임계값이 왜 그 값인지를 물을 때 이 명세를 본다. **무엇을 옮기고
  무엇을 버렸는가는 여기가 아니라 ADR-0039의 조건 대조표가 답한다.** 둘을 섞으면 원본이
  요구한 것과 우리가 구현한 것의 차이가 사라진다.
- MUST NOT: 이 문서를 에이전트 판단으로 고치지 않는다. 개정본이 오면 통째로 교체한다.
  `guard-spec.sh`가 두 정본 모두에 `ask`를 건다.
- MUST: spec과 어긋나는 요구가 이 명세에 있으면 **spec을 따르고 그 사실을 ADR-0039의
  "원본에 있으나 기획 정본과 충돌해 넣지 않은 것"에 적는다.** §6의 포지션 사이징·잠정
  등급이 그렇게 빠졌다(spec §3·§10과 `.claude/rules/frontend.md`가 매매 실행 지시를 금지).
- MUST NOT: 사본을 만들지 않는다. 저장소 어디에도 이 명세의 두 번째 벌을 두지 않는다.

## 검증

```premise 스윙 명세가 정본 지위와 충돌 규칙을 스스로 말한다
sh -c 'f=docs/external/screener-spec-midterm-swing.md; n=$(ls docs/*스윙*.md docs/external/*swing*.md 2>/dev/null | wc -l | tr -d " "); if [ ! -f "$f" ]; then echo "정본 파일이 없다 — 이름이 바뀌었거나 지워졌다"; elif [ "$n" -gt 1 ]; then echo "명세 사본이 ${n}벌이다 — 한 벌만 남긴다"; elif ! grep -q "spec이 이긴다" "$f"; then echo "충돌 규칙이 헤더에서 사라졌다 — 다음 세션이 두 정본을 동급으로 읽는다"; else echo "OK 정본 1벌 + 충돌 규칙 있음"; fi'
```

도달 경로가 셋 다 살아 있는가 — **3이어야 한다.** 줄면 이 ADR이 고치려던 상태로 돌아간 것이다.

```bash
grep -l "screener-spec-midterm-swing" \
  CLAUDE.md docs/phase-criteria.md .claude/skills/api-sources/SKILL.md | wc -l
```

## 배경

이전 세션은 이 명세를 제대로 기록했다 — `docs/external/`에 두고 13줄 헤더로 "§6은 사용자
결정으로 제외"까지 적었다. **그런데 그 파일을 가리키는 곳이 ADR-0039 본문 한 줄뿐이었다.**
사용자는 기록이 없다고 판단했고 — 35KB ADR 본문 안의 한 줄이 유일한 경로였으니 합리적인
판단이다 — 같은 문서를 `docs/중장기-스윙.md`로 다시 올렸다. **사본이 둘이 됐고 새 쪽에는
§6 경고 헤더가 없었다.**

즉 병은 "기록하지 않았다"가 아니라 **"기록했는데 도달할 수 없었다"**이고, 도달할 수 없는
정본은 없는 정본과 같은 결과를 낸다. 지위를 올리면서 경로를 함께 늘리는 이유가 이것이다.

## 버린 대안

- **`docs/중장기-스윙.md`로 통일** — 한글 이름이 알아보기 쉽다. 버린 이유는 `docs/` 최상위가
  spec과 같은 층이라 **"어느 쪽이 정본인가"를 매번 다시 묻게 되기 때문**이다. `external/`은
  폴더 이름 자체가 "밖에서 받은 것"을 말해 층을 드러낸다.
- **"정본 아님"을 유지** — 변경이 가장 작다. 버린 이유는 사용자가 이 문서를 프리셋 기획서로
  쓰고 있고, 지위를 부정한 채 두면 **같은 오해가 반복되기 때문**이다. 실제로 한 번 반복됐다.
- **spec과 동급 승격** — 버린 이유는 정본이 둘이 되어 범위 경계를 매번 판단해야 하기
  때문이다. 층을 나누고 충돌 규칙을 박는 쪽이 판단을 한 번만 하게 한다.

## 결과

- **쉬워지는 것**: 프리셋 조건의 근거를 묻는 세션이 세 경로 중 아무 곳에서나 명세에 닿는다.
  `guard-spec.sh`가 임의 수정을 막으므로 명세와 구현이 조용히 수렴하지 않는다.
- **어려워지는 것**: 사람이 쓴 문서가 둘이라 **어느 쪽 범위인지를 판단해야 한다.** 충돌
  규칙(spec 승)이 그 판단을 한 줄로 줄이지만 없애지는 못한다.
- **되돌리려면**: 헤더 주석 · `CLAUDE.md` 한 줄 · `guard-spec.sh`의 두 번째 경로 ·
  `phase-criteria.md` Phase 7 머리 넷을 되돌린다. 코드는 건드리지 않는다.
