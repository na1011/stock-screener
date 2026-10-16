# 관문 실행 도구 계획 — `ops/apply-window`를 미국 관문 기준으로 다시 쓴다 (2026-10-16 · v2 — 반증 두 렌즈 반영)

`docs/reload-gate.md` 순서 2의 「`ops/apply-window`를 이 관문 기준으로 고쳐 쓰기」와 순서 10(관문 실행)의 절차를 스크립트로 만든다.
**이 계획은 관문의 내용(어떤 수정이 들어가는가 · 3~8단계)을 정하지 않는다** — 관문 설정 하나(`stockscreener.gate.us`) 뒤에 모인 것을 한 번에 켜고 재적재·백필하는 **실행기**만 정한다.
그래서 3~8단계가 무엇을 더하든 이 실행기는 안 바뀌어야 한다(바뀌면 그 묶음이 설정 밖으로 샌 것이다).

## 0. 잰 것 (★ = 내가 이번에 잼 · 10-16)

- ★ **옛 절차의 모양**: 덤프 → 전체 사본 둘(S1 플래그 참 · S0 플래그 거짓) 복원 → 운영과 S1에 같은 refill → ①「운영 후 = S1 후」 · ②순위 재료 · ③S0 대비 구간 규칙(Q4V 전용 `r3nk.py`) · ④국내 불변 · ⑤ADJ_CLOSE 감소 0 · `08-gate`(두 사본의 다음 정기 refill이 같다).
  전체 사본 둘은 `CLAUDE.md` 「드라이런이란 무엇인가」가 막는 형태다(사본 하나 약 7.5GB 복원 + 전체 refill). ①③과 `08-gate`는 Q4V(ADR-0161) 한 축의 판정이다.
- ★ **지금 멈추는 자리 셋**(progress가 적은 것을 다시 잼): `00-precheck`의 마이그레이션 기준은 운영 jar 커밋(`app.jar.commit`)이고 오늘 배포(`87d0f0bd` · V69)로 운영 jar 뒤 마이그레이션 차이가 0이다 → 지금은 서지만 관문 날 V70이 HEAD에만 있으면 멈춘다 ·
  `02-snapshot`의 프리셋 날짜 21개 중 2025-09-01·09-26·10-15·10-31 등 앞 다섯이 미국 보존 창(★ `indicator_trade_date` US 2025-10-10~2026-10-15 · 255일) 밖이다 — 창은 매일 민다 ·
  `premises.py`는 ADR 넷(0126·0135·0151·0161)의 펜스만 돈다 — 세션 훅 `check-premises.sh`는 전제 156개(참 146 · 발견 10)를 돈다.
- ★ **관문 설정을 읽는 곳 8**(`@Value("${stockscreener.gate.us}")`): `SecEdgarFacts`(통해 `SecEdgarFinancialAdapter`) · `UsMarketCapUniverseAdapter` · `JdbcStoredShareCounts` · `GovernanceFactDeriver` · `ShareBasisDeriver` · `IndicatorReconciler` · `IndicatorDataReader` · (`GateSettingGuard`는 출처만).
  **꺼지면 V70 표를 안 건드린다**: 어댑터는 `usGate && shareClass != null`일 때만 `saveInput`(`SecEdgarFinancialAdapter:168-169`) · `ShareClassStore` javadoc이 「켜진 갈래에서만 부른다」 — 그래서 V70을 실은 jar를 관문 직전에 꺼진 채 배포해도 값이 안 바뀐다(아래 결정 2 · 드라이런 G2가 잰다).
- ★ **재적재 모집단**: 현 편입 1,000(`US_TOP1000` 열린 편입) · 후보 술어 = 미국 · 활성 · 활성 유니버스의 열린 편입 없음 = **7,504** 중 재무 대상(SEC 매핑 ∪ 재무 행) — 로그 「후보 모드 손 FR: 후보 N · 재무 대상 M …」(`CandidateIntake:126`) ·
  보존 창 안 과거 편입(지금 편입 아님) **213 · 비활성 0** → 후보 실행이 덮는다(`run past`를 뺀다 — 비활성이 생기면 그 종목은 후보 술어 밖이라 precheck가 멈춘다).
  `US_LISTED`(비활성 유니버스) 열린 편입 8,495는 후보 술어가 활성 유니버스만 보므로 안 막는다.
