---
id: ADR-0192
status: 채택됨
date: 2026-10-25
applies_to:
  - "src/main/java/**/datasource/kr/DartXbrl*"
  - "src/main/java/**/datasource/kr/xbrl/**"
  - "src/main/java/**/batch/KrXbrlCollectJob*"
  - "src/main/java/**/collector/KrCommonIncome*"
  - "src/main/resources/db/migration/V69_5__dart_xbrl_material.sql"
  - "src/test/resources/db/testmigration/V70_6__kr_common_income.sql"
---

# ADR-0192. 국내 주당 지표 분자는 DART XBRL 표준 재료로 지은 보통주 몫이다(국내 관문 뒤)

## 결정

국내 주당 지표 분자(PER · EPS 계열)를 지배 NI가 아니라 **보통주 귀속 이익**으로 바꾼다(2026-10-25 사용자 결정 「분자를 보통주 몫으로」 ·
ADR-0098 기록). 재료는 정기 공시 판마다의 **DART 재무제표 원본(XBRL · `fnlttXbrl.xml`)**과 **주식총수 명부(`stockTotqySttus`)**의
표준 분류 사실뿐이다. 판정은 재무 행(판 하나)마다 `financial_common_income`에 붙이고, 값을 바꾸는 판정 · 읽기는 국내 관문
(`stockscreener.gate.kr`) 뒤에 둔다. 재료 받기는 값을 안 바꾸므로 관문 앞에 먼저 놓는다(무해한 선행 배포).

판단 기준(2026-10-25 사용자): 「원장 자체가 뒤틀려서 특정 종목만을 위한 로직이 들어가야 한다면 정합성 훼손으로 판단해. 원장에서 주는
정보로 조합이 되면 개발을 해.」 — 모든 종목에 같은 규칙으로 표준 재료에서 지어지는 것만 값으로 내고, 그 밖은 미표시다.

계획 · 반증 · 실측 전부는 탐색 브랜치 `explore/financial-sync/KR-NUM-PLAN-1025.md`(§8 v2.1 · §9 · §10 v1.1)에 있다.

## 규칙

**재료**
- MUST: XBRL은 접수번호(판)마다 받는다 — 판마다 인스턴스가 다르다(`dart-xbrl.md` · 고려아연 FY2024 세 판). 재무 행의 판은
  `published_at`이 종목별 정기 공시 목록(`list.json` corp_code)의 접수번호 앞 8자리 또는 `rcept_dt`와 같은 접수번호다.
- MUST: 받은 인스턴스는 gzip으로 디스크 캐시(`stockscreener.dart.xbrl-cache-dir`)에 두고, 판독기를 지난 재료 사실만 DB(`dart_report_xbrl`)에
  둔다 — 판독기 판이 바뀌면 캐시에서 0콜로 다시 판독한다. 캐시는 다시 받을 수 있는 원본이라 되돌리기 재료가 아니다.
- MUST: 주식총수 명부는 재무 행이 있는 사업연도와 그 앞 해의 11011을 받아 `dart_share_register`에 둔다 — `governance_report_row`에
  쓰지 않는다(거버넌스 유도를 움직이면 관문 밖에서 값이 바뀐다).
- MUST NOT: 명부의 구분 라벨로 보통주를 가르지 않는다 — 회사마다 자유 문구다(`dart-xbrl.md`). 「합계 · 비고를 뺀 발행수 > 0 종류 행이
  둘 이상」이면 보통주 밖 주식이 있다 · 보통주 수는 가장 큰 종류 행이다(상법 제344조의3 — 의결권 없는 · 제한 주식은 발행 총수의 1/4 이하).
- MUST NOT: 회사 고유 확장 개념(`entity…:`)을 값의 재료로 쓰지 않는다. 회사 구성원은 표준 축(`ClassesOfShareCapitalAxis`)의 우선주 종류에만
  받는다 — 표준 축 · 표준 개념의 종류별 합은 모든 종목에 같은 규칙이다.
- MUST NOT: 원본 문서(`document.xml`) 주석표를 파싱하지 않는다 · 분기 주식총수로 가중평균을 근사하지 않는다 · 단위 오태깅을 배율로
  고치지 않는다(사용자 기준 — 정합성 훼손).

**판정(재무 행 하나 · 그 기간 · 그 기저)**
- MUST: 앵커 — 행 지배 NI = 연결 `ProfitLossAttributableToOwnersOfParent`(그 태그가 없으면 연결 `ProfitLoss`) · 지배 NULL 행은 행 NI = 별도
  (또는 연결 맥락이 없는 파일의 축 없는) `ProfitLoss`. 안 맞으면 미표시(`none:anchor`). 그 뒤 재료는 앵커가 정한 기저의 맥락만.
- MUST: 보통주 밖 지분상품이 있으면 값은 E × W(표준 보통주 EPS × 표준 보통주 가중평균 · 허용 구성원 {보통주 · `dart:ReportedAmountMember` ·
  `EquityAttributableToOwnersOfParentMember` · `ParentMember`})다 — 검산이 닫혀도 지배 NI로 안 바꾼다. 원장 EPS가 반올림이면 참 몫은
  E × W ± 0.5W 안이고 지배 NI의 오차 상한은 W라 최악 오차가 작은 쪽이다. 직접 태그(`ProfitLossAttributableToOrdinaryEquityHoldersOfParentEntity`)가
  그 안이면 그 값.
