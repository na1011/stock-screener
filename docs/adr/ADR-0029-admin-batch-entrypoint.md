---
id: ADR-0029
status: 채택됨
date: 2026-08-08
applies_to:
  - "src/main/java/com/stockscreener/pipeline/batch/AdminJobLauncher.java"
  - "src/main/java/com/stockscreener/pipeline/batch/BatchExecutionReader.java"
  - "src/main/java/com/stockscreener/pipeline/batch/web/**"
---

# ADR-0029. 배치는 비동기로 제출하고, 단일 실행 판정의 정본은 인프로세스 가드다

## 결정

관리자 화면의 배치 기동은 `AsyncTaskExecutor`에 **직접 제출**하고 HTTP는 `202`로 즉시 돌아온다.
같은 잡이 겹쳐 도는 것은 `AdminJobLauncher`의 **인프로세스 집합**이 막는다 —
`batch_execution`의 `RUNNING` 행은 **화면 표시용**이고 판정 근거가 아니다.

화면에서 띄울 수 있는 잡의 정본은 `AdminJobLauncher`의 카탈로그이고 `AdminJobLauncherTest`가
그 목록을 고정한다 — **여기에 세어 적지 않는다**(그 수가 실제로 두 번 낡았다).
**`initial-load`는 목록 밖이고** 그 경로는 `BootstrapRunner`에 남는다.

부분 재실행은 `daily-update`만 지원한다. 지원하지 않는 잡과 미해결 실패가 없는 실행은
**409로 거절한다.**

## 규칙

- MUST: 잡 기동은 `202`이고 응답이 잡의 완료를 기다리지 않는다.
- MUST NOT: `@Async`를 쓰지 않는다. 자기 호출이 프록시를 거치지 않는 함정을 다시 들이지
  않기 위해서다(ADR-0007). 그 함정은 **동기로 조용히 도는 것**으로 나타나 테스트에 잘 안 걸린다.
- MUST: 인프로세스 가드는 `finally`에서 반드시 푼다. 이름이 남으면 그 잡은 재시작 전까지 다시
  띄울 수 없는데, 화면에는 아무것도 도는 중이 아닌 것으로 보인다.
- MUST: 제출한 작업의 `RuntimeException`을 스레드 경계에서 잡아 ERROR로 남긴다. catch-all이
  아니다 — 받을 호출부가 없으므로 잡지 않으면 **어디에도 남지 않는다.**
- MUST NOT: `initial-load`를 목록에 더하지 않는다. 재무 초기 적재가 DART 9,800콜로 일일 한도의
  절반을 한 번에 태운다.
- MUST NOT: 재실행이 대상 일자를 오늘로 바꾸지 않는다. `batch_execution.target_date`는 논리적
  대상이고, 바꾸면 어제 것을 오늘 것으로 덮는다.
- MUST: 잡 목록·파라미터 종류·재실행 지원 여부를 서버가 응답에 담는다. 프론트가 잡 이름으로
  분기하는 순간 잡이 늘 때마다 화면을 고쳐야 한다.

## 검증

```bash
./gradlew test --tests "*AdminJobLauncherTest*" --tests "*AdminBatchApiTest*" --tests "*SecurityConfigTest*"
```

`AdminJobLauncherTest`가 가드의 정본을 확인한다 — 겹침 거절, 완료 후 해제, **예외로 끝나도 해제**,
잡 이름 단위 격리, 초기 적재 부재.

`@Async`가 새어 들어오지 않았는지:

```bash
rg '^\s*@Async|^\s*@EnableAsync' src/main/java
```
아무것도 반환하지 않아야 한다. **줄 시작으로 앵커를 거는 이유는** 애너테이션은 줄 앞에 오지만
`{@code @Async}` 같은 주석 언급은 그렇지 않기 때문이다 — 앵커가 없으면 이 결정을 설명하는
주석 자체가 위반으로 잡힌다(`7f4fc51`이 같은 함정을 고쳤다).