- ★ **화해 기록**: 손 `indicator-reconcile` = `governance-derive`(유도) → `indicator-origin-rewrite`(① · note `원본층 N종목 · 쓴 a · 지운 b`) → `indicator-derived-rebuild`(② · note `파생층 N거래일 · 쓴 a · 지운 b · 고아 정리 c · 색인 d`) — 오늘 운영 행에서 읽은 문형.
- ★ **공시 보충 표** `sec_filing_supplement`: `status` · `failure_kind` · `stale_used_at` · `reader_version` — 관문 판정 「FAILED와 옛 판을 쓴 행」을 SQL로 센다. 클래스 축 판독 판 `ClassFactReader.READER_VERSION = 1` · 보충 `XbrlInstanceReader.READER_VERSION = 8`.
- ★ **손 FR 뒤 주식수 판정은 자동**: `BootstrapRunner:450`이 `REDERIVE_SHARE_BASIS_AFTER` 잡 뒤 `IndicatorReconciler.deriveShareBases()`(관문 켬이면 클래스 축 판정 → 기저 판정 · 저장 재료 전부) — 그래서 `cur` → `cand` 순서는 최종 판정을 안 바꾼다(`cand` 뒤 판정이 둘 다의 재료를 본다). 후보 실행의 `company-profile`은 후보 ∩ SIC 없음만(`CandidateIntake:121-123`). `docs/reload-gate.md`의 `BootstrapRunner:431-433`은 낡은 줄 번호다.
- ★ **정기 FR 연수 8**(`FinancialRefillJob.DEFAULT_YEARS_BACK`) · 관문 11 — 옛 README의 「적재 창 의존」(LNT 2018·2019 Q4) 꼴이 켜진 jar의 첫 정기 FR에서 「교정」으로 나올 수 있다.
- ★ **펜스 훅** `check-premises.sh`는 전제 본문의 `psql -d stock_screener`(ADR 171곳)를 그대로 돈다 — 운영에서만 뜻이 있고, 결과는 발견·무판정만 JSON(`additionalContext`)으로 낸다(참은 수만) · 전체 예산 75초라 경합 중엔 뒤쪽이 무판정으로 잘린다.
- ★ **순위 스냅숏** `RankShareCountSnapshotTest`는 `new JdbcStoredShareCounts(jdbc)`(꺼진 판독기)만 쓴다 → 관문 뒤 클래스 축이 바꾼 순위 재료를 못 본다.
- ★ **작은 DB 재료**: 운영 표 35 중 `stock_id`가 있는 것이 큰 것 전부(`indicator_value` 9.4GB · `price_daily` 538MB · `financial_statement` 97MB · `batch_execution_failure` 49MB · 거버넌스 둘 47MB …) · 없는 것은 전부 7.6MB 이하(`interaction_log` 7.6MB · `stock` 2.3MB · `sec_filing_supplement` 1.4MB · 나머지 ≤1MB).

