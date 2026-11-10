---
id: ADR-0206
status: 채택됨
date: 2026-11-10
applies_to:
  - "src/main/java/**/indicator/calculator/ReportingBasis.java"
  - "src/main/java/**/indicator/calculator/FinancialMath.java"
  - "src/main/java/**/indicator/calculator/ValuationHistory.java"
  - "src/main/java/**/indicator/ProvisionalRelease.java"
  - "src/main/java/**/indicator/IndicatorContext.java"
  - "src/main/java/**/pipeline/datasource/kr/DartProvisionalDocument.java"
  - "src/main/java/**/pipeline/datasource/kr/DartViewerClient.java"
  - "src/main/java/**/pipeline/collector/KrProvisionalStore.java"
  - "src/main/java/**/pipeline/collector/KrFilingTimeStore.java"
  - "src/main/java/**/pipeline/batch/KrProvisionalJob.java"
  - "src/main/java/**/stock/FinancialBasisReader.java"
  - "src/test/resources/db/testmigration/V70_14__kr_provisional_release.sql"
---

# ADR-0206. 잠정실적은 그 분기의 손익 행으로만 들어간다

## 결정

국내 잠정 관문 켬(`stockscreener.gate.kr-provisional`)에서, 새 실적 발표로 재무 지표를 막은 봉(ADR-0195)이라도 그 분기의 잠정실적 공시가 서면
**그 분기의 손익 행**을 지어 봉 사본에 넣는다(`ReportingBasis.provisionalQuarter` → `FinancialMath.financialsAt`). 그 행으로 계산이 서는 지표만
값을 내고 나머지는 지금처럼 정식 보고서까지 빈다. 같은 분기면 정식이 이긴다.

- **원자료**: DART 웹 공시 뷰어 본문(I002 영업(잠정)실적 · I001 매출액또는손익구조 변동) — 원문 바이트를 파일 캐시에 두고(`stockscreener.dart-prov.cache-dir`)
  해석 결과를 관문 표 `kr_provisional_release`에 둔다(시험 전용 마이그레이션 V70_14 → 관문 직전 주 경로).
- **행의 칸**: 매출 · 영업이익 · 세전이익 · 순이익 · 지배 순이익(3개월)뿐. 잔액 · 현금흐름 · 주식수는 비고, 보통주 몫은 「못 정함」(갈래
  `none:provisional`), 중단영업 판정 규칙은 직전 정식 분기의 것을 잇는다. 연간 판은 그 봉 사본에서 비운다(지금 막기와 같다).
- **함께 고친 것**(같은 관문 · 이 흐름이 지나가는 날짜와 판정): 공시 게시 시각의 재게시 줄이 원래 시각을 덮던 결함(`KrFilingTimeStore`) ·
  월간 실적을 실적 발표로 읽던 것(`EarningsReleaseStore.periodReleaseOnly`) · 상세 카드의 「대기」 · 「잠정」 표지와 재무 기준 상태
  `PROVISIONAL`(결과 행 · 워치리스트 보류 · 알림 문구).

## 규칙

- MUST: 잠정 행이 서는 조건은 넷 전부다 — ① 그 봉의 유량 기준이 분기 기저이고 공시 기간이 그 다음 분기다 ② 양식 기준(연결 · 별도)이 정식 판
  기준(`financial_common_income.statement_basis`)과 같고 정식 판이 원화다 ③ 항목마다 자체 검사 — 당해 = 누계 − 같은 해 정식 앞 분기 합이
  두 값의 반올림 폭(문서 끝자리 반 칸씩) 안 · 1분기는 당해 = 누계 · 4분기는 당해가 없으면 결산 − 정식 1~3분기 ④ 넣은 사본이 그 행을 유량 기준으로
  삼고 그보다 새 발표가 없다. 하나라도 안 서면 전체 막기다.
- MUST: 지표 계산과 재무 기준 표시가 `ReportingBasis.provisionalQuarter` 한 함수를 쓴다. `ValuationHistory`는 `financialsAt`과 같은 사본을 쓰고,
  잠정 행으로 계산한 봉에서는 그 봉 표본이 있을 때만 중앙값을 낸다.
