---
id: ADR-0138
status: 제안됨
date: 2026-09-11
applies_to:
  - "src/main/java/**/pipeline/batch/IndicatorBackfillJob.java"
  - "src/main/java/**/indicator/calculator/ichimoku/IchimokuAnalyses.java"
  - "src/main/java/**/indicator/calculator/WeeklyMacdVSeries.java"
  - "src/main/resources/application.yaml"
---

# ADR-0138. 지표 백필의 종목 루프를 병렬화하고, 기계의 일부는 서버에 남긴다

## 결정

`IndicatorBackfillJob`의 **종목 루프만** 팬아웃한다. 날짜 루프는 직렬로 둔다.
그 전제로 공유 분석 메모이저 둘(`IchimokuAnalyses`·`WeeklyMacdVSeries`)의 크기-1 캐시를
**싱글턴 필드에서 스레드별로** 옮긴다. 병렬도의 상한은 **코어 유보와 커넥션 풀에서 유도해
코드가 자른다** — 설정값으로 넘을 수 없다.

## 규칙

- MUST: 날짜 루프는 직렬로 둔다. 백분위·업종 상대값이 「그날 전 종목의 값」을 요구한다.
- MUST: 한 날짜의 종목을 **전부 조인한 뒤에** `percentileCalculator.calculate`·
  `sectorRelativeCalculator.calculate`로 간다. 그 조인이 곧 배리어다.
- MUST: 메모이저의 **키 대조를 유지한다**(`Key(stockId, first, last, bars)`). 스레드별이 됐다고
  「한 스레드는 한 종목만 본다」에 기대면 ADR-0054 §버린 대안이 기각한 물건이 되고, 틀렸을 때
  증상은 **값이 하루 낡은 것**뿐이다.
- MUST: 워커 수의 상한을 **코어 유보**(`cores − max(2, cores/3)`)와 **풀 여유**
  (`maxPoolSize − 2`)와 `targets.size()`에서 유도한다. 이 기계에는 운영 앱이 함께 사는데,
  기본값이 우연히 작은 것에 맡기면 인자 하나로 깨진다.
- MUST: 결정된 워커 수와 **어느 항이 이겼는지**를 잡 시작 로그에 찍는다. 클램프는 평소 아무것도
  안 자르므로 **깎일 때가 조용하고**, 조용히 1이 되면 「병렬이 안 빨라졌다」는 거짓 결론이 난다.
- MUST NOT: `try (ExecutorService …)`를 쓰지 않는다. JDK 21 `ExecutorService.close()`는
  `awaitTermination(1L, DAYS)` 루프를 돌고, 호출 스레드가 인터럽트되면 `shutdownNow()`로
  **워커 전원을 인터럽트한다** — ADR-0078이 기각한 축이다. `finally`에서 `shutdown()` +
  상한 있는 대기 + 초과 시 `shutdownNow()`를 **우리 코드에** 적는다.
- MUST: 첫 실패에서 남은 `Future`를 `cancel(false)`하고, **제출한 것을 전부 조인해** Throwable을
  모은다. 첫 번째를 다시 던지고 나머지는 `addSuppressed`로 싣는다 — 안 꺼내면 silent fail이다.
- MUST: `ExecutionException`을 풀어 `DataSourceException`과 `RuntimeException`
  (= `BatchShutdownException` 포함)을 **원본 그대로** 다시 던진다. 감싸면
  `failUnexpectedly`의 조기 반환이 안 걸려 **종료마다 가짜 마감 경합 경고**가 난다(ADR-0078).
- MUST NOT: `spring.task.scheduling.pool.size`를 건드리지 않는다. 두 `@Scheduled`가 겹치지 않는
  유일한 근거이고, 워커 풀은 그것과 별개다.

## 검증

