---
id: ADR-0076
status: 채택됨
date: 2026-08-20
applies_to:
  - "src/main/java/com/stockscreener/pipeline/batch/BatchExecutionContext.java"
  - "src/main/java/com/stockscreener/pipeline/batch/BatchExecutionRecorder.java"
  - "src/main/java/com/stockscreener/pipeline/batch/DailyUpdateJob.java"
---

# ADR-0076. 실패 해소를 재실행 경로에서 떼어내 종목·단계에 붙인다

## 결정

**`batch_execution_failure.resolved`를 세우는 자리를 잡에서 `BatchExecutionContext.process`로 옮긴다.**
어느 잡이든 **한 종목·한 단계**를 성공으로 끝내면 그 종목·그 단계의 미해결 실패를 닫는다.
실행 id로 좁히지 않는다 — 실패를 낸 실행과 그것을 채우는 실행이 대개 다르기 때문이다.

**이것은 spec §4의 「미해결 에러 건수」가 무엇을 뜻하는지를 바꾸는 결정이다.**
「그 실행의 실패가 재실행 경로로 해소됐는가」(실행 이력)에서
**「그 종목의 그 단계가 그 뒤로 한 번이라도 성공했는가」**(데이터 상태에 가까운 것)로 옮긴다.
승인은 **2026-08-20 사용자 결정**이다.

## 규칙

- **MUST: 해소는 단계를 함께 받는다.** 시세 수집 성공을 근거로 업종정보 실패를 지우면
  **어느 단계가 실제로 다시 돌았는지가 사라진다.** `StockFailureIsolationTest`의
  「다른 단계의 성공은 남의 실패를 닫지 않는다」가 그 대조군이며, 단계 구분을 지우면
  실제로 붉어지는 것을 확인했다.
- **MUST NOT: 잡마다 해소를 호출하지 않는다.** `process` 한 곳이 정본이다. 흩뿌리면 부르는 잡과
  안 부르는 잡이 갈리고, **안 부르는 쪽의 실패는 데이터가 채워진 뒤에도 영영 남는다** —
  그것이 이 결정 전의 상태였다.
- **MUST: 실행 시작 때 미해결 집합을 한 번만 읽는다.** 성공할 때마다 조회하면 평상시(미해결 0건)
  에도 종목 수 × 단계 수만큼 쿼리가 붙는다. 집합에서 빼면서 쓰므로 같은 종목이 두 번 성공해도
  UPDATE는 한 번이다.
- **MUST NOT: 「성공」을 「행을 썼다」로 좁히지 않는다.** `StockTaskExecutor`가 예외만 없으면
  성공으로 세고, 그것은 **거래정지처럼 정상적으로 빈 종목을 영원히 미해결로 남기지 않으려는
  의도**다(`DailyUpdateJob`이 그 판단을 적어 두었다). **그 대가를 신고한다** — 같은 단계를 쓰는
  다른 잡이 0행 성공만으로도 이 실패를 닫을 수 있다. `FINANCIAL`을 쓰는 잡이 셋이고
  받아오는 범위가 서로 다르다(`initial-load`는 주요계정, `full-statement-refill`은 현금흐름까지).
  **「같은 단계」가 「같은 데이터」를 뜻하지 않는다.**
- **MUST NOT: 재실행 정책을 함께 넓히지 않는다.** ADR-0029가 *"부분 재실행은 `daily-update`만
  지원한다"*를 의도된 결정으로 두었고 그 §버린 대안이 *"재실행을 모든 잡에 열기"*를 이미 기각했다.
  이 결정은 **해소 기록**을 재실행에서 떼어낸 것이지 재실행을 넓힌 것이 아니다.

## 검증

해소 호출이 잡으로 새어 나가지 않았는가 — `process` 바깥에서 부르면 이 결정이 무너진다.

```bash
rg -n "resolveFailures\(" src/main/java --glob '!**/BatchExecutionContext.java' \
  --glob '!**/BatchExecutionRecorder.java'
```

아무것도 반환하지 않아야 한다.

단계 구분과 그 대조군:

```bash
./gradlew test --tests "*StockFailureIsolationTest*"
```

```premise 미해결 실패가 데이터 상태와 어긋나 있지 않다
psql -d stock_screener -At -c "WITH u AS (SELECT DISTINCT stock_id, failed_phase FROM batch_execution_failure WHERE NOT resolved) SELECT CASE WHEN (SELECT count(*) FROM batch_execution_failure) = 0 THEN '잴 것이 없었다 — 실패 원장이 비었다' WHEN (SELECT count(*) FROM u) = 0 THEN 'OK 미해결 실패가 없다' WHEN (SELECT count(*) FROM u WHERE failed_phase = 'SECURITY_MASTER' AND EXISTS (SELECT 1 FROM stock s WHERE s.id = u.stock_id AND s.industry_code IS NOT NULL AND s.accounting_month IS NOT NULL)) > 0 THEN '데이터가 채워졌는데 미해결로 남은 SECURITY_MASTER가 ' || (SELECT count(*) FROM u WHERE failed_phase = 'SECURITY_MASTER' AND EXISTS (SELECT 1 FROM stock s WHERE s.id = u.stock_id AND s.industry_code IS NOT NULL AND s.accounting_month IS NOT NULL)) || '건 있다 — 그 잡을 한 번 돌리면 닫힌다(company-profile)' ELSE 'OK 미해결 ' || (SELECT count(*) FROM u) || '건이 전부 데이터도 결손인 자리다' END"
```

