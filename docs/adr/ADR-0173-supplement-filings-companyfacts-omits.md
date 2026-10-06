---
id: ADR-0173
status: 채택됨
date: 2026-10-06
applies_to:
  - "src/main/java/**/datasource/us/SecFilingSupplement.java"
  - "src/main/java/**/datasource/us/SecFilingClient.java"
  - "src/main/java/**/datasource/us/XbrlInstanceReader.java"
  - "src/main/java/**/datasource/us/SecFilingSupplementStore.java"
  - "src/main/java/**/datasource/us/SecEdgarFinancialAdapter.java"
  - "src/main/resources/db/migration/V64__sec_filing_supplement.sql"
---

# ADR-0173. companyfacts가 빠뜨린 정기 공시를 그 공시의 XBRL 인스턴스로 보충한다

## 결정

미국 재무의 원자료는 **companyfacts + 공시 보충**이다.

- **보충 대상**: 공시 목록(submissions)에서 아래를 모두 만족하는 공시.
  - 받는 창(라벨 창보다 한 해 앞, `from − 1`년 1월 1일) 안에 있다.
  - 보충 폼(10-K·10-Q·20-F·40-F·6-K와 각 정정)이다.
  - 원장이 XBRL이 있다고 말한다(`isXBRL`).
  - companyfacts가 그 접수번호의 us-gaap 팩트를 하나도 안 싣는다.
- **읽는 법**: 그 공시 폴더에서 인스턴스(`.xml` 중 `FilingSummary.xml`과 링크베이스를 뺀 하나)를 골라 companyfacts 노드 모양으로 합친다. 축 없는 us-gaap·dei 수치이고, (개념 · 단위 · 기간)마다 가장 정밀한 것을 고른다.
- **저장**: 받은 원 팩트는 `sec_filing_supplement`(V64)에 (접수번호, CIK)마다 정밀도째 남긴다. 그래서 평생 한 번 받는다.
- **보충한 공시의 판은 이른 판이어도 넣는다**(`VintageGuard.supplemented`). 이력과 무관하게 「이번 실행에서 실은 공시」만 본다. 그래서 성공·실패의 순서와 배포 시점의 저장 상태(늦은 판이 이미 있음)에 무관하게 같은 판 집합에 닿는다.
- **실패 계약**: 보충 하나가 실패해도 종목을 세우지 않는다.
  - 그 공시의 제출일 판을 회수에서 지킨다(`VintageGuard.unconfirmed`).
  - 실패를 `FAILED`로 남긴다.
- **companyfacts가 나중에 그 공시를 실으면** 상태(OK·NO_XBRL·FAILED)와 무관하게 `COVERED`로 바꾸고, 그 공시의 판을 매 실행 이른 판이어도 넣게 연다 — 여는 날짜는 **companyfacts가 그 공시에 붙인 `filed`**다(덮인 뒤 그 공시의 판은 그 날짜로만 나온다 · 17:30 ET 뒤 접수면 목록 날짜보다 하루 이틀 늦다 · 15 / 55,736 접수번호 · 미리 못 가른다 · 목록 날짜를 열면 그날 다른 공시의 판만 이른 판으로 들어온다). 공시 목록을 못 받은 실행도 이 정리와 같은 날 보호를 한다(대상은 companyfacts가 안 싣는 공시뿐이라 정리는 목록이 필요 없다).
  - 공시 목록을 못 받으면 저장분만 합치고, 그 종목의 회수를 멈춘다(`VintageGuard.uncertain`).
- **실패가 아닌 원장의 사실 둘**:
  - 폴더에 XBRL 산출물(인스턴스 · 스키마 · 링크베이스)이 없으면 `NO_XBRL`이다.
  - 인스턴스에 이 CIK의 컨텍스트가 없으면(다른 등록자의 팩트만 싣는 공시) 팩트 0이다.

## 규칙

