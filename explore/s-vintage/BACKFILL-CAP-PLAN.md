# 관문 선행 — 재무 정정 자동 백필 상한(100)을 실측으로 다시 정한다 (계획 v1 · 2026-10-04 · 반증 전)

## 뿌리
- `InvalidationBackfiller.AUTO_BACKFILL_FINANCIAL_STOCK_LIMIT = 100` — 넘으면 백필을 안 돌리고 경고만 남겨 **그 종목들의 지표가 지워진 채 남는다**(`:110`).
- javadoc의 근거는 둘이다: 사건률(국내 표본 30종목 → 2종목 · 350 외삽 ≈ 23) · 비용(「전 종목 단일 날짜 3분 45초 · 100종목이면 훨씬 싸다 · 그보다 많으면 전수 재적재 같은 예외적 상황」).
- **둘 다 지금 서지 않는다**:
  - 사건률(미국): 스윕 정정판 근사로 28일 창 49개(2023-01~2026-10 · 현 편입) 중 **11개가 100 초과**(최대 185 · 2025년 165·147·112 · 재현함 — `p3/rv-out/radius.py`). 시즌마다 넘는다 — 예외가 아니다.
  - 비용: 이 백필은 「단일 날짜」가 아니라 **보존 경계부터 그날까지**(`clampToRetention(null)`) 돈다. 실측 종목당 **17.2~19.3초**(`batch_execution` 389·478·499·533 — 318~518종목 × 269일 · 91~166분).
    185종목 ≈ 56분 · 400종목 ≈ 2시간.
- 정기 `financial-refill`은 목요일 14:15에 마지막 성공 뒤 25일이 넘으면 돈다(`PipelineScheduler`) — 지금까지 SCHEDULER 실행 0(MANUAL 91). **첫 정기 실행이 10월 말 미국 Q3 시즌에 걸린다.**

## 수정
1. 상한을 **400**으로 올리고 javadoc 근거를 위 실측으로 바꾼다 — 시즌 반경(미국 최대 185 + 국내 몫)과 규칙 변경 재적재(관측 466·552·757)를 가르는 자리이고 최악 소요 약 2시간(스케줄러 스레드 1 — 그날 15:30 신선도 점검만 밀린다).
2. 시험: 상한 경계(400 = 돈다 · 401 = 안 돈다)를 단언하는 기존 시험이 있으면 고치고, 없으면 더한다.

## 배포
- 운영 jar는 10-01 빌드이고 그 뒤 main의 `src/main` 변경은 S0 세 파일뿐이다(설정 꺼짐 = 출력 바이트 동일 · 스윕 md5로 확인됨). 이 수정을 얹어 배포하면 바뀌는 동작은 상한 하나다.
- **배포는 사용자 승인이 필요하다** — 첫 정기 refill(10월 말) 전에 올린다.

## 재지 않은 것
- 국내 시즌 반경(국내 분기보고서 11월 중순) — 미국과 같은 실행에 묶이므로 합이 상한 판단에 든다.
- 10월 말 첫 정기 실행의 실제 반경(시즌 초입).
- 백필 2시간 동안 같은 PG에서 도는 다른 잡과의 경합.

## 반증 v1 판정 (2026-10-04 · 두 렌즈) — 상한값이 아니라 백필 모양을 고친다
1. **종목당 17~19초는 「해야 할 일」이 아니라 지금 경로의 중복을 잰 수다** — 자동 경로 `run(floor..asOf, tickers)`는 269개 날짜마다 원본층을 다시 계산하는데 `IndicatorRunner.run`은 한 번에 보존 창 전체를 쓴다(앞 268회는 덮인다 · batch-load skill · [A46]).
   두 단계 모양(① 무효화 종목 전부를 최신 거래일 하루로 ② 보존 경계~asOf 파생층 루프)의 실측: 665+666 — 1,687종목 9.7분(같은 일을 날짜 루프로 돈 520은 1,350종목 411.9분) · 434+435 — 214종목 6.1분(521은 63.1분).
   **두 단계면 상한을 둘 비용 사유가 없다** · `backfillAdjustment`(상한 10 · 근거 「2.6~8시간」)도 같은 모양. 함정: asOf가 두 시장 휴장이면 `tradeDatesBetween(asOf, asOf)`가 비어 0거래일 SUCCESS — ①은 `max(trade_date) ≤ asOf`로.
   두 모양의 최종 `indicator_value`가 바이트까지 같은지는 재지 않았다(사본 드라이런).
2. **「넘으면 경고만」은 조용한 파생층 결손이다** — 원본층은 다음 평일 13:30 일일 갱신이 다시 쓰고(약 23시간~주말 사흘 공백) 남는 것은 그 종목들의 과거 날짜 파생층(백분위·업종)이다. `log.warn` 한 줄 · Sentry 최소 error · refill 알림은 백필 여부와 무관하게 같다 · `INDICATOR_DROP`은 거짓.
3. **반경 근사는 실제 무효화 목록과 다른 집합이고 과소다** — 실제 목록은 두 시장 한 목록 · 교정·판 회수도 건다(실제 full refill은 매번 교정 > 정정) · 미국 슬라이딩 창 최대 26일 197 · 38일 213 ·
   **첫 정기 실행은 10-30~11-05 사이**(세 무거운 잡의 시계가 10-03에 함께 리셋 · 동률을 `Map.of` 순회 순서로 가름 · 데이터 창 미국 32~38일 · 국내 38~44일) · 8년(정기) 대 11년(저장본) 드리프트는 안 쟀다 ·
   **[A30](채움·새 라벨 무효화)을 닫으면 미국만 28일 최대 831** — 400은 [A30]이 열린 상태에서만 서는 수.
4. 400은 비용이 고른 임계다 · 관문 뒤 첫 정기 refill이 손 재적재보다 먼저 돌면 그 refill 자체가 규칙 변경 재적재가 된다.
5. 같은 순환에서 `full-statement-refill`(FS)·`governance-load`(G)도 지금 jar로 돈다 — FS는 백필을 안 잇는데 교정은 무효화를 부른다 · KOSDAQ 마지막 전량 FS(09-21)가 `DartFullStatementAdapter` 수정(`2c6dc51`·`29ae624`)보다 앞이다.
6. 배포가 바꾸는 것: 서버 클래스는 S0 세 파일뿐(jar CRC 대조) · **프론트 번들 44개 + index.html이 다르다**(프론트 커밋 09-26 뒤 0 — 원인 안 잼) · 저장소 `.env`가 배포로 복사되므로 거기 `US_GATE`가 켜져 있으면 배포가 운영 S0를 켠다(확인 못 함 — `.env`는 안 읽는다).
7. 시험: 재무 상한을 단언하는 시험이 없다 · 「401이면 안 돈다」 경계 시험은 결함을 명세로 잠그는 일 → 두 단계 순서 · 휴장 asOf · (상한이 남으면) 초과 갈래에서도 파생층 루프.

