---
id: ADR-0078
status: 채택됨
date: 2026-08-21
applies_to:
  - "src/main/java/**/pipeline/batch/BatchExecutions.java"
  - "src/main/java/**/pipeline/batch/BatchExecutionContext.java"
  - "src/main/java/**/pipeline/batch/BatchExecutionSweeper.java"
  - "ops/*.sh"
---

# ADR-0078. 프로세스가 내려갈 때 배치 실행을 어떻게 마감하는가

## 결정

마감을 **`ContextClosedEvent`에서** 하고, **종단 판정을 알림보다 앞에** 두며, **종료 유예에
상한을 명시해** `ops/stop.sh`의 대기보다 짧게 유지한다. 종료 마감은 **알리지 않되**, 끄기
**전에** `ops`가 도는 배치를 사람에게 알린다.

세 기제가 분담한다. 어디에도 적혀 있지 않아 이 문서를 쓴다.

| 기제 | 담당 | 알림 |
|---|---|---|
| `BatchExecutions`의 종료 리스너 | **이 JVM이** 열어 둔 실행 | 없음 |
| `BatchExecutionContext`의 종단 플래그 | 한 실행을 **두 번** 마감하려는 시도 | 없음(건너뛴 것을 `warn`) |
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
```

동적 검증은 `BatchExecutionShutdownRaceTest`가 한다 — 워커를 돌려 놓고 컨텍스트를 닫아
`close()` 소요·마감 상태·사유·알림 수를 함께 잰다. **상한을 지우면 30초가 나와 떨어진다.**

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
- **`Future.cancel(true)` 또는 `process()`에서 인터럽트를 보고 루프 절단** — 인터럽트는
  신뢰할 수 있는 중단 신호가 아니다. `ExternalApiClientFactory`가 `SimpleClientHttpRequestFactory`
  (=`HttpURLConnection`)라 소켓 읽기가 인터럽트를 무시하고, pgjdbc도 보지 않는다. 입구는
  `TokenBucketRateLimiter`의 `Thread.sleep` 하나뿐이라 **`IndicatorBackfillJob`처럼 어댑터를
  안 타는 잡에는 아예 안 통한다.** 아래 「결과」의 결손 1이 이것이다.
- **`@Order(Ordered.HIGHEST_PRECEDENCE)`로 리스너를 맨 앞에** — 실익이 없다. executor의
  같은 이벤트 리스너는 `executor.shutdown()`만 부르는 비차단 호출이라 순서가 결과를 안 바꾸는데,
  맨 앞이 되면 여기서 새는 예외의 폭발 반경만 최대가 된다.
- **아무것도 안 하고 `BatchExecutionSweeper`에 맡기기** — 스위퍼는 `RUNNING` 행만 닫는다.
  배포가 중단되는 것도, 가짜 알림도 막지 못한다.
- **종료 시 알림을 한 건 보내기** — 리스너로 옮기면 알림 빈이 살아 있어 **가능해진다.**
  그래도 안 한다. 동기 전송이고 웹훅이 최악 15초라 그만큼이 `stop.sh`의 대기에 얹혀
  위 결함을 다른 경로로 되살린다. 대신 **끄기 전 경고**를 `ops`에 두었다 — 예산을 안 먹고,
  사람이 되돌릴 수 있는 시점에 오며, 알림 채널에 의존하지 않는다.
  `BatchExecutionContext.abortOnShutdown`이 적고 있던 옛 근거(「그 시점에 알림 채널의 빈이
  이미 파괴됐을 수 있다」)는 **이 결정으로 거짓이 됐고**, 그래서 근거를 갈아 끼웠다.
- **`ops/stop.sh`의 대기만 늘리기** — 처음에 「문제를 미룰 뿐」이라며 기각했다가 **뒤집었다.**
  그 기각은 **상한이 없던 상태의 논리**였다. 상한이 생긴 뒤의 질문은 "대기가 상한 + 빈 파괴
  비용을 덮는가"이고, 그것은 미루는 것이 아니라 덮는 것이다. 45초로 채택했다.
- **`StockTaskExecutor`에서 인터럽트면 실패로 세지 않기** — 가짜 실패 행 하나만 놓고 보면
  가장 싸지만 루프를 못 멈춘다. 위 인터럽트 항목과 같은 한계에 걸린다.

## 결과

배치가 도는 중에도 배포할 수 있다. 종료 마감이 한 번만, 조용히 일어난다. 끄기 전에 사람이
무엇을 끊는지 안다.

되돌리려면 `application.yaml`의 상한과 `ops/stop.sh`의 대기를 **함께** 건드려야 한다.
한쪽만 되돌리면 배포가 조용히 깨진다 — 그래서 `.claude/rules/config.md`에 짝으로 적었다.

### 남은 결손

1. **인터럽트가 중단 신호로 안 통한다.** 종료 상한 15초 동안 종목 루프가 계속 돌며 쿼터를
   태운다. 그 뒤 인터럽트가 와도 `IndicatorBackfillJob`처럼 어댑터를 안 타는 잡에는 안 통한다.
   실측에서 인터럽트된 워커는 자기 실패를 **기록조차 못 하고**
   `CannotCreateTransactionException`으로 죽었다(풀이 이미 닫혔다).
2. **그 예외가 Sentry로 간다.** 경로가 둘이다 — `AdminJobLauncher`의 `log.error`와,
   예외를 안 잡는 `PipelineScheduler`(Spring 기본 핸들러가 ERROR로 찍는다). 운영에서 실제로
   밟는 것은 후자다. 전자의 문구(「batch_execution에는 남지 않았을 수 있다」)는 이 결정
   이후 **거짓**이다 — 행은 ABORTED로 남는다.
3. **`flushNow`의 좁은 catch.** `DataAccessException`만 잡는데 EMF가 닫힌 뒤엔
   `IllegalStateException`이 나서 빠져나간다. **상한을 15초로 당긴 것이 이 확률을 높인다.**
   그 좁은 catch는 「그 밖의 예외는 우리 코드의 버그이고 버그는 드러나야 한다」는 의도라
   넓히지 않았다.
4. **집계가 조금 낮다.** 마감이 스냅샷을 찍는 순간 워커가 처리 중인 종목의 커밋은 뒤에 난다.
   ABORTED 행의 `success_count`가 실제보다 1~2 낮을 수 있다 — 버그가 아니다.
5. **사후 복구 경로가 없다.** 끊긴 날의 백분위·업종 상대값은 수동 `indicator-backfill`뿐이다.
   `ops`의 경고는 **사전** 완화이지 복구가 아니다.
6. **`MANUAL` 상시 오탐.** 스위퍼는 `MANUAL`을 **다음 적재 실행 때만** 닫으므로 죽은 적재가
   남긴 행이 영구히 남아 경고에 매번 뜬다. 갈라 표시하는 것으로만 완화했다.