## 배경

spec §9의 다섯째 수용 기준("운영자가 관리자 화면에서 배치 실행 현황을 확인하고, 실패한 배치를
수동으로 재처리할 수 있다")이 HTTP 진입점을 요구한다. 지금까지 실행 경로는 `BootstrapRunner`
(CLI)와 스케줄러뿐이었고, `TriggeredBy.ADMIN`은 값만 정의된 채 아무도 쓰지 않는 자리로 남아
있었다.

`SecurityConfigTest`가 `GET /api/admin/batches` → 404를 단언하며 "인가는 통과했으나 핸들러가
없다"를 기록하고 있었다. 이 결정이 그 단언을 200으로 옮긴다.

## 버린 대안

**DB의 `RUNNING` 행으로 단일 실행을 판정.** 이쪽이 인스턴스가 늘어도 성립한다는 이점이 실재하고,
`idx_batch_exec_running` 부분 인덱스도 이미 있다. 그러나 **애플리케이션이 잡 도중에 죽으면 그
행은 영원히 `RUNNING`으로 남는다.** 그 뒤로 그 잡은 화면에서 다시는 띄울 수 없고, 복구 수단이
SQL뿐이 된다 — 관리자 화면을 만드는 목적과 정면으로 어긋난다. 타임아웃으로 유령 행을 무시하는
방법도 검토했으나, 그 임계값은 "오래 걸리는 정상 실행"과 "죽은 실행"을 가르지 못한다(초기
적재는 몇 시간이 걸린다). 인스턴스가 하나라는 전제는 ADR-0025가 이미 억제 상태를 메모리에 두며
같은 자리에서 밝혀 두었다.

**기동 시점에 `batch_execution` 행을 미리 만들어 id를 202에 실어 주기.** 화면이 "방금 띄운 것이
이것"이라고 말할 수 있다는 이점이 있다. 그러나 **기록의 시작점이 두 곳**이 된다 — 지금은
`BatchExecutions.start()` 하나뿐이고, 잡마다 대상 건수를 세어 넣는다. 미리 만들면 그 건수를 아직
모르는 행이 하나 더 생기고, 잡이 자기 행을 또 만들지 말지가 잡마다의 판단이 된다. 목록을 새로
고치는 것은 몇 백 밀리초의 일이다.

**초기 적재까지 화면에 열기.** 관리자 화면 하나로 모든 운영을 끝낼 수 있다는 점이 매력적이지만,
버튼 하나가 DART 일일 한도 18,000의 절반을 한 번에 태운다. 오조작이 그날의 재적재를 불가능하게
만들고, 그 사실은 다음 날에야 드러난다. CLI는 `--job=initial-load`를 손으로 타야 해서 그 자체가
확인 절차다.

**재실행을 모든 잡에 열기.** 유니버스 리밸런싱은 실패 단위가 종목이 아니고 백필은 외부 호출이
없어, `retryFailures`에 해당하는 것이 없다. 억지로 열면 "재실행했는데 아무 일도 없었다"가 된다.
409로 거절하고 이유를 말하는 편이 낫다.

## 결과

**쉬워지는 것.** 배치 운영이 화면에서 끝난다. `WatchlistAlertRunner`를 실데이터로 태워 볼 경로가
처음 생긴다 — 지금까지 실행 경로가 일일 배치뿐이라 관통에서 판정까지 못 봤다.

**어려워지는 것.** 인스턴스를 둘 이상 띄우면 같은 잡이 겹쳐 돈다. 그날이 오면 판정을 DB나
분산 락으로 옮겨야 하고, 그때 유령 `RUNNING` 문제를 함께 풀어야 한다.

**되돌리려면.** `AdminJobLauncher`·`BatchExecutionReader`·`pipeline/batch/web` 전체를 지우고,
`SecurityConfigTest`의 단언을 404로 되돌리며, 계약 픽스처에서 세 problem type을 뺀다.