**판정**: v1(상한 400)은 버린다. v2 = `backfillFinancial`·`backfillAdjustment`를 두 단계 모양으로 · 상한 제거(이상 반경은 알림으로만) · [A30](채움·새 라벨 종목도 백필 대상) 한 수정 · 15:30 점검에 「무효화 뒤 백필 SUCCESS 없음」 사슬 술어 · 사본에서 두 모양 바이트 대조.
**사용자 판단**: 관문이 10-30 전에 안 열리면 그 사이 정기 무거운 잡(FR 두 시장 · FS · G — 지금 jar)을 돌게 둘지 멈출지 — 그 답이 관문 전 별도 배포를 정한다.

# 계획 v2 (2026-10-04 · 반증 전) — 두 단계 백필 · 상한 제거 · 무거운 잡 뒤 파생층 창 재생성

**전제(사용자가 판단을 맡겼고 내 판단 · main `docs/progress.md` 「재적재 관문」)**: 관문 전 정기 무거운 잡을 멈추지 않는다 → 첫 정기 실행(10-30~11-05) 전에 이 수정을 배포한다(배포 직전 사용자 확인).

## 코드로 확인한 사실
- `IndicatorRunner.run(stock, asOf, …)`는 계산한 점 전부를 `retain(points, cutoff)`로 써 **보존 창 전체**를 한 번에 쓴다 · 값은 `KNOWN_ON`이라 **날짜 d의 값이 asOf와 무관**하다(javadoc · ADR-0128).
- `IndicatorBackfillJob.run(from, to, …, tickers, …)`은 날짜마다 ① 대상 종목 원본층(위 러너) ② 그날 백분위·업종(**전 종목** — 날짜만 받는다)을 돈다 → 원본층 계산이 날짜 수만큼 반복된다(앞 것은 다음 것에 덮인다).
- 손 복구 절차(`FinancialRefillJob` 알림 본문 · batch-load skill · `ops/apply-window/05-backfill.sh`)가 이미 두 단계다 — 실측 665+666 = 1,687종목 9.7분.
- 무효화를 거는 쓰기: `FinancialWriter`(정정·교정·이른 판 — FR·FS 둘 다) · `AdjustmentRecalculator`(기업 행위) · 러너 자신(값을 그만 낸 지표). 채움·새 라벨은 무효화를 안 건다([A30]) — 원본층은 다음 평일 13:30 일일 갱신이 현 편입 창 전체를 다시 쓰고 **파생층 과거 날짜만 남는다**.
- 지금 FS(`full-statement-refill`) 뒤에는 백필이 아예 없다(`PipelineScheduler.runHeavy`).

## 수정
1. `IndicatorBackfillJob`에 **파생층만 창으로 다시 내는 진입점**(`rebuildDerived(from, to, by)`): 거래일마다 `percentileCalculator`·`sectorRelativeCalculator`(전 종목) → 창 밖 고아 정리 → 거래일 색인. 원본층은 안 돈다. `batch_execution`은 같은 잡 이름(`indicator-backfill`)에 note 「파생층만」.
2. `InvalidationBackfiller`를 두 단계로: ① **원본층** — 대상 종목을 「asOf 이하 마지막 거래일」 하루로(`tradeDatesBetween(asOf, asOf)`가 휴장으로 비는 함정 — 마지막 거래일을 시세에서 찾는다) ② **파생층** — `rebuildDerived(경계, 그 거래일)`.
   **상한 둘(재무 100 · 기업 행위 10)을 없앤다** — 근거였던 비용(「2시간」·「2.6~8시간」)이 날짜 루프의 중복이었다. 이상 반경(예: 한 실행이 편입의 절반 넘게 무효화)은 백필은 그대로 돌리고 알림만 낸다.
3. **무거운 잡 셋(FR·FS·G) 뒤에는 무엇이든 썼으면 파생층 창을 다시 낸다** — 원본층 ① 대상은 그 잡이 무효화한 종목(FR은 `Invalidation.tickers` · FS는 같은 꼴의 보고를 새로 낸다), ②는 늘. 이것이 [A30]의 남는 몫(파생층 과거 날짜)을 닫는다 — 채움·새 라벨 종목의 원본층은 13:30이 다시 쓴다.
4. 15:30 신선도 점검에 사슬 술어 하나: **최신 거래일에 시세가 있는 현 편입 종목인데 지표가 0행** — 무효화 뒤 백필이 끊겨도(앱 중단 · 예외) 상태로 잡힌다(지금 `INDICATOR_DROP`은 `codes_prev = 0`이라 거짓).
5. 시험: ① 다음 ② 순서 · 휴장 asOf · 상한 없음(1,350종목도 돈다) · FS 뒤에도 돈다 · 파생층 진입점이 원본층을 안 부른다 · 사슬 술어.

## 드라이런(사본 · 배포 전 · 0콜)
- 운영 덤프에서 사본 둘 → 같은 종목 집합(예: 최근 refill의 무효화 목록 일부 + 기업 행위 1종목)의 `indicator_value`를 지운 뒤 (가) 지금 모양 (나) 두 단계로 백필 → **두 사본의 `indicator_value` 전량이 바이트까지 같은가**(코드·종목·날짜·값) · 소요.
- `ops/apply-window`는 지금 멈춘다(00-precheck·boot()·02-snapshot) — 이 드라이런에 필요한 만큼(덤프 → createdb → `DB_URL`·`BOOTSTRAP_DB`·알림 변수 비움 → bootRun) 손으로 돈다.

## 배포(사용자 확인)
- 전제: HEAD = `main` · `git status --porcelain` 빈 상태 · SHA 기록 · 배포 시각은 평일 13:30·14:00·14:15·15:30 근처와 토 19:50~20:40을 피하고 stop 직전 RUNNING 0.
- jar 차이를 그때 다시 잰다(지금: 서버 클래스는 S0 셋 · 프론트 청크 22개는 Sentry가 박는 git SHA 차이뿐 — 동작 같음 · 열린 탭은 새로고침 필요).
- 사용자 확인 항목: 저장소 `.env`에 `^(US_GATE|STOCKSCREENER_GATE|PIPELINE_SCHEDULER)` 줄이 있는지(나는 `.env`를 안 읽는다).
- 배포 뒤 대조군: 첫 정기 무거운 잡이 도는 날(10-30~11-05)의 `batch_execution`에 「원본층 하루 + 파생층 창」 두 행이 생기고 15:30 사슬 술어가 0.

## 재지 않은 것
- 두 모양의 바이트 동일(위 드라이런이 잰다) · 첫 정기 실행의 실제 무효화 수 · 8년 정기 창 대 11년 저장본 드리프트 · 거버넌스가 바꾸는 지표의 범위.