```bash
# ① 메모이저에 공유 가변 상태가 남아 있지 않은가 (둘 다 0이어야 한다)
#    행 머리만 센다 — javadoc이 이 낱말을 설명하고 있어 이름만 세면 산문에 걸린다.
grep -cE '^\s*(public|private|protected).*\bsynchronized\b' \
  src/main/java/com/stockscreener/indicator/calculator/ichimoku/IchimokuAnalyses.java \
  src/main/java/com/stockscreener/indicator/calculator/WeeklyMacdVSeries.java

# ② 키 대조가 살아 있는가 (둘 다 1 이상이어야 한다)
grep -c 'key.equals(cached.key)' \
  src/main/java/com/stockscreener/indicator/calculator/ichimoku/IchimokuAnalyses.java \
  src/main/java/com/stockscreener/indicator/calculator/WeeklyMacdVSeries.java

# ③ 상한이 코어와 풀 둘 다에서 나오는가 — 눈으로 본다
grep -n 'RESERVED_CORES\|RESERVED_CONNECTIONS\|availableProcessors' \
  src/main/java/com/stockscreener/pipeline/batch/IndicatorBackfillJob.java

# ④ try-with-resources가 되살아나지 않았는가 (0이어야 한다)
#    MUST: 행 머리로 센다. 낱말로 세면 **이 파일의 javadoc이 그것을 금지한다고 설명하고 있어**
#    1이 나온다 — 결정이 지켜져도 우는 검사가 된다(ADR-0078 §검증이 같은 함정을 적었다).
grep -cE '^\s*try \(' \
  src/main/java/com/stockscreener/pipeline/batch/IndicatorBackfillJob.java
```

동적 검증이 셋이다.

```bash
./gradlew test --tests "*MemoizerThreadIsolationTest*"   # 스레드별 캐시
./gradlew test --tests "*StockFailureIsolationTest*"     # ADR-0007이 동시 실행에서도 서는가
./gradlew clean build
```

**`MemoizerThreadIsolationTest`는 대조군을 실제로 붉혀 확인했다**(2026-09-11) — 변경을 되돌리면
두 시험 모두 **본 단언**(`isSameAs`)에서 실패하고, 그 앞의 전제 단언(`isNotSameAs`)은 통과한다.
그 순서가 중요하다: 전제가 먼저 무너지면 **빈 싱글턴 맵**(`Collections.emptyNavigableMap()`)을
양쪽이 받은 것이라 시험이 아무것도 검사하지 않는다.

**`premise` 펜스는 만들지 않는다**(ADR-0078과 같은 판단). 이 결정에는 DB에서 관측되며 낡는
전제가 없고 — 워커 수·유보·풀은 코드와 yaml이 들고 클램프가 어긋남을 구조로 막는다 —
**전제 예산이 45초/45초로 이미 차 있다.** 억지로 더하면 뒤쪽 전제가 조용히 무판정이 되어
지금 있는 검사를 망가뜨린다.

## 배경

전량 백필이 **22.5시간**이다(5.0분/거래일 × 269거래일). 결손 백필이 17%에서 중단돼 결손 칸
15,512가 남아 있고, 그 시간이 **결손을 닫는 것 자체를 막는다.** 단일 스레드가 12코어 중 하나만
쓰고 있었다.

**ADR-0123 §버린 대안의 「적재를 병렬화한다」와는 무관하다.** 그 문장은 폐기된 시점 재현
저장소(`PitLoadTest`)의 적재를 가리키고, 거기 적힌 3.8시간은 **ADR-0131이 0으로 만들었다**
(*"값을 처음 만드는 비용 3.8시간 → 0(배치가 이미 만든다)"*). 백필 병렬화를 기각하거나 미뤄 둔
ADR은 저장소에 **없었다.**

**막고 있던 것은 구조가 아니라 메모이저 둘이었다.** 31개 `ICHI_*` 계산기가
`IchimokuAnalyses`의 분석 하나를, 주봉 MACD-V 셋이 `WeeklyMacdVSeries`의 계열 하나를 나눠
쓰는데, 그 캐시가 **싱글턴 필드 + `synchronized` + 크기 1**이었다. 워커들이 서로 다른 종목을
돌면 키가 매번 어긋나 전부 미스가 되고, 계산이 락 안이라 재계산이 전 워커에 걸쳐 직렬화된다.
**정확성은 안 깨진다 — 값이 한 칸도 안 변하므로 시험으로는 드러나지 않는다.**

## 측정 — 0단계(구현 전, `computeAt` 경로)

160종목 × 2026-08-20 · 12코어 · macOS · `computeAt`(저장·삭제 없음). **값합 18,104이 열 판
전부 동일**했다 — 스레드 수가 값을 바꾸지 않는다.

