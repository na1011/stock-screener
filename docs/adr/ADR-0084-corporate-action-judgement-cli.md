---
id: ADR-0084
status: 채택됨
date: 2026-08-25
applies_to:
  - "src/main/java/**/pipeline/batch/CorporateActionJudgeJob.java"
  - "src/main/java/**/pipeline/batch/BootstrapRunner.java"
  - "src/main/java/**/pipeline/batch/BootstrapDatabaseGuard.java"
---

# ADR-0084. 원장 판정에 두 번째 문을 내되, 판정하는 것은 여전히 사람이다

## 결정

기업 행위 원장을 **셸에서** 보고 판정하는 잡 둘을 만든다 — `corporate-action-review`(읽기)와
`corporate-action-judge`(쓰기). 값은 사람이 argv로 주고, 증거는 계산해 내되 **후보를 고르지 않는다.**

**닫는 것은 인증이지 판정이 아니다.** ADR-0049가 세운 관문은 *"사람이 확인하지 않은 후보는
적용되지 않는다"*이고, 그 관문은 CLI에서도 그대로다. 사실상 막고 있던 것은 브라우저였다 —
`/api/admin/**` 앞의 `ROLE_ADMIN`에 닿는 길이 OIDC 세션 쿠키뿐이라(`formLogin`·`httpBasic`이
없다) 판정 한 건에 앱을 띄우고 로그인하고 개발자 도구 콘솔에서 `fetch`를 쳐야 했다.

**신뢰 경계는 넓어지지 않는다.** 셸이 있으면 이미 `psql -d stock_screener`가 자격증명 없이
붙는다(실측). 오히려 좁아진다 — psql 직접 `UPDATE`는 `judge()` 안의 가드 다섯(근접중복 ·
무상증자 신주상장일 · 부호/양수 · `APPROVED`+`UNKNOWN` · `type`·`ratio` 짝)을 통째로 건너뛰고,
V21의 `CHECK`가 보는 것은 그중 부호 하나뿐이다.

## 규칙

- **MUST: CLI도 `CorporateActionLedger.judge`를 지난다.** 잡은 argv를 `Judgement`로 옮기기만
  한다. **MUST NOT: 러너나 잡이 값들 사이의 관계를 다시 검증하지 않는다** — HTTP 쪽
  `CorporateActionJudgementRequest`가 Jakarta 제약을 하나도 안 쓰는 것과 같은 이유이고,
  사본을 들면 두 문이 서로 다른 것을 통과시킨다.
- **MUST NOT: CLI가 `type`·`ratio`를 유도해 채우지 않는다.** 증거는 전부 찍고 하나를 제안하지
  않는다(ADR-0050의 루닛 2026-05-04 실측).
- **MUST: 판정이 성공한 *뒤에* 실행 행을 연다.** 반대로 두면 사람의 argv 오타가
  `failUnexpectedly` → `alertPort.send(ERROR, …)`를 타 **운영자 알림 채널로 나간다.** 입력이
  배치가 아니라 사람이 친 줄이므로 오타가 흔하고, 그 노이즈가 진짜 장애를 가린다. 그리고
  `judge()`가 `@Transactional`이라 실패는 아무것도 안 쓴 실패다 — 남길 실행 행 자체가 없다.
- **MUST: `finish(note)`로 무엇을 판정했는지 남긴다.** 인자 없는 `finish()`는 "판정 잡이 한 번
  돌았다"까지만 말하고, `plan`·`countExternally`가 없으면 행이 **「대상 1 / 성공 0」**으로 남는다
  (`ck_batch_counts`는 음수만 막고 `BatchExecutionRecorder.finish`는 `failureCount == 0`이면
  `SUCCESS`를 내므로 **제약도 상태도 초록이다**). 이것이 CLI 판정의 유일한 질의 가능한 흔적이다.
- **MUST NOT: `process(...)`로 돌리지 않는다.** 처리 단위가 종목이 아니라 원장 행이고
  `batch_execution_failure.stock_id`가 NOT NULL + FK다. 그 자리는 `countExternally`다
  (`CollectionPhase.MARKET_INDEX`의 javadoc이 같은 판단을 이미 했다).