## 계획 v2 반증 판정 (2026-10-04 · 두 렌즈) → v3
- **서는 것**: 「마지막 거래일 하루 원본층 = 날짜 루프의 원본층」은 코드로 안 깨졌다(KNOWN_ON 재무·거버넌스에 기간 상한 없음 · 보존 창 안 억제 창 클램프 없음 · 재귀 시드 잔차는 워밍업 1,261봉 이상) — 단 **전칭은 아니다**: `RETAINED_TO_MARKET_4Y`는 `bars.get(0)` 앵커라 asOf에 따라 움직인다(ADR-0130 §규칙).
- **깨진 것**
  1. **① 범위가 좁아 순서가 거꾸로** — 채움·새 라벨·거버넌스로 바뀐 종목은 무효화가 없어 ①에 안 들고, ②가 옛 원본으로 과거 파생층을 매긴 뒤 13:30이 원본만 다시 써 파생층이 다시 낡는다 → **① = 현 편입 전원**(실측 665: 1,687종목 4분 7초 · batch-load skill의 ①도 전원). 「[A30]도 닫힌다」는 이 조건에서만 선다.
  2. **주식수 기저 판정(`share-basis-derive`)보다 백필이 먼저다**(지금 코드도) — 판정 전 기저로 계산된다 · 백필이 예외면 판정이 빠진다 → `잡 → 기저 판정 → ① → ②`.
  3. **15:30 「최신일 0행」 술어는 ①만 잡고 다음 13:30에 저절로 낫는다** — ②(파생층 창)가 끊기면(배포·예외) 아무도 다시 안 돈다(FR은 백필 전에 SUCCESS로 시계 리셋) → `batch_execution` 원장 사슬: 쓰기·무효화를 낸 잡(FR·FS·G·재구축·initial-load·손 CLI)의 마지막 종료가 ② SUCCESS의 마지막보다 늦으면 **다음 슬롯이 ②를 다시 돈다**(멱등) + 알림 · 손 bootRun이 RUNNING이면 판정 보류.
  4. 「무엇이든 썼으면」은 가르지 않는다(G는 매번 `facts.size()` · 정기 FR·FS는 늘 신규) → 조건 없이 늘 「① 전원 + ②」.
  5. 낡은 동작을 단언하는 시험 다섯(`PipelineSchedulerChainTest` — 기업 행위 상한 · 「정정 없으면 백필 없음」 · 단일 `run(from…)` 캡처 셋) · 상한·「백필이 남았다」 문장의 독자(src 5 · 규칙 1 · 문서 8 · `invalidationAlertBody` WARNING · `insertEarlierVintages=false`의 사유).
  6. 드라이런이 배선(`runHeavy` → ① → ②)을 안 탄다(`InvalidationBackfiller`는 스케줄러 빈 안) → 사본에서 같은 메서드를 부르는 CLI 별칭.
  7. **전제 정정**: 세 무거운 잡이 10-30~11-05에 다 도는 것이 아니다 — 세 잡 다 SCHEDULER 성공 0이라 꼬리의 GAP_FILL이 시계를 되돌리고([V]), 주마다 신규 편입이 있어(40·20·14·13·10·6) **G는 관문 전에 사실상 안 돈다** · FS는 국내 신규 편입 주에 리셋 · FR은 손 `--tickers` 한 번에 밀린다.
     배포 순서: **이 수정 먼저, [V] 수정은 그 뒤**(A56 — [V]를 고치는 순간 즉시 연체로 그때 jar가 전량을 돈다).
  8. 「`.env`에서 켜는 길」: `application.yaml`이 `${US_GATE:false}`를 바인딩하고 relaxed binding(`STOCKSCREENER_GATE_US`)도 서서 「켜는 길은 하나」는 **규칙이지 기제가 아니다** — 배포 전 `.env` 확인이 그 위험을 진다(설정 분리 v2가 env 이름을 걷는다).
- **판단 근거에 단 수(직접 · 0콜)**: (a) 8년 정기 창 대 11년 저장본 — FY≥2018 어댑터 출력 칸 차이 **0** · 판 차이 LNT 2(이미 재고에 있음) · (c) 지난 시즌 새 공시의 결함 발화 — K11 1~2건/시즌 · K8 0 · K1(정당한 재작성) 약 200/시즌.
- **사용자 결정과의 관계(내 해석)**: 무거운 잡 뒤 파생층 창 재생성은 「전체 … 백필 … 결손이 닫힌 뒤 한 번」이 막으려던 것(수정마다 손으로 도는 재적재·백필이 지표를 지운 채 남기는 고리)이 아니라 **지운 것을 그 자리에서 채우는 정기 유지**다 — 13:30 일일 갱신이 이미 매일 원본층을 창 전체로 다시 쓰는 것과 같은 층의 일이다. 사용자가 이 축의 판단을 맡겼다(progress).

# 계획 v3 — v2 + 위 판정
1. `IndicatorBackfillJob.rebuildDerived(from, to, by)` — 파생층만(전 종목 백분위·업종 · 고아 정리 · 색인).
2. `IndicatorBackfillJob`(또는 `InvalidationBackfiller`)에 「① 현 편입 전원 · 시장별 asOf 이하 마지막 거래일 하루 · 잡의 새 풀(메모이저)」 진입점 — 휴장 asOf 함정을 잡 쪽 실DB 시험으로.
3. `PipelineScheduler.runHeavy`: `잡 → (FR·FS) 기저 판정 → ① → ②` · ①이 실패해도 ②는 돈다 · 상한 제거(재무·기업 행위) · 기업 행위 사슬도 `재구축 → ① → ②(재구축 시작일 클램프부터)`.
4. 원장 사슬 술어(15:30) + 다음 슬롯 재실행 · 손 bootRun RUNNING이면 보류.
5. 시험 다섯 갱신 + 새 시험(순서 `InOrder` · ① 실패에도 ② · 휴장 asOf 실DB · 사슬 술어 · 상한 없음) · 문서 독자 갱신.
6. 드라이런(사본 둘 · 배포 전): 무효화 포트의 술어 그대로(`DELETE … stock_id=? AND trade_date>=?`) 표본(억제 종목·정지 종목·KR 휴장일 포함)을 지운 뒤 (가) 지금 모양 (나) v3 경로(CLI 별칭) → **판정 규칙을 대조 전에 커밋**: 원본층은 asOf 의존 코드(`RETAINED_TO_MARKET_4Y` 등 — 목록을 먼저 grep으로)만 차이 허용 · 파생층은 (나)와 「(가)에 파생층만 다시 돌린 것」을 비교 · 나머지는 바이트 동일.
7. 배포(사용자 확인) — 전제·창·`.env` 확인은 v2 그대로 · [V] 수정은 이 배포 뒤.

## 계획 v3 반증 판정 (2026-10-04 · 두 렌즈) → v4
- **받는 것**: (1) ①이 실패해도 ②를 돌면 무효화 종목이 빠진 모집단으로 269일 순위를 다시 매기는 회귀(지금 날짜 루프는 「무효화 종목 행 없음」만 남긴다) → **②는 ①이 SUCCESS · 실패 0일 때만** ·
  (2) ① 모집단: 현 편입(1,350)과 보존 창 편입(1,683 · 과거 편입 333 — 파생층 순위는 그날 편입으로 매긴다)이 다르다 → **① = 보존 창 편입** · 날짜는 13:30과 같은 규약(`runner(asOf)` 팬아웃 — 러너는 asOf가 거래일이기를 요구하지 않는다) · ② to = asOf(합집합 거래일이 두 시장을 덮는다) ·
  (3) `governance-derive`가 사슬에 없다(FY 재무·원장을 읽는다 · 실측 3~4초) · 기업 행위 사슬에도 기저 판정이 없다 → 순서 `무거운 잡 → (FR·FS) 기저 판정 → governance-derive → ① → ②` · `수집 → 재구축 → 기저 판정 → ① → ②` ·
  (4) 원본층 바이트 대조에 「asOf 의존 코드 차이 허용」은 근거가 없다(날짜 루프의 최종 상태 = 마지막 반복 = ①과 같은 봉 창) → **원본층은 예외 없이 바이트 동일** · 파생층은 (나)와 「(가)+파생층만 재실행」을 비교 · (가)·(나)의 ① 모집단을 표본으로 맞추고 전원 효과는 따로 diff ·
  (5) 15:30 여유가 G 날 약 3분으로 준다(javadoc 계산 · 시험은 크론 간격만 본다) — 값은 안 틀리고 점검이 밀린다.
