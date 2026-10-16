# 관문 실행 도구 계획 — `ops/apply-window`를 미국 관문 기준으로 다시 쓴다 (2026-10-16 · v1)

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

## 1. 결정

1. **전체 사본을 없앤다 — 관문은 운영 한 판 + 전후 스냅숏 + 하드 단언이다.** 귀속(어느 묶음이 어떤 칸을 바꿨나)은 관문 전에 각 묶음의 0콜 스윕이 낸 기대와 대조한다(`CLAUDE.md` 「재적재·백필」 — 수용 기준을 재적재 뒤에 재지 않는다). S1의 역할(명령이 서는가 · 끼어듦)은 **작은 DB 드라이런**이 갖는다(결정 6). S0의 역할(설정 귀속)은 각 묶음 스윕의 꺼짐/켬 diff가 이미 갖는다.
   버리는 것: `01-dump-shadows.sh` · `07-judge.sh`의 ①③ · `08-gate.sh` · `r3nk.py` · `premises.py` — 지우지 않고 `ops/apply-window/q4v/`로 옮겨 ADR-0161 기록이 가리키는 절차로 남긴다(ADR-0161·README가 경로로 부른다 — 부르는 곳을 함께 고친다).
2. **마이그레이션 기준은 운영 jar 커밋 그대로** — 관문 날 첫 단계가 「V70을 주 마이그레이션으로 옮긴 커밋 → `ops/deploy.sh`(설정 꺼짐)」다. 이러면 `00-precheck`·`boot()`의 「HEAD = 운영 jar = 사전 점검 HEAD」가 그대로 서고, 관문 `bootRun`과 운영 jar가 같은 스키마를 본다. 기준을 HEAD로 바꾸는 대안은 버린다 — 운영 jar에 없는 마이그레이션을 `bootRun`이 운영 DB에 넣게 되고, 실패하면 운영 jar가 모르는 스키마로 남는다.
3. **재적재는 두 실행**(같은 인자 `--financial-years=11 --insert-earlier-vintages=true --stockscreener.gate.us=true`): `cur` = `--universe=US_TOP1000`(대상 = precheck의 N_CUR) → `cand` = `--population=candidates`(대상 = 그 실행 로그의 「재무 대상 M」 = FR 실행 행 `target_count` · 「업종 먼저 K」 > 0이면 `company-profile` 실행 행도 하나 · FAILED면 멈춤).
   - **매 `boot` 로그에서 `관문 설정 stockscreener.gate.us=true (출처: …명령줄…)`을 단언한다** — `.env`를 읽지 않고(시크릿) 실효값과 출처를 앱이 말한 대로 본다. `US_GATE`가 어긋나면 `GateSettingGuard`가 기동을 세우고 `new_exec`가 「새 실행 행 0」으로 멈춘다.
   - **실패는 구성원마다**: `expected-failures.tsv`(티커 · 종류 · 근거 한 줄 — 드라이런·후보 스윕의 `fetch-failures.tsv`가 낸다)에 없는 실패 → 멈춤. `TRANSIENT`·`RATE_LIMITED`는 같은 모집단 인자(`cand`면 `--population=candidates`)에 `--tickers=`로 재시도.