- MUST: 보충 폼 목록을 `SecEdgarFacts`의 정기보고서 목록과 **따로** 둔다. 그쪽에 8-K를 더하는 변경이 보충을 따라 넓히면 대상이 약 81배가 된다(83 CIK 실측: 8-K XBRL 8,315 중 companyfacts us-gaap 0이 7,012). 이 목록은 그쪽의 부분집합이어야 한다 — 여기만 넓히면 보충해도 버려진다. 시험이 둘 다 단언한다.
- MUST: 저장 표의 팩트는 **고르기 전**의 원 팩트다. 해석 규칙(읽기 · 고르기 · 인스턴스 고르기 · 후보 파일 규칙 · 신원)을 **하나라도** 바꾸면 `XbrlInstanceReader.READER_VERSION`을 올린다 — 해석 실패 행은 판이 같은 동안 다시 받지 않으므로, 안 올리면 고친 규칙이 그 행에 영영 안 닿는다(SWX의 신원 규칙을 고치고도 판을 안 올렸다면 영구 실패였다). `ReaderVersionPinTest`가 받은 바이트의 해석(`SecFilingSupplement.interpret` — 읽기 · 신원 · 고르기 · 해석 실패 분류)과 후보 파일 규칙·인스턴스 고르기의 출력을 판마다 박아 묻는다 — 재료는 해석 실패 자리(`throw malformed(`)마다 하나(그 수를 소스에서 세어 단언) · 건너뛰는 조건마다(네임스페이스 · 차원 · 기간 · CIK) · 고르기 키 구성요소마다 그 요소만 다른 쌍 · 공백 · 자기 컨텍스트 0이고, 신원은 보충 폼 × (보충 폼 · 전환 보고서 · 다른 공시 · 없음) 행렬이다. 판 범위의 분기를 더하면 재료를 함께 더한다 — 2026-10-06 반증 변이 19개(규칙마다 하나)가 전부 붉다.
  - 보충 대상인 행은 판이 다르면 다시 받는다.
  - 다시 받지 못하면(또는 공시 목록을 못 받으면) 옛 판의 저장분을 쓰고 그 사실을 남긴다(`stale_used_at`). 그 행은 미해결이다.
- MUST: **관문 설정 뒤에 두는 해석 규칙은 판을 그 설정의 함수로 둔다**(설정을 보충까지 잇는다). 상수 하나면 꺼진 정기 FR이 옛 규칙으로 다시 읽고 새 판 도장을 찍어, 켠 관문 실행이 그 행을 다시 안 받는다. 지금 그런 규칙은 없다 — 첫 후보는 「축에만 실린 미국 재무」다.
- MUST: 결정적 실패는 **받은 파일을 해석하지 못한 것**(`UNREADABLE` — 인스턴스 후보 둘 이상 · 스키마는 있는데 인스턴스 없음 · 읽기 · 문서 종류 불일치 · 같은 정밀도 충돌)뿐이다. 해석 규칙의 판이 같은 동안은 다시 받지 않는다. 받지 못한 것(404 · 5xx · 본문 이상 · 한도)은 다음 실행이 다시 받는다 — 일시 404 한 번을 영구로 만들지 않는다.
- MUST: 저장 표의 상태 전이는 **종목 쓰기와 같은 트랜잭션**에서 일어난다(`StockTaskRunner`의 `REQUIRES_NEW` 안에서 호출자 트랜잭션에 참여).
- MUST NOT: 보충 하나의 실패로 종목을 세우지 않는다. 세우면 companyfacts 몫까지 못 써서 보충이 없던 때보다 후퇴한다. 종목이 실패하는 것은 저장 표 쓰기 자체가 실패할 때뿐이다.
- MUST NOT: 원장 행을 지우지 않는다 · 덮인 공시를 실패 행만 고르지 않는다. companyfacts가 그 공시를 나중에 실으면 상태와 무관하게 `COVERED`로 남기고 **매 실행** 그 판을 연다 — 그 전에 늦은 판이 먼저 들어왔어도 「처음부터 companyfacts가 실었을 때」와 같은 상태에 닿게. 한 번만 열면 그 실행이 APPEND_ONLY이거나 같은 날 다른 공시가 실패할 때 열림이 효과 없이 사라지고, 받았던 공시와 실패였던 공시가 다른 판 집합에 닿는다(실측 전이 EXE 1건 · 09-15→10-06). 받는 창 밖으로 나간 행도 그대로 둔다 — 창이 좁은 손 실행이 기본 창의 행을 지우면 안 된다.
- MUST NOT: 「원본 10-K·10-Q의 us-gaap 0」을 실패로 세우지 않는다. 실물 일곱(GEHC 표지만 · BTU·CEG 축에만 · MAIN·OBDC·OTF)이 전부 원장의 사실이었다.
- MUST: 측정 도구(`SecEdgarFactsSweepTest` · `SecEdgarFactsReplayTest` · `EarlierVintageReplayHarnessTest`)는 보충 설정(같은 날 모은 원자료 경로 또는 `off`)을 **명시해야** 돈다. 경로를 주면 「원자료 없음 0」을 단언한다. 보충은 어댑터 출력의 일부라, 안 주면 운영과 다른 출력을 조용히 낸다.
- MUST: 관문 재적재 앞에서 미해결(`FAILED` ∪ 옛 판을 실제로 쓴 행)을 구성원마다 판정한다(`docs/reload-gate.md` 10단계). FR·initial-load 실행 기록의 `미해결보충=N(표 전체 M)`·`회수정지종목=`이 그 재료다(initial-load는 APPEND_ONLY라 회수 정지가 없다 — 리밸런싱 꼬리가 보충을 타는 정기 경로라 칸을 단다) — 보충 실패와 목록 실패는 종목을 안 세워 실행이 SUCCESS로 끝나므로, 이 칸이 아니면 안 뜬다. N은 그 실행이 본 행뿐이다(`last_seen_at` · `stale_used_at`) — 표 전체를 세면 기본 FR이 안 도는 과거 편입의 행이 섞여 관문 사이에 0으로 못 내려온다. 관문은 M을 본다. 「판이 낮다」만으로는 안 센다 — 안 쓰이는 행(COVERED · 대상 밖)은 판정할 것이 없고, 세면 판을 한 번 올린 뒤 M이 0으로 못 내려온다.