- ★ **(v2) 후보 대상 하한**: 후보 7,504 중 재무 행 있음 **4,128**(`financial_statement` EXISTS) — 「재무 대상 M」 ≥ 4,128이어야 한다(M = 재무 행 ∪ SEC 매핑이라 하한이 독립이다) · SIC 없음 2,236 · 그중 재무 행 있음 77. 후보 전원이 어느 유니버스든 편입 이력이 있다(7,504) — 「과거 편입」은 보존 창과 활성 유니버스로 걸어 센다(213).
- ★ **(v2) FK**: 공용 스키마 FK 35 중 `stock_id` 없는 자식이 `stock_id` 있는 부모를 부르는 것은 `corporate_action_distribution → corporate_action` **하나**다. 나머지 `stock_id` 없는 표는 통째로 옮겨도 고아가 없다(부모가 전부 `stock_id` 없는 표거나 통째인 표).
- ★ **(v2) `a2.sql` 미국 7행**(CNH 2021 FY · CRK 2022 Q4 · DNTH 2021 Q4 · PNC 2021·2022 Q4 · TEVA 2016 FY · TPG 2022 Q4) — 전부 가운데 판 번호가 빈 꼴(`0, 2`)이다. `FinancialWriter.renumberOutOfOrderLabels`는 `insertEarlier || insertedEarlier > 0`일 때만 돈다(`:292`) — 관문 재적재(이른 판 켬)가 그 종목을 쓰면 번호를 다시 매긴다. 여섯 종목 중 넷은 현 편입 · CRK·DNTH는 후보. 번호 연속을 가정하는 읽기는 `src/main`에 Writer 자신뿐(`grep revision_no ± 1`). **구멍의 원인(정기 FR의 회수가 번호를 안 다시 매긴다)은 짐작이고 재보지 않았다** — 아래 G0가 단위 시험으로 잰다.
- ★ **(v2) 창 안 편입의 시세 결손 0**: 활성 유니버스 · 보존 창과 겹치는 편입 행 중 자기 구간에 봉이 없는 것 — 미국 관측 1,114 · 복원 1,653 · 국내 넷 평면 모두 **0**. `V`·`STZ`·`FWONA`는 `price_daily` 0행(BRK.A·BRK.B는 252행 · 2024-01-02~). 결손 보충 `membershipGaps()`는 `effective_to IS NULL`(열린 편입)만 본다(`StockRegistrar:690`) · 화해 원본층은 `windowMemberIds()`(복원 평면 포함)를 쓴다 — 시세만 있으면 과거 날짜를 쓴다.
- ★ **(v2) 관측 평면**: `US_TOP1000` `OBSERVED`의 `effective_from`이 08-31(1,000행 · 그중 918행이 아직 열림) · 09-04 · 09-07 · 09-11 · 09-18 · 09-25 · 10-02 · 10-09 — 전부 꺼진 판독기로 고른 것이다. `RESTORED`는 08-31에서 끊긴다(`UniverseLedgerRestorer:65` — 하한 = `OBSERVED`의 `min(effective_from)`). `origin`을 읽는 `src/main` 코드는 `UniverseLedgerRestorer` 하나다(관측 평면을 「보였던 기록」으로 쓰는 소비자가 없다). 같은 기준일 재실행은 그날 연 행을 지우고 그 전에 연 행을 닫는다(`UniverseReconciler:113-130`) · 순위가 `previous`를 받으므로 경로 의존이다.
- ★ **(v2) 가드·창**: `guard-rebalance-on-dirty-rank.sh:58-62`는 명령에 `gradlew`와 `--job=universe*`가 함께 있을 때만 발화한다 — 스크립트로 감싸면 안 탄다 · `00-precheck.sh:11`의 크론 검사는 평일 15:10 뒤에만 돈다 · `ops/dump-db.sh:23` `DB="${DB_NAME:-stock_screener}"`.
- ★ **(v2) 옮기는 스크립트를 부르는 곳**: `07-judge`를 이름으로 부르는 파일 8(`FinancialRefillJob:361·384` · `MarketCompleteness:157` · `MarketCompletenessTest:149` · `FinancialRefillAlertTest:42` · ADR-0161:58 · ADR-0174:99 · README) — 그 정규식(계수 토큰)은 새 `env.sh`의 `note_nums()`가 이어받는다 · `check-doc-staleness.sh`의 이름 해석 디렉터리 목록에 `ops/apply-window/q4v`가 없다 · `boot()`를 작은 DB 실행기로 지목하는 규칙 문장 둘(`CLAUDE.md` 「재적재·백필」 · `.claude/rules/data-pipeline.md`).
- ★ **(v2) 재생 하네스의 재료**: `.factcache` 1,218개사 · 가장 늦은 파일 09-27 — 후보 7,504를 못 덮고 관문 날 받을 판과 다르다.
- 반증 보고(재보지 않음): 미국 워치리스트 알림 항목 0(국내 1) · 지난 다섯 토요일 중 10-03은 SCHEDULER 리밸런싱 행이 없고 MANUAL 21:14.

## 1. 결정

1. **전체 사본을 없앤다 — 관문은 운영 한 판 + 전후 스냅숏 + 하드 단언이다.** S0의 역할(설정 귀속)은 각 묶음 스윕의 꺼짐/켬 diff가 갖는다. S1의 역할은 둘로 나눠 갖는다: 명령이 서는가·끼어듦은 **작은 DB 드라이런**(결정 6) · 저장 계층의 기대(옛 ①「운영 후 = S1 후」)는 **표본 동일성**(결정 12).
   버리는 것: `01-dump-shadows.sh` · `07-judge.sh`의 ①③ · `08-gate.sh` · `r3nk.py` · `premises.py` — 지우지 않고 `ops/apply-window/q4v/`로 옮긴다. **부르는 곳을 같은 커밋에서 고친다**(§0 v2 — 8파일 · 계수 정규식은 `env.sh` `note_nums()`로 이름을 바꿔 가리킨다 · `check-doc-staleness.sh`의 디렉터리 목록에 `ops/apply-window/q4v` · `boot()`를 지목하는 규칙 문장 둘은 새 `boot()`가 같은 계약(작은 DB면 세션 변수·알림 변수를 걷는다 · 운영이면 쓰기 세션 안에서만)을 지키므로 경로만 맞춘다).
