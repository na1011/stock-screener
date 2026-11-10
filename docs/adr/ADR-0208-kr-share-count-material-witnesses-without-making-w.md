---
id: ADR-0208
status: 채택됨
date: 2026-11-10
applies_to:
  - "src/main/java/**/pipeline/datasource/kr/xbrl/KrShareTimeline.java"
  - "src/main/java/**/pipeline/datasource/kr/xbrl/KrXbrlCollector.java"
  - "src/main/java/**/pipeline/datasource/kr/xbrl/KrXbrlStore.java"
  - "src/main/java/**/pipeline/datasource/kr/xbrl/KrCommonIncomeRows.java"
  - "src/test/resources/db/testmigration/V70_17__kr_share_material.sql"
---

# ADR-0208. 국내 정기보고서 주식수 표는 증인으로만 쓴다 — W를 만들지 않는다

## 결정

국내 관문 켬(`stockscreener.gate.kr-provisional`)에서 정기보고서마다(사업연도 · 보고서 코드) 주식수 표 셋 — 주식총수(`stockTotqySttus` · 분기 ·
반기만 · 사업보고서 명부는 `kr_share_register`) · 증자(감자) 현황(`irdsSttus`) · 자기주식 취득 · 처분(`tesstkAcqsDspsSttus`) — 을 받아 새 표
`kr_share_material`(관문 마이그레이션 V70_17)에 원장 필드 그대로 둔다. 보통주 몫 엔진은 이것을 두 자리에서만 읽는다.

- **SW 증인**: 지분상품 없음 · W 없음 · 앞 해 명부 증인이 실패한 행에서, 앞 해 명부 + 기간 사건(일자 가중 · 소급 형태는 전 기간) ± 자기주식([−취득,
  +처분])으로 기간 가중 보통주 유통 구간을 짓고, 그 판 명부와 닫힐 때만(±0.1%) · 폭 1% 이하 · NI ÷ (E ± 0.5)와 1% 안이면 배분 0이다. 값은 지배 NI
  그대로다.
- **QREG 시점 명부**: 앞 해 명부가 없는 첫해 분기 행의 보통주 자본 몫은 그 판 · 같은 해 앞 판 분기 명부(행 공시일까지 접수된 것)로 정한다. 없으면
  그 해(미래) 명부는 값을 막는 데만 쓴다.

## 규칙

- MUST NOT: 시간표 구간으로 W 값을 만들지 않는다 — 원장 W가 없는 지분상품 행(우선주 · 신종자본)의 값은 R1 · R1+(원장이 밝힌 비보통 몫과의 닫힘)가
  정한다. 답을 아는 행에서 「명부 = W」는 교정 없이 4%가 틀렸다(연결 자기주식 · 소급 결합).
- MUST: 닫힘 검사 없이 구간을 내지 않는다 — 시작 유통은 앞 해 명부만(역산 금지: 역산은 닫힘을 구성상 참으로 만든다).
- MUST: 쓴 표의 판(`rcept_no`) 접수일이 행 공시일보다 늦으면 그날부터 보이게 한다(ADR-0207 `laterReceipt`). 원장은 그 보고서 코드의 가장 늦은 판을 준다.
- MUST: 받기 · 읽기는 관문 켬 갈래에만 둔다 — 운영 DB에는 관문 직전 마이그레이션까지 표가 없다.
- MUST: 「없음」(`013`) 표는 그 보고서 판이 그 「없음」 뒤 날짜로 목록에 오르면 다시 묻는다(사업보고서 명부와 같은 규칙 — 1~3월에 앞 해를 물으면
  영구로 굳는다).

## 검증

- 단위: `KrShareTimelineTest`(일자 가중 · 닫힘 · 반기 굴림 · 보이는 날) · `KrCommonIncomeTest`
  `swWitnessesTheIncomeOnTheShareTimeline` · `qregTakesTheQuarterRegisterInsteadOfTheYearEndOneForAFirstYearQuarter` · `KrXbrlCollectorTest`
  `collectsShareMaterialOnlyWhenGated` · `asksAgainForARegisterAnsweredBeforeItsAnnualReport`.
- 0콜 대조: 작은 DB에서 기준 구현(탐색 `KR-BLANKS-PLAN-1106.md`의 `engine4.py` + `k3b.py`)과 갈래 · 값 · 가시일이 행마다 같다(2026-11-10 · 22,604/22,604).
- 받기 드라이런: `KrShareMaterialDryRunLiveTest`(작은 DB에만 붙는다).

## 배경

증인 실패 행(작은 DB 1,227)의 약 680행은 함의 W(NI ÷ E)가 두 해 명부 사이에 있는데 그해 주식수 변동이 커서 앞 해 명부 한 점으로는 못 가르던 행이다.
계획 v3는 `stockTotqySttus` 하나만 묻고 「분기말 주식수가 원장에 없다」를 적었다 — 같은 보고서의 증자 이력 · 자기주식 표가 기간 변동을 주고, 분기
주식총수는 회사 절반이 숫자로 준다(표본 40 중 20). 표본 330콜 · 의심 종목 드라이런 9,271콜로 쟀다.

## 버린 대안

- **구간 중간값을 W로 쓴다**: 원장이 주지 않은 값을 만든다. 대조군 110 중 10은 구간이 원장 W 밖이었다(주식분할 · 합병 소급 · 연결 자기주식) — 값으로
  쓰면 틀린 값이고, 증인으로 쓰면 실패하는 쪽이다.
- **지금 증인 여유(5%)를 그대로 쓴다**: 숨은 신종자본 배분 2.3%(CJ대한통운 2018 1분기)를 통과시키고 있다. 시간표는 폭 1% · 간격 1%로 그 여유를
  약 2%로 줄인다(이마트 2024 FY 0.97%는 여전히 지나간다).
- **관문 전에 마이그레이션 · 받기를 앞당긴다**: 2015년 이후 경로당 약 1.6만 콜로 하루 18,000 안이라 관문 날 하루에 받힌다 — 관문 직전 마이그레이션
  규칙을 깰 이유가 없다.

## 결과

관문 날 `kr-xbrl-collect`(켬)가 판독기 판 2 재판독과 함께 주식수 표를 받는다(엔드포인트당 약 1.6만 · 분기 명부 약 1.2만). 작은 DB 드라이런(117종목만
받음): SW 새 값 221(4분기 포함) · 잃은 값 0 · QREG 자본 몫 값→값 4 · 모름→값 1 · 값→모름 5. 되돌리려면 `Rules.V4`에서 SW · QREG를 빼면 표는 읽히지
않는다.