- **MUST: 두 잡은 `sweepBootstrapOrphans()`를 건너뛴다.** 스윕은 기동 시각 이전의
  `MANUAL` + `RUNNING`을 **전부** 닫으므로 **다른 JVM에서 지금 돌고 있는 행까지** 마감한다.
  적재끼리는 "둘을 동시에 띄우는 드문 실수"였지만, 원장 조회·판정은 몇 초짜리라 **적재가 도는
  중에 아무렇지 않게 치게 된다** — 그러면 몇 시간짜리 백필의 실행 행이 `ABORTED`가 되고 백필
  자신은 계속 돌다가 마감에 실패한다.
- **MUST: `--ca-*`가 원장 잡 밖에서 오면 거절한다.** `KNOWN_OPTIONS`가 전역이고
  `corporate-action`이 두 이름의 **접두어**라, 뒤의 `-judge`를 빠뜨리면 판정은 한 건도 안 들어간
  채 **수집 잡**이 돌아 350종목 700콜을 태우고 성공으로 끝난다.
- **MUST: `--ca-note`가 쪼개졌거나 다른 플래그를 품었으면 거절한다.** 실측으로 형태가 셋이다 —
  ① **따옴표를 안 닫으면** gradle이 `unbalanced quotes in …`으로 빌드를 세운다(조용하지 않다).
  ② **공백을 안 감싸면** 첫 낱말만 남고 나머지가 non-option 인자가 되는데, `getOptionNames()`만
  보는 검사는 그것을 **못 본다.** ③ **닫혀 있는데 그 안에 뒤 플래그가 들어가면** 그 플래그가
  적용되지 않고, `UPDATE`가 `status = coalesce(?, status)`라 상태를 안 바꾼 채 note만 덮는다.
  **MUST NOT: 이것을 "non-option 인자 전면 거절"로 바꾸지 않는다** — 이 저장소가 문서로 싣는
  `--args='… \`+개행 형태가 **리터럴 백슬래시**를 non-option 인자로 넣어(`od -c` 실측) 정상
  명령이 통째로 막힌다. 그래서 백슬래시만 예외로 둔다.
- **MUST NOT: 이 잡들을 `AdminJobLauncher` 카탈로그·스케줄러에 올리지 않는다.** `JobRequest`가
  날짜 셋뿐이라 판정값을 실을 수 없다. 스케줄러 등재는 ADR-0050이 이미 기각했다.
- **MUST: bootstrap 기동이 **연결된** 데이터베이스를 기동 시점에 단언한다**(`BootstrapDatabaseGuard`).
  기대값은 `${BOOTSTRAP_DB:stock_screener}`이고 **언제나** 대조한다 — 「선언했을 때만 대조」로
  두면 잊고 안 선언한 실행이 지금과 똑같이 운영 DB에 붙는데 그것이 정확히 막으려던 사고다.
  **MUST: `InitializingBean`으로 둔다** — 시더는 `ApplicationRunner`라 refresh가 전부 끝난 뒤에
  돌므로, refresh 중에 도는 이 형태라야 앞선다. `@Order`로는 이 순서를 못 만든다.
  **MUST NOT: 설정(`spring.datasource.url`)을 읽어 대조하지 않는다** — 그 문자열이 실제로 어디에
  붙었는지는 아무것도 확인되지 않는다(`ReadOnlyDatabaseGuard`의 javadoc이 같은 함정을 적었다).
- **MUST: 판정으로 `status IN ('OBSERVED','UNSUPPORTED')`인 종목 수가 바뀌면 ADR-0071의
  premise를 함께 고친다.** 그 전제가 그 수를 **7로 못박아** 두었고, 안 고치면 매 세션 거짓
  발견이 난다. **이 CLI의 존재 이유가 그 7건을 닫는 것이므로 첫 실사용에 걸린다.**

## 검증

```bash
./gradlew test --tests "*CorporateActionJudgeJobTest*" --tests "*BootstrapArgumentGuardTest*" \
               --tests "*CorporateActionLedgerTest*" --tests "*AdminCorporateAction*"