2. **마이그레이션 기준은 운영 jar 커밋 그대로** — 관문 날 첫 단계가 「V70을 주 마이그레이션으로 옮긴 커밋 → `ops/deploy.sh`(설정 꺼짐)」다. 기준을 HEAD로 바꾸는 대안은 버린다 — 운영 jar에 없는 마이그레이션을 `bootRun`이 운영 DB에 넣게 되고, 실패하면 운영 jar가 모르는 스키마로 남는다.
3. **재적재는 두 실행**(같은 인자 `--financial-years=11 --insert-earlier-vintages=true --stockscreener.gate.us=true`): `cur` = `--universe=US_TOP1000` → `cand` = `--population=candidates`.
   - **대상 수는 독립 하한과 대조한다**(v2 · A8): `cur`는 precheck가 SQL로 센 열린 편입 수와 같다 · `cand`의 「재무 대상 M」은 precheck가 센 「후보 ∩ 재무 행 있음」 이상이고 「후보 수」 이하다. 작은 DB에선 둘 다 표본에서 정확한 수로 떨어진다.
   - **매 `boot` 로그에서 `관문 설정 stockscreener.gate.us=true (출처: …명령줄…)`을 단언한다.**
   - **실패는 구성원마다**: `expected-failures.tsv`(티커 · 종류 · 근거 한 줄)에 없는 실패 → 멈춤. `TRANSIENT`·`RATE_LIMITED`는 같은 모집단 인자에 `--tickers=`로 재시도.
4. **재적재 뒤 하드 단언**: ① 클래스 축 미해결 0(`FAILED ∧ failure_kind ≠ UNREADABLE ∧ reader_version = 1`) · `UNREADABLE`은 종목마다 판정 파일에 ② 공시 보충 `FAILED`·`stale_used_at IS NOT NULL` 전원 판정 파일에 ③ **`a2.sql` 미국 0 — 관문 뒤에 건다**(v2 · A2: 관문 전 7행은 이른 판 켬 실행의 재번호가 닫는 꼴이다 · 7행의 종목이 재적재 대상에 다 드는지 precheck가 센다 · 0이 아니면 그 행을 연다) ④ 재무 대상 하한(결정 3).
5. **화해 두 번**: 둘째 판 ①② 「쓴 0 · 지운 0」 · 두 판 모두 실패 0.
6. **드라이런 = 작은 DB에서 실행기 전 구간**(`small-db.sh <db> <티커 파일>`): 스키마(pre-data) → `stock_id` 있는 표는 표본 종목 행만 · **`stock_id` 없는 자식이 그런 부모를 부르면 FK를 따라 그 부모 행만**(v2 · A4 — `pg_constraint`에서 그 짝을 실행 때 읽는다 · 지금은 `corporate_action_distribution` 하나) · 나머지 통째 → post-data(제약·색인 — 고아가 있으면 여기서 실패).
   **작은 DB에 자기 쓰기 가드 행을 둔다**(v2 · A5): `guard_setting`에 그 DB 이름의 행을 넣어 `ops/write-session.sh` 경로를 작은 DB에서 그대로 태운다 — 운영 쓰기 세션 경로가 한 번도 안 돈 것을 관문 날 처음 태우지 않는다. `boot()`의 운영 갈래는 「대상 DB가 가드 켬인가」로 가르고 이름으로 가르지 않는다.
   표본(근거를 적어 고른다): 현 편입 — LNT(창 의존 대조군) · Up-C 다섯(SYM·GLXY·LB·CWEN·BLLN) · 숨김 넷(BNT·VNOM·WSE·BROS) · 보충 판정 대상 상위 · 실패 기대(APGE·CRNX) · 대조 AAPL · **`a2.sql` 일곱 행의 여섯 종목** · 후보 — V·BRK.B·STZ·FWONA(순위 진입 · 시세 0행) · SIC 없는 후보 · companyfacts 404 후보 · 후보 스윕의 fetch 실패 전원. 약 45종목.
