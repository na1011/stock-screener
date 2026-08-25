---
id: ADR-0078
status: 채택됨
date: 2026-08-21
applies_to:
  - "src/main/java/**/pipeline/batch/BatchExecutions.java"
  - "src/main/java/**/pipeline/batch/BatchExecutionContext.java"
  - "src/main/java/**/pipeline/batch/BatchExecutionSweeper.java"
  - "src/main/java/**/pipeline/batch/BatchShutdownException.java"
  - "src/main/java/**/pipeline/batch/StockTaskExecutor.java"
  - "src/main/java/**/pipeline/batch/AdminJobLauncher.java"
  - "src/main/java/**/pipeline/scheduler/PipelineScheduler.java"
  - "src/main/java/**/observability/SentryConfig.java"
  - "ops/*.sh"
---

# ADR-0078. 프로세스가 내려갈 때 배치 실행을 어떻게 마감하고 멈추는가

## 결정

마감을 **`ContextClosedEvent`에서** 하고, **종단 판정을 알림보다 앞에** 두며, **종료 유예에
상한을 명시해** `ops/stop.sh`의 대기보다 짧게 유지한다. 종료 마감은 **알리지 않되**, 끄기
**전에** `ops`가 도는 배치를 사람에게 알린다.

마감한 실행의 워커는 **협조적으로 멈춘다** — 잡이 새 작업 단위를 시작하려는 자리에서
`BatchShutdownException`을 던져 루프를 끊는다. 그때 나는 예외·로그·알림은 **버그가 아니라
신호로** 다룬다.

기제가 분담한다. 어디에도 적혀 있지 않아 이 문서를 쓴다.

| 기제 | 담당 | 알림 |
|---|---|---|
| `BatchExecutions`의 종료 리스너 | **이 JVM이** 열어 둔 실행 | 없음 |
| `BatchExecutionContext`의 종단 플래그 | 한 실행을 **두 번** 마감하려는 시도 | 없음(건너뛴 것을 `warn`) |
| `BatchExecutionContext`의 **버림 플래그** | 마감된 실행의 워커를 **멈추는 것** | 없음 |
| `BatchExecutionRecorder`의 DB 가드 | **다른 JVM**이 남긴 행과의 경합 | 해당 없음 |
| `BatchExecutionSweeper` | 훅조차 못 돈 경우(SIGKILL·전원 차단) | 없음 |

## 규칙

- MUST: 종료 마감은 `@EventListener(ContextClosedEvent)`에서 한다. `@PreDestroy`로 되돌리지
  않는다 — 그쪽의 순서 보장은 **빈 생성 순서**에서 나오고, 그것은 컴포넌트 스캔이 클래스를
  만나는 차례에 달려 있어 어긋나도 드러나지 않는다.
- MUST: 그 리스너는 예외를 밖으로 내지 않는다. 이벤트 리스너에서 새면 **같은 이벤트의 나머지
  리스너가 통째로 건너뛰어진다**(executor의 조기 정지가 그중 하나다).
- MUST: 종단 판정(`claimTerminal`)이 **DB 쓰기와 알림 둘 다보다 앞**에 있다. DB 가드는 쓰기만
  막고, 알림은 그 결과를 보지 않는다.
- MUST NOT: 종단 플래그로 DB 가드를 대신하지 않는다. 플래그는 한 JVM 안에서만 유효하다.
- MUST: `spring.lifecycle.timeout-per-shutdown-phase`(15s)와 `ops/stop.sh`의 앱 대기(45s)를
  **짝으로 다룬다.** 한쪽만 고치면 배포가 조용히 깨진다.
- MUST NOT: 종료 경로에서 알림 포트를 부르지 않는다. `CompositeAlertPort.send`가 동기이고
  웹훅이 connect 5초 + read 10초라 **종료 예산을 먹어 아래 「배경」의 결함을 되살린다.**
- MUST: `ops`의 사전 경고는 **막지 않고 알리기만 한다**(ADR-0043). 조회에 실패하면 **「확인하지
  못했다」를 찍는다** — 「없음」과 구분되지 않으면 그 경고는 있으나 마나다.

### 협조적 중단

- **MUST: 버림 플래그를 `claimTerminal` 성공 **뒤**, `recorder.abort` **앞**에서 켠다.**
  그래야 「플래그가 참」 = 「이미 마감됐다」가 되고, **잡 열한 개가 자기
  `catch (RuntimeException)`을 안 고쳐도 되는 근거가 그 불변식 하나다.** 순서를 바꾸면
  열한 곳을 전부 고쳐야 한다.
