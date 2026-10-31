---
id: ADR-0201
status: 채택됨
date: 2026-11-01
applies_to:
  - "src/main/java/com/stockscreener/pipeline/datasource/kr/DartXbrlStatementRows.java"
  - "src/main/java/com/stockscreener/pipeline/datasource/kr/DartEarlierInstances.java"
  - "src/main/java/com/stockscreener/pipeline/datasource/kr/DartFullStatementAdapter.java"
  - "src/main/java/com/stockscreener/pipeline/datasource/kr/xbrl/DartXbrlFactReader.java"
  - "src/main/java/com/stockscreener/pipeline/datasource/kr/xbrl/KrXbrlCollector.java"
  - "src/main/java/com/stockscreener/pipeline/datasource/kr/xbrl/KrXbrlStore.java"
  - "src/main/java/com/stockscreener/pipeline/collector/FinancialWriter.java"
  - "src/main/java/com/stockscreener/pipeline/datasource/kr/xbrl/DartXbrlLabels.java"
  - "src/main/java/com/stockscreener/pipeline/datasource/kr/DartXbrlClient.java"
---

# ADR-0201. 국내 이른 판의 전체 재무제표는 그 판 자신의 XBRL 인스턴스로 짓는다

## 결정

국내 관문 켬(`stockscreener.gate.kr`)에서 전체 재무제표 어댑터가 저장된 이른 판(소스 판 · 복사본과 유도 판 아님)마다 **그 판의 XBRL 인스턴스**
(`kr-xbrl-collect`가 받아 둔 디스크 캐시 · 0콜)를 응답 행 모양으로 옮겨(`DartXbrlStatementRows`) 같은 매퍼로 보고서를 짓고 함께 낸다. 쓰기기는
국내 관문 켬에서 전체 재무제표 쓰기(`FILL_WITHOUT_REVISION`)도 같은 공시일의 이른 판 행을 채운다(판은 안 넣는다).

원칙 하나: **판마다 그 판의 원장으로 짓는다.** DART 재무 API는 보고서마다 가장 늦은 판만 주므로, 정정 전 판이 최신이던 동안 전체 재무제표를
못 받은 저장 판은 그 칸(지배 자본 · 지배주주 순이익 · 매출원가 · 현금흐름 · 법인세 …)이 영영 빈다 — 원장(인스턴스)은 주는데 구현이 못 받던 가짜 한계다.

## 규칙

- MUST: 인스턴스 행은 **같은 보고서의 최신 응답이 그 표에 실은 줄**(`account_id` · 접두어도 응답 것)만 옮긴다 — 인스턴스에는 주석의 표준 사실도
  실려(주당이익 주석의 보통주 귀속 중단영업 등) 그대로 옮기면 매퍼가 본문에 없는 줄을 집는다(대조: 지배 중단영업 다름 6 · 인스턴스만 10 → 0).
- MUST: 대응은 재무상태표 = 기말 시점 · 손익 당기 = 그 분기 3개월(사업보고서 12개월) · 누계 = 사업연도 시작부터 · 현금흐름 = 누계 · 연결 =
  `ConsolidatedMember`(없으면 별도). 연결 축 밖의 차원이 붙은 사실은 버린다 · 같은 칸에 값이 갈린 사실은 모른다(버린다).
- MUST: 인스턴스의 가장 늦은 기말이 그 기간 말일이 아니면 그 보고서가 아니다(비12월 결산의 달력 근사).
- MUST: 인스턴스로 지은 보고서는 **부재를 판정하지 않는다**(`RawFinancialReport.withoutAbsenceJudgement` — 쓰기기는 판정된 부재로만 저장값을
  지운다). 인스턴스에 그 기간 중단영업 사실이 있는데 행으로 못 옮겼으면(누계로만 · 본문 줄에 없음 · 값 갈림) 중단영업은 「모른다」
  (`withoutDiscontinued` — 매퍼의 「줄이 없으면 0」은 응답에 줄이 없을 때의 뜻이다 · 폴백 근거를 소스마다 다시 묻는다).
- MUST: 계정명 — 표준 줄은 최신 응답의 그 줄 이름, 표준계정코드 없는 줄(회사 확장 개념)은 그 판의 한국어 이름표(`_lab-ko.xml`의 표준 역할 ·
  `DartXbrlLabels`)가 최신 응답의 같은 표 비표준 줄 이름과 같을 때만. 이름표는 원본 ZIP에 함께 오므로 수집기가 인스턴스와 함께 캐시하고,
  인스턴스만 캐시하던 때 받은 **비최신** 판은 이름표만 다시 받는다(`kr-xbrl-collect` · 새로 받기 상한 안 · 값을 안 바꾸는 재료).
- MUST: 이른 판은 `NOT is_latest` · 그 라벨 저장 최신보다 이른 공시일 · 복사본(`copied_from`) · 유도 판(`derived_from`)이 아닌 소스 판만 —
  같은 날 접수가 여럿이면 가장 늦은 접수번호 하나이고 그 접수가 판독 전이면 짓지 않는다(더 이른 같은 날 접수로 물러나지 않는다 · 경고).
  쓰기기의 열린 갈래도 복사본 · 유도 판은 건너뛴다(원천이 정한다).
- MUST: 이른 판을 채우거나(쓰기기) 4분기 판을 짓고 고치고 지우면(`KrEarlierVintages`) **그 판의 공시일부터** 무효화한다 — 읽는 쪽이 빈 지배
  칸을 연결 기저로 폴백하므로 「채우기 전에는 지울 낡은 값이 없다」가 이 칸들에 안 선다.