7. **명부 다시 짓기는 관문 내용이 정한다 — 실행기는 그 단계를 부르는 자리와 뒤의 단언을 갖는다**(v2 · 계획 밖 1·A7).
   - 클래스 축이 순위를 바꾸므로 **관측 평면(08-31~10-09 · 열린 918행 포함)도 옛 기저다** — 복원 하한 앞 격자만 다시 지으면 창 안에 기저가 둘 섞인다. 관측 평면을 「보였던 기록」으로 읽는 소비자는 없다(§0 v2). 그래서 「관측 평면 재복원 + 열린 편입을 켬 기저로 다시 고르기」는 **클래스 축 묶음의 설계 항목**으로 `docs/reload-gate.md`에 올리고 따로 계획·반증·드라이런한다(크기는 후보 스윕 뒤 관측 날짜 여덟에서 꺼짐/켬 순위를 저장 재료로 0콜 재생해 잰다 — `CLASS_SWEEP_POP=none CLASS_SWEEP_AS_OF=…`).
   - 복원 여부 판정(옛 「순위 스냅숏 diff > 0일 때만」)은 버린다 — ADR-0135의 다시 도는 조건은 「재무·원장이 바뀌면」이고, 관문 재적재는 그 자체로 그 조건이다(A7 · SIC 없는 후보 77도 그 안에 든다). **명부 다시 짓기는 언제나 돈다.**
   - **`05-roster.sh`가 순위 가드의 이상치 질의를 대상 DB에 직접 돌리고 1건이라도 있으면 멈춘다**(v2 · 계획 밖 3 — 훅은 스크립트 감싸기를 못 본다 · V70 뷰 갈래 포함). 훅 질의를 복사하지 않고 훅이 쓰는 SQL 파일을 함께 읽는다(같은 질의 사본 금지).
   - **뒤에 창 결손 보충과 단언**(v2 · 계획 밖 2): 활성 유니버스 · 창과 겹치는 편입 행 중 자기 구간에 봉이 없는 종목 → 시세 적재 → 같은 SQL이 0. 이 보충은 관문 전용이 아니라 **복원 잡의 꼬리**로 main에 넣는다(리밸런싱 꼬리가 「편입이 바뀐 직후가 결손이 생기는 유일한 순간」으로 결손 보충을 부르는 것과 같은 이유 · 지금 그 결손이 0이라 무해한 선행 배포다 · 결손 판정 SQL은 `membershipGaps()`와 축을 공유하게 짓고 사본을 안 만든다).
8. **전후 산출물**: 프리셋 결과(날짜 = 그날 창에서 규칙으로) · 순위(꺼짐 판독기 전 · 켬 판독기 후 · `RANK_SNAPSHOT_CLASS_AXIS=true` · **SIC와 거름 결과 열을 더한다**(A7)) · 재무 행 서명 · 지표 묶음 해시 · 펜스 · 국내 해시. **하드 단언**: 국내 재무·지표 불변 · `ADJ_CLOSE` 감소 0 · 표본 동일성(결정 12) · 결정 4·5·7의 단언.
   **펜스는 훅이 아니라 실행기가 돌린다**(v2 · A3): 훅 `check-premises.sh`는 DB를 못 고르고 참을 수로만 낸다 — 실행기가 ADR 전제 본문을 읽어 DB 이름을 갈아 끼워 돌리고 **참·발견·무판정을 전제마다** 적는다(질의당 시간 상한은 훅과 따로 · 전체 상한 없음). 전후 비교에서 바뀐 전제 하나하나를 묶음에 귀속하는 칸을 둔다(계획 밖 6).
9. **켜진 jar 배포는 스크립트 밖 — `09-flip.md` 점검표**: 코드 기본값 커밋 · `.env`에 `US_GATE` 줄 없음(사람) · `ops/deploy.sh` · 기동 로그의 출처 줄 · 첫 14:20 귀속 · **켜면서 고칠 ADR·펜스 목록**(`docs/reload-gate.md` 각 행의 「켜면서」 · 「관문 뒤」를 말하는 ADR 10 — 목록을 grep으로 뽑아 표에 박는다) · **설정과 꺼진 갈래 걷기**(관문 순서 10의 끝 문장) · 미국 워치리스트 알림 항목 수 — 0보다 크면 플립 전에 켬 기저로 `last_satisfied`를 다시 맞추는 길을 정한다(지금 0 · precheck가 센다).
10. **창과 앱**(v2 · 계획 밖 4·A10):
    - 앱은 관문 동안 내려 있다 — 실행기 첫 단계가 `ops/crons-done.sh` → `ops/stop.sh --app`이고(관문 순서 10의 「VACUUM → `stop --app`」), **매 `boot` 앞에 「앱 pid 없음」을 단언한다.**
    - precheck는 요일과 무관하게 **그 주 토요일 `universe-rebalance` 행이 SUCCESS이고 꼬리(결손 보충 · 따라잡기 · 후보 인입) 행이 RUNNING 0**인지 단언한다 — 토요일 발화가 빠진 주(10-03)가 있다.
    - 창의 끝은 결정 7의 설계가 정한다(열린 편입을 켬 기저로 다시 고르는 길이 토요일 정기 리밸런싱이면 그 전에 끝나야 하고, 같은 기준일 재실행이면 토 20:00 뒤가 된다). 소요는 G3의 종목당 시간 × 대상 수로 적는다.
    - **되돌림 경로**: 되돌릴 수 없는 첫 쓰기 = `03`의 첫 실행 행. 그 전에 멈추면 아무것도 안 바뀐다. 그 뒤에 멈추면 ① 앱을 내린 채 둔다(꺼진 jar가 뜨면 14:20 화해가 반쯤 쓴 상태를 정기 경로로 덮는다) ② 덤프로 되돌릴지 이어 갈지를 사람이 정한다 — 덤프 복원 시간은 **재보지 않았다**(덤프 511MB · 운영 DB 약 9.4GB 색인 포함) — G1 작은 DB 복원 시간을 표본 비로 늘린 값은 추정이라 쓰지 않고, 관문 전 주에 운영과 같은 기계에서 `pg_restore --list`가 아니라 실제 복원을 한 번 재는 것을 「전체 사본」 금지의 예외로 사용자에게 묻는다(CPU 비용: 복원 1판).