- **내 해석 문장의 정정**: 「13:30과 같은 층의 일」은 ②에 거짓이다(13:30은 파생층을 그날 하루만 쓴다). 사실은 이렇다 — **지금의 자동 백필(재무 정정 · 기업 행위)이 이미 날짜마다 백분위·업종을 전 종목으로 다시 낸다**(`IndicatorBackfillJob` 날짜 루프 · 2026-09-01 사용자 결정 「앞의 둘만 걸고 백필을 사람에게 남기지 않는다」 · ADR-0116).
  v4가 넓히는 것은 그 경로의 계기(무효화가 없어도 무거운 잡 뒤 — 채움·새 라벨 몫)와 모양(원본층 한 판)이다. 관문의 「전체 백필 한 번」은 규칙·매핑 변경 뒤의 전 종목·전 창 재계산을 말하고 정기 잡 뒤 자동 백필은 그 대상이 아니다 — **내 해석**이고 사용자가 이 축의 판단을 맡겼다.
- **미룬 것(관문 재고 C 항목으로 · 이 배포 밖)**: 끊긴 사슬을 원장으로 감지·재실행하는 술어(반증이 정의 구멍 일곱을 잡았다 — 비교 열 · 상태 · 닫힘 조건 · 원장에 안 보이는 쓰기(`universe-restore`·CLI `share-basis-derive`) · 보류와 MANUAL 고아 · ② 식별 · 슬롯) ·
  [V] 신선도 시계(이 배포 뒤) · `company-profile`·`universe-restore`·일일 갱신의 늦은 데이터가 남기는 파생층 과거 날짜 · 기업 행위 원장이 무효화 없이 억제만 바꾸는 경우(OBSERVED 0 · UNSUPPORTED 7 — 9월 전).

# 계획 v4 — 이 배포의 범위 (반증 전)
**실측(10-04 · SELECT)**: 보존 창 편입 1,683(현 편입 1,350 · 과거만 KOSDAQ 93 · KOSPI 37 외) · ① 모양 실측 `batch_execution` 665 = 1,687종목 4분 7초 · ② 모양 666 = 269일 5분 37초(1종목 원본 포함) · 434·435 = 214종목 1분 38초 · 4분 29초 — 전부 자정 실행(지금 블로트에서는 안 쟀다).
1. `IndicatorBackfillJob`에 두 진입점(기존 `run(from,to,…)`은 손 경로로 그대로):
   - **① `rewriteOriginLayer(asOf, tickers, by)`** — 대상의 원본층을 `indicatorRunner.run(stock, asOf, 시장별 경계, marketIndexesFor(asOf))` 한 번씩(13:30 `DailyUpdateJob`과 같은 asOf 규약 · 러너는 asOf가 거래일이기를 요구하지 않는다) · 기존 풀·`resolveWorkers`·`awaitAll` 재사용 · `tickers`가 비면 **보존 창 편입 전원**(`StockRegistrar`에 `activeUniverseMemberIdsByTicker`의 창 술어를 티커 없이 쓰는 질의 하나 — 같은 SQL을 공유하게 뗀다) · 잡 이름 `indicator-origin-rewrite`.
   - **② `rebuildDerived(from, to, by)`** — `tradeDatesBetween`(합집합)마다 `percentileCalculator.calculate(d, null)`·`sectorRelativeCalculator.calculate(d, null)` → 고아 정리 → 거래일 색인 · 원본층은 안 부른다 · 잡 이름 `indicator-derived-rebuild` · 보존 경계 밖 경고는 기존 문장을 재사용.
2. `InvalidationBackfiller.rebuild(asOf, tickers, by)` 하나로 모은다: ① → (①이 SUCCESS이고 실패 0일 때만) ②(from = 시장별 경계의 최솟값 + 1일 · to = asOf) · 아니면 ②를 안 돌고 WARNING 알림(이유 · ① 실행 id · 손 복구 명령 `--job=indicator-rebuild`). **상한 둘(10 · 100)을 없앤다** — 근거였던 비용이 날짜 루프의 중복이었다. `backfillFinancial`·`backfillAdjustment`는 지운다(호출부 둘뿐).
3. `PipelineScheduler`
   - `runHeavy`: `잡 → (FR·FS) share-basis-derive(US) → governance-derive(전 종목) → rebuild(asOf, 전원)` — 무효화 여부와 무관하게 늘(채움·새 라벨·거버넌스는 무효화를 안 건다 — v2 판정 1·4).
   - 기업 행위 사슬: `corporate-action → adjustment-rebuild → share-basis-derive(US · 늘 — CLI `REDERIVE_SHARE_BASIS_AFTER`가 이미 그렇다) → (무효화가 있으면) governance-derive(무효화 종목) → rebuild(asOf, 무효화 종목)`. ① 모집단을 무효화 종목으로 두는 근거: 재구축은 채우지 않고 같은 날 13:30이 현 편입 전원의 원본층을 이미 다시 썼다(v3 반증 밖 렌즈 7). ②는 전 창(무효화 시작일이 보존 경계 앞이라 어차피 전 창이다 — `clampToRetention`).