- MUST NOT: 누계를 3개월 칸에 채우지 않는다 — 다른 개념에서 3개월 값을 받던 칸이 누계로 바뀐다(대조: 영업이익 6 · 순이익 4 판).
- MUST NOT: 인스턴스로 판을 넣지 않는다 — 저장된 판의 칸만 채운다(쓰기기 `findVintage` · 그 공시일의 행이 없으면 건너뛴다).
- 못 옮기는 것(비어 남는다 · 저장값을 안 건드린다): 자본변동표 구성요소 줄(차원 · 이익잉여금 대체) · 이름표를 아직 안 받은 판의 비표준 줄.

## 버린 대안

| 대안 | 근거(실측) |
|---|---|
| 인스턴스의 표준 사실을 다 옮긴다 | 주석 사실이 섞여 지배 중단영업 다름 6 · 인스턴스만 10(녹화본 대조) |
| 3개월 사실이 없으면 누계로 채운다(00105271 2020 반기 응답이 그렇다) | 영업이익 6 · 순이익 4 판이 누계로 바뀐다 — 응답의 일반 규칙이 아니다 |
| 다음 판의 값으로 이른 판을 채운다 | 정정이 숫자를 바꾼 판에 그 시점에 없던 값이 든다(010130 FY2022 순이익 798B → 정정 619B) |
| 이른 판을 비운 채 「한계」로 둔다 | 원장이 판마다 값을 준다 — 가짜 한계 |
| 비표준 줄을 못 옮기는 것을 한계로 둔다 | 원본 ZIP에 `_lab-ko.xml` · `_pre.xml`이 함께 온다(2026-11-01 1콜 탐침) — 수집기가 버리고 있었다 · 비표준 줄 이름 15/15가 이름표 표준 역할과 같다 |
| 인스턴스 보고서도 응답처럼 부재를 판정한다 | 인스턴스는 버린 줄(본문 밖 · 갈림)이 있어 「없다」가 판정이 아니다 — 쓰기기가 저장값을 지운다(반증 두 렌즈) |

## 검증

- 단위 · 쓰기기: `./gradlew test --tests '*DartXbrlStatementRowsTest*' --tests '*FinancialWriterTest*'`.
- 0콜 대조(인스턴스가 낸 값 = 응답이 낸 값): `DART_REPLAY_DIR=.dartcache KR_XBRL_DIR=~/.stock-screener/kr-xbrl DART_SWEEP_OUT=<tsv> ./gradlew test --tests '*DartXbrlStatementSweepTest.compare*'` — 열마다 `differ` · `instance-only`가 0이어야 한다.
- 0콜 드라이런(저장 이른 판): 같은 테스트의 `frozen`(운영에서 읽기만 해 뽑은 TSV).

## 결과

**0콜 대조(녹화본 전체 재무제표 응답 9,134 · 같은 접수번호 인스턴스 · 비교 9,110 · 2026-11-01 · 반증 반영 뒤 코드)**: 모든 금액 열에서 다름 0 ·
인스턴스만 0. 이름표를 받은 749판(응답만 있던 칸이 있던 판)까지 넣은 뒤 응답만(인스턴스가 못 냄 → 비어 남아 저장값을 안 건드림): 지배 NI
553 → 239 · 이익잉여금 190 → 138(자본변동표 차원) · 매출 133 → 16 · 영업이익 48 → 14 · OCF 42 → 30 · 순이익 33 → 16.

**드라이런(저장 이른 판 60 · 41종목 · 잡과 같은 경로 — 녹화본 재생 위 `fetchStatements` · 종목 단위 게이트 · 54행이 녹화본으로 재생됨)**: 채움 지배
자본 38 · 현금 30 · 재고 29 · 지배 NI 29 · 매출원가 26 · 세전 14 · 법인세 14 · capex 13 · OCF 10 · 이익잉여금 6 · 유동 자산/부채 5 · 중단영업 53(운영에
없는 관문 칸) · 다름 0 · 지움 0 — 주요계정 칸은 54행 전부 저장값과 같다(그 판의 인스턴스가 그 판의 원장이라는 교차 확인). 재지 않았다: 녹화본에
최신 해 응답이 없는 5종목 6행(운영 실행은 실제 응답으로 짓는다).

**판독기 리팩터**: 캐시 인스턴스 18,435 전부 판독 결과가 저장 재료와 같다(`DartXbrlFactReaderStoredTest`). 관문 꺼짐 녹화 스윕 정렬 md5 기준선
(`3cbb9e3d…`)과 같다.

**반증 두 렌즈(「고쳐서 간다」 · 이 판에서 닫음)**: 인스턴스 보고서의 부재 판정 · 저장 최신 판이 이른 판으로 다시 지어지는 술어 · 같은 날 판독 전
접수로 물러남 · 캐시 없음의 조용한 건너뜀 · 버린 중단영업 사실의 0 · 통화 「KRW」 꾸밈 · 복사본 · 유도 판 왕복 · 채움의 무효화 전제(관문 켬 채움은
그 판의 공시일부터 무효화 — 쓰기기 일반 규칙) · 잡 기록의 복원 · 재맞춤 수 · 소스 4분기 이른 판의 빈 유량 칸 · 이름표가 ZIP에 있는데 버림.
같은 축의 더 큰 결함(비지배 게이트 증거)은 `docs/reload-gate.md` 「국내 매퍼가 원장이 주는 지배주주 순이익을 못 집는다」 행에서 닫았다.