11. **쓰기 세션**: 운영에 쓰는 단계는 `ops/write-session.sh exec <id> -- <스크립트>`로. 작은 DB도 같은 경로(결정 6).
12. **표본 동일성 — 저장 계층의 기대**(v2 · A1): 같은 날 같은 코드로 작은 DB(G3)와 운영이 표본 약 45종목을 같은 인자로 쓴다. `07`이 **표본 종목의 재무 행 서명(값·공시일·판 번호·최신 표지)이 운영 후 = 작은 DB 후**인지 단언한다. 다르면 그 종목의 두 실행 사이 새 공시(제출 목록의 `filingDate`)로 귀속하고, 귀속이 안 되는 차이 1건이면 멈춘다. 재생 하네스로 전 모집단 기대를 짓는 대안은 버린다 — 재료(`.factcache` 1,218개사 · 09-27)가 후보를 못 덮고, 새로 받으면 운영 재적재와 같은 SEC 콜을 한 번 더 쓰는 「운영 한 판의 복제」다.

## 2. 스크립트

| 파일 | 하는 일 | 옛것과 |
|---|---|---|
| `env.sh` | `GATE_PROD`(기본 운영) · `boot()`(앱 pid 없음 · 가드 켬 DB면 쓰기 세션 안에서만) · `new_exec` · `failures_of` · `gate_line()` · `note_nums()`(옛 07의 계수 정규식) | `Q4V_*` 걷음 · 산출물 기본 `~/stock-screener-gate-run` |
| `small-db.sh` | 결정 6(FK 짝 · 자기 가드 행) | 새것 |
| `00-precheck.sh` | HEAD·트리·RUNNING·bootRun·크론·마이그레이션(운영 jar) · 대상 수와 하한 · `a2` 7행 종목 ⊂ 대상 · 토요일 리밸런싱·꼬리 · 미국 알림 항목 수 · LATEST · 프리셋 날짜 | 요일 무관 · 하한 더함 |
| `00b-stop.sh` | `crons-done.sh` → VACUUM → `stop.sh --app` | 새것(관문 순서 10 첫머리) |
| `01-dump.sh` | `DB_NAME=$GATE_PROD ops/dump-db.sh` · 경로 기록 | 사본 복원 뺌 · DB 이름을 글자로 |
| `02-snapshot.sh <라벨> <db> <off\|on>` | 결정 8 | 펜스를 실행기가 · 순위 판독기 갈래 · SIC 열 |
| `03-refill.sh <db>` | 결정 3 | `run past` 뺌 · `cand` 더함 |
| `04-unresolved.sh <db>` | 결정 4 | 새것 |
| `05-roster.sh <db>` | 결정 7(관문 내용이 정한 명부 잡 · 순위 이상치 · 창 결손 0) | 새것 |
| `06-reconcile.sh <db>` | 결정 5 + VACUUM | `05-backfill`·`06-vacuum` 대체 |
| `07-judge.sh <후> <전> [<작은 DB 후>]` | 국내 불변 · ADJ_CLOSE · 표본 동일성 · 펜스 귀속표 | ①②③ 뺌 |
| `09-flip.md` | 결정 9 | 새것 |

## 3. 드라이런 (작은 DB · 실키는 표본 약 45종목 · 13:20~15:45 밖)