```

**문이 둘이어도 원장을 쓰는 곳은 하나다** — 아래가 `CorporateActionLedger.java` 밖의 자리를
하나라도 내면 두 문이 서로 다른 것을 통과시키기 시작한 것이다.

```bash
rg -n "UPDATE corporate_action" src/main/java
```

**CLI가 아직 필요한가** — 아래 둘이 무언가를 내면 브라우저 없이 `ROLE_ADMIN`에 닿는 길이 생긴
것이므로, 이 결정의 전제를 다시 잰다.

```bash
rg -n "formLogin|httpBasic" src/main/java
```

## 배경

원장 실측(2026-08-25): APPLIED 88 · REJECTED 3 · UNSUPPORTED 7 · **OBSERVED 0**. `note`가 붙은
행의 `updated_at`을 보면 2026-08-13에 묶음으로, **2026-08-24에 4건**이 판정됐다 — 후자는
리밸런싱으로 오래 상장된 종목 9개가 들어오며 난 것이고, 전부 브라우저 콘솔에서 처리됐다.
리밸런싱마다 되풀이되는 모양이다.

**조회를 함께 여는 이유는 쓰기만으로는 문이 반쪽이기 때문이다.** 사람이 `id`와 증거를 먼저 봐야
판정할 수 있는데 그 조회도 같은 `ROLE_ADMIN` 뒤에 있다. 증거를 psql로 대신하면
`explainsPriceGap`(1.35/0.65) 판정의 사본이 하나 더 생긴다 — ADR-0050 §검증의 premise가 이미 하나다.

**잡을 둘로 가른 이유는 `--ca-status`가 조회에서는 필터이고 판정에서는 값이기 때문이다.** 한 잡에
합치면 같은 플래그의 뜻이 모드에 따라 뒤집힌다.

## 버린 대안

- **관리자 화면을 만든다** — ADR-0049·0050이 이미 기각했고 그 판단이 유지된다. 판정은 드물게
  일어나고 API와 원장만으로 성립한다.
- **`curl`로 세션 쿠키를 흉내낸다** — `formLogin`·`httpBasic`이 없어 브라우저 없이 세션을 못 얻는다.
- **psql로 원장을 직접 `UPDATE`한다** — 가드 다섯을 통째로 건너뛴다. 지금도 아무도 그것을 막지
  않으므로(`guard-destructive.sh`는 `UPDATE`를 안 본다) **이 CLI가 그 자리를 대신할 더 안전한 문**이다.
- **`AdminJobLauncher`에 올린다** — `JobRequest`가 `targetDate`·`from`·`to` 셋뿐이라 `id`도
  `ratio`도 실을 수 없다.
- **증거가 하나로 좁혀지면 자동 승인한다** — ADR-0050이 루닛 실측으로 기각했다. 창 안에 점프가
  셋이고, 고르는 규칙이 틀린 날 사람은 그것을 알 방법이 없다.
- **잡 이름을 접두어가 아닌 것으로 바꾼다**(`ca-judge` 등) — `--ca-*` 오용을 이름으로 피할 수
  있지만 발견성이 떨어진다. 가드로 막는 편이 낫고, 그 가드는 이름과 무관하게 필요하다.
- **non-option 인자를 전면 거절한다** — 위 §규칙의 백슬래시 실측이 근거다.
- **`--ca-*` 밖의 값에도 쪼개짐 검사를 건다**(`--tickers` 등) — 같은 사고가 거기서도 가능하지만
  기존 잡의 동작을 바꾸는 일이라 이 결정에 묶지 않았다. 넓히려면 별도로 잰다.

## 결과

- **쉬워지는 것**
  - 리밸런싱 뒤의 판정 묶음이 셸 명령 몇 줄이 된다. 앱을 띄우고 로그인할 필요가 없다.
  - 증거 계산이 코드 한 곳에 남는다 — psql로 재현하려던 네 번째 사본이 생기지 않는다.
  - 판정이 `batch_execution`에 남아 **"마지막으로 언제 무엇을 판정했는가"**를 질의할 수 있다.
- **어려워지는 것**
  - **관리자 인터랙션 조회가 CLI 판정을 못 본다.** `interaction_log`의 축이 HTTP
    (`http_method`·`path`·`status_code`·`user_id`)라 CLI 행이 들어가면 축이 깨진다. spec의
    인터랙션 요구는 **웹 앱의 화면 행위**를 가리키므로(*"모든 화면 조작을 다 남길 필요는 없으며"*)
    정본을 어기는 것은 아니지만, `ADMIN_CORPORATE_ACTION_JUDGE`로 세던 수가 이제 브라우저
    경로만 센다. 대체 흔적은 `finish(note)`다.
  - **잡 이름이 둘 늘어 거울 넷을 함께 고쳐야 한다** — `check-doc-staleness.sh` §5c가
    `BootstrapRunner`의 `switch`(정본) · `load-process.sh` · `batch-load` skill의 frontmatter와
    본문 코드펜스를 집합으로 대조한다.
  - **판정 하나에 JVM이 한 번 뜬다.** 읽기 실행 실측 약 3초. 브라우저를 띄우는 것보다는 싸다.
  - **포트는 이제 프로파일이 비운다**(`application.yaml`의 `on-profile: bootstrap`에
    `server.port: 0`). 판정은 운영 앱이 8080에 뜬 채로 하는 일이라 손으로 주는 플래그에 맡기면
    빠뜨리는 날이 오고, 그때 컨텍스트가 죽는데 **러너는 아예 시작되지 않아** 실패 처리가 걸리지
    않는다 — gradle은 `BUILD SUCCESSFUL`과 종료코드 0을 낸다(실측). 잡을 고른 **뒤의** 실패는
    종료코드 1이라 이것과 구분된다. **다만 그 exit 경로에는 테스트가 없다**(`System.exit`은
    단위 테스트로 감쌀 수 없다) — 두 줄을 지워도 전부 초록이고 증상은 다시 침묵이다.
  - **그 대신 교환 둘을 받는다.**
    ① **포트 충돌이 사실상의 상호배제였다.** 배치에 DB 잠금이 없어(`pg_advisory`·`LOCK TABLE`·
    `FOR UPDATE` 전부 0건) 지금까지는 적재 JVM 둘을 겹쳐 띄우면 둘째가 8080에서 죽었다. 이제
    둘 다 뜨고, **둘째의 `sweepBootstrapOrphans()`가 몇 시간째 도는 첫째의 `batch_execution`
    행을 `ABORTED`로 마감한다** — 첫째는 계속 돌다가 마감에 실패한다. 남는 층은
    `warn-load-running.sh` 하나이고 그것은 `PostToolUse` 권고라 막지 못한다.
    ② **bootstrap 기동이 이제 항상 컨텍스트를 띄운다.** 그 컨텍스트는 카탈로그 시더 둘
    (`IndicatorCatalogInitializer` `@Order(0)` · `PresetCatalogInitializer` `@Order(10)`, 둘 다
    `@Profile` 없음)을 러너보다 **먼저** 돌려 `indicator`·`screen_condition`에 쓴다 —
    실측으로 행 수는 안 바뀌고 `updated_at`이 갱신된다. **그러므로 「읽기 전용 잡」이라는 말은
    잡에만 참이고 실행 전체에는 거짓이다.**
    **그래서 `BootstrapDatabaseGuard`가 붙었다**(2026-09-14) — 기동이 기대와 다른 DB에 붙으면
    시더가 쓰기 전에 선다. 임시 DB로 관통 검증을 하려면 `DB_URL`과 `BOOTSTRAP_DB`를 **둘 다**
    그 이름으로 맞춘다. **시더를 `@Profile("!bootstrap")`로 끄는 쪽은 안 골랐다** — ADR-0071
    §규칙이 *"이름이 아니라 **타입으로** 뺀다 … 이름으로 지목하면 다음에 더해질 러너가 조용히
    샌다"*로 이미 그 형태를 기각했고, `screen_condition.indicator_code → indicator(code)` FK가
    실재해(`V3__condition.sql:90`) 시더를 끄면 새 지표가 생긴 뒤 카탈로그가 낡은 채로 남는다.
- **되돌리려면**: 두 `case`와 `CorporateActionJudgeJob`을 걷고, `KNOWN_OPTIONS`의 `ca-*` 아홉과
  두 가드를 지우고, 거울 넷에서 이름을 뺀다. **판정 경로가 브라우저 콘솔 하나로 돌아가므로,
  되돌리기 전에 다음 리밸런싱의 판정을 누가 어떻게 할지 먼저 답한다.**