4. CLI `--job=indicator-rebuild [--tickers=…]` → `InvalidationBackfiller.rebuild`(스케줄러와 같은 메서드) — 사본 드라이런과 손 복구가 같은 경로를 탄다 · `REDERIVE_SHARE_BASIS_AFTER`에 안 든다.
5. 관문 설정(같은 jar · 별도 커밋): `stockscreener.gate.us: false`(`${US_GATE:…}` 바인딩 제거 — relaxed 이름 `STOCKSCREENER_GATE_US`는 남는다) · `@Value` 기본값 제거(키 오타가 기동을 세운다) · 어댑터 생성 시 유효 상태 INFO 한 줄.
6. 시험: 낡은 시험 다섯 갱신 · 순서(`InOrder` — 두 사슬) · ① 실패(부분 실패 포함) 시 ② 안 돎 · 상한 없음 · ① 모집단 = 보존 창 편입(실DB) · ②가 원본층을 안 부르고 모집단을 안 좁힌다 · CLI 별칭 · 관문 설정 · 15:30 시험 간격에 ①·② 실측을 더한다.
7. 드라이런(사본 둘 · `ops/apply-window/env.sh`의 `boot()`·`q()`·`new_exec()` 재사용 — 비운영 DB면 `DB_URL`·`BOOTSTRAP_DB`·알림 변수를 이미 비운다 · `head.txt`는 그 작업 폴더에): 판정 규칙을 대조 전에 커밋 — (가) 지금 jar의 `backfill --tickers=<표본> --from=<경계> --as-of=<최신>`(날짜 루프) (나) `indicator-rebuild --tickers=<같은 표본>` → **원본층 `indicator_value`(파생 코드 제외) 바이트 동일** · 파생층은 (나) 대 「(가) + `rebuildDerived`만 다시」 바이트 동일 · 전원 모양은 (나′) `indicator-rebuild`(전원)로 소요·`pg_stat_user_tables` 증분을 잰다(지금 블로트).
8. 문서: 상한·「백필이 남았다」 독자(src 5 · 규칙 1 · 문서 8) · `invalidationAlertBody` · `insertEarlierVintages=false` 사유 · 15:30 javadoc 예산 · `CLAUDE.md` 「재적재·백필」의 **내 해석** 줄에 「정기 잡 뒤 자동 재생성(①+②)은 데이터 변경을 반영하는 유지라 관문의 「한 번」 대상이 아니다 — 2026-09-01 사용자 결정의 자동 백필과 같은 경로」를 덧붙인다(사용자 문장과 갈라서).
9. 배포: 사용자 확인 · 전제(HEAD = main · 깨끗 · 창 · RUNNING 0 · `.env` 확인) · 배포 뒤 기동 로그의 관문 줄과 첫 정기 실행의 ①·② 두 행 · **[V]는 다음 일**(같은 jar에 넣지 않는 이유: [V]를 고치면 세 무거운 잡이 즉시 연체가 되는데, 그 첫 실행을 이 배포의 첫 대조군과 겹치지 않게 — [V]는 이 배포의 첫 정기 실행 확인 뒤).

## 재지 않은 것(v4)
- 지금 블로트에서 ①·② 소요와 행 변화(드라이런 7이 잰다) · `share-basis-derive` 소요(행이 없다 — 로그로만) · 첫 정기 실행의 실제 무효화 수.

## 계획 v4 반증 판정 (2026-10-04 · 두 렌즈 · 원문 scratch `bf4-rv-out/`) → v5
- **선 것**: ① 한 판의 원본층 = 날짜 루프 최종 원본층(러너 입력에 asOf가 없다 · 봉 창과 KNOWN_ON만) · ① 모집단 1,683 = ②가 순위를 매기는 합집합 · ②가 부르는 함수·순서 = 날짜 루프의 파생층 · 10-29 전 배포 전제(세 잡 SCHEDULER 성공 0 · 마지막 성공 10-03 21:14~21:17) · 새 잡 이름이 깨는 훅·프런트 분기 0.
- **깨진 것과 받는 것**
  1. **「① 실패 0일 때만 ②」는 회귀** — 종목 단위 실패는 지금 날짜 루프에서도 삼켜지고(`StockTaskExecutor:84-95`) 그날 순위는 돈다. v3 판정은 ① 통째 실패의 근거를 종목 실패로 옮긴 것(폴백 근거의 이식) → **②는 ①이 예외 없이 끝나고 FAILED가 아니면 돈다**.
  2. **기업 행위 사슬 「① = 무효화 종목」 근거가 구성원마다 안 선다** — 재구축에만 선다. `corporate-action`의 OBSERVED 삽입(9월 정기 사슬 3건 — id 218·287·288)은 억제를 바꾸고, 기저 판정·거버넌스 유도는 무효화 없이 값을 바꾼다(갱신 0행 APPLIED).
  3. **[V]를 미룬 근거가 안 선다** — A56의 사유(즉시 연체 → 백필 상한)는 무거운 잡마다 ①②가 붙으면 사라진다 · G는 꼬리 GAP_FILL이 매주 시계를 되돌려 조용히 멈춰 있다 · **GAP_FILL만 폴백에서 빼면 즉시 연체가 안 생긴다**(잼: G 수동 09-20 16:14 → 10-19 · FS 수동 09-23 00:26 → 10-20 · FR 수동 10-03 21:17 → 10-30 — `toDays() > 25`).
  4. **CA 사슬에서 좁힌 `governance-derive`를 SCHEDULER로 남기면 「스케줄러 실행은 언제나 전량」(`JobFreshnessPolicy:38-45`)이 깨진다.**
  5. **관문 설정**: 스윕만 `US_GATE`를 읽게 되어 env로 켠 사본 bootRun은 조용히 꺼진 채 0으로 끝난다 · relaxed 이름이 남는다 · 어댑터가 `@ConditionalOnApiKey`라 어떤 시험도 배선을 안 태운다 → **유효값이 환경변수에서 오면 기동을 세운다**(경고는 수정이 아니다).
  6. **CLI 선례 인용이 거짓** — `REDERIVE_SHARE_BASIS_AFTER`에 `adjustment-rebuild`가 없다(APPLIED는 재구축만 찍는다 · 실측 GOSS 09-28 APPLIED → 판정 10-03).
  7. **해석 문장 사실 오류 둘**: 과거 편입 333의 원본층 마지막 쓰기는 09-23 00:46 이전이고 그 뒤 운영 jar에 계산기 커밋 셋(`d3dbae2`·`11afb2a`·`109e30f`)이 들어갔다 — **지금 같은 날 순위에 새 계산기(현 편입 · 13:30이 매일 씀)와 옛 계산기(과거 편입)가 섞여 있다**(이미 있는 결손) · 「09-01 자동 백필과 같은 경로」는 ②에만 선다.
  8. 드라이런이 v3 판정에서 셋을 잃었다(무효화 술어로 지우기 · 표본 구성 · 전원 diff) · 소비자 누락(`data-pipeline.md` 14:15 줄 · `env.sh:37` · `FinancialRefillJob` 알림 · `JobFreshnessPolicy:66-67` · batch-load skill · `guard-rebuild-without-dump.sh` · `load-process.sh` · `MembershipGapFiller` 링크) · 앞단(기저 판정·거버넌스 유도) 예외 시 동작이 계획에 없다 · ② 보존 경계 밖 경고가 US로 매 실행 운다 · 15:30 수(CA 최악 12:44 · ② 최근 거래일당 약 2초 → 약 9분).
- **v5로 가는 판단(내 것)**: 파생층·원본층 쓰기는 `IS DISTINCT FROM`이라 값이 같으면 0행이다(`PercentileCalculator:120` · `SectorRelativeCalculator:195` · `IndicatorValueWriter`) — **매 평일 무조건 화해(① 보존 창 편입 전원 + ② 창 전체)는 블로트 없이 시간만 쓴다.** 그 모양이면 2(CA 사슬 모집단) · 끊긴 사슬(다음 평일이 고친다) · 손 CLI 겹침 · [A30] 파생층 몫 · 과거 편입의 계산기 낡음(7)이 **한 자리에서** 닫힌다 — 무효화 감지를 늘리는 쪽은 감지 자체가 구멍을 가진다(v3 술어 구멍 일곱).

