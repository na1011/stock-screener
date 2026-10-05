# (가) companyfacts가 빠뜨린 최신 분기 — 실측 (2026-10-05 23시 · SEC 15콜 · 탐침 `SecGapProbeLiveTest.java`·`A2ListingProbeLiveTest` facts 모드 — 커밋 안 한 일회 시험)
- 현 편입 미국 1,000 중 6월 분기 행이 0인 것 80(브랜치 `explore/universe/refute5/q04`) — ABT·KO·NEE·DUK·SO·SPGI·PYPL·CB …
- **submissions**: ABT 10-Q 2026-06-30(07-28 공시 · `0001628280-26-050134` · isXBRL 1 · inline 1) · KO 2026-07-03(07-29) · NEE 06-30(07-24) · DUK 06-30(08-04) — 전부 있다.
- **companyfacts**: 그 공시의 팩트 0 — 팩트의 최대 `filed`가 ABT 04-29 · KO 04-30 · NEE 07-09 · DUK 08-12(그 10-Q가 아닌 다른 공시) · **대조군 AAPL 07-31(그 10-Q 포함)**.
- **frames**(`us-gaap/Assets/USD/CY2026Q2I` 5,486 CIK · `NetIncomeLoss/USD/CY2026Q2` 4,931): ABT `0001628280-26-050134` Assets 109,215,000,000 · 순이익 928,000,000 · KO `0001628280-26-050503` 있음 · **NEE·DUK 없음** · AAPL 있음 → frames는 일부를 준다(부분 커버).
- **공시 묶음**(`Archives/edgar/data/1800/000162828026050134/index.json`): XBRL 인스턴스 `abt-20260630_htm.xml`(1.85MB) · `-xbrl.zip` · `FilingSummary.xml` · R 파일 64 — **공시 자체가 구조화 값을 준다.**
- **SEC 재무제표 데이터셋**: `2026q2.zip` 200(60.4MB · 08-19 수정) · `2026q3.zip` **404**(7~9월 공시 묶음은 아직 안 나옴) — 지금은 이 경로로 못 닫는다.
- 판정: **가짜 한계** — ① companyfacts · frames · 공시 묶음 · 데이터셋 넷에 물었다 ② 대조군 AAPL은 companyfacts에서 받았다 ③ 모집단 = 현 편입 미국 1,000(6월 분기 0인 80 중 넷을 물음 — 나머지 76은 재지 않음) ④ 뿌리 = 어댑터가 companyfacts 하나만 읽는다 · 0콜 재현 = `facts-*.json`의 최대 `filed` < `subs-*.json`의 10-Q `filingDate`.