- **G0 — 닫힘(기존 시험으로)**: `FinancialWriterTest.renumbersALabelLeftWithAGapOnlyWhenSwitchedOn`이 이미 「이른 판 끔 FILL_MISSING의 회수는 가운데 판을 지우고 번호를 0·2로 남긴다 — 지금의 동작」을 고정한다. a2 미국 7행의 원인이 그것이다. 결함인가의 세 답: 읽는 곳 — 번호 연속을 가정하는 `src/main` 코드는 Writer 자신뿐(최신 선택은 `revision_no` 순서만 본다) · 틀리는 것 — 값 0 · 닿는가 — 안 닿는다. 그래서 결함이 아니라 사실이고, 관문 재적재(이른 판 켬)가 그 종목을 재번호로 닫는다(결정 4③). 정기 FR이 그 뒤 다시 구멍을 낼 수 있으므로 a2 단언은 관문 실행 안에서만 건다.
- **G1** `small-db.sh` — 표본 행 수 = 운영 같은 종목 행 수(표마다) · post-data 실패 0 · 앱 `boot`가 그 DB로 뜬다 · `write-session.sh`가 그 DB의 가드 행으로 선다.
- **G2** 결정 2의 무해 — **V70을 주 마이그레이션 경로로 넣은 jar**로 작은 DB를 띄우고(옮긴 커밋을 작은 DB 기준으로 먼저 만든다 · 운영에는 안 간다) 꺼진 `bootRun --job=financial-refill --tickers=AAPL,SYM` → V70 표 0행 · 같은 실행 V70 없는 DB와 `financial_statement` 서명 같음 · **V70의 쓰기 가드 트리거가 세 표에 걸렸는지**를 카탈로그에서 센다(A6 — 빈 표만 보면 사소하게 초록).
- **G3** 실행기 전 구간(`GATE_PROD=<작은 db>`) — 00 → 00b(작은 DB에선 앱 정지를 건너뛰되 pid 단언은 돈다) → 01 → 02 전 → 03 → 04 → 05(명부 잡이 아직 없으면 「언제나 돈다」 자리에 지금 `universe-restore`를 둔다) → 06 → 02 후 → 07. 단언 전부가 **실제로 발화하는지** 대조군으로: 기대 실패 목록에서 하나를 빼면 03이 멈춘다 · 관문 인자를 빼면 출처 단언이 멈춘다 · `class_fact` 한 행을 FAILED로 고치면 04가 멈춘다 · V의 봉을 지운 채 05를 돌리면 결손 단언이 멈춘다 · 둘째 화해 전에 지표 한 칸을 지우면 06이 멈춘다 · 앱 pid가 있으면 boot가 멈춘다. 종목당 시간을 적는다(결정 10).
- **G4** 옛 `08-gate`의 대체 — G3 뒤 같은 작은 DB에 정기 FR과 같은 인자(연수 8 · 이른 판 거짓 · 관문 켬)로 한 판 → note `신규·정정·채움·교정·회수` 전부 0 · 0이 아니면 그 행을 열어 「창 의존(기존 결함)」인지 관문 묶음이 만든 것인지 가른다(LNT 대조군). **이어서 후보 소비(14:16 경로)와 후보 ⑧을 표본 후보에 한 판**(A9 — 켜진 jar의 첫 평일 경로).
- 판정이 끝나면 작은 DB·덤프·산출물을 지운다(`CLAUDE.md`).

## 4. 반증 판정과 닫은 방법 (v1 → v2)

계획 안 렌즈 「고쳐서 간다」 · 계획 밖 렌즈 「고쳐서 간다」 — 뼈대(운영 한 판 + 작은 DB)는 둘 다 세웠다. 지적은 내가 재서 닫았다(§0 v2 ★). 다시 띄우지 않는다.

| 지적 | 닫은 자리 |
|---|---|
| A1 저장 계층 기대 없음 | 결정 12 표본 동일성 (재생 하네스는 재료가 못 덮어 버림) |
| A2 `a2` 이미 7행 | 결정 4③(관문 뒤 0) · G0(원인) |
| A3 펜스 훅의 한계 | 결정 8(실행기가 돌림 · 전제마다 기록) |
| A4 FK 고아 | 결정 6(FK 짝 · 실측 하나) |
| A5 쓰기 세션 경로 미실행 | 결정 6·11(작은 DB 자기 가드 행) |
| A6 G2 사소한 초록 | G2(주 경로 jar · 트리거 수) |
| A7 SIC 무시 · 복원 판정 | 결정 7(언제나 돈다) · 결정 8(SIC 열) |
| A8 대상 수 자기 비교 | 결정 3(하한 4,128) |
| A9 소비 경로 | G4 |
| A10 되돌림 경로 | 결정 10 |
| 밖 1 관측 평면 옛 기저 | 결정 7 → `docs/reload-gate.md` 새 행(클래스 축 묶음 설계) |
| 밖 2 복원 신규 종목 시세 없음 | 결정 7(복원 꼬리 결손 보충 · main 선행) |
| 밖 3 순위 가드 우회 | 결정 7(05가 직접) |
| 밖 4 토요일·앱 정지 | 결정 10 · `00b` |
| 밖 5 옮기는 스크립트의 소비자 | 결정 1 |
| 밖 6 켜면서 할 일 | 결정 8·9 |
| 밖 7 알림·덤프 DB | 결정 9 · `01` |