4. **재적재 뒤 하드 단언**(하나라도 서지 않으면 다음 단계로 안 간다): ① 클래스 축 미해결 0(`sec_filing_class_fact` `FAILED ∧ failure_kind ≠ UNREADABLE ∧ reader_version = 1`) · `UNREADABLE`은 `unreadable-verdicts.tsv`에 종목마다 판정이 있어야 한다 ② 공시 보충 `FAILED`·`stale_used_at IS NOT NULL` 전원이 `supplement-verdicts.tsv`에 판정 · 회수정지종목 같음 ③ `a2.sql`(판 번호·최신 표지 정합) 미국 0.
5. **화해 두 번**: `indicator-reconcile --stockscreener.gate.us=true` 두 판 · 둘째 판의 ①② 「쓴 0 · 지운 0」 · 두 판 모두 실패 0(관문 순서 10의 대조군 문장 그대로). 그 사이 `universe-restore`가 필요하면(결정 7) 화해 앞.
6. **드라이런 = 작은 DB에서 실행기 전 구간**(`small-db.sh <db> <티커 파일>`): 스키마(pre-data) → `stock_id` 있는 표는 표본 종목 행만 · 없는 표는 통째(전부 ≤7.6MB) · `stock`은 표본 + 표본이 FK로 부르는 행 → post-data(제약·색인 — 고아 행이 있으면 여기서 실패해 드러난다).
   표본(근거를 적어 고른다): 현 편입 — LNT(창 의존 대조군) · 클래스 축 Up-C 다섯(SYM·GLXY·LB·CWEN·BLLN) · 숨김 넷(BNT·VNOM·WSE·BROS) · 보충 판정 대상 상위 · 실패 기대(APGE·CRNX) · 대조 AAPL · 후보 — V·BRK.B·STZ(순위 진입) · SIC 없는 후보 · companyfacts 404 후보 · 후보 스윕의 fetch 실패 전원. 약 40종목.
   작은 DB에선 `--universe=US_TOP1000`과 후보 술어가 그 DB의 행으로 풀리므로 대상 수가 표본 수로 정확히 떨어진다 — 그것이 대상 수 단언의 시험이다.
7. **복원 판정**: 순위 스냅숏을 꺼짐 판독기(전)와 켬 판독기(후)로 찍고 관측 하한 앞 격자를 diff — 차이 > 0이면 ADR-0135 규칙대로 `market-calendar` → `universe-restore`(인자로 켬). 순위 스냅숏에 `RANK_SNAPSHOT_CLASS_AXIS=true`를 더한다(시험 코드 한 줄 · `new JdbcStoredShareCounts(jdbc, new ShareClassStore(jdbc), true)`).
8. **전후 산출물(판정이 아니라 귀속 재료)**: 프리셋 결과(날짜 = 그날 창에서 규칙으로 — 창 첫날 · 첫날+1거래일 · 리밸런싱일 각 월 첫 · 분기 공시 몰림 주 · LATEST) · 순위(위) · 재무 행 서명 · 지표 묶음 해시 · 펜스(`check-premises.sh`를 그 DB로 — `DB_NAME`) · 국내 해시. **하드 단언은 둘만 남긴다**: 국내 재무·지표 불변 · `ADJ_CLOSE` 감소 0.
9. **켜진 jar 배포는 스크립트 밖** — `09-flip.md` 점검표(코드 기본값 커밋 · `.env`에 `US_GATE` 줄 없음은 사람이 확인 · `ops/deploy.sh` · 기동 로그의 출처 줄 · 첫 14:20 귀속). 스크립트가 배포하면 실패한 관문 뒤에도 켜진 jar가 뜰 수 있다.
10. **창**: 토 20:00 리밸런싱(후보 풀 · 인입) 끝 뒤 ~ 월 13:30 전. 앱이 내려 있는 동안 크론 발화가 사라지므로(재기동하면 그날 남은 발화가 사라진다) 크론이 없는 창에 둔다. 소요는 후보 스윕의 콜 속도로 재서 적는다(재적재는 그보다 무겁다 — 보충 · 이른 판).
11. **쓰기 세션**: 운영에 쓰는 단계(재적재 · 복원 · 화해 · VACUUM)는 `ops/write-session.sh exec <id> -- <스크립트>`로(ADR-0175) · 작은 DB에선 `boot()`가 세션 변수를 걷는다(지금 코드).

## 2. 스크립트