- **MUST: 게이트를 `process()`만이 아니라 `stage()`·`plan()`에도 둔다.** 종목 루프만 막으면
  `DailyUpdateJob`의 백분위·업종 상대값·워치리스트가 그대로 돈다 — 그 셋은 `process()`를
  지나지 않는다. **구간 신고는 곧 「그 구간을 시작한다」는 선언이다.**
- **MUST: 게이트를 울타리가 아니라 경계로 읽는다.** 한 단계 *도중에* 켜지면 그 단계는 끝까지
  간다. 신고를 아예 안 하는 루프(`UniverseRebalanceJob`·`MarketIndexJob`)는 직접 물어야 한다.
- **MUST NOT: 종료 중 알림을 「막는」 것으로 끝내지 않는다 — 로그로 내린다.** 여기 오는 알림
  여섯은 대응하는 개별 로그가 **아예 없어** 막기만 하면 그 사실이 어디에도 안 남는다.
  `UniverseRebalanceJob`은 포트를 직접 부르므로 같은 판단을 그 잡 안에서 다시 한다.
- **MUST: 종료 신호를 실패로 세지 않고, 종료 중에는 진척을 쓰지 않는다.** 전자를 어기면
  「이미 마감된 …」 경고가 **종료마다** 나 진짜 마감 경합을 가리키기를 그만두고, 후자를
  어기면 `flushNow`의 좁은 catch를 통과하는 예외가 난다(근거는 그 메서드 옆에 있다).
- **MUST: 종료 중에는 `ERROR`로 찍지 않는다.** `sentry.logging.minimum-event-level`이 `error`라
  ERROR 로그가 곧 이슈다. `SentryConfig`의 필터는 **백스톱이지 주 기제가 아니다** — 종료 중
  로그 대부분은 원본 예외를 실어 원인 체인에 신호가 없다.

## 검증

정적 검사다. `premise`로 태그하지 않는다 — 자연스러운 전제(종료로 끊긴 실행의 수)는
**정상 운영에서 모집단이 0**이라 매 세션 「잴 것이 없었다」를 발견으로 보고하게 되고,
오탐이 반복되면 검사 자체가 무시된다.

```bash
# ① 종료 마감이 @PreDestroy로 되돌아가지 않았고 리스너가 살아 있는가 (0, 1이어야 한다)
# MUST: 행 머리의 애너테이션만 센다. 'PreDestroy'를 그냥 세면 **이 파일의 javadoc이
# 그 단어를 설명하고 있어** 3이 나온다 — 산문에 걸리는 검사는 결정이 지켜져도 운다.
rg -c '^\s*@PreDestroy' src/main/java/com/stockscreener/pipeline/batch/BatchExecutions.java || echo 0
rg -c '^\s*@EventListener' src/main/java/com/stockscreener/pipeline/batch/BatchExecutions.java

# ② 유예 상한이 첫 문서(운영 프로파일)에 살아 있는가 (1이어야 한다)
awk '/^---$/{exit} /timeout-per-shutdown-phase/{n++} END{print n+0}' src/main/resources/application.yaml

# ③ 상한(15)이 stop.sh의 앱 대기(45)보다 작은가 — 두 수를 함께 뽑아 눈으로 본다
rg -o 'timeout-per-shutdown-phase: \d+' src/main/resources/application.yaml
rg -o '"\$APP_PATTERN" \d+' ops/stop.sh

# ④ 셸 경고가 set -e에서 죽지 않는가 — 실패 두 경로를 실제로 태운다
bash -c '. ops/lib.sh; set -euo pipefail; DB_NAME=없는db warn_running_batches "검증"; echo "살아 있다"'
bash -c '. ops/lib.sh; set -euo pipefail; pg_bin() { return 1; }; warn_running_batches "검증"; echo "살아 있다"'

# ⑤ 게이트가 세 자리에 다 있는가 — process·stage·plan (3이어야 한다)
# MUST: 행 머리 + 세미콜론으로 센다. 이름만 세면 javadoc의 {@link}가 함께 잡힌다.
rg -c '^\s*abortIfAbandoned\(\);' src/main/java/com/stockscreener/pipeline/batch/BatchExecutionContext.java

# ⑥ 신고를 안 하는 루프 둘이 명시적으로 묻는가 (3, 1이어야 한다)
# UniverseRebalanceJob은 루프 머리 + 안쪽 catch 둘이라 3이다. 하나라도 줄면 종료 중에
# 가짜 알림이 동기로 나가는 경로가 되살아난다.
rg -c 'execution\.abortIfAbandoned\(\)' src/main/java/com/stockscreener/pipeline/batch/UniverseRebalanceJob.java
rg -c 'execution\.abortIfAbandoned\(\)' src/main/java/com/stockscreener/pipeline/batch/MarketIndexJob.java

# ⑦ 불변식: 플래그가 claimTerminal **뒤**인가 — 두 줄 번호를 뽑아 눈으로 본다
awk '/void abortOnShutdown/,/^    }/' \
  src/main/java/com/stockscreener/pipeline/batch/BatchExecutionContext.java \
  | grep -n 'claimTerminal(\|^        abandonedOnShutdown = true;'
```

