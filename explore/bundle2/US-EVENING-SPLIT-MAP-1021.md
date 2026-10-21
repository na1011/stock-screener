# 미국 저녁 사슬 분리 — 결합 지도 (탐색 에이전트 보고 요약 · 2026-10-21 · 읽기 전용 조사)

사용자 결정(10-21): 「시장별 분리」 — 국내는 13:30 사슬 유지, 미국은 저녁(예: KST 21시 · 뉴욕 개장 전) 사슬. 아래는 계획의 재료이고 (추정) 표시는 안 쟀다.

## 지금 상태
- 정기 잡 중 시장 인자를 가진 것이 없다 — 전부 `CollectedMarkets.ALL` · 활성 유니버스 전체. 좁히는 수단은 `tickers` · `universeCode`(스케줄러 금지 · NARROW로 찍힘)뿐.
- `PipelineScheduler:183-184` 「미국은 이 시각을 쓸 수 없다」는 낡음. `DailyUpdateJob:629-630` 「미국 지수 없음」도 낡음(`MarketIndexCodes`에 US).

## 이미 시장별이라 안전
- 백분위 `PARTITION BY universe_code` · `trade_date = ?` 정확 일치 · 업종 상대값(분류 접두) · `asOfByMarket` · 0건/지수 결손 알림 · 보존 경계 · `PriceCalendar` · 화면 `MarketScope`.

## 전역이라 주의
- `IndicatorReconciler.dataAsOf` = 전 시장 최신 지표일 최댓값(`IndicatorCalendar:60-62`). 값은 안 틀림(러너가 asOf 이하 마지막 봉). KR 휴장·US 개장일엔 14:20과 저녁 asOf가 갈려 `RETAINED_TO_MARKET_4Y`가 두 판 사이를 오감(추정 미미).
- 화해 ② 파생층 거래일 목록이 시장 구분 없음(`IndicatorBackfillJob:720-726`) — 무해.

## 분리하면 깨지는 것
1. 13:30이 US까지 받으면 잠정 거래량으로 지표·백분위·워치리스트를 낸 뒤 저녁이 다시 씀 — 워치리스트 엣지가 두 번 나갈 수 있다. 13:30의 대상 · 지수 · 구멍 구간 셋 다 시장으로 좁혀야 한다(`DailyUpdateJob:199,210,281`).
2. **기업행위 적용 지연**: `PriceRefutation:32` — 효력일 뒤 봉이 없으면 APPROVED로 미룸. 지금 미보정 약 45분 → 분리 뒤 14:00 사슬에 US 봉이 없어 약 17시간. **미국 저녁 사슬에 수집 → 재구축 → 화해를 붙여야 한다.**
3. 확정 재확인 술어(`StockRegistrar:558-559` `updated_at::date <= trade_date + 1`)가 13:30 전제 — 저녁 수집 봉도 걸려 한 번 더 받음(값 무해 · 비용). `PriceGapRunsTest:260`이 묶음.
4. 신선도 시계가 `job_name` 하나(`JobFreshnessPolicy:94,294-302`) — 같은 이름이면 KR 성공이 US 실패를 가림. 잡 이름을 가르거나 시장 미완료 술어를 넓힌다.
5. 저녁 크론이 수면으로 자정을 넘기면 대상일이 바뀌고 `clampEnd`(`AlpacaPriceAdapter:236-240`)로 장중 미완성 봉 위험(추정 · 실측 없음). 여유: 13:30은 10.5시간, 21:00은 3시간.
6. 15:30의 INDICATOR_DROP · 시세 원장 감시가 US에 대해 약 18시간 늦게 잰다(거짓 양성 없음).

## 시각 소비자 (고칠 자리)
- 코드: `PipelineScheduler` javadoc 전반 · `application.yaml:17-29`(풀 1 근거 · 일곱 시각) · `:224` · `ManualWriteDoor:14-15`(+`FinancialRefillAlertTest:102` 문구 단언) · `IndicatorReconciler:63-77` · `BootstrapRunner:376-380,526` · `StockRegistrar:558` · `MembershipGapFiller`(토 20:00 → 월 13:30) · `FreshnessCatchUpJob:39-43,110-113` · `JobFreshnessPolicy:94-127` 예산 · 기타 문구 다수.
- 시험: `CronsDoneTableTest`(표 = `@Scheduled` · 요일 꼴 MON-FRI/SAT/SUN만) · `PipelineSchedulerTest`(사슬 순서 · 15:30 간격 · 「평일만」) · `PipelineSchedulerChainTest:639` 쓰기 크론 수 7 · `:74,303,355` 13:30 대상일 · 운영 시계 재생.
- ops: `crons-done.sh:45-54`(표 · 「다음 명목 발화 5분」 → 저녁 손 작업 창이 21:00에서 끊김) · `stop.sh` · `deploy.sh` · `apply-window/env.sh:29` · `00b-stop.sh` · `00-precheck.sh` · `q4v/*`(18:50 SEC 접수 창) · `08-next-regular.sh` · `09-flip.md` · `restore-runbook.md`.
- .claude: `rules/data-pipeline.md:182-194`(겹침 표 · 13:30~15:40 창) · `skills/batch-load` · `skills/browser-driving`. hooks 0.
- docs: ADR-0042 · 0116 · 0166 · 0167 · 0175 · 0176 · 0177 · 0178 · 0181 · 0141 · 0173(SEC 실키 13:20~15:45) · api-spec alpaca-bars · data-go-kr · reload-gate.md:72.
- frontend: 박은 시각 없음.

## 잠금 · 토요일
- 풀 1 · 크론은 잠금을 상한 없이 기다림. 정상일 오후 사슬과 안 겹침(끝 14:24~14:50 · G 날 16:37). 저녁 손 작업 · bootRun과 새로 경합. 리미터는 JVM 단위라 동시 실행 시 429(추정).
- 토 20:00 리밸런싱은 US 시세(꼬리 initial-load)·지표를 쓰고 20:12께 끝남(10-17). 평일 크론이면 안 겹침 — 금요일 봉은 월 저녁(지금보다 7.5시간 늦음). 토 20:00 시점 금요일 거래량은 이미 확정.