| 파일 | 하는 일 | 옛것과 |
|---|---|---|
| `env.sh` | `GATE_PROD`(기본 운영) · `boot()` · `new_exec` · `failures_of` · `gate_line()`(boot 로그 단언) · `note_nums()` | `Q4V_*` 걷음 · 산출물 기본 `~/stock-screener-gate-run` |
| `small-db.sh` | 결정 6 | 새것 |
| `00-precheck.sh` | HEAD·트리·RUNNING·bootRun·크론·마이그레이션(운영 jar) · 대상 파일(현 편입 · 창 안 과거 편입 중 비활성 0) · LATEST · 프리셋 날짜 | 날짜·과거 편입 단언 더함 |
| `01-dump.sh` | `ops/dump-db.sh` 한 번 · 경로 기록 | 사본 복원 뺌 |
| `02-snapshot.sh <라벨> <db> <off\|on>` | 결정 8 | 날짜를 파일에서 · 순위 판독기 갈래 · 펜스를 훅으로 |
| `03-refill.sh <db>` | 결정 3 | `run past` 뺌 · `cand` 더함 · 관문 인자 · 출처 단언 |
| `04-unresolved.sh <db>` | 결정 4 | 새것 |
| `05-restore.sh <db>` | 결정 7(차이 있을 때만) | 새것 |
| `06-reconcile.sh <db>` | 결정 5 + VACUUM | `05-backfill`·`06-vacuum` 대체 |
| `07-judge.sh <후> <전>` | 국내 불변 · ADJ_CLOSE · 산출물 diff 요약 | ①②③ 뺌 |
| `09-flip.md` | 결정 9 | 새것 |

## 3. 드라이런 (작은 DB · 실키는 표본 약 40종목 · 13:20~15:45 밖)

- **G1** `small-db.sh` — 표본 행 수 = 운영 같은 종목 행 수(표마다) · post-data 실패 0 · 앱 `boot`가 그 DB로 뜬다.
- **G2** 결정 2의 무해 — 작은 DB에 V70을 넣고 꺼진 `bootRun --job=financial-refill --tickers=AAPL` → V70 표 0행 그대로 · 같은 실행 V70 없는 DB와 `financial_statement` 서명 같음.
- **G4** 옛 `08-gate`의 대체 — G3 뒤 같은 작은 DB에 정기 FR과 같은 인자(`--universe=US_TOP1000` · 연수 기본 8 · 이른 판 거짓 · 관문 켬)로 한 판 → note `신규·정정·채움·교정·회수` 전부 0 · 0이 아니면 그 행을 열어 「창 의존(기존 결함)」인지 관문 묶음이 만든 것인지 가른다(표본에 LNT를 넣는다 — 대조군).
- **G3** 실행기 전 구간(`GATE_PROD=<작은 db>`) — 00 → 01(작은 DB 덤프) → 02 전 → 03 → 04 → 05 → 06 → 02 후 → 07. 단언 전부가 **실제로 발화하는지** 대조군으로: 기대 실패 목록에서 하나를 빼면 03이 멈춘다 · 관문 인자를 빼면 출처 단언이 멈춘다 · `class_fact` 한 행을 FAILED로 고치면 04가 멈춘다 · 둘째 화해 전에 지표 한 칸을 지우면 06이 멈춘다.
- 판정이 끝나면 작은 DB·덤프를 지운다(`CLAUDE.md`).

## 4. 반증에 넘기는 공격로

- 결정 1: 전체 사본을 빼면 잃는 판정이 있는가 — 옛 S1이 잡던 「끼어듦·비결정성」을 작은 DB가 잡는가 · G4가 옛 `08-gate`를 정말 대체하는가(표본 크기).
- 결정 6: 작은 DB에서 후보 술어·`US_TOP1000` 대상 수가 운영과 다른 이유로 맞아떨어지는 자리(표본 밖 종목을 부르는 조인 — 섹터 백분위 · 시장 지수 · 순위).
- 결정 8: 하드 단언 둘만 남긴 것이 「싼 대체」인가 — 각 묶음의 수용 기준이 관문 뒤에 재야 하는 수를 가지면 이 실행기가 그것을 받을 자리가 있는가.