동적 검증이 둘이다. `BatchExecutionShutdownRaceTest`가 **종료 예산**을 잰다 — 워커를 돌려
놓고 컨텍스트를 닫아 `close()` 소요·마감 상태·사유·알림 수를 함께 본다. **상한을 지우면
30초가 나와 떨어진다.** `BatchExecutionCooperativeStopTest`가 **루프가 멈추는가**를 잰다 —
컨텍스트를 닫지 않고 `closeOpenExecutions()`만 불러 유예를 안 태운다.

**MUST NOT: 둘을 하나로 합치지 않는다.** 앞의 것은 게이트를 못 잰다 — 가짜 작업이 인터럽트로
끝나고 그 인터럽트가 닫힌 풀을 만나 죽으므로 루프가 **고치기 전에도** 2회차에 못 가, 진입
횟수가 1 → 1이라 갈리지 않는다. 그렇다고 작업 시간을 줄이면 유예 상한이 안 밟혀 14~25초
밴드가 무너지는데, 그 밴드가 상한과 `stop.sh` 대기를 지키는 유일한 펜스다.

### 측정 결과 (2026-08-21)

`BatchExecutionShutdownRaceTest`로 잰 것이다. 워커가 종목 루프 안에서 60초 자는 동안 닫았다.

| | 통제군(고치기 전) | 처리군 |
|---|---|---|
| `close()` | **30,061ms** | **15,032ms** |
| `ops/stop.sh` 앱 대기 | 30초 → **반드시 초과** | 45초 → 여유 |
| 마감 상태 / 사유 | ABORTED / 있음 | ABORTED / 있음 |
| 가짜 실패 행 | 0 | 0 |
| 알림 | 0 | 0 |
| 워커의 최후 | `CannotCreateTransactionException` | 같음 |

통제군에서도 마감 자체는 훅이 이겼다 — **경합이라 회차마다 갈린다.** 「워커가 이겨서
PARTIAL_SUCCESS로 덮는다」는 단정할 수 없고, 이번 회차에는 나타나지 않았다. 종단 플래그를
넣는 근거는 그 확률이지 관측이 아니다.

### 측정 결과 — 협조적 중단 (2026-08-25)

`BatchExecutionCooperativeStopTest`로 잰 것이다. 짧은 작업 5개짜리 루프를 돌려 놓고 첫
작업이 잡혀 있는 동안 `closeOpenExecutions()`를 부른 뒤 놓아줬다.

| | 통제군(고치기 전) | 처리군 |
|---|---|---|
| 종목 작업 진입 | **5회** — 종료 뒤에도 남은 넷을 마저 돌았다 | **1회** |
| 루프를 끊은 것 | 없음(끝까지 돌았다) | `BatchShutdownException` |
| 마감 상태 | ABORTED | 같음 |
| 알림 | 0 | 0 |

**`close()` 소요는 이 표에 없다** — 이 테스트는 컨텍스트를 닫지 않는다. 그 수는
`BatchExecutionShutdownRaceTest`가 답하고 **이 변경 뒤에도 같다: 15,023ms**(2026-08-25 실측,
2026-08-21의 15,032ms 대비 9ms). 가짜 작업이 60초 잠 안에서 플래그를 맞아 게이트에 안 닿기
때문이다. 밴드 14~25초에 대해 **하한 여유 1.0초 · 상한 여유 10.0초** — 하한이 얇은 것은
설계다(상한을 지우면 30초가 나와 떨어져야 한다).