# 계획 v5 (반증 전)
**실측(10-04 · SELECT)**: 보존 창 편입 1,683 · ① 665 = 4분 8초 · ② 666·685·689·696 = 3분 54초~7분 57초(1종목 원본 포함) · 매일 유도의 쓰기 — 거버넌스 유도 `governance_fact` 43,660행 지우고 다시 씀(12MB) · 기저 판정 29행 · 파생층·원본층은 값이 같으면 0행.
1. `IndicatorBackfillJob` 두 진입점(기존 `run`은 손 도구로 남김):
   - **① `rewriteOriginLayer(asOf, tickers, by)`** — 대상 원본층을 `indicatorRunner.run(stock, asOf, 시장별 경계, marketIndexesFor(asOf))` 한 번씩 · 기존 풀·`resolveWorkers`·`awaitAll` 재사용 · `tickers`가 비면 **보존 창 편입 전원**(`StockRegistrar`의 창 술어를 `activeUniverseMemberIdsByTicker`와 한 SQL 조각으로 공유) · 잡 이름 `indicator-origin-rewrite`.
   - **② `rebuildDerived(asOf, by)`** — from = 시장별 경계 최솟값 + 1(경계가 없으면 시세 첫 거래일 — 보존 창이 아직 안 찼다) · 합집합 거래일마다 `percentileCalculator.calculate(d, null)`·`sectorRelativeCalculator.calculate(d, null)` → 고아 정리 → 거래일 색인 · 원본층·시장별 경계 경고를 안 부른다 · 잡 이름 `indicator-derived-rebuild`(대상 0 · 계획 = 거래일 수).
2. `InvalidationBackfiller` → **`IndicatorReconciler.reconcile(asOf, tickers, by)`**: ① → (예외 없이 끝나고 FAILED가 아니면) ② · FAILED면 ②를 안 돌고 ERROR 로그(다음 평일 화해가 다시 돈다) · **상한 둘을 없앤다.**
3. `PipelineScheduler`
   - 14:00 기업 행위 사슬: `corporate-action → adjustment-rebuild`(백필을 뗀다 — 같은 오후 14:20 화해가 잇는다).
   - 14:15 무거운 잡: 잡만(기저 판정·백필을 뗀다).
   - **신설 14:20 `runIndicatorReconcile`(평일)**: `share-basis-derive(US) → governance-derive(전 종목) → reconcile(asOf = targetDateFor(오늘), 전원)` — 무엇이 돌았든 늘 · 풀 1이라 앞 둘이 끝난 뒤 돈다 · 앞단 예외는 ①②를 막는다(판정 전 기저로 계산하지 않는다 — ADR-0162 · 다음 평일이 다시 돈다) · 2026-09-01 사용자 결정 「앞의 둘만 걸고 백필을 사람에게 남기지 않는다」는 같은 오후 자동 화해로 선다(손으로 남기지 않는다).
4. [V] 좁게: `JobFreshnessPolicy.daysSinceLastSuccess`의 폴백(SCHEDULER 성공이 없을 때)에서 **GAP_FILL을 뺀다** — 꼬리가 매주 되돌리던 시계 · 남는 것: SCHEDULER 성공 전의 손 `--tickers`(첫 정기 성공 뒤엔 SCHEDULER 시계만 본다). 화해 ②에 신선도 예산(일일 갱신과 같은 값)을 준다 — 화해가 끊기면 그것이 운다.
5. CLI `--job=indicator-reconcile [--tickers] [--as-of]` → `IndicatorReconciler.reconcile`(스케줄러와 같은 메서드) · `--from` 거절 · `guard-rebuild-without-dump.sh` 대상에 넣는다(②가 그날 모집단 밖 파생 행을 지운다 — backfill과 같은 사유) · `REDERIVE_SHARE_BASIS_AFTER`에 `adjustment-rebuild`를 더한다.
6. 관문 설정(별도 커밋): `stockscreener.gate.us: false`(env 자리표시자 제거) · `@Value` 기본값 제거 · **항상 뜨는 빈 하나가 기동 시 유효값을 찍고, 그 값의 출처가 환경변수이거나 환경변수 `US_GATE`가 있으면 기동을 세운다**(스윕은 JUnit 단독이라 영향 없음) · 시험: 출처별(인자 · yaml · 환경변수) 기동 판정.
7. 시험: 낡은 체인 시험 갱신 · 14:20 순서(`InOrder`: 기저 → 유도 → ① → ②) · 앞단 예외 시 ①② 안 돎 · ① FAILED면 ② 안 돎 · ① PARTIAL이면 ② 돎 · 상한 없음 · ① 모집단 = 보존 창 편입(실DB) · ②가 원본층을 안 부르고 모집단을 안 좁힘 · 휴장·주말 asOf(실DB) · 경계 없음 · CLI 별칭·`--from` 거절 · [V] GAP_FILL 제외 · 크론 간격(14:00 < 14:15 < 14:20 < 15:30) · 관문 설정.
8. 드라이런(사본 둘 · `env.sh`의 `boot()`·`q()`·`new_exec()` · 작업 폴더 따로): 표본 = 억제 종목 · 정지 종목 · 과거 편입 · KR 휴장일에 걸친 US · 최근 무효화 종목 → 무효화 포트 술어(`stock_id=? AND trade_date>=?`)로 그 행을 지운 뒤 (가) `backfill --tickers=<표본> --from=<경계+1> --as-of=<최신>`(날짜 루프) (나) `indicator-reconcile --tickers=<표본> --as-of=<최신>` →
   **판정 규칙(대조 전 커밋)**: 원본층(파생 코드 제외) 바이트 동일 · 파생층 (가) 대 (나) 차이는 asOf 의존 원본(목록을 grep으로 먼저 — `RETAINED_TO_MARKET_4Y` 등)의 파생 코드에만 · (가)+② 대 (나) 바이트 동일 · 그다음 (나′) 전원 `indicator-reconcile`로 과거 편입 333의 원본층이 몇 칸 바뀌는지(계산기 커밋 셋 몫)와 소요 · 복원 사본은 블로트가 없어 운영 소요는 첫 정기 실행이 잰다.
9. 문서·독자: 위 8의 소비자 · ADR 신설(매 평일 화해 · ADR-0116의 사슬 백필·상한 절을 대체) · `CLAUDE.md` 「재적재·백필」 **내 해석** 줄에 「매 평일 화해는 그날 데이터 변경을 반영하는 유지이고 값을 바꾸는 코드는 꺼진 설정 뒤라 퍼뜨리지 않는다 — 관문의 「한 번」 대상이 아니다」 · `progress.md` 「재적재 관문」·`reload-gate.md`(상한 줄 · [A30] · [V] · 순서 8).
10. 배포(사용자 확인): HEAD = main · 깨끗 · 창 · RUNNING 0 · 사용자 `.env` 확인(기동 가드가 생겨도 첫 배포는 확인) · 배포 뒤 기동 로그의 관문 줄 · 첫 14:20의 ①·② 두 행 · 10-19 G가 첫 무거운 잡 대조군.

## 재지 않은 것(v5)
- 14시대 부하에서 ①② 소요 · 과거 편입 333의 실제 변화 칸 수(드라이런 (나′)) · `share-basis-derive` 소요 · 손 CLI와 화해가 같은 날짜를 동시에 쓸 때의 교착(305 전례 — 다음 평일이 고치지만 그날 실패는 남는다).