| 워커 | A. 싱글턴 `synchronized` 캐시 | B. 스레드별 캐시 |
|---|---|---|
| 1 | 14,309ms · 1.00배 | 13,706ms · 1.00배 |
| 2 | 7,479ms · 1.91배 | 7,096ms · 1.93배 |
| 4 | 5,598ms · **2.56배** | 3,773ms · 3.63배 |
| 6 | 5,890ms · 2.43배 | 2,619ms · 5.23배 |
| 8 | 7,731ms · **1.85배** | 2,036ms · **6.73배** |

**A행이 「고치지 않았을 때」다. 직렬보다 느려지지는 않지만 4워커에서 천장을 치고 그 뒤로는
워커를 더할수록 되돌아간다** — A행의 8워커가 2워커보다 느리다. B행의 같은 8워커는 3.8배 빠르다.

**이 표는 CPU 확장성만 답한다.** `computeAt`이 UPSERT/DELETE를 안 타므로 DB 쓰기 경합은 여기
없다 — 그 축은 `run()` 경로의 A/B가 답한다.

## 버린 대안

- **날짜 루프도 병렬화한다** — 백분위가 「그날 전 종목의 값」을 요구한다. 날짜가 바깥인 이유가
  그것이고 그 주석이 코드에 있다.
- **가상 스레드** — CPU 바운드라 얻는 것이 없고, `spring.threads.virtual`을 켜면
  `spring.task.scheduling.pool.size: 1`이 무시되어 두 `@Scheduled`의 겹침 방지가 사라진다.
- **메모를 `IndicatorContext`에 담는다** — 생성 지점 68곳이 함께 움직인다
  (`IndicatorRunner` javadoc).
- **메모이저를 `ConcurrentHashMap`으로** — 키가 (종목 × 창)이라 무한히 는다.
- **ADR-0054가 기각한 「`ThreadLocal`로 분석을 나른다」** — 그 기각 근거는 **"캐시 키가
  없어져"**다. 여기서는 `Key` 대조를 그대로 두고 소유만 옮기므로 그 근거에 걸리지 않는다.
  같은 ADR §규칙의 「캐시 키에 창의 양 끝과 봉 수를 함께 넣는다」도 그대로 선다.
  **번호만 보고 되돌리지 마라 — 다른 안이다.**
- **`ExecutorService.close()`(try-with-resources)** — 대기 상한이 1일이고, 호출 스레드가
  인터럽트되면 워커 전원을 인터럽트한다. 관리자 화면 경로의 잡은
  `applicationTaskExecutor` 풀 스레드에서 돌고 그 풀의
  `shutdown.await-termination`이 기본 `false`라 빈 파괴가 그 스레드를 인터럽트한다.
  ADR-0078이 인터럽트 축을 이미 기각했다.
- **문서로만 「풀보다 크게 잡지 마라」** — 결함에 우회를 처방하는 형태다. 클램프로 닫는다.
- **가용 코어를 전부 쓴다** — 이 기계에 운영 앱이 **온디바이스로 함께 산다**(2026-09-11 사용자
  판단). 8워커가 6워커보다 29% 빠르지만 유휴를 절반에서 1/3로 깎는다. 기본은 유휴 절반을
  남기는 쪽으로 두고, 상한은 그보다 위에 둔다.

## 결과

- **쉬워지는 것**: 결손 백필·리밸런싱 뒤 백필·정정공시 재백필이 전부 같은 배수만큼 짧아진다.
  「판정을 한 번에 다 끝내야 한다」는 제약이 그만큼 느슨해진다.
- **어려워지는 것**: 메모이저 캐시가 JVM당 1벌에서 **캐시를 만든 살아 있는 스레드 수만큼**이
  된다. 워커 풀은 잡마다 닫히지만 `applicationTaskExecutor` 코어 스레드와 `DailyUpdateJob`·
  스케줄러 스레드에는 남는다. **분석 하나의 크기는 재지 않았다.**
- **되돌리려면**: `IndicatorBackfillJob`의 팬아웃과 메모이저 둘을 함께 되돌린다. 메모이저만
  되돌리면 값은 맞는데 확장이 2.56배에서 막힌다.
- **다음 세션이 잴 거리**: `PipelineScheduler.AUTO_BACKFILL_STOCK_LIMIT = 10`의 근거가
  *"자동으로 시간 단위 작업을 시작하면 그날의 다른 스케줄이 전부 밀린다"*인데 그 시간이 줄면
  근거가 약해진다. 그 상수는 이미 *"재본 적이 없다"*로 적혀 있다.