## 검증

```bash
./gradlew test --tests "*XbrlInstanceReaderTest" --tests "*SecFilingSupplementTest" \
               --tests "*SecFilingSupplementWriteContractTest" --tests "*FinancialWriterTest"
```

- **시험**: 리더 · 보충(받기/해석 실패의 층 · XBRL 없음 · 무상태 판 열기 · 나중에 companyfacts가 실은 실패 행의 `COVERED`와 매 실행 열림 · 미해결이 실행 범위 · 페이지 · 폼 독립) · 운영 수집 경로(어댑터 → 보충 → 저장 표 → `FinancialCollector` → Writer)에 「실패 먼저 · 성공 먼저 · 옛 코드가 늦은 판만 쌓은 상태에서 첫 성공 · 다시 읽기 실패 · companyfacts가 뺀 공시의 보호 · 목록 실패의 회수 정지 표지 · 덮인 공시가 APPEND_ONLY 뒤에도 열림」. **열림 기제의 증거는 이 시험뿐이다** — RS1에서 그 갈래는 한 번도 안 탔다(아래).
- **RS0**(2026-10-06 · 같은 날 수집 — 모집단 1,209 · 대상 547 · 콜 4,102 · 0콜 대조).
  - 제거 대조 18: companyfacts에서 그 공시의 us-gaap를 지우고 보충한 출력이 원래 보충 출력과 **바이트까지 같다**.
    - 대조군(지우기만 함)은 7티커에서 그 판을 잃었다(인라인 10-Q · 10-K · 비인라인 · 비교열 판 · 정정 둘).
    - 정정 일곱은 지워도 출력이 안 바뀌어 대조가 본체에 닿지 않았다.
  - 전/후: 보충 활동 종목 313 · 보충 없는 종목은 출력 차이 0.
    - 늘어난 행: 새 라벨 133 · 늦은 판 14 · HOLE 해 4(AOS·SPGI·EMAT) · 같은 값을 먼저 다시 실은 판으로 바뀐 것 3.
    - 「그 밖」(같은 판의 값 차이)은 0이다.
  - 실패 0 · XBRL 없음 37 · 다른 등록자 팩트만 1(SWX FY2016) · 문서 종류 불일치 0 · 같은 정밀도 충돌 0.