## 5. 드라이런 결과 (2026-10-16 19:10~19:45 · 작은 DB 39종목 · 작업 트리 `explore/gate-dryrun` = main + V70을 주 경로로 옮긴 커밋 하나)

- **G1** 셋(`gate_a`·`b`·`c`) 각 108MB · 표 35개 전부 행 수 = 운영 같은 술어 · 제약 151 · 트리거 24 · 가드 행.
- **G2** V70이 주 경로로 `gate_a`에만 적용 · 관문 꺼짐(출처 classpath yaml) · V70 표 셋 0행 · 세 표 모두 `write_guard` 트리거(이벤트 트리거가 붙임) · `financial-refill --tickers=AAPL,SYM`의 재무 행 서명 md5가 V70 없는 `gate_b`와 같다(`ce702174…`).
- **G3** 00 → 00b → 01 → 02 전 → 03 → 04 → 05 → 06 → 02 후 → 07 끝까지 돌았다. 실행기 결함 넷을 잡아 main에서 고쳤다:
  측정 도구 — 백테스트 하네스 컨텍스트에 `ShareClassStore`·`CatalogWriteGate`가 빠져 프로브 열 개가 기동에서 죽고 있었다(`ce5694e7`) ·
  05의 맨 불리언 `--apply`를 `BootstrapRunner`가 거부(`48a99ea8`) · 작은 DB에서 복원은 순위 문턱(후보 1,000 미만이면 부분 목록 거부)에 원리적으로 선다 — 드라이런 한계로 적고 운영은 그대로 멈춘다(`8f8…` 05 갈래) ·
  한글이 붙은 `$변수`가 bash 3.2에서 unbound(`72cd4df8`).
  - 03: 현 편입 18 · 후보 재무 대상 20(하한 14 ≤ 20 ≤ 후보 20) · 업종 먼저 5 · 관문 줄 단언 통과 · **APGE·CRNX NOT_FOUND가 기대 밖이라 멈췄다** — 원장: 둘 다 9월 15-12G · SEC 티커 파일 둘에 없음 · Alpaca inactive → 구성원 판정을 적고 덤프로 되돌려(1.6초) 다시 돌았다. 뿌리(상장 폐지가 `stock.is_active`에 안 내려감 · 재무 행 있는 후보 80)는 `docs/reload-gate.md` 새 행.
  - 04: 클래스 축 미해결 0 · a2 미국 7 → 0(G0 예측대로).
  - 06: 첫 판 원본층 쓴 345,118 · 지운 6,469 · 파생층 쓴 28,421 · 지운 18,724 → 둘째 판 0 · 0 · 실패 0.
  - 07: ② ADJ_CLOSE 감소 0 · ① 국내 지표 묶음 20 차이 — **전부 005930의 파생층**(백분위·업종) — 작은 DB의 국내 모집단이 1이라 생긴 것(원본층 차이 0 · 운영 모집단은 그대로) · ④ 바뀐 펜스 29가 귀속 파일 없이 실패(설계대로).
- **대조군 발화**: 기대 실패 밖(03 · 실제) · `class_fact` 한 행 FAILED(04 rc=1) · 시세 없는 V에 닫힌 복원 편입(05 ④ rc=1) · 관문 줄 없는 로그(`gate_line` rc=1) · 운영 앱이 떠 있음(`app_down` rc=1). 복원 꼬리가 부를 적재(V · 시세 7년 · 재무 건너뜀)를 손으로 태워 1,759봉 · 닫힌 복원 편입만 가진 종목도 `initial-load` 술어에 든다.
- **G4** 후보 표본(후보 ⑧) 다섯 계수 0 · 사건 0 · 실제 `candidate-consumption` SUCCESS(소비 1 · FR 0) · **현 편입은 LNT 2018·2019 Q4 정정 2로 멈췄다** — 기존 결함 「LNT 8년/11년」(적재 창 의존 · `docs/reload-gate.md` 행)이 대조군으로 재현됐다. 관문 뒤 첫 정기 FR이 이 둘을 되돌리므로 관문 전에 닫을 일이다.
- **재지 않음**: 06의 실패 대조군(둘째 판 앞에 칸을 지우기 — 스크립트 중간에 끼울 자리가 없다) · 복원 자체(작은 DB 한계 — 운영 복원 경로는 ADR-0135 실행 기록) · 운영 덤프 복원 시간.
- 곁 결함: `initial-load`의 재무 건너뜀 note가 사유와 무관하게 「그 시장에 재무 소스가 없다」를 적었다(운영 30행) — `81dcdc52`.