## 계획 v5 반증 판정 (2026-10-04 · 두 렌즈 · 원문 scratch `bf5-rv-out/`) → v6
- **선 것**: 크론은 밀릴 뿐 안 건너뛰고 예정 시각 순으로 돈다(풀 1 · Spring 7.0.8 `ReschedulingRunnable` · 9월 14:00 사슬 24일 중 23일 발화 · 14:11~14:12 끝) · 좁은 [V] 날짜(G 10-19 · FS 10-20 · FR 10-30) · ① 정상 상태 쓰기 ≈ 0 · 예산 잡 중 「한 번도 안 돎」이 되는 것 0 · 워치리스트는 엣지를 저장값으로 판정해 과거 재기록에 오발화 없음 · 매일 기저 판정이 토요일 꼬리의 판정 공백을 닫는다 · v5보다 더 크게 틀어진 축 없음.
- **v5가 닫는 지금의 결손(잼 — 내가 사본에서 다시 돌림)**: 09-28·29·30 PCTL이 6,304·7,697·7,134칸 낡았다(삽입 7·24·15) · 대조 09-24·10-01 = 0 — 클래스주(TAP.A·MOG.B·WSO.B) 봉이 하루 늦게 오는데 13:30은 파생층을 최신일 하루만 다시 낸다. **드라이런 대조군으로 쓴다.**
- **깨진 것과 받는 것**
  1. **해석 문장 「꺼진 설정 뒤라 퍼뜨리지 않는다」가 첫 회차에 거짓** — 설정 뒤에 없는 계산기 커밋 셋(`d3dbae2` 09-23 18:42 · `11afb2a` 09-27 · `109e30f` 09-28)이 과거 편입에 안 닿았다(마지막 창 전원 실행 665는 09-23 00:38 · 그 뒤 684는 현 편입만). 첫 14:20은 **이미 배포된 계산기의 전 종목 반영**이다 — 지금 같은 날 순위가 두 계산기로 섞여 있는 결손을 닫지만 `CLAUDE.md` 해석(「값을 바꾸는 계산기 변경 뒤 전 종목 백필도 관문의 한 번으로 묶는다」)과 부딪친다 → **배포 직전 사용자 확인에 이 사실을 싣는다**(그 확인이 이 축의 승인이다).
  2. **거버넌스 유도 대상이 ①과 다르다** — 기본이 현 편입 1,350(`GovernanceDeriveJob:87`) · 과거 편입 US 10종목에 마지막 유도 뒤 새 FY 행 → **기본 모집단을 보존 창 편입으로**. 매일 다시 쓰는 것은 33,053행(표 전체 43,660이 아니다).
  3. **앞단 예외 게이트는 근거가 좁고 해가 크다** — 기저 판정 실패는 롤백돼 어제 판정이 남고, 틀릴 수 있는 것은 오늘 새 판이 생긴 주식 교환 종목뿐(지금 판정 행 29·2종목) · 13:30은 판정과 무관하게 매일 계산한다(`FinancialFigures:100-109` NULL = OK) → **유도와 화해를 다른 크론으로 갈라 유도 실패가 화해를 안 막게 한다.**
  4. **무거운 잡 날 기업 행위 무효화 공백이 약 4분 → 약 1시간**(지금 사슬 백필 1:50~4:01 · 무효화 22평일 중 3일) → **14:00 사슬에 「무효화가 있으면 화해(무효화 종목 원본 + 파생 창)」를 남긴다.**
  5. **「블로트 없이 시간만」은 일부 거짓** — 죽은 튜플은 안 늘지만 `ON CONFLICT … WHERE`는 값이 같아도 행을 잠가 WAL·전체 페이지 이미지를 남긴다(`pg_stat_wal` 09-26 리셋 뒤 113GB · 하루 약 13.7GB · 그중 잠금 몫은 안 쟀다) → **사본에서 화해 한 판의 WAL 증분을 잰다**(기각 사유는 아니고 근거 문장을 고친다).
  6. 「한 자리에서 닫힌다」 다섯을 구성원마다: 끊긴 사슬·손 CLI 겹침은 **닫힘이 아니라 「다음 평일까지」의 상한**이다.
  7. **관문 백필이 `--job=backfill`(현 편입)이면 화해와 대상이 달라** 기본값 뒤집은 jar의 첫 14:20이 관문 대조 뒤 값을 또 바꾼다 → **관문 백필 = `indicator-reconcile`**, 관문 뒤 첫 14:20의 변화 0이 관문 대조군.
  8. **관문 설정 가드는 허용 목록이어야 한다**(Boot 4.1.0 소스: `SPRING_APPLICATION_JSON`·`JAVA_TOOL_OPTIONS -D`·작업 디렉터리 `config/application.yaml`·`SPRING_CONFIG_ADDITIONAL_LOCATION`이 거부 목록을 통과) · `US_GATE`는 존재가 아니라 **유효값과 다를 때** 거부(`.env`의 `US_GATE=false`가 운영 기동을 세우지 않게).
  9. [V]를 `daysSinceLastSuccess`에만 걸면 `stale()`(배너·알림)과 스케줄러가 다른 답 → **`lastSuccessAt` 폴백 자체에서 GAP_FILL을 뺀다** · ② 예산의 실행 방법 문구.
  10. ② 「대상 0」의 성공 수 미정(`env.sh:37` · 화면 0/270) → 거래일을 성공 단위로 센다 · ①②의 쓴 행 수를 note에 남긴다(`calculate()` 반환값을 지금 버린다 — 꺼진 jar가 창을 되돌려도 원장에 안 보인다).
  11. CLI 「스케줄러와 같은 메서드」는 ①②에만 섰다 → CLI가 유도 둘까지 같은 메서드로.
  12. **배포일은 10-16(금) 13:30 전**이 맞다 — 그래야 첫 14:20(계산기 전파)이 무거운 잡 없는 10-16에 홀로 돌고 10-19 G와 안 겹친다(귀속).
  13. 문서: `progress.md:80-83`이 v3·「[V]는 배포 뒤」·틀린 브랜치 경로를 가리킨다 · `reload-gate.md` 순서 1(b) 「채움 무효화」는 v5 뒤 0행 구간만 만드는 해로운 일 · 순서 10 · `data-pipeline.md:98-102·138·187`(겹침 표에 14:20) · `CLAUDE.md:94`(「정기 refill이 되돌린다」 → 화해도) · ADR-0161:24 · ADR-0128:61 · ADR-0164:179 · `PresetResultSnapshotTest` 운영 전후 대조에 화해 몫이 섞인다 · 배포 금지 창에 14:20~15:40.
  14. 드라이런에 원래 결함 대조군이 없었다(→ 위 09-28~30) · 허용 목록 예시 `RETAINED_TO_MARKET_4Y`는 파생 자식이 없어 허용 목록이 비어 있다 → 「차이 0」으로 커밋.