- MUST: 못 읽은 본문도 사유 행으로 남긴다. 실적 발표 판정은 「읽어서 분기 · 연도가 아님을 확인한 줄」만 발표에서 뺀다.
- MUST NOT: 잠정 행을 주식수 입구(`sharesBySegment`) · 거버넌스 `annualOf` · `PbrMedian5y` 기저 판정에 넣지 않는다 — 셋 다 바깥 `visibleAt`이다.
- MUST NOT: I001의 재무상태 합계로 잔액을 채우지 않는다(사용자가 승인한 설명 「PBR · 부채비율 · 현금흐름 지표는 정식까지 빔」).
- MUST: 관문 꺼짐 · 미국은 잠정 본문이 0건이라 지금과 같다(리더가 빈 목록을 낸다).

## 검증

- `./gradlew test --tests "*ProvisionalQuarterTest" --tests "*DartProvisionalDocumentTest" --tests "*KrProvisionalStoreTest" --tests "*KrFilingTimeStoreTest"`
- `rg "visibleAt\(" src/main/java/com/stockscreener/indicator/calculator/ValuationHistory.java` — 막기 판정용 사본 한 자리만 있어야 한다(재무 사본은 `financialsAt`).

## 배경

정식 보고서는 분기 말 뒤 45일 안팎에 오고, 그 앞 몇 주에 같은 분기의 잠정실적이 나온다. ADR-0195는 그 사이를 「새 실적 · 옛 재무」로 보고 재무
지표를 통째로 막았다. 막기 자체는 맞다(가격은 새 실적에 반응했다). 그러나 원장이 그 분기의 표준 서식 손익을 이미 주고 있었다 — 2분기 연결 양식
204종목의 그 분기 마지막 잠정 대 첫 정식이 0.1% 안: 매출 184/187 · 영업이익 194/202 · 순이익 182/193 · 지배 순이익 175/184. 원장이 주는 값을
안 쓰고 비운 것은 가짜 한계다. 사용자 결정(2026-11-10): 흐름 제안 → 「진행해」, 주당 지표는 「(나) 원칙대로 비움」(EPS · PER · PEG는 정식까지 빔 ·
예외 규칙 없음).

## 버린 대안

- **잠정 값으로 연간 · 잔액 지표까지 채운다**(I001 재무상태 합계 · 빈 행으로 연간 지표 살리기) — 승인 설명을 넘는다. 두 렌즈 반증(계획 v1)이 「틀린
  설명으로 받은 승인」으로 잡았다.
- **보통주 몫을 직전 분기 갈래로 잇는다**(가) — 실측 `kr-ni` 다음 분기도 `kr-ni` 90%였다. 기각: 잠정은 보통주 귀속 이익을 안 주고, 직전 분기의 관계를
  이어 붙이는 것은 원장이 안 준 값을 짓는 것이다. 사용자는 처음 「(나) 원칙대로 비움」을 골랐고, 그 선택지 설명의 「성장률 잠정으로 채움」이 틀렸던 것이
  드라이런으로 드러나 다시 물었을 때(2026-11-10) 「대원칙대로 판단해」라고 답했다 — 대원칙(그날 공개된 원장 값만 · 원장이 안 준 값은 짓지 않는다)으로
  (나)를 유지한 것은 내 판단이다.
- **정식 보고서까지 항상 기다린다**(ADR-0195 그대로) — 위 실측으로 원장이 값을 준다.

## 결과

- 막힌 봉의 손익 지표(매출 · 이익률 · 성장률 · PSR · PER 중앙값 표본)가 잠정 공시 다음 보이는 봉부터 선다. 주당 · 재무상태 · 현금흐름 · 연간 지표는
  정식 보고서까지 비고 카드에 「대기」가, 잠정으로 계산한 카드에 「잠정」이 붙는다.
- **성장률 다섯은 잠정 기간에 빈다**(드라이런 실측) — 매출 · 영업이익 · 재고 성장률은 연간 기준, EPS 성장률 둘은 EPS 기반이다. 1~3분기 잠정은 연간 값을 안 주므로
  옳다. **4분기 결산 공시는 연간 손익을 주는데 지금 그 연간 판도 비운다** — 대원칙과 어긋나 관문 전에 고친다(`docs/progress.md`).
- 연간(4분기) 잠정은 분기보다 덜 맞는다(FY2025 순이익 0.1% 안 192/329 · 5% 넘게 다름 42). 별도 기준 잠정의 정확도 · 첫 잠정 판(정정 전)의
  정확도 · 자체 검사 통과율은 **재지 않았다** — 관문 전 0콜 스윕으로 잰다.
- 되돌리려면: 설정을 끈다(관문 전) · 관문 뒤면 `kr_provisional_release` 표와 `ReportingBasis.provisionalQuarter` 호출 두 자리를 걷는다.