- **RS1**(같은 날 캐시를 같은 시작 상태의 사본 둘에 Writer째 재생 · 정기 플래그 · 1,207종목): 보충 쪽에만 있는 판 154가 RS0 분류와 정확히 같다 · 보충 없는 종목의 행 차이 0 · 같은 판의 값 차이 0(41열 전부) · 두 번째 재생은 저장분 경로만 타고 쓰기 0. Writer의 이른 판 넣기는 **0번**이다 — 「같은 값을 먼저 다시 실은 판」 3은 회수 뒤 정정 갈래로 들어왔다.
- 보충 설정 `off`인 스윕은 보충 전 트리와 바이트까지 같다(`.factcache` 1,218종목).

## 배경

2026-10-06 실측(83 CIK 표본 · `explore/financial-gap`). companyfacts가 창 안 정기 공시 87건의 us-gaap를 통째로 안 싣는다.
- 2026 인라인 10-Q 78
- 정정 6
- 원본 3(GEHC · CLX · SPGI)

모집단(현 편입 + 과거 편입 1,209)에서는 보충 대상이 547이고, 313종목이 보충으로 무언가를 받았다.
- 외국 발행사의 6-K도 들어 있다. AER·BNT·DSGX·ICLR은 6-K 인스턴스에 us-gaap가 있어 새 분기 라벨이 생긴다.
- 그 공시의 인스턴스는 companyfacts와 같은 값을 준다: AAPL `0000320193-26-000020` 283/283 · JPM 787/788 · MSFT 597/601. 어긋남은 전부 우리 매핑 밖 개념의 companyfacts 쪽 정규화다.

그래서 그 분기 라벨이 통째로 비거나 다음 공시의 비교열이 그 판을 대신했다 — 원장이 정직하게 주는데 우리가 안 받은 **가짜 한계**다.

## 버린 대안

1. **SEC가 companyfacts를 채우기를 기다린다** — 2026-07~08에 낸 6월 분기 10-Q가 10-06 같은 날 수집에도 빠져 있었다. 언제 채우는지 계약이 없다.
2. **판 이력을 전부 낸다는 표지(`completeVintageHistory`)를 SEC에서 끈다** — 회수가 유도 라벨로만 좁아지고 이른 판 넣기가 꺼진다. 그래도 유도 라벨은 회수하므로 안전하지 않고, 판 이력 정리를 잃는다.
3. **보충이 실패하면 종목을 세운다** — 위 MUST NOT. 그 종목의 companyfacts 정정까지 막는다.
4. **고른 팩트만 저장한다** — 고르기 규칙을 고치면 저장분 전부를 다시 받아야 한다.
5. **회복을 「FAILED였다」는 이력으로 판정한다**(첫 구현) — 배포 시점처럼 늦은 판이 이미 있으면 「첫 시도 성공」은 원본 판을 이른 판으로 건너뛰고 「실패 뒤 성공」은 넣어, 순서가 결과를 바꿨다. 수동 `initial-load`(APPEND_ONLY)가 표지를 소비하는 구멍도 있었다(구현 반증 두 렌즈).
6. **404·응답 이상까지 결정적 실패로 접는다**(첫 구현) — 받지 못한 것을 해석 실패와 같게 다뤄, 일시 404 한 번이 판을 올릴 때까지 영구가 됐다.
7. **companyfacts가 실은 실패 행을 지우고 그 날짜를 한 번 연다**(둘째 구현) — 「무상태」 근거를 성공 갈래에서 정리 갈래로 옮겨 단 것이다. 열림을 쓰는 것은 FILL_MISSING Writer뿐이라 APPEND_ONLY 실행·같은 날 다른 공시의 실패가 열림을 소비했고, 지운 뒤에는 다시 안 열렸다(확인 반증 두 렌즈).

## 결과