# 계획 v6 (반증 전 — v5 + 위 판정)
1. `IndicatorBackfillJob`: ① `rewriteOriginLayer(asOf, tickers, by)`(빈 tickers = 보존 창 편입 · 잡 `indicator-origin-rewrite`) · ② `rebuildDerived(asOf, by)`(경계 최솟값+1 또는 시세 첫 거래일 ~ asOf · 잡 `indicator-derived-rebuild` · **대상 = 거래일 수, 거래일마다 성공 1** · 백분위·업종 쓴 행 수를 합쳐 note) · 기존 `run`은 손 도구.
2. `StockRegistrar.windowMemberIds()` — `activeUniverseMemberIdsByTicker`와 창 술어를 한 조각으로 공유 · `GovernanceDeriveJob` 기본 모집단 = 보존 창 편입.
3. **`IndicatorReconciler`**(`InvalidationBackfiller` 대체 · 상한 없음): `deriveInputs(asOf, by)` = `share-basis-derive(US)` → `governance-derive(기본 모집단)` · `reconcile(asOf, tickers, by)` = ① → (예외 없이 끝나고 FAILED가 아니면) ② · FAILED면 ERROR 로그(다음 평일이 다시 돈다).
4. `PipelineScheduler`: 14:00 `corporate-action → adjustment-rebuild → (무효화가 있으면) deriveInputs → reconcile(무효화 종목)` · 14:15 무거운 잡만 · **14:18 `deriveInputs`** · **14:20 `reconcile(전원)`** — 풀 1이라 예정 시각 순 · 유도 실패가 화해를 안 막는다.
5. [V]: `JobFreshnessPolicy.lastSuccessAt`의 폴백(SCHEDULER 성공이 없을 때)에서 GAP_FILL 제외 — `stale()`과 스케줄러가 같은 답 · `indicator-derived-rebuild` 예산(일일 갱신과 같은 값 · 실행 방법 = CLI `indicator-reconcile`).
6. CLI `--job=indicator-reconcile [--tickers] [--as-of]` = `deriveInputs` → `reconcile` · `--from` 거절 · `guard-rebuild-without-dump.sh` 대상 · `REDERIVE_SHARE_BASIS_AFTER` += `adjustment-rebuild`.
7. 관문 설정(별도 커밋): yaml `stockscreener.gate.us: false` · `@Value` 기본값 제거 · **항상 뜨는 빈이 기동 시 유효값과 출처를 찍고, 출처가 `commandLineArgs` 또는 classpath 안 `application*.yaml`이 아니면 기동을 세운다** · 환경변수 `US_GATE`가 있고 유효값과 다르면 기동을 세운다 · 시험: 출처별 판정(인자 · classpath yaml · 환경변수 · `spring.application.json` · systemProperties · 파일).
8. 시험: 체인 시험 갱신 · 14:00 무효화 시 `deriveInputs → reconcile(무효화 종목)` · 14:18·14:20 크론과 간격(14:00 < 14:15 < 14:18 < 14:20 < 15:30) · `reconcile` ① 던지면 ② 안 돎 · ① FAILED면 ② 안 돎 · PARTIAL이면 돎 · 상한 없음 · ① 모집단 = 보존 창 편입(실DB) · ②가 원본층을 안 부르고 모집단을 안 좁힘 · 휴장·주말 asOf · 경계 없음 · ② 성공 수 = 거래일 수 · CLI 별칭·`--from` 거절 · GD 기본 모집단 · [V](`stale()`·스케줄러 둘 다) · 관문 가드.
9. 드라이런 — 아래 「드라이런 판정 규칙」을 대조 전에 커밋 · 사본 `stock_screener_bf_a`·`_b`(10-04 20:45 덤프 · 지표 51,728,336행).
10. 문서·독자: 위 판정 13 · ADR 신설(매 평일 화해 — ADR-0116의 사슬 백필·상한을 대체) · `CLAUDE.md` 해석 줄을 사실대로(「매 평일 화해는 데이터 변경을 반영하는 유지다 · 다만 설정 뒤에 없는 계산기 변경이 main에 들어가면 그다음 화해가 곧 전 종목 반영이다 — 그래서 값을 바꾸는 계산기 코드는 설정 뒤에 둔다는 기존 규칙이 화해의 전제다」) · 관문 백필 = `indicator-reconcile`.
11. 배포(사용자 확인 · **10-15(목) 안**): 확인 문면에 판정 1의 사실(첫 14:20이 계산기 셋을 과거 편입에 퍼뜨림 · (나′)의 수) · HEAD = main · 깨끗 · 평일 13:30~15:40 밖 · RUNNING 0 · `.env` 확인 · 배포 뒤 기동 로그의 관문 줄 · 10-16 14:20의 ①② 행과 note가 (나′) 기대와 맞는지 · 10-19 G.

## 드라이런 판정 규칙 (대조 전에 커밋 — 2026-10-04)
- **표본**(시장마다 · 사유): KOSPI `207940`(UNSUPPORTED 2025-11-24 — 억제가 창 안) · `001740`(10-02 재편입 · 마지막 봉 09-15) · `005930`(대조) · KOSDAQ `009520`(과거 편입) · US `GOSS`(APPLIED 09-28 — 최근 무효화) · `VSH`(10-02 재편입 · 마지막 봉 09-10) · `TAP.A`(봉이 하루 늦는 클래스주) · `ABG`(과거 편입) · `AAPL`(대조 · `RETAINED_TO_MARKET_4Y` 보유).
- **준비(두 사본 같게)**: `share-basis-derive` → `governance-derive --tickers=<보존 창 편입 전원>`(지금 코드로 창 술어를 탄다 — 두 사본의 입력을 맞춘다) → 표본을 무효화 포트 술어 그대로 지운다(`DELETE FROM indicator_value WHERE stock_id=? AND trade_date >= <그 종목 첫 봉>`).
- (가) 사본 a · 지금 코드(`0b29f2c`): `backfill --tickers=<표본> --from=<경계 최솟값+1> --as-of=<최신>`.
- (나) 사본 b · v6 코드: `indicator-reconcile --tickers=<표본> --as-of=<최신>`.
- **판정 A**: 표본의 원본 코드(파생 아님) 전 행이 a = b 바이트 동일. **기대 차이 0.**
- **판정 B**: 파생 코드 전 행(전 종목 · 보존 창)이 a = b 바이트 동일 — 허용 목록 없음(asOf 의존 원본 `RETAINED_TO_MARKET_4Y`는 파생 자식이 없다). **기대 차이 0.**
- **판정 C(대조군)**: 09-28·29·30의 낡은 PCTL(준비 전 6,304·7,697·7,134)이 a·b 둘 다 0 — 날짜 루프와 ② 둘 다 그날 전 종목을 다시 매긴다. 0이 아니면 도구부터 의심한다.
- **판정 D (나′)**: 사본 b에서 이어 `indicator-reconcile`(전원) → 앞뒤 diff · **기대**: 현 편입 원본 변화 0(13:30이 이미 지금 코드로 썼다) · 과거 편입 원본 변화는 세 커밋이 바꾼 계산기 코드에만 · 파생 변화는 그 결과로 · 수를 첫 운영 화해의 기대로 남긴다 · WAL 증분(`pg_current_wal_lsn` 앞뒤 — 클러스터 공용이라 조용한 시각에)과 소요.