**다만 그 회차의 「워커의 최후」는 바뀌었다: `CannotCreateTransactionException` →
`BatchShutdownException`.** 위 2026-08-21 표의 그 칸은 고치기 **전**의 값이다. 인터럽트된
워커가 기록조차 못 하고 죽던 것이 이제 신호를 달고 나온다 — `StockTaskExecutor`가 종료를
인지해 원인을 실어 보내기 때문이고, 그것이 없으면 Sentry 백스톱이 그 예외를 못 알아본다.

**Sentry 이벤트가 실제로 몇 건 줄었는지는 재지 않았다** — 로컬·테스트는 `sentry.dsn`이 빈
문자열이라 `BeforeSendCallback`이 단위 테스트 밖에서 돌지 않는다. 재려면 운영 DSN이 실린
인스턴스에서 배치를 띄우고 `ops/deploy.sh`를 쳐야 한다.

### 운영 관통 (2026-08-21)

`indicator-backfill`(외부 API 0콜)을 관리자 화면에서 띄우고 **도는 중에** `ops/deploy.sh`를 쳤다.

| 확인한 것 | 결과 |
|---|---|
| `deploy.sh`가 빌드 앞에 경고 | `[deploy] 도는 배치가 있다 … indicator-backfill [ADMIN · 00:00:16 경과]` — **그 스크립트의 해당 줄만 따로 태운 결과다.** 전체 배포를 한 번 더 돌리면 복구 중이던 잡을 또 끊게 되어 그렇게 했다 |
| `stop.sh`가 kill 앞에 경고 | `[stop] 도는 배치가 있다 … 00:01:11 경과` |
| **deploy가 `start.sh`까지 갔는가** | **갔다.** `[stop] 앱 종료` → `[start] 앱 OK` → 외부 `UP` |
| `stop.sh` 소요 | **약 16초** (SIGTERM 02:06:23 → 종료 02:06:39). 대기 45초 대비 여유 29초 |
| 마감 | `ABORTED` + `프로세스가 종료돼 중단됐다. 이 실행은 저절로 이어지지 않는다 …` |
| 종료 훅 로그 | `02:06:23.620 [ionShutdownHook] 내려가면서 열린 배치 실행을 마감했다. id=102` — **운영에서 처음 걸렸다.** SIGTERM과 같은 순간이고, 15초 대기는 그 **뒤에** 온다 |
| 이중 마감 | `이미 마감된 …` 0건 |
| 알림 | 「부분 실패」 0건 |
| 그 실행의 `batch_execution_failure` | **0건** — 결손 1 그대로다. `indicator-backfill`은 어댑터를 안 타 인터럽트가 관측되지 않고, 워커는 JVM과 함께 사라졌다 |
| Sentry 오염 | `기록 바깥에서 터졌다` 0건 — 결손 2는 이 회차에 안 밟혔다 |
| 재기동 후 | `RUNNING` 0건, 스위퍼가 문 행 0건 |

끊긴 실행은 `353/2100`에서 멈췄다 — 6거래일 중 1일을 마치고 다음 날 3종목까지 갔다.
그 다음 날(2026-08-12)이 **부분 상태**로 남았다(지표 31,580행, 완료된 날은 31,777행).
같은 범위를 다시 돌려 복구했다(`2100/2100` SUCCESS, 4분 50초). 여섯 날짜의 백분위가
3,569~3,596으로, 업종 상대값이 1,783~1,804로 고르게 돌아왔고 미해결 실패는 0건 그대로다.
백필이 저장된 시세에서 다시 계산하므로 멱등하다 — **그것이 이 잡을 관통 대상으로 고른 이유다.**

원장 실측(2026-08-20): 상태 SUCCESS 88 / ABORTED 10 / PARTIAL_SUCCESS 3, **RUNNING 0 · FAILED 0**.
ABORTED 10건은 전부 `MANUAL`이고 **현행 스위퍼가 만든 것이 아니다** — 로그 원문이
`10건 (기준 3시간)`이라 시간 임계값 버전의 산물이다. 현행 스위퍼가 문 행은 0건이고,
종료 훅 문구도 0건이다. 정상 종료는 로그상 58회였다.

## 배경

`docs/progress.md`가 「셧다운 훅이 운영에서 한 번도 안 걸렸다」를 결함으로 적고 있었다.
**그것은 결함이 아니었다.** 정상 종료 58회에 훅 문구가 0건인 이유는 배선이 아니라 그 58번의
순간에 열린 실행이 하나도 없었기 때문이다(101건 전부 종단 상태). SIGTERM이 컨텍스트 close까지
닿는 것은 `HikariPool - Shutdown initiated/completed` 58쌍이 이미 증명한다.

