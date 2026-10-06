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
- MUST: 저장 표의 팩트는 **고르기 전**의 원 팩트다. 저장되는 것(상태 · 원 팩트 · 머리 · 실패 종류)을 정하는 코드를 **한 토큰이라도** 바꾸면 `XbrlInstanceReader.READER_VERSION`을 올린다 — 리팩터여도, 시험 재료만 바꿨어도 올린다. 해석 실패·XBRL 없음 행은 판이 같은 동안 다시 받지 않으므로, 안 올리면 고친 규칙이 그 행에 영영 안 닿는다(SWX의 신원 규칙을 고치고도 판을 안 올렸다면 영구 실패였다). `ReaderVersionPinTest`가 묻는다:
  - **판 범위 = 파일 전체**(받기 · 색인 JSON · 읽기 · 고르기 · 신원 · 분류 · 저장 형태 — 목록은 그 시험의 `SOURCE_SCOPE`) · 정규형은 javac가 파싱한 트리를 다시 찍고 형 종류를 실은 것이다(주석·서식만 버린다). 메서드 목록이던 범위는 종료 상태를 정하는 자리 셋을 빠뜨렸고 손으로 짠 주석 걷기는 리터럴·텍스트 블록에서 샜다(2026-10-06 v7·v8 반증).
  - 범위 파일이 쓰는 프로젝트 형은 범위 안이거나 「저장을 못 바꾼다」는 사유가 적힌 예외다(javac 속성 분석 — static import · 완전한 이름 포함).
  - 고정 재료의 출력(해석 실패 자리마다 하나 · 특성 · 신원 행렬 · 이름 · 폴더)과 읽기 구현 · JDK 판을 판마다 박는다. **출력이 그대로라는 것은 행동이 그대로라는 증거가 아니다** — 반증 변이 20개 중 12개가 출력 해시를 지났다(v6). 그래서 소스 해시가 있다.
  - **박힌 해시는 덧붙이기만 한다** — 지금 판 값을 고치는 갈래가 없다. git 이력으로 묻는다(v8 `066c325a`부터).
  - 보충 대상인 행은 판이 다르면 다시 받는다.
  - 다시 받지 못하면(또는 공시 목록을 못 받으면) 옛 판의 저장분을 쓰고 그 사실을 남긴다(`stale_used_at`). 그 행은 미해결이다.
