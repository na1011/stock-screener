---
id: ADR-0007
status: 채택됨
date: 2026-08-05
applies_to:
  - "src/main/java/com/stockscreener/pipeline/batch/**"
  - "src/main/resources/db/migration/V5__batch_execution.sql"
---

# ADR-0007. 종목 단위 실패 격리는 REQUIRES_NEW로 하고 Spring Batch의 skip을 쓰지 않는다

## 결정

종목 하나의 처리를 독립된 `REQUIRES_NEW` 트랜잭션에서 실행하고, 실패는 **또 다른** 독립 트랜잭션으로 `batch_execution_failure`에 기록한다. Spring Batch의 `faultTolerant().skip()`은 쓰지 않는다. 실행 이력은 Spring Batch의 `BATCH_JOB_EXECUTION`을 대체하지 않고 `job_execution_id`로 참조한다. **(2026-08-20 — 그 링크는 한 번도 안 꽂혔고 Spring Batch와 함께 걷혔다. 이제 `batch_execution`이 실행 이력의 유일한 정본이다 — ADR-0075.)**

## 규칙

- MUST: 종목 작업과 실패 기록이 **각각 독립된** `REQUIRES_NEW` 트랜잭션이다. 작업 트랜잭션 안에서 기록하면 롤백과 함께 기록도 사라져 실패가 조용히 증발한다.
- MUST: `@Transactional`에 `rollbackFor = Exception.class`를 붙인다. Spring의 기본 롤백 규칙은 **checked exception에 커밋한다.**
- MUST: 트랜잭션 경계를 별도 빈(`StockTaskRunner`)에 둔다. 같은 빈 안의 자기 호출에는 프록시가 걸리지 않아 `REQUIRES_NEW`가 조용히 무시된다.
- MUST: `DataSourceException`과 `RuntimeException`을 구분해 잡는다. 후자는 우리 코드의 버그일 가능성이 높아 `UNEXPECTED`로 따로 분류한다.
- MUST: `AUTH` 실패는 배치 전체를 중단시킨다. 키가 틀렸는데 850종목을 시도하면 쿼터만 태우고 동일한 실패 기록 850건을 남긴다.
- MUST: 마감과 알림을 한 지점(`BatchExecutionContext.finish()`)에서 수행한다. 분리하면 마감했지만 알리지 않은 경로가 생긴다.
- MUST NOT: catch 블록을 비워두지 않는다. 모든 실패 경로가 기록과 로그를 남긴다.
- MUST NOT: 종목마다 알림을 보내지 않는다. 850건 실패에 850통이면 알림 자체가 무의미해진다.
- MUST NOT: `faultTolerant()`·`skipPolicy()`·`.skip()`을 쓰지 않는다.

## 검증

```bash
./gradlew test --tests "*StockFailureIsolationTest*"
```
통과해야 한다. 특히 "실패한 종목의 쓰기는 롤백되고 성공한 종목의 쓰기는 커밋된다"가 `REQUIRES_NEW`와 `rollbackFor`가 **실제로** 걸려 있다는 증거다. 둘 중 하나라도 빠지면 이 테스트가 실패한다.

```bash
grep -rnE 'faultTolerant|skipPolicy|\.skip\(' src/main/java/com/stockscreener/ | grep -vE ':[0-9]+: *(\*|//)'
```
아무것도 반환하지 않아야 한다(의도적으로 쓰지 않는다).

```bash
grep -n 'rollbackFor' src/main/java/com/stockscreener/pipeline/batch/StockTaskRunner.java
```
`rollbackFor = Exception.class`가 있어야 한다.

```bash
psql -d stock_screener_test -c "\d batch_execution_failure"
```
`uq_failure (execution_id, stock_id, failed_phase)`와 `message NOT NULL`이 있어야 한다.

## 배경

`data-pipeline.md`가 두 가지를 동시에 요구한다 — 일부 종목이 실패해도 나머지는 정상 처리되고, **어느 종목이 왜 실패했는지 나중에 식별할 수 있어야 한다.** 후자가 없으면 spec §4의 "실패한 부분만 골라 재실행"이 성립하지 않는다.

Spring Batch의 `skip`을 쓰지 않는 이유는 성능이 아니라 **쿼터**다. skip 정책은 청크를 롤백한 뒤 아이템을 하나씩 다시 처리하므로, 실패한 종목 하나 때문에 같은 청크의 성공 종목이 API를 다시 호출한다. 일일 호출 한도가 있는 소스에서 이는 그대로 낭비이며, 850종목 초기 적재에서 쿼터 소진을 앞당긴다.

IMPORTANT — `rollbackFor = Exception.class`가 없으면 이 설계가 **조용히 무너진다.** Spring의 기본 롤백 규칙은 `RuntimeException`과 `Error`만 되돌리고 checked exception에는 커밋한다. `DataSourceException`이 checked이므로, 수집 도중 실패한 종목의 부분 저장이 그대로 남는다. 격리가 있다고 믿는 채로 반쪽짜리 데이터가 쌓이고 아무 에러도 나지 않는다. 구현 중 실제로 이 결함이 발생했고 격리 테스트가 잡았다.

`batch_execution`을 Spring Batch 테이블과 공존시킨 것은 중복이 아니다. Spring Batch는 실패한 아이템을 구조화해 저장하지 않고(`EXIT_MESSAGE`에 문자열일 뿐), `READ_COUNT`에 도메인 의미가 없으며, "실패한 종목만"을 쿼리하려면 종목 단위 행이 필요하다. 관리자의 부분 재실행이 문자열 파싱에 의존할 수는 없다. 상태·시각의 중복은 `app_user.is_admin`이 `allowed_email.is_admin`의 사본인 것과 같은 패턴이며 ADR-0004가 이미 정당화했다.

## 버린 대안

- **`faultTolerant().skip()`** — 위 참조. 성공 종목의 API 재호출이 쿼터를 태운다.
- **한 트랜잭션에 전 종목** — 한 종목의 실패가 그때까지 저장한 모든 종목을 되돌린다.
- **트랜잭션 없이 각 종목 처리** — 시세와 재무를 나눠 쓰는 도중 실패하면 반쪽짜리 종목이 남는다.
- **실패를 작업과 같은 트랜잭션에 기록** — 롤백과 함께 기록도 사라진다. 관리자 화면에는 아무 일도 없었던 것으로 보인다.
- **`StockTaskExecutor` 한 클래스에 실행과 실패 처리를 모두 둠** — 자기 호출이라 프록시가 걸리지 않고 `REQUIRES_NEW`가 무시된다. 격리가 사라졌는데 테스트는 통과할 수 있다.
- **`batch_execution` 없이 Spring Batch 테이블만 사용** — 실패 종목 목록을 `EXIT_MESSAGE` 문자열에서 파싱해야 한다.
- **실패를 로그로만 남김** — 관리자의 부분 재실행이 로그 grep에 의존하게 된다.

## 결과

- 쉬워지는 것: 실패 종목을 SQL로 조회할 수 있어 부분 재실행이 평범한 쿼리가 된다. 재실행이 성공 종목의 API를 다시 부르지 않는다.
- 어려워지는 것: 트랜잭션 경계를 위해 빈이 하나 더 있고(`StockTaskRunner`), 그 이유가 코드만 봐서는 드러나지 않는다 — 그래서 이 ADR이 있다.
- 되돌리려면: `pipeline/batch`의 실행 계층과 V5 마이그레이션을 함께 고쳐야 한다.