진짜 결함은 **걸릴 상황이 왔을 때** 있었고, 워커를 실제로 돌려 놓고 재야 드러났다.

`ExecutorConfigurationSupport`는 `SmartLifecycle`이라 종료 시 도는 태스크가 끝나기를 기다린다.
그 상한이 Boot 기본 30초였고 `ops/stop.sh`의 대기도 30초였다. 실측 30,061ms — **반드시 초과**다.
`ops/deploy.sh`는 `set -euo pipefail` 아래에서 `stop.sh --app`을 `then` 블록에서 부르므로,
그 실패가 곧 스크립트 중단이고 `start.sh`에 도달하지 못한다. 앱은 SIGTERM을 이미 받았으니
잠시 뒤 죽고, ADR-0043의 환경에서는 되살리는 것이 아무것도 없다. **배치가 도는 중에 배포하면
아무것도 안 도는 상태로 끝난다.**

두 번째는 이중 마감이다. `updateTerminalState`의 `AND status = 'RUNNING'`은 **쓰기만** 막는데
알림은 그 결과를 보지 않고 나간다. 종료 훅이 마감한 실행에 뒤늦게 도착한 워커가 가짜
「부분 실패」를 그대로 쏜다.

세 번째는 관측이다. 종료 마감이 알리지 않는데, 끊긴 날의 백분위·업종 상대값·워치리스트
통지는 **다음 실행이 다시 만들지 않는다** — `PercentileCalculator.calculate(LocalDate)`와
`SectorRelativeCalculator.calculate(LocalDate)`가 하루치만 받고 `DailyUpdateJob`이 `asOf`
하나로만 부르기 때문이다. ADR-0042의 15일 창이 메우는 것은 **시세뿐**이다. 그래서 13시 30분
잡이 끊기면 그 사실을 알 경로가 하나도 없었다.

## 버린 대안

- **`spring.task.execution.shutdown.await-termination=true`** — 더 나쁘다. 그 플래그가
  `lateShutdown`을 켜서 executor의 조기 정지를 통째로 건너뛴다. 종료가 오히려 길어진다.
- **`Future.cancel(true)` 또는 인터럽트를 보고 루프 절단** — 인터럽트는 신뢰할 수 있는 중단
  신호가 아니다. `ExternalApiClientFactory`가 `SimpleClientHttpRequestFactory`
  (=`HttpURLConnection`)라 소켓 읽기가 인터럽트를 무시하고, pgjdbc도 보지 않는다. 입구는
  `TokenBucketRateLimiter`의 `Thread.sleep` 하나뿐이라 **`IndicatorBackfillJob`처럼 어댑터를
  안 타는 잡에는 아예 안 통한다.**
  **이 기각은 지금도 유효하다.** 위 「협조적 중단」이 뒤집은 것이 아니다 — 신호를 읽는 자리가
  소켓이 아니라 **잡과 프레임워크가 만나는 경계**(`process`·`stage`·`plan`)여서 같은 한계에
  안 걸릴 뿐이고, 어댑터를 안 타는 잡도 그 경계는 지난다. **MUST NOT: 「협조적 중단이 있으니
  인터럽트도 켜자」로 읽지 않는다** — 진행 중인 호출을 못 끊는 것은 그대로라 얻는 것이 없고,
  어댑터가 인터럽트를 수집 실패로 오분류하는 경로만 늘어난다.
- **`@Order(Ordered.HIGHEST_PRECEDENCE)`로 리스너를 맨 앞에** — 실익이 없다. executor의
  같은 이벤트 리스너는 `executor.shutdown()`만 부르는 비차단 호출이라 순서가 결과를 안 바꾸는데,
  맨 앞이 되면 여기서 새는 예외의 폭발 반경만 최대가 된다.
- **아무것도 안 하고 `BatchExecutionSweeper`에 맡기기** — 스위퍼는 `RUNNING` 행만 닫는다.
  배포가 중단되는 것도, 가짜 알림도 막지 못한다.
- **종료 시 알림을 한 건 보내기** — 리스너로 옮기면 알림 빈이 살아 있어 **가능해진다.**
  그래도 안 한다. 동기 전송이고 웹훅이 최악 15초라 그만큼이 `stop.sh`의 대기에 얹혀
  위 결함을 다른 경로로 되살린다. 대신 **끄기 전 경고**를 `ops`에 두었다 — 예산을 안 먹고,
  사람이 되돌릴 수 있는 시점에 오며, 알림 채널에 의존하지 않는다.