- MUST: **관문 설정 뒤에 두는 해석 규칙은 판을 그 설정의 함수로 둔다**(설정을 보충까지 잇는다). 상수 하나면 꺼진 정기 FR이 옛 규칙으로 다시 읽고 새 판 도장을 찍어, 켠 관문 실행이 그 행을 다시 안 받는다. 지금 그런 규칙은 없다 — 첫 후보는 「축에만 실린 미국 재무」다.
- MUST: 결정적 실패는 **받은 파일을 해석하지 못한 것**(`UNREADABLE` — 인스턴스 후보 둘 이상 · 스키마는 있는데 인스턴스 없음 · 읽기 · 문서 종류 불일치 · 같은 정밀도 충돌)뿐이다. 해석 규칙의 판이 같은 동안은 다시 받지 않는다. 받지 못한 것(404 · 5xx · 본문 이상 · 한도)은 다음 실행이 다시 받는다 — 일시 404 한 번을 영구로 만들지 않는다. 전송 실패 · 5xx · 덜 온 2xx는 **같은 실행 안에서도** 대기(2초 · 10초)를 두고 두 번 다시 받는다 — 공시 파일 · 색인 · 공시 목록 · companyfacts가 한 자리(`SecFilingClient.fetch`)로(v11 반증: 대기 없는 세 시도는 0.4초 안에 끝나 그보다 긴 끊김을 못 넘었고, 따로 짜인 companyfacts 쪽이 인터럽트 처리에서 갈렸다). 다시 묻기를 다 쓴 콜이 연달아 셋이면 대기를 건너뛴다(소스가 통째로 내려간 날 콜마다 12초를 쌓지 않게 · 응답 하나가 다시 닫는다). 한도 · 리미터 대기의 인터럽트 · 4xx는 다시 묻지 않는다.
  - **「받았다」는 길이가 색인 크기와 같다는 뜻이다.** 지금 전송은 본문 도중 끊겨도 받은 만큼 예외 없이 넘기고, 200 오류 페이지도 본문이다 — 길이를 안 맞추면 둘 다 해석 실패로 접혀 영구가 된다(v8 반증 네 꼴 · 전송 끊김은 시험으로 재현). 원장은 길이를 준다(같은 날 캐시 인스턴스 530/530 일치 · 색인의 빈 크기 1,701항목은 전부 색인 html·묶음 `.txt`).
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
  - **손 FR 절차(글자 그대로 · `N`=1 첫 손 FR · `N`=2 둘째 손 FR · 사용자 승인 · 앱을 내린 채 · 실키는 평일 13:20~15:45 밖)** — 셸 변수 `R`=main 작업 트리 · `T`=작업 폴더(FR마다 새로) · `N` · `A="$HOME/Library/Application Support/stock-screener/app.jar.commit"` · `D`=그 파일의 커밋 · `W=$T/wt`(D에 뜬 worktree) · `TICKERS=$(cut -f2 $T/targets.tsv | paste -sd, -)`. **아래 `./gradlew …`는 전부 `cd $W && caffeinate -is ./gradlew …`로 친다**:
    - **언제**: 첫 손 FR = RS1 Writer 차이 종목(창 편입) · 토 10-10 낮의 두 jar `universe-restore --dates` 재생 **전**(금요일 밤까지) — 그 재생이 주식수를 읽어 A의 귀속과 섞인다. **1부 배포 뒤 첫 리밸런싱(토 20:00) 전에 마친다** — 그 꼬리(`InitialLoadJob`)가 보충을 타 `sec_filing_supplement`에 행을 쓰므로 넘기면 0-2가 멈추고 RS1 사전 기대가 서지 않는다(v11 계획 밖 반증 7) · 넘겼으면 `N`=1도 사전 기대 없이 2의 재생을 비교 상대로 삼는다(`N`=2와 같은 꼴). 둘째 손 FR = 10-10 리밸런싱의 편입 전원 · 그 리밸런싱의 꼬리가 끝난 뒤(0단계 4가 기록으로 가른다 — 크론이 밀리면 20:00이 아니다 · 손으로 돌렸으면 bootRun이다).
    - **앱을 내리는 이유**: 운영 쓰기 잡 사이의 배타는 「평일 13:30~15:40 밖」이라는 시각 창 하나뿐인데, 앱 크론은 기계가 잠든 만큼 밀려 그 창 밖에서도 돈다(2026-10-06: 13:30 크론이 15:24:51에 돌았다 · `docs/reload-gate.md` 「앱 크론이 기계 수면만큼 밀린다」). 겹치면 화해끼리 교착하고(#305), 덤프 뒤 앱의 쓰기가 사후 대조를 오판시키며 되돌리기가 그 쓰기까지 지운다. 관문과 같은 꼴이다.
    - **잠들지 않게 감싼다**: 앱을 내리면 앱의 `caffeinate -s -w <앱 PID>`도 끝나고 이 기계는 `pmset` sleep=1(AC·배터리)이다 — 잠들어 끊긴 전송은 색인 크기 대조가 받기 실패로 돌리지만 실행 자체가 멈춘다.
    - **`bootRun`마다 붙은 DB를 글자로 단언한다** — 출력을 `2>&1 | tee $T/<단계>.log`로 받고 `grep -c "bootstrap이 데이터베이스 <기대 DB>에 붙었다." $T/<단계>.log` = 1 · `grep -c "부트스트랩 러너가 끝났다. job=<잡> status=SUCCESS" $T/<단계>.log` = 1. 기동이 실패해도 gradle은 「BUILD SUCCESSFUL」과 종료코드 0이다(2026-10-06 연습: Flyway 밖에서 V64를 넣은 사본에 bootRun → V64 「이미 있다」로 죽었는데 종료코드 0 · 두 줄 0건이 그것을 잡았다). 같은 셸에서 사본 → 운영을 이어 치므로, 환경이 이월되면 운영 명령이 사본에 붙는다(Gradle 데몬의 환경 이월은 재보지 않았다).
    0. **전제**(하나라도 아니면 멈춘다 · 1~4는 앱과 DB를 안 건드린다 — 1의 worktree·링크는 로컬 파일이다):
       1. **지금 도는 jar의 코드로 돈다**: `grep -qx 'dirty=false' "$A"` · `grep -qx 'captured=build' "$A"`(빌드 전후 상태로 적은 것 — `stage.sh`를 혼자 돌려 적은 `captured=stage`는 빌드한 커밋을 가리킨다는 보장이 없다) · `D=$(sed -n 's/^commit=//p' "$A")`가 `unknown`이 아님 · `cmp -s $R/.env "$HOME/Library/Application Support/stock-screener/.env"`(앱은 스테이징 때 복사한 `.env`를 쓰고 worktree는 main의 것을 링크한다 — 값은 읽지 않는다) · `git -C $R worktree add --detach $W $D` · `ln -s $R/.env $W/.env`(값은 읽지 않는다). main HEAD와 같을 필요는 없다 — main은 배포 뒤에도 움직인다(9-20 뒤 17일 중 11일에 src 커밋 · 1부 v8 계획 밖 반증). 2026-10-06 연습: worktree의 bootRun이 운영 덤프 사본에 V63·V64를 적용하고 잡을 돌렸다(두 단언 줄 각 1).
       2. 운영: `select 1 from flyway_schema_history where version='64' and success` = 1 · **`N`=1이면** `select count(*) from sec_filing_supplement` = 0(아니면 「언제」의 넘긴 갈래).
       3. 서명 질의(미국 전 종목 · 비교는 대상 티커만): `git -C $R show 8e8f8484:explore/financial-sync/p1-fr1/fr1_tickers.sql > $T/signature.sql`(탐색 브랜치는 작업 트리에 없을 수 있다).
       4. **`N`=2이면 그 주 리밸런싱이 끝났다 — 기록으로, 글자 그대로**(v11 두 렌즈: 「그 시점의 마지막 SCHEDULER 행」으로 읽으면 지난 토요일 6번 중 3번 지난 명부로 통과했고 · 트리거를 박으면 손으로 돌린 주(10-03 #703 MANUAL)와 부분 성공(09-05 #278)이 영구히 막힌다):
          ```sql
          select id, triggered_by, status, started_at, finished_at from batch_execution
          where job_name = 'universe-rebalance' and target_date = '2026-10-09' and status in ('SUCCESS', 'PARTIAL_SUCCESS')
          order by id desc limit 1
          ```
          - 0행이면 멈춘다(아직 안 돌았거나 실패). `PARTIAL_SUCCESS`면 멈추고 어느 지수가 실패했는지 그 JVM의 로그(「유니버스 리밸런싱 부분 실패」 · 지수별 알림)로 가른다 — `US_TOP1000`이 실패했으면 둘째 FR은 그 지수가 성공한 다시 돌기 뒤다.
          - **꼬리가 끝났다**: 그 행을 돌린 JVM의 로그에 그 행의 `finished_at` 뒤로 「편입 결손을 다시 쟀다」 줄이 있다 — SCHEDULER·ADMIN은 앱 `stdout.log`(회전된 `.gz` 포함 · 그 JVM의 PID), MANUAL은 그 bootRun의 출력(이 절차의 `tee` 로그)과 「부트스트랩 러너가 끝났다. job=universe-rebalance」 줄. 「편입 결손 보충이 종료로 중단됐다」·「편입 결손 보충·통지가 실패했다」면 멈춘다(꼬리가 덜 돌아 편입 원자료가 빈다). 그리고 `select count(*) from batch_execution where id > <그 행 id> and status = 'RUNNING'` = 0.
          - **미국 명부가 그 날짜로 움직였다**: `select max(effective_from) from universe_membership where universe_code = 'US_TOP1000'` = `2026-10-09`. **이르면 그 주 미국 편입이 0이다 — 둘째 FR은 필요 없다**(1단계 질의가 지난 주의 편입을 고르므로 돌면 안 된다 · 그 사실을 적고 끝낸다).
          - `ops/crons-done.sh`의 0은 「지금 앱을 내려도 빠질 발화가 없다」일 뿐이라 리밸런싱 전 토요일 낮에도 0이다 — 이 관문 없이 돌면 1단계 질의가 지난 명부를 조용히 고른다(v10 반증).
       5. **위가 다 서면 앱을 내린다**: `$R/ops/stop.sh --app`(안에서 `ops/crons-done.sh`를 묻고 앱 크론이 남았으면 멈춘다 — 그때는 기다렸다 다시 친다 · 그 출력의 「다음 명목 발화」 전에 6을 끝낸다 · 그 출력의 bootstrap 프로세스가 없어야 한다) → **내린 뒤 1의 세 단언(`dirty=false` · `captured=build` · `.env` 같음)과 `D`가 그대로인지, 2를 한 번 더 묻는다** — 기다리는 사이 배포·리밸런싱 꼬리가 그 값을 바꿀 수 있고(v11 계획 안 반증 6), 앱이 내려간 뒤라야 그 값이 고정된다 → `select max(id) from batch_execution` → `E0`. **앞 넷을 먼저 묻는 것은, 앱을 내린 뒤 멈추면 사이트가 내려간 채로 남기 때문이다** — 이 뒤 어느 단계에서 멈추든 `$R/ops/start.sh --app`으로 앱을 다시 올리고 멈춘다.
    1. **대상** `$T/targets.tsv`(`stock_id<TAB>ticker`):
       - `N`=1: `git -C $R show 8e8f8484:explore/financial-sync/p1-fr1/fr1_tickers.txt > $T/tickers.txt` → `psql -d stock_screener -AtF $'\t' -c "select id, ticker from stock where market_code='US' and ticker = any(string_to_array('$(paste -sd, $T/tickers.txt)', ','))" > $T/targets.tsv` — 109행이 아니면 멈춘다.
       - `N`=2: 그 리밸런싱의 편입 전원 — 재편입도 넣는다(보존 창 밖에서 돌아온 종목은 첫 손 FR 범위가 아니라 아무도 1부 코드로 갱신하지 않았고, 이미 갱신된 종목은 같은 원장이면 쓰기 0이다). `--tickers`의 술어(`StockRegistrar.activeUniverseMemberIdsByTicker`)와 같은 조건이라 note의 `대상=`이 행 수와 같아야 한다 · 창 편입이 아닌 A 새 대표(BTSG·NWSA·UHAL.B)는 여기서 빠진다(「후보 쪽 재무 낡음」 재고로). 10-02 리밸런싱에 대어 13 = 그날 편입 13(2026-10-06):
         ```sql
         with r as (select max(effective_from) d from universe_membership where universe_code = 'US_TOP1000')
         select distinct s.id, s.ticker from universe_membership m
         join stock s on s.id = m.stock_id
         join universe u on u.code = m.universe_code and u.is_active
         cross join r
         where m.effective_from = r.d and s.is_active and s.market_code = 'US'
           and (m.effective_to is null
                or (m.effective_from <= (select max(d.trade_date) from indicator_trade_date d where d.market_code = s.market_code)
                    and m.effective_to > (select min(d.trade_date) from indicator_trade_date d where d.market_code = s.market_code)))
         order by s.ticker
         ```
         → `> $T/targets.tsv` · `cut -f2 $T/targets.tsv > $T/tickers.txt`.
    2. **사본 재생(사전 기대 · 중단 조건)** — 이름은 `N`을 단다:
       1. `$R/ops/dump-db.sh` → 그 덤프를 새 DB 둘에 복원한다: `createdb stock_screener_q4v_fr${N}_base && pg_restore -d stock_screener_q4v_fr${N}_base <덤프>` · 같은 것을 `stock_screener_q4v_fr${N}`로(앞의 것은 되돌리기용 · 손대지 않는다).
       2. 같은 날 보충 원자료(운영 FR 직전에): `SUPP_CACHE_DIR=$T/cache SUPP_TICKERS=$T/tickers.txt SUPP_FROM=2018 ./gradlew liveTest -PskipFrontend --tests "*SecFilingSupplementCacheLiveTest*"` — 수집 시각을 적는다.
       3. 재생: `Q4V_REPLAY_DB=stock_screener_q4v_fr${N} Q4V_REPLAY_CACHE=$T/cache/companyfacts Q4V_REPLAY_TARGETS=$T/targets.tsv Q4V_REPLAY_OUT=$T/replay.tsv Q4V_REPLAY_FROM=2018 Q4V_REPLAY_INSERT_EARLIER=false Q4V_REPLAY_SUPPLEMENT_DIR=$T/cache Q4V_REPLAY_SUPPLEMENT_STORE=db TEST_DB_URL=jdbc:postgresql://localhost:5432/stock_screener_test ./gradlew test -PskipFrontend --tests "*EarlierVintageReplayHarnessTest*" --rerun` — 출력 줄 `Q4V_REPLAY …`.
       4. 사본 주식수 기저(운영 FR 뒤에 도는 것과 같게 · `ops/apply-window/env.sh` `boot()`의 사본 갈래와 같은 꼴 — 알림 변수를 비워야 사본의 알림이 운영 채널로 안 간다): `DB_URL=jdbc:postgresql://localhost:5432/stock_screener_q4v_fr${N} BOOTSTRAP_DB=stock_screener_q4v_fr${N} ALERT_WEBHOOK_URL= MAIL_USERNAME= SENTRY_DSN= ./gradlew bootRun -PskipFrontend --args='--spring.profiles.active=bootstrap --job=share-basis-derive' 2>&1 | tee $T/2-4.log` · 기대 DB `stock_screener_q4v_fr${N}`.
       5. **중단 조건**: 2-3의 종료코드가 0이 아니거나 출력의 `실패`가 0이 아니거나(캐시가 빈 종목 — 되돌리기의 CIK 매핑도 같은 캐시에 기댄다) · 이른판 · 보충실패 · 목록실패종목 · 회수정지 중 하나라도 > 0이면 운영 FR을 안 한다. **`N`=1의 사전 기대**(RS1의 109 몫): 대상 109 · 신규 128 · 정정 28 · 교정 3 · 이른판 0 · 회수 3 · 보충실패 0 · 목록실패종목 0 · 회수정지 0종목 · 실패 0 — 신규·정정·교정·회수의 차이는 10-06 뒤 공시(`published_at` > 2026-10-06)로 구성원마다 설명돼야 하고, **설명이 안 되는 차이가 하나라도 있으면 운영 FR을 안 하고 그 구성원마다 원장에 물어 판정한다.** 이 수는 1부 코드로 다시 잰 것이다(2026-10-06 · `sc_a` 사본 · v7 · v8 · v9 — 종목 표가 처음 잰 RS1 b1과 같다). `N`=2는 사전 기대가 없다 — 이 재생이 5의 비교 상대다.
    3. 운영 FR: `./gradlew bootRun -PskipFrontend --args="--spring.profiles.active=bootstrap --job=financial-refill --tickers=$TICKERS" 2>&1 | tee $T/3.log` · 기대 DB `stock_screener` — **`--universe`·`--financial-years`·`--insert-earlier-vintages`를 붙이지 않는다**(`--financial-years=3`이면 RS0 차이 6행이 빠지고, `--insert-earlier-vintages=true`면 이른 판 5개가 들어가며, 11이면 보충 창이 2014로 넓어진다).
       - 실행 행 단언: `select id, triggered_by, status, failure_count, started_at, note from batch_execution where id > $E0 and job_name='financial-refill' order by id` — 행 수 = 3을 친 횟수 · 전부 MANUAL · 마지막 행의 note `연도=2018~2026 대상=<targets.tsv 행 수>종목` · `이른판=0`.
       - 마지막 행의 `status`가 SUCCESS가 아니거나 · `failure_count` > 0이거나 · note의 `보충실패`·`미해결보충`·`회수정지`가 > 0이면 5의 (나)다(보충실패는 수만 싣고 티커를 안 싣는다 — 그래서 (나)는 같은 대상으로 다시 돈다). 다만 `select count(*) from sec_filing_supplement where status='FAILED' and failure_kind='UNREADABLE'` > 0이면 (다)다 — 해석 실패는 판이 같은 동안 다시 받지 않아 다시 돌아도 안 닫힌다.
    4. 화해: `./gradlew bootRun -PskipFrontend --args="--spring.profiles.active=bootstrap --job=indicator-reconcile --tickers=$TICKERS" 2>&1 | tee $T/4.log` · 기대 DB `stock_screener`(①은 대상 · ②는 창 전체 · 유도 입력 포함).
    5. **사후 대조**: 서명 v2(`git -C $R show f02d2738:explore/financial-sync/p1-fr1/post_signature.sql > $T/post_signature.sql` — FR이 쓰는 표 셋: 재무 행 · 결손 표(`observed_at` 제외) · 보충 원장(시각 칸 제외))를 운영과 `stock_screener_q4v_fr${N}`에 `-v ids="$(cut -f1 $T/targets.tsv | paste -sd, -)" -v ciks="<대상 CIK — 2-2 캐시의 companyfacts에서 0콜로>"`로 돌려 diff한다(v11 계획 안 반증 7 — 재무 행만 보면 나머지 둘의 어긋남을 못 본다). 먼저 `select job_name, triggered_by from batch_execution where id > $E0`가 이 절차가 친 잡뿐인지 본다 — 앱이 내려가 있으므로 「덤프 뒤 남의 쓰기」는 원인 후보가 아니어야 한다. 차이는 셋으로 가른다:
       - (가) 2-2 수집 시각 뒤 공시로 설명됨 → 받아들인다.
       - (나) 3의 단언이 (나)였다 → 3·4를 같은 대상으로 다시 돌고(저장된 보충은 다시 쓰고 받기 실패한 공시만 다시 받는다 · Writer는 같은 원장이면 쓰기 0 — 2026-10-06 사본 재실행) 다시 5 · 두 번 다시 돌아도 (나)면 (다)로.
       - (다) 그 밖 → **멈추고 그 종목마다 원장에 물어 판정한다** · 결함으로 판정된 종목만 아래로 되돌린다.
    6. `select count(*) from batch_execution where status='RUNNING' and id > $E0` = 0(그 앞 유령은 이 절차의 것이 아니다) → `$R/ops/start.sh --app` → `git -C $R worktree remove $W`(사용자 확인 뒤 · 지우는 일이다).
  - **되돌리는 법(종목 단위 · 원시 SQL이라 따로 사용자 승인 · 앱을 내린 채)** — 표째 `pg_restore -t`는 쓰지 않는다: 표 정의만 다시 만들어 identity · pkey · unique · index를 잃고(Writer INSERT와 보충 저장의 `ON CONFLICT`가 깨진다), 덤프 뒤의 다른 종목·다른 시장 쓰기까지 지운다(반증 실측 · `ops/dump-db.sh`가 찍던 좁힌 복원도 같은 형태였고 main `09120ba3`이 데이터만 되돌리는 꼴로 고쳤다 — 그 꼴도 표 단위라 종목 단위 되돌리기에는 못 쓴다). `$T/rollback.tsv` = 되돌릴 종목의 `stock_id<TAB>ticker`(targets.tsv의 부분집합) · `IDS=$(cut -f1 $T/rollback.tsv | paste -sd, -)` · `BASE=stock_screener_q4v_fr${N}_base`:
    1. **보충 행은 되돌린 종목의 것만, 사본의 상태로 되돌린다**(원장 행을 지우지 않는다는 MUST NOT — 남의 행을 지우면 그 사이 companyfacts가 그 공시를 실은 갈래에서 COVERED도 열림도 없다 · 「`S` 뒤에 받은 행 지우기」는 둘째 FR이 upsert로 덮은 앞 행(FAILED · 옛 판)을 앞 상태로 못 돌린다 — v9 반증): CIK는 같은 날 캐시에서 0콜로 — `CIKS=$(for t in $(cut -f2 $T/rollback.tsv); do jq -r .cik $T/cache/companyfacts/$t.json; done | sort -u | paste -sd, -)` — **CIK가 빠짐없이 수인지 단언한다**: `for t in $(cut -f2 $T/rollback.tsv); do jq -er '.cik | numbers' $T/cache/companyfacts/$t.json >/dev/null || echo "CIK 없음: $t"; done`가 아무것도 안 찍어야 한다(파일이 없거나 `cik`가 없으면 그 종목의 보충 행이 조용히 안 돌아온다 — v10 반증). **CIK를 나눠 쓰는 대상(PPL·PPLC · TAP·TAP.A 꼴)은 함께 되돌린다** — `for t in $(cut -f2 $T/targets.tsv); do echo "$t $(jq -r .cik $T/cache/companyfacts/$t.json)"; done`에서 CIK가 `CIKS`에 드는데 rollback.tsv에 없는 티커가 있으면 더하고 다시 시작한다.
    2. 사본에서 꺼내고 행 수를 잰다: `psql -X -v ON_ERROR_STOP=1 -d $BASE -c "\copy (select * from financial_statement where stock_id in ($IDS)) to '$T/fs.copy'" -c "\copy (select * from financial_column_absence where stock_id in ($IDS)) to '$T/fca.copy'" -c "\copy (select * from sec_filing_supplement where cik in ($CIKS)) to '$T/sup.copy'"` · `N_FS=$(psql -X -At -d $BASE -c "select count(*) from financial_statement where stock_id in ($IDS)")` · `N_FCA`(`financial_column_absence` · 같은 `$IDS`) · `N_SUP`(`sec_filing_supplement` · `cik in ($CIKS)`)도 같은 꼴.
    3. 운영 한 트랜잭션 — 파일을 지어(`$$`는 셸이 펼치지 않게 `\$\$` · heredoc은 들여쓰기를 걷고 친다 — 들여쓴 채면 종결자 `EOF`가 안 서서 psql이 실패하고 트랜잭션째 되돌린다) `psql -X -v ON_ERROR_STOP=1 -1 -d stock_screener -f $T/rollback.sql`:
       ```bash
       cat > $T/rollback.sql <<EOF
       create temp table r_fs (like financial_statement);
       create temp table r_fca (like financial_column_absence);
       create temp table r_sup (like sec_filing_supplement);
       \copy r_fs from '$T/fs.copy'
       \copy r_fca from '$T/fca.copy'
       \copy r_sup from '$T/sup.copy'
       delete from financial_statement where stock_id in ($IDS);
       delete from financial_column_absence where stock_id in ($IDS);
       delete from sec_filing_supplement where cik in ($CIKS);
       insert into financial_statement overriding system value select * from r_fs;
       insert into financial_column_absence select * from r_fca;
       insert into sec_filing_supplement select * from r_sup;
       do \$\$ begin
         if (select count(*) from r_fs) <> $N_FS or (select count(*) from r_fca) <> $N_FCA or (select count(*) from r_sup) <> $N_SUP
            or (select count(*) from financial_statement where stock_id in ($IDS)) <> $N_FS
            or (select count(*) from financial_column_absence where stock_id in ($IDS)) <> $N_FCA
            or (select count(*) from sec_filing_supplement where cik in ($CIKS)) <> $N_SUP
         then raise exception '되돌린 행 수가 사본(%, %, %)과 다르다', $N_FS, $N_FCA, $N_SUP; end if;
       end \$\$;
       EOF
       ```
       행 수는 **사본에서 잰 수와** 비교한다 — 「넣은 수 = 임시 표에 읽은 수」는 늘 참이라 짧게 끊긴 복사 파일을 못 잡는다(2026-10-06 연습: 그 꼴은 한 줄 뺀 파일로 8,116행을 커밋했고, 이 꼴은 예외로 트랜잭션째 되돌렸다). 커밋 뒤 두 표의 서명(`select count(*), md5(string_agg(t::text, '|' order by t::text)) from (select * from <표> where stock_id in ($IDS)) t`)이 운영과 `$BASE`에서 같은지 본다. 보충 행을 시각(「첫 FR 시작 뒤에 받은 행」)이 아니라 사본의 상태로 되돌리는 것은, 시각 기준이 (나) 재실행(v8 반증 — 기준을 다시 친 FR로 두면 161행이 남음)과 둘째 FR의 덮어쓰기(v9 반증) 둘 다에서 어긋나기 때문이다.
    4. `share-basis-derive` → `indicator-reconcile --tickers=<되돌린 티커>`(각각 DB 단언 · 지표·거버넌스는 복원하지 않고 다시 계산한다) → `$R/ops/start.sh --app`.
    - **연습(2026-10-06 · 사본 · 글자 그대로)**: 첫 꼴 — `$BASE` = `stock_screener_sc_a` · 운영 자리 = RS1 재생을 마친 그 복제(`stock_screener_q4v_rs1c`) → 109종목 두 표의 서명이 `sc_a`와 md5까지 같아졌다(재무 8,117 · 부재 1,528) · 나머지 종목 그대로. 지금 꼴(보충 행을 사본 상태로) — `$BASE` = V64를 Flyway로 적용한 복제(`stock_screener_q4v_rs1e` · 보충 0행) · 운영 자리 = v9 재생을 마친 `rs1c` → 두 표 서명이 `$BASE`와 같고 107 CIK의 보충 161행이 0으로(사본 상태) · 다른 CIK의 384행은 그대로.
    - **1부를 코드째 되돌리는 배포**: 운영 DB의 V64는 V64 없는 코드에 「앞선(future) 마이그레이션」이다(운영 Flyway가 그것을 무시하고 뜨는지는 재지 않았다 — 되돌리기 전에 사본에서 잰다). **시험 DB는 다르다** — 시험 프로파일의 V900 때문에 V64가 future가 아니라 「적용됐는데 코드에 없음」으로 읽혀 빌드가 선다(v11 계획 밖 반증: V64가 든 시험 DB에서 main + 0부 빌드 758 실패 · V62 사본 대조 1,770/0). 되돌리는 배포의 빌드 전에 `TEST_DB_URL`을 V64 없는 시험 DB로 둔다 · 같은 이유로 **1부가 합쳐지기 전에는 1부 시험을 `stock_screener_test`에 돌리지 않는다**(0부 배포 빌드가 그 DB를 쓴다).
    - **MUST NOT: `--universe`를 붙이지 않는다** — 붙이면 열린 편입만 잡는다(`StockRegistrar.oneUniverseMemberIds`). `--tickers`만 주면 보존 창 편입(닫힌 편입 포함 · 화해와 같은 술어)을 잡는다. 붙였다면 (가)가 이미 쫓아낸 종목을 못 잡는다 — DOX는 FY2025 20-F를 companyfacts가 안 실어 최신 기말이 2024-09-30에 멈췄고, 주식수 나이 거름(2년)에 걸려 10-02에 편출됐다.
- **정기 경로의 이른 판 예외 하나**: 이른 판 넣기는 관문의 한 번에 속한다(ADR-0161). 다만 **보충으로 실은 공시의 판**은 정기 경로에서도 넣는다. 그 판은 원장이 처음부터 준 판이고, 넣지 않으면 순서 의존이 남는다. 크기: RS1(1,207종목)에서 이 예외는 **한 번도 안 터졌다**(이른 판 넣기 0 · 그 갈래는 통합 시험만 묻는다). 보충 몫으로 지표가 무효화되는 종목은 15(현 편입 10: AEP·ALNY·AOS·ED·FTV·GD·JCI·LII·RRC·SPGI)다. 새 라벨을 받은 108종목 중 94는 새 라벨만(무효화 없음)이고 14는 무효화도 있다 · 무효화만 있는 것 1. 무효화된 날짜는 14:20 화해가 채운다.
- **사정거리**:
  - 보존 창 밖 과거 편입(1,209 중 1)만 관문 재적재에서 고쳐진다. 창 안 과거 편입은 위 손 FR이 닿는다.
  - 후보(편입 이력 없음)는 어느 잡도 재무를 갱신하지 않는다 — `docs/reload-gate.md` 「후보 쪽 재무 낡음」.
  - 재무가 축(법인·시나리오)에만 실린 공시(BTU·CEG)는 축 없는 리더가 못 읽는다 — 같은 문서 「축에만 실린 미국 재무」.
  - 관문 재고의 수(S0·[S]·P3a·ADR-0151)는 보충 전 트리에서 잰 것이다. 관문 전에 보충 설정을 준 도구로 다시 잰다.
- **ADR-0135 펜스**: 적용 뒤 「복원 뒤 재무」가 늘어난다 — RS1 사본에서 78 → 231(+153 · 현 편입만이면 124행·90종목 · v3 계획 밖 반증 실측). 그 처방은 관문 10단계의 복원이다(ADR-0135 §규칙).