- **설정 뒤에 두지 않는다.** 관문 규칙(`CLAUDE.md` 「재적재·백필」)의 「값을 바꾸는 어댑터 코드는 꺼진 설정 뒤」는 관문 한 번에 묶일 변경을 위한 것이다. 이 보충은 현 편입을 정기 FR로 고치는 결손 수정이다(구현 반증 계획 밖 렌즈가 과거 편입 · 후보 · 재고를 대 보고 「선다」).
  - 그 대가로 1부 배포 뒤 다음 정기 FR(10-30 예정 · 약 +8분)이 이 변경을 현 편입 전원에 싣는다.
  - 결손 종목은 그 전에 좁힌 손 FR과 `indicator-reconcile`을 한 승인·한 번에 적용한다(사용자 승인 · FR과 화해 사이엔 무효화된 종목의 지표가 0행이다). 0부(ADR-0172) 뒤라 그 실행은 FR 시계를 안 민다.
    - **화해는 FR과 같은 `--tickers`(또는 인자 없이)** — FR 알림 문구의 「위 종목」은 무효화된 종목뿐이라, 새 라벨만 받은 종목(RS1 94)의 지표가 다음 정기 화해까지 낡는다.
    - **손 FR 직전마다 `ops/dump-db.sh`** — FR 쓰기를 되돌리는 길은 덤프뿐이고(ADR-0071), 덤프 가드는 덤프가 하나라도 있으면 통과한다.
  - **첫 손 FR 절차(글자 그대로 · 사용자 승인 · 앱을 내린 채 · 실키는 평일 13:20~15:45 밖 · 토 10-10 낮 두 jar 재생 전)** — 셸 변수 `R`=main 작업 트리 · `T`=작업 폴더 · `D`=1부를 배포한 커밋(배포 직후 `docs/progress.md` 1부 줄에 적은 해시) · `TICKERS=$(cut -f2 $T/targets.tsv | paste -sd, -)`:
    - **앱을 내리는 이유**: 운영 쓰기 잡 사이의 배타는 「평일 13:30~15:40 밖」이라는 시각 창 하나뿐인데, 앱 크론은 기계가 잠든 만큼 밀려 그 창 밖에서도 돈다(2026-10-06 +1:55 · `docs/reload-gate.md` 「앱 크론이 기계 수면만큼 밀린다」). 겹치면 화해끼리 교착하고(#305), 덤프 뒤 앱의 쓰기가 사후 대조를 오판시키며 되돌리기가 그 쓰기까지 지운다. 관문과 같은 꼴이다.
    - **`bootRun`마다 붙은 DB를 글자로 단언한다** — 출력을 `2>&1 | tee $T/<단계>.log`로 받고 `grep -c "bootstrap이 데이터베이스 <기대 DB>에 붙었다." $T/<단계>.log` = 1 · `grep -c "부트스트랩 러너가 끝났다. job=<잡> status=SUCCESS" $T/<단계>.log` = 1. 가드가 서도 gradle은 종료코드 0이다(`BootstrapDatabaseGuard`). 같은 셸에서 사본 → 운영을 이어 치므로, 환경이 이월되면 운영 명령이 사본에 붙는다(Gradle 데몬의 환경 이월은 재보지 않았다).
    0. **전제**(하나라도 아니면 멈춘다):
       1. 오늘 크론이 다 돌았다 — 시계가 아니라 기록으로(밀린 날은 15:40 뒤에도 13:30 잡이 안 돌았을 수 있다): 평일이면 `psql -d stock_screener -Atc "select count(*) from batch_execution where job_name='indicator-derived-rebuild' and triggered_by='SCHEDULER' and started_at::date = current_date and finished_at is not null"` ≥ 1 · `grep -c "^$(date +%F).*편입 원자료를 점검했다" ~/Library/Logs/stock-screener/stdout.log` ≥ 1 · `select count(*) from batch_execution where status='RUNNING'` = 0 → `$R/ops/stop.sh --app`.
       2. 무엇을 컴파일하는가(`bootRun`은 작업 트리를 컴파일한다): `git -C $R diff --quiet $D HEAD -- src build.gradle settings.gradle` · `git -C $R status --porcelain -- src build.gradle settings.gradle` 비어 있음.
       3. 운영: `select 1 from flyway_schema_history where version='64' and success` = 1 · `select count(*) from sec_filing_supplement` = 0(되돌리기가 거기 기댄다) · `select max(id) from batch_execution` → `E0`.
       4. 목록 파일(탐색 브랜치는 작업 트리에 없을 수 있다): `git -C $R show 8e8f8484:explore/financial-sync/p1-fr1/fr1_tickers.txt > $T/fr1_tickers.txt` · 같은 꼴로 `fr1_tickers.sql`(서명 질의).
    1. 대상: `psql -d stock_screener -AtF $'\t' -c "select id, ticker from stock where market_code='US' and ticker = any(string_to_array('$(paste -sd, $T/fr1_tickers.txt)', ','))" > $T/targets.tsv` — 109행이 아니면 멈춘다.
    2. **사본 재생(사전 기대 · 중단 조건)**:
       1. `$R/ops/dump-db.sh` → 그 덤프를 새 DB 둘에 복원한다: `createdb stock_screener_q4v_fr1_base && pg_restore -d stock_screener_q4v_fr1_base <덤프>` · 같은 것을 `stock_screener_q4v_fr1`로(앞의 것은 되돌리기용 · 손대지 않는다).
       2. 같은 날 보충 원자료(운영 FR 직전에): `cd $R && SUPP_CACHE_DIR=$T/cache SUPP_TICKERS=$T/fr1_tickers.txt SUPP_FROM=2018 ./gradlew liveTest -PskipFrontend --tests "*SecFilingSupplementCacheLiveTest*"` — 수집 시각을 적는다.
       3. 재생: `cd $R && Q4V_REPLAY_DB=stock_screener_q4v_fr1 Q4V_REPLAY_CACHE=$T/cache/companyfacts Q4V_REPLAY_TARGETS=$T/targets.tsv Q4V_REPLAY_OUT=$T/replay.tsv Q4V_REPLAY_FROM=2018 Q4V_REPLAY_INSERT_EARLIER=false Q4V_REPLAY_SUPPLEMENT_DIR=$T/cache Q4V_REPLAY_SUPPLEMENT_STORE=db TEST_DB_URL=jdbc:postgresql://localhost:5432/stock_screener_test ./gradlew test -PskipFrontend --tests "*EarlierVintageReplayHarnessTest*" --rerun` — 출력 줄 `Q4V_REPLAY …`.
       4. 사본 주식수 기저(운영 FR 뒤에 도는 것과 같게 · `ops/apply-window/env.sh` `boot()`의 사본 갈래와 같은 꼴 — 알림 변수를 비워야 사본의 알림이 운영 채널로 안 간다): `cd $R && DB_URL=jdbc:postgresql://localhost:5432/stock_screener_q4v_fr1 BOOTSTRAP_DB=stock_screener_q4v_fr1 ALERT_WEBHOOK_URL= MAIL_USERNAME= SENTRY_DSN= ./gradlew bootRun -PskipFrontend --args='--spring.profiles.active=bootstrap --job=share-basis-derive' 2>&1 | tee $T/2-4.log` · 기대 DB `stock_screener_q4v_fr1`.
       5. **사전 기대**(RS1의 109 몫): 대상 109 · 신규 128 · 정정 28 · 교정 3 · 이른판 0 · 회수 3 · 보충실패 0 · 목록실패종목 0 · 회수정지 0종목 · 실패 0. **이른판 · 보충실패 · 목록실패종목 · 회수정지 중 하나라도 > 0이면 운영 FR을 안 한다.** 신규·정정·교정·회수의 차이는 10-06 뒤 공시(`published_at` > 2026-10-06)로 구성원마다 설명돼야 하고, **설명이 안 되는 차이가 하나라도 있으면 운영 FR을 안 하고 그 구성원마다 원장에 물어 판정한다.** 이 수는 1부 코드로 다시 잰 것이다(2026-10-06 · `sc_a` 사본 · v7과 v8 둘 다 — 종목 표 1,207행이 처음 잰 RS1 b1과 같다).
    3. 운영 FR: `cd $R && ./gradlew bootRun -PskipFrontend --args="--spring.profiles.active=bootstrap --job=financial-refill --tickers=$TICKERS" 2>&1 | tee $T/3.log` · 기대 DB `stock_screener` — **`--universe`·`--financial-years`·`--insert-earlier-vintages`를 붙이지 않는다**(`--financial-years=3`이면 RS0 차이 6행이 빠지고, `--insert-earlier-vintages=true`면 이른 판 5개가 들어가며, 11이면 보충 창이 2014로 넓어진다).
       - 실행 행 단언: `select id, triggered_by, status, failure_count, started_at, note from batch_execution where id > $E0 and job_name='financial-refill'` — 정확히 한 행 · MANUAL · 그 `started_at`을 `S`로 적는다(DB 시계 · 되돌리기 기준) · note `연도=2018~2026 대상=109종목` · `이른판=0`.
       - `status`가 SUCCESS가 아니거나 · `failure_count` > 0이거나 · note의 `보충실패`·`회수정지`가 > 0이면 5의 (나)다(보충실패는 수만 싣고 티커를 안 싣는다 — 그래서 (나)는 같은 109로 다시 돈다).
    4. 화해: `cd $R && ./gradlew bootRun -PskipFrontend --args="--spring.profiles.active=bootstrap --job=indicator-reconcile --tickers=$TICKERS" 2>&1 | tee $T/4.log` · 기대 DB `stock_screener`(①은 109 · ②는 창 전체 · 유도 입력 포함).
    5. **사후 대조**: `fr1_tickers.sql`을 운영과 `stock_screener_q4v_fr1`에 돌려 109 종목 서명을 비교한다. 먼저 `select job_name, triggered_by from batch_execution where id > $E0`가 이 절차가 친 잡뿐인지 본다 — 앱이 내려가 있으므로 「덤프 뒤 남의 쓰기」는 원인 후보가 아니어야 한다. 차이는 셋으로 가른다:
       - (가) 2-2 수집 시각 뒤 공시로 설명됨 → 받아들인다.
       - (나) 3의 단언이 (나)였다 → 3·4를 같은 109로 다시 돌고(저장된 보충은 다시 쓰고 실패한 공시만 다시 받는다) 다시 5 · 두 번 다시 돌아도 (나)면 (다)로.
       - (다) 그 밖 → **멈추고 그 종목마다 원장에 물어 판정한다** · 결함으로 판정된 종목만 아래로 되돌린다.
    6. `select count(*) from batch_execution where status='RUNNING'` = 0 → `$R/ops/start.sh --app`.
  - **되돌리는 법(종목 단위 · 원시 SQL이라 따로 사용자 승인 · 앱을 내린 채)** — 표째 `pg_restore -t`는 쓰지 않는다: 표 정의만 다시 만들어 identity · pkey · unique · index를 잃고(Writer INSERT와 보충 저장의 `ON CONFLICT`가 깨진다), 덤프 뒤의 다른 종목·다른 시장 쓰기까지 지운다(반증 실측 · `ops/dump-db.sh`가 찍는 좁힌 복원도 같은 형태). `$T/rollback.tsv` = 되돌릴 종목의 `stock_id<TAB>ticker`(targets.tsv의 부분집합) · `IDS=$(cut -f1 $T/rollback.tsv | paste -sd, -)` · `S` = 3의 `started_at`:
    1. 사본에서 꺼낸다: `psql -X -v ON_ERROR_STOP=1 -d stock_screener_q4v_fr1_base -c "\copy (select * from financial_statement where stock_id in ($IDS)) to '$T/fs.copy'" -c "\copy (select * from financial_column_absence where stock_id in ($IDS)) to '$T/fca.copy'"`.
    2. 운영 한 트랜잭션 — 파일을 지어(`$$`는 셸이 펼치지 않게 `\$\$`) `psql -X -v ON_ERROR_STOP=1 -1 -d stock_screener -f $T/rollback.sql`:
       ```bash
       cat > $T/rollback.sql <<EOF
       create temp table r_fs (like financial_statement);
       create temp table r_fca (like financial_column_absence);
       \copy r_fs from '$T/fs.copy'
       \copy r_fca from '$T/fca.copy'
       delete from financial_statement where stock_id in ($IDS);
       delete from financial_column_absence where stock_id in ($IDS);
       insert into financial_statement overriding system value select * from r_fs;
       insert into financial_column_absence select * from r_fca;
       delete from sec_filing_supplement where fetched_at >= '$S';
       do \$\$ begin
         if (select count(*) from financial_statement where stock_id in ($IDS)) <> (select count(*) from r_fs)
            or (select count(*) from financial_column_absence where stock_id in ($IDS)) <> (select count(*) from r_fca)
         then raise exception '되돌린 행 수가 사본과 다르다'; end if;
       end \$\$;
       EOF
       ```
       `sec_filing_supplement`는 0단계에서 비어 있었고 앱이 내려가 있었으므로 `S` 뒤 행은 전부 이 절차의 것이다 — 되돌리지 않은 종목의 행도 지워지지만 다음 FR이 다시 받는다(못 받으면 실패 행이 그 날짜를 지킨다 · 목록을 못 받으면 회수가 선다).
    3. `share-basis-derive` → `indicator-reconcile --tickers=<되돌린 티커>`(각각 DB 단언 · 지표·거버넌스는 복원하지 않고 다시 계산한다) → `$R/ops/start.sh --app`.
  - **둘째 손 FR**: 10-10 20:00 리밸런싱의 첫 편입 전원 — 목록은 리밸런싱 뒤 FR 술어(`StockRegistrar.IN_RETENTION_WINDOW`)를 그대로 쓴 질의로 내고, note의 `대상=`이 그 수와 같은지 단언한다 · 창 편입이 아닌 A 새 대표(BTSG·NWSA·UHAL.B)는 `--tickers`가 WARN 한 줄로 버리므로 넣지 않는다(「후보 쪽 재무 낡음」 재고로) · 0·3·4·5·6단계(앱을 내린 채 · 단언 포함)는 첫 손 FR과 같다 — 다만 0단계의 「보충 표 0행」은 첫 손 FR에만 선다. 둘째는 첫 손 FR의 행이 있고, 되돌리기는 `S` 뒤에 받은 행만 지운다(같은 CIK를 나눠 쓴 앞 종목의 행이 그 안에 들면 다음 FR이 다시 받는다).
    - **MUST NOT: `--universe`를 붙이지 않는다** — 붙이면 열린 편입만 잡는다(`StockRegistrar.oneUniverseMemberIds`). `--tickers`만 주면 보존 창 편입(닫힌 편입 포함 · 화해와 같은 술어)을 잡는다. 붙였다면 (가)가 이미 쫓아낸 종목을 못 잡는다 — DOX는 FY2025 20-F를 companyfacts가 안 실어 최신 기말이 2024-09-30에 멈췄고, 주식수 나이 거름(2년)에 걸려 10-02에 편출됐다.
    - 첫 손 FR = RS1 Writer 차이 종목(창 편입) · 토 10-10 낮의 두 jar `universe-restore --dates` 재생 **전**(금요일 밤까지)에 끝낸다 — 그 재생이 주식수를 읽어 A의 귀속과 섞인다.
    - 둘째 손 FR = 10-10 20:00 리밸런싱의 첫 편입 전원(창 편입만 — 위) · 리밸런싱 행과 그 꼬리 행이 끝난 것을 기록으로 본 뒤(크론이 밀리면 20:00이 아니다) · 21시 확인 뒤.
- **정기 경로의 이른 판 예외 하나**: 이른 판 넣기는 관문의 한 번에 속한다(ADR-0161). 다만 **보충으로 실은 공시의 판**은 정기 경로에서도 넣는다. 그 판은 원장이 처음부터 준 판이고, 넣지 않으면 순서 의존이 남는다. 크기: RS1(1,207종목)에서 이 예외는 **한 번도 안 터졌다**(이른 판 넣기 0 · 그 갈래는 통합 시험만 묻는다). 보충 몫으로 지표가 무효화되는 종목은 15(현 편입 10: AEP·ALNY·AOS·ED·FTV·GD·JCI·LII·RRC·SPGI)다. 새 라벨을 받은 108종목 중 94는 새 라벨만(무효화 없음)이고 14는 무효화도 있다 · 무효화만 있는 것 1. 무효화된 날짜는 14:20 화해가 채운다.
- **사정거리**:
  - 보존 창 밖 과거 편입(1,209 중 1)만 관문 재적재에서 고쳐진다. 창 안 과거 편입은 위 손 FR이 닿는다.
  - 후보(편입 이력 없음)는 어느 잡도 재무를 갱신하지 않는다 — `docs/reload-gate.md` 「후보 쪽 재무 낡음」.
  - 재무가 축(법인·시나리오)에만 실린 공시(BTU·CEG)는 축 없는 리더가 못 읽는다 — 같은 문서 「축에만 실린 미국 재무」.
  - 관문 재고의 수(S0·[S]·P3a·ADR-0151)는 보충 전 트리에서 잰 것이다. 관문 전에 보충 설정을 준 도구로 다시 잰다.
- **ADR-0135 펜스**: 적용 뒤 「복원 뒤 재무」가 늘어난다 — RS1 사본에서 78 → 231(+153 · 현 편입만이면 124행·90종목 · v3 계획 밖 반증 실측). 그 처방은 관문 10단계의 복원이다(ADR-0135 §규칙).