- MUST: 지분상품이 없고 E × W가 지배 NI와 반올림(0.5W) 안에서 닫히면 값은 지배 NI(`kr-ni` · 더 정밀하다). 닫히지 않으면 지분상품 증거가
  없어도 E × W다 — 감사받은 보통주 EPS가 보통주 몫을 직접 밝히고, 구조 추론은 EPS × W가 없을 때만 쓰는 간접 증거다(드라이런 이력 표본:
  NAVER FY2024 지배 NI 1,923.2B 대 E × W 1,912.8B · 0.54% — 숨기면 그 FY가 Q4를 막아 최신 TTM까지 숨는다). W가 없으면 구조로 `kr-ni`이고
  E × 기말 보통주 수 증인(±5% · 놓친 지분상품의 큰 배분만 거른다)을 건다. 명부가 그 해 · 앞 해 둘 다 없으면 구조 미정 → 미표시.
- MUST: 「지분상품 있음」 = 명부의 종류 둘 이상 ∨ 그 판 인스턴스의 `dart:` 표준 신종자본증권 개념(이름에 `Hybrid` · 값 ≠ 0 · 비지배 구성원 밖)
  ∨ 표준 우선 개념(`dart:BasicEarningsLossPerSharePreferredStock*` · `dart:IssuedCapitalOfPreferredStock` · `ifrs-full:*OtherThanOrdinaryShares`
  EPS · W). 태그 하나의 부재를 지분상품의 부재로 읽지 않는다(반증 — GS건설은 `HybridBonds` 없이 이자 개념만 있다).
- MUST: W ÷ (명부 앞 해 보통주 유통 × 그 뒤 기간 끝까지 APPLIED 기업행위 배수)가 0.5~2 밖이면 미표시(단위 · 주식-일 같은 큰 오류) ·
  W가 보통주 밖 포함 유통과 3% 안이고 보통주 밖 비중이 3% 넘으면 미표시(구형우선주를 보통주에 섞음).
- MUST: Q4 = FY 몫 − Q1~Q3 몫(각 분기는 `published_at` ≤ Q4 행 `published_at`인 마지막 판) · 하나라도 못 정하면 미표시.
  3개월 E · W가 없고 누계가 서면 누계 몫 − 직전 보고서의 누계 몫.
- MUST: 배당성향 분모(2026-10-25 사용자 결정 「지배 NI − 신종자본증권 몫」) — 신종자본증권 증거가 없으면 지배 NI 그대로 · 있으면 보통주 몫 +
  우선주 몫(명부가 한 종류면 0 · 아니면 우선주 종류마다 E_p × W_p의 합 · Σ W_p를 명부 보통주 밖 유통과 맞대는 증인) · 그 밖 미표시.
  `ProfitLossAttributableToParticipating…OtherThanOrdinaryShares`는 회사마다 뜻이 갈려(신종자본증권 몫으로도 쓴다) 안 쓴다.
- MUST NOT: 비교열(뒤 공시가 다시 실은 앞 기간)을 앞 판 행에 붙이지 않는다 — 그 판이 보이던 날들에 원장이 몰랐던 값이다.

**저장 · 관문**
- MUST: 재료 표(`V69_5`)는 main 마이그레이션이다 — 관문 꺼짐에서 읽는 코드가 없어 「마이그레이션은 관문 직전」의 사유가 안 선다.
  `financial_common_income`의 국내 열(`V70_6`)은 시험 마이그레이션이고, **먼저 여는 관문이 V70~V70_6 전부를 번호 순서대로** main으로
  옮긴다(운영 Flyway는 out-of-order가 꺼져 있다).
- MUST: 읽는 쪽은 붙일 때의 행 스냅숏(`published_at` · `coalesce(net_income_controlling, net_income)`)이 지금 행과 같을 때만 쓴다 — 국내
  `report_code`는 판을 안 가른다.
- MUST: 관문 꺼짐 출력은 main과 바이트까지 같다.

## 검증

- 판정 규칙: `./gradlew test --tests '*KrCommonIncome*'` — 갈래마다 · 앵커 · Q4 · 누계 · 배당성향 성분.
- 판독기: `./gradlew test --tests '*DartXbrlFactReader*'` — 두 형식(2019 분류체계 기본 이름공간 · `xbrli:` 접두) 픽스처.
- 0콜 스윕: `DartCommonIncomeSweepTest`(`KR_XBRL_DIR` · `KR_ROWS` · `KR_REGISTER` · `KR_CA`) — 받아 둔 2026 H1 346의 갈래 수가 탐색의
  파이썬 기준 구현(`kr5/plan9h.py`)과 같다.
- 회사 확장 개념을 값 재료로 안 쓴다: `rg -n '"entity' src/main/java/com/stockscreener/pipeline/datasource/kr/xbrl` 가 비어야 한다.

## 결과

(구현 · 드라이런 뒤에 수로 채운다.)