## 배경

**해결 기제가 운영에서 한 번도 안 돌았다.** 전수로 세니 `resolved = true` 354건 중 **코드가 푼 것은
1건**이고 그것도 *"재실행 경로 관통 검증용으로 심은 합성 실패"*였다. 나머지 353건은 과거 세션의
수기 `UPDATE`다 — `xmin`이 그것을 증명한다(350행이 트랜잭션 하나를 공유하는 것은 단일 `UPDATE`
말고 만들 수 없고, `resolveFailure`는 `REQUIRES_NEW`라 행마다 트랜잭션이 하나여야 한다).

**증상은 `company-profile`의 52건이었다.** 2026-08-11 실행 40이 `ck_stock_industry_code` 위반으로
52종목을 실패로 남겼고, `V18`이 제약을 3~5자리로 넓힌 뒤 실행 41이 **2분 만에 전부 채웠다.**
그런데 원장은 52건을 미해결로 들고 있었다 — 해소가 (a) `daily-update`의 (b) `PRICE` 단계에만
붙어 있었고 (c) 실패를 낸 실행 id로 좁혀 갱신했기 때문이다. 셋 중 어느 하나만으로도 이 52건은
안 닫혔다.

**그 수가 정본에 걸린다.** `spec:31`이 *"최근 배치 실행 결과와 **미해결 에러 건수**를 한눈에
확인할 수 있어야 한다"*를 수용 기준으로 요구하고, `resolveFailure`의 javadoc이 이미
*"이 갱신이 없으면 spec §4의 「미해결 에러 건수」가 영원히 줄지 않아 관리자 화면의 요약이
무의미해진다"*고 적고 있었다. **화면에 수는 떴지만 그 수가 거짓이었다.**

## 버린 대안

- **화면 집계만 고친다** — 「재실행이 원리적으로 불가능한 잡」의 실패를 미해결 수에서 뺀다.
  범위가 가장 작다. 버린 이유는 그 수가 **「지금 손이 필요한가」가 아니라 「재실행 대상인가」**를
  세게 되기 때문이다. `company-profile`처럼 사람이 CLI로 다시 돌려 고칠 수 있는 일이
  그 수에서 사라지면, 화면은 조용해지지만 할 일은 남는다.
- **재실행을 모든 잡에 연다** — ADR-0029 §버린 대안이 이미 기각했다(*"유니버스 리밸런싱은 실패
  단위가 종목이 아니고 백필은 외부 호출이 없어 … 억지로 열면 「재실행했는데 아무 일도 없었다」가
  된다"*). 그 기각은 이 결정 뒤에도 유효하다.
- **`resolved`를 계산 뷰로 바꿔 매번 데이터 상태를 다시 판정한다** — 원장에 플래그를 두지 않으면
  어긋날 일도 없다. 버린 이유는 **「정상」의 정의가 단계마다 다르기 때문**이다(시세는 봉이 있나,
  업종은 `industry_code`가 있나, 재무는 몇 기가 있나). 판정 로직이 단계 수만큼 생기고,
  거래정지처럼 **정상적으로 비어 있는 종목**을 그 로직이 결손과 구별하지 못한다.
- **해소 조건을 `writtenRows > 0`으로 좁힌다** — 「같은 단계가 같은 데이터를 뜻하지 않는다」는
  위 결손을 막는다. `DailyUpdateJob`이 이미 기각해 두었다 — *"거래정지처럼 정상적으로 빈 종목이
  영원히 미해결로 남게 된다."* 그래서 좁히지 않고 **결손으로 신고**한다.

## 결과

**쉬워지는 것** — 어느 잡이든 실패를 고치면 원장이 따라온다. 잡을 새로 만들 때 해소를 잊을 자리가
없다.

**어려워지는 것 둘** — ① 같은 단계를 쓰는 다른 잡이 **더 얕은 수집으로도** 남의 실패를 닫을 수
있다(위 §규칙). ② **지금 화면의 52건은 이 변경만으로 안 사라진다.** 그 행들은
`company-profile` 실행 40의 것이고 **그 잡을 다시 안 돌리면 아무 일도 일어나지 않는다** —
최근 실행 8건에 그 잡이 없다. 위 `premise`가 그 상태를 세션 시작에 알린다.