- **`ops/stop.sh`의 대기만 늘리기** — 처음에 「문제를 미룰 뿐」이라며 기각했다가 **뒤집었다.**
  그 기각은 **상한이 없던 상태의 논리**였다. 상한이 생긴 뒤의 질문은 "대기가 상한 + 빈 파괴
  비용을 덮는가"이고, 그것은 미루는 것이 아니라 덮는 것이다. 45초로 채택했다.
- **`StockTaskExecutor`에서 인터럽트면 실패로 세지 않기** — 가짜 실패 행 하나만 놓고 보면
  가장 싸지만 **루프를 못 멈춘다.** 위 인터럽트 항목과 같은 한계에 걸린다. 지금 그 클래스가
  종료를 인지하는 것은 이것과 다른 목적이다 — 루프는 컨텍스트의 게이트가 멈추고, 그쪽은
  **ERROR로 안 찍기 위해서**다(그 로그가 곧 Sentry 이슈이고 돌던 종목 수만큼 난다).

## 결과

배치가 도는 중에도 배포할 수 있다. 종료 마감이 한 번만, 조용히 일어난다. 끄기 전에 사람이
무엇을 끊는지 안다.

되돌리려면 `application.yaml`의 상한과 `ops/stop.sh`의 대기를 **함께** 건드려야 한다.
한쪽만 되돌리면 배포가 조용히 깨진다 — 그래서 `.claude/rules/config.md`에 짝으로 적었다.

### 남은 결손

**협조적 중단으로 둘이 좁아졌고 하나가 닫혔다. 다섯이 남는다.**

1. **작업 *사이*만 멈춘다.** 이미 시작된 하나는 끝까지 간다 — `GovernanceLoadJob`은 그 하나가
   DART **22콜**이다(`YEARS_BACK`의 8표). 인터럽트로도 못 끊는 것은 「버린 대안」이 답한다.
2. **Sentry 오염이 좁아졌지 닫히지 않았다.** 신호를 다는 것은 `process()` 안뿐이다.
   `process()` 밖의 DB 작업 — `priceCalendar` 조회, 백분위·업종 상대값 계산, 고아 정리 —
   **도중에** 플래그가 켜지면 raw 예외가 그대로 올라가 원인 체인에 신호가 없다. 그러면
   `SentryConfig`의 백스톱도 `PipelineScheduler`의 catch도 그것을 못 알아본다.
   **잔여 빈도는 재보지 않았다.**
3. **집계가 조금 낮다.** 마감이 스냅샷을 찍는 순간 워커가 처리 중인 종목의 커밋은 뒤에 난다.
   ABORTED 행의 `success_count`가 실제보다 1~2 낮을 수 있다 — 버그가 아니다.
4. **사후 복구가 조건부로만 있다.** 끊긴 날의 백분위·업종 상대값은 여전히 스스로 안 돌아온다.
   다만 **끊긴 `daily-update`에 미해결 실패 행이 남으면** 관리자 화면의 부분 재실행이
   열리고, 그 재실행이 `collect()`를 다시 돌려 그날의 백분위·업종 상대값·워치리스트를
   **함께** 만든다(`AdminBatchController`의 게이트가 상태를 안 보는 것이 여기서는 이롭다).
   **그 행이 남는 일 자체가 드물다** — 게이트가 진입에서 끊으므로 다음 종목은 시작도 안 하고,
   진행 중이던 하나는 풀이 닫힌 뒤에야 예외를 받아 기록도 함께 실패한다. 운영 원장 실측
   (2026-08-25)으로 `ABORTED`+미해결 조합은 **0건**이고 `ABORTED` 12건 중 `daily-update`는
   **0건**이다. 조건이 안 맞으면 수동 `indicator-backfill`뿐이다.
5. **`MANUAL` 상시 오탐.** 스위퍼는 `MANUAL`을 **다음 적재 실행 때만** 닫으므로 죽은 적재가
   남긴 행이 영구히 남아 경고에 매번 뜬다. 갈라 표시하는 것으로만 완화했다.

**닫힌 것** — `flushNow`의 좁은 catch를 빠져나가던 예외. catch를 넓히는 대신 **종료 중에는
진척을 아예 쓰지 않는 것**으로 트리거를 없앴다. 그 좁음은 「그 밖은 우리 코드의 버그이고
버그는 드러나야 한다」는 의도라 지금도 유지한다.
