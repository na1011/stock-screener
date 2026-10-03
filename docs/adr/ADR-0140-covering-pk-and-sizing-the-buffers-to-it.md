---
id: ADR-0140
status: 채택됨
date: 2026-09-13
applies_to:
  - "src/main/resources/db/migration/V51__indicator_value_covering_pk.sql"
  - "src/main/java/com/stockscreener/screening/query/ScreeningRepository.java"
  - "src/main/java/com/stockscreener/screening/query/ConditionTranslator.java"
  - "src/main/java/com/stockscreener/indicator/IndicatorValueWriter.java"
---

# ADR-0140. 커버링 인덱스를 PK에 접고, 줄어든 워킹셋에 버퍼를 맞춘다

## 결정

**층이 넷이고 순서가 결과를 바꾼다.** 하나씩 따로 돌려 몫을 갈라 쟀다.

1. **`VACUUM (ANALYZE)`를 먼저 단독으로 돌린다.** `allvisfrac`이 0.349였고 그 하나가 힙 페치
   22%, 화면 질의의 플래너 선택, 전제 예산을 동시에 쥐고 있었다.
2. **`REINDEX`로 블로트를 걷는다.** 인덱스 9,135 → 4,995 MB.
3. **`idx_iv_screen`을 PK에 접는다** — `PRIMARY KEY (trade_date, indicator_code, stock_id)
   INCLUDE (value)`. 인덱스 4,995 → 3,097 MB이고 **쓰기 경로가 3개에서 2개**가 된다.
4. **`postgresql.conf`를 그 줄어든 워킹셋에서 유도한다.** `shared_buffers` 128 MB → **6 GB**
   외 다섯.

**병합만 따로 보면 백필을 10% 되돌린다**(PK가 2,118 → 2,759 MB로 커진다). 그래도 채택한
이유는 **④가 덮어야 할 총량을 8,684 → 6,786 MB로 줄여 6 GB 한 장으로 전부 들어가게 만들기
때문**이고, 그 결과가 §결과의 대조군 ④다. **순서를 뒤집으면 같은 효과에 메모리가 두 배 든다.**

## 규칙

- **MUST: 이 넷의 몫을 뭉뚱그리지 않는다.** `ADR-0045`가 *"MUST NOT: 그 축소를 이 결정의
  성과로 읽지 않는다 — 줄인 것은 REINDEX이고 … 귀속은 그렇게 둘로 나눠야 성립한다"*를 이미
  적었다. §결과의 표가 그 이행이다.
- **MUST: 느려졌으면 최적화 전에 가시성 맵을 먼저 본다 — `pgstattuple_approx('indicator_value')`의
  `scanned_percent`로**(`ADR-0011` §검증이 가리키는 순서). **VACUUM 한 번이 전제 예산의 회귀분을 통째로
  되돌렸다** — 45초/45초·무판정 6 → 43초/45초·**무판정 0**. **`relallvisible/relpages`·`n_dead_tup`으로
  재지 않는다** — VACUUM·ANALYZE 때만 갱신되는 대리값이라 2026-10-03에 1.000·48.5만으로 「멀쩡」을
  가리켰는데 실제 not-all-visible은 **27.8%**였다. 그 상태를 매 평일 만드는 것은 보존 정리의 날짜
  DELETE다(힙이 종목 순이라 국내 하루 58,963행이 힙의 6.8%에 흩어져 있다).
- **MUST NOT: PK에서 `INCLUDE (value)`를 떼지 않는다.** 떼면 `(trade_date, indicator_code)`
  슬라이스마다 약 1,500건의 무작위 힙 페치가 되살아난다. 그 슬라이스를 읽는 것이
  `PercentileCalculator`·`SectorRelativeCalculator`이고 **백필의 날짜마다 코드 수만큼 돈다.**
- **MUST NOT: `value`를 인덱스에서 완전히 빼서 HOT 갱신을 되살리려 하지 않는다.** HOT이 되려면
  `value`가 **어느 인덱스에도** 없어야 하는데(실측: fillfactor 70에서 `INCLUDE`에 있어도
  `hot_upd` 0), 그러면 위 힙 페치를 사는 것이다. **`value`를 정확히 하나의 인덱스에만 두는
  것**이 이 워크로드의 답이다.
- **MUST: 표 단위 autovacuum 임계를 병합과 한 마이그레이션에 둔다.** `INCLUDE`가 붙으면 B-tree
  중복 제거가 꺼지고(`bt_metap.allequalimage` **t → f**, 실측) 그것이 8,530만 갱신의 버전
  churn을 흡수하던 기제다. 자주 도는 autovacuum이 그 상실의 상쇄 수단이다.
- **MUST NOT: 일부러 상한을 푸는 psql을 별칭 경로로 부르지 않는다** — `ADR-0120` §규칙을 이
  결정이 정정했다. `command psql`을 쓴다.
- **MUST: 인덱스를 다시 지을 때 `maintenance_work_mem`을 세션에서 올린다.** 기본 64 MB면 외부
  병합 정렬로 흐른다. 실측으로 2 GB에서 세 인덱스 전부가 **127초**다.

## 검증

```bash
psql -d stock_screener -c "\d indicator_value"
# → "indicator_value_pkey" PRIMARY KEY, btree (trade_date, indicator_code, stock_id) INCLUDE (value)
#   "idx_iv_stock_date" btree (stock_id, trade_date)          ← 둘뿐이다
./gradlew test --tests "*Indicator*" --tests "*Screening*"
```

**이 결정이 검증된 지점을 펜스로 묻는다**(2026-10-03). 처음에는 「전제 예산이 40초/45초이고 그 예산이 이
결정이 고친 축 자체」라 펜스를 안 만들고 `ADR-0045`의 블로트 펜스에 맡겼는데, **그 펜스는 행당 인덱스 크기라
행이 늘거나 힙이 부푸는 것을 원리적으로 못 본다.** 그 사이 표 총이 6,788 → 9,316MB가 됐고 그중 힙이
3,689 → 5,004MB(+36%)인데 행은 약 5%만 늘었다 — `pgstattuple_approx`의 `approx_free_percent` 27.3%,
살아 있는 힙은 검증 당시와 거의 같다. **즉 이 결정의 전제가 힙 블로트로 무너져 있었다.** 펜스는 지금
발견을 내고, 그것이 가시성 맵 작업(보존 정리의 날짜 DELETE가 만든다)의 입력이다.

```premise 표 총이 이 결정이 검증된 shared_buffers 비 안에 있다
# 이 결정이 검증된 지점은 「표 총」(힙 + 인덱스)이 shared_buffers의 1.105배였을 때다(병합 뒤 6,788MB
# 대 6,144MB · 그 상태에서 둘째 판의 블록 읽기가 0이었다). 「다 들어간다」는 경계가 아니라 그 지점이다.
# 힙을 넣는 이유: 쓰기 경로(UPSERT의 IS DISTINCT FROM · DELETE_UNWRITTEN)는 VM과 무관하게 힙을 읽고,
# 읽기 경로도 VM이 낡으면 힙을 읽는다.
psql -d stock_screener -At -c "WITH x AS (SELECT pg_relation_size('indicator_value') heap, pg_indexes_size('indicator_value') idx, pg_size_bytes(current_setting('shared_buffers')) buf)
SELECT CASE
  WHEN (SELECT heap FROM x) = 0 THEN '잴 것이 없었다 — indicator_value가 비어 있다'
  WHEN (SELECT heap + idx FROM x) <= 1.105 * (SELECT buf FROM x)
    THEN 'OK 표 총 ' || pg_size_pretty((SELECT heap + idx FROM x)) || '(힙 ' || pg_size_pretty((SELECT heap FROM x)) || ' · 인덱스 ' || pg_size_pretty((SELECT idx FROM x)) || ')가 shared_buffers ' || pg_size_pretty((SELECT buf FROM x)) || '의 ' || round((SELECT (heap + idx)::numeric / buf FROM x), 2) || '배 — 결정이 검증된 1.105배 안이다'
  ELSE '표 총 ' || pg_size_pretty((SELECT heap + idx FROM x)) || '(힙 ' || pg_size_pretty((SELECT heap FROM x)) || ' · 인덱스 ' || pg_size_pretty((SELECT idx FROM x)) || ')가 shared_buffers ' || pg_size_pretty((SELECT buf FROM x)) || '의 ' || round((SELECT (heap + idx)::numeric / buf FROM x), 2) || '배다 — 이 결정이 검증된 1.105배(6,788MB 대 6,144MB)를 넘었다. 행이 는 것인지 블로트인지 먼저 가른다: 인덱스는 ADR-0045 펜스, 힙은 pgstattuple_approx의 approx_free_percent'
END"
```

`ADR-0045`의 블로트 펜스는 그대로 둔다 — 그쪽은 인덱스 밀도(재생성 기준선 63 → 지금 83 MB/백만행)를,
이쪽은 버퍼 대비 부피를 묻는다. 둘은 다른 축이다.

## 배경

긴 백필이 뒤로 갈수록 느려졌다(2026-09-13 · id 389 · 318종목 × 270거래일 · 91분 22초 ·
일자당 7.2초 → 29.7초). **저장 행수는 평평했고** 곡선은 선형 증가 뒤 포화였다. 그 대가로
전제 예산이 38 → 45초로 차서 두 펜스가 **무판정**이 됐다 — 무판정은 침묵과 구분되지 않는다.

**원인을 대조군이 갈랐다.** 318종목 × **단일 날짜** 백필을 돌리니 **저장 0행인데 389.5초**가
걸렸고, 그동안 `indicator_value_pkey`를 **13,673,840회** 탐침하며 블록 18.6M개(약 151 GB)를
읽었다. 13,673,840 ≈ **318종목 × 255보존일 × 169코드**다 — `IndicatorRunner`가 종목마다 보존
창 전체에 행 단위 UPSERT를 던지고, `ON CONFLICT … WHERE value IS DISTINCT FROM`이 탐침마다
힙을 읽는다. **즉 병목은 계산도 배리어도 아니라 무작위 읽기였고, PK 2,118 MB + 힙 3,689 MB가
`shared_buffers` 128 MB를 넘는 것이 그 무작위 읽기를 디스크로 보냈다.**

**`idx_iv_screen`은 그 경로에 없다** — 같은 실행에서 스캔 **44회**다. 그래서 이 결정에서
병합이 겨누는 것은 백필의 읽기가 아니라 **부피와, ④가 덮어야 할 총량**이다.

## 버린 대안

| 대안 | 왜 버렸나 |
|---|---|
| **`idx_iv_screen`을 그대로 두고 REINDEX만** | 부피 −45%에서 멈춘다. 그러면 화면 경로까지 덮는 데 총 8,684 MB가 필요해 `shared_buffers`가 8~10 GB로 커지고, 그것이 온디바이스 앱과 나누는 24 GB에서 곧 비용이다 |
| **`value`를 모든 인덱스에서 빼고 fillfactor를 낮춰 HOT을 되살린다** | 파생층이 (date, code)마다 무작위 힙 페치 1,500건이 된다. 실측으로 `INCLUDE`에 있어도 `hot_upd`는 0이므로 **절반만 해서는 아무것도 못 얻는다** |
| **`loadIndicatorValues`에서 `stock_id` 푸시다운을 뺀다** | 병합 PK에서 `indicator_code`·`stock_id`가 **둘 다 큰 배열**이면 플래너가 Index Cond를 `trade_date` 하나로 줄여 30,302버퍼가 된다. 빼면 4,083버퍼 · 3.0ms로 지금(30,302버퍼 · 14.6ms)보다 낫다. **그러나 무조건 빼면 작은 목록 호출부가 대가를 치른다** — `StockController`의 단일 종목이 **47버퍼 · 9행 · 0.41ms**에서 **2,541버퍼 · 13,057행 · 5.29ms(13배)**가 된다(실측). 즉 크기 분기가 필요한데 **표시값 쪽 이득이 11.6ms뿐이라 그 분기를 살 근거가 안 된다.** 여는 조건은 표시값이 병목으로 드러나는 때다 |
| **`shared_buffers`만 올리고 스키마는 안 건드린다** | 부피가 1바이트도 안 준다. 그리고 **대형 순차 스캔은 링 버퍼(256 kB)를 써 `shared_buffers`를 안 탄다** — `indicator_value`를 읽는 전제 **42개**가 그 부류다. **기각은 그대로 선다**(링 버퍼 논거는 수와 무관하다) |
| **보존 창·지표 코드 수를 줄여 부피를 줄인다** | `ADR-0129`가 창을 **넓히기로** 했고, 코드 수는 `ADR-0077`이 실측 56.1%로 기각했다 |
| **`indicator_value`를 `trade_date`로 파티셔닝한다** | 보존 정리가 `DROP PARTITION`이 되어 매력적이지만, **백필이 종목마다 보존 창 전체를 쓰므로 워킹셋이 안 줄고** 이 결정이 겨눈 축에 안 닿는다. 폭발 반경도 훨씬 크다 |

## 결과

**부피 — 층별로 갈라 적는다**

| 단계 | 힙 | 인덱스 | 표 총 | DB |
|---|---|---|---|---|
| 시작 | 3,689 MB | **9,135 MB** | 12.5 GB | **13.1 GB** |
| VACUUM 뒤 | 3,689 | 9,136 (**안 준다**) | 12.5 GB | 13.1 GB |
| REINDEX 뒤 | 3,689 | **4,995** (−45.3%) | 8,685 MB | 9,321 MB |
| 병합 뒤 | 3,689 | **3,097** (−38.0%) | **6,788 MB** | **7,424 MB** |

**DB 13.1 GB → 7,424 MB (−43.3%).** 그중 **REINDEX가 4,140 MB, 병합이 1,898 MB**다.
인덱스 밀도는 **186 → 102 → 63 MB/백만행**(REINDEX 직후 102가 `ADR-0045`의 옛 기준선 101과
1% 안에서 맞는다 — 그 기준선은 건전했다).

**속도 — 대조군 넷.** 318종목 고정 목록 × 2026-09-10 단일 날짜 × 6워커. **네 판 모두 저장 0행**
이므로 「값이 달라서 느렸다」가 섞이지 않는다(워밍업 한 판을 먼저 돌렸다).

| 판 | 배치 소요 | PK 블록읽기 | 힙 블록읽기 |
|---|---|---|---|
| ① VACUUM 뒤·블로트 有 | **389.5초** | 8,136,421 | 10,515,901 |
| ② REINDEX 뒤 | 111.8 / 102.5초 | 6,901,813 / 7,393,586 | 10,037,386 |
| ③ 병합 뒤 | 111.9 / 123.7초 | 8,729,641 / 7,930,758 | 10,052,138 |
| ④ 설정 뒤 | 78.9 / **60.5초** | 352,892 / **0** | 352,821 / **0** |

**389.5초 → 60.5초(6.4배).** 귀속은 **REINDEX가 3.5~3.8배, 병합이 −10%, 설정이 1.7~1.9배**다.
④의 둘째 판에서 **블록 읽기가 양쪽 다 0** — 워킹셋이 통째로 `shared_buffers` 안에 들어왔다.

**화면 — 퇴행한 자리와 안 한 자리**

| 경로 | 시작 | 최종 |
|---|---|---|
| 파생층 1코드 | 670버퍼 · 힙페치 342 | 309버퍼 · **0** |
| 다조건 FILTER 화면(5조건) | 3,376버퍼 · 6.4ms | 4,192버퍼 · **4.6ms** |
| 표시값 1,350종목 × 15코드 | 8,827버퍼 · 9.1ms | 30,302버퍼 · **14.6ms**(전부 캐시 히트) |
| 단일 종목 상세 | — | 47버퍼 · **0.149ms** |

**화면을 브라우저로 직접 몰아 확인했다**(2026-09-13 · 배포 뒤). `버핏 우량주`를 코스닥 시총
상위 150에 돌려 **9종목**, 같은 프리셋을 미국 시총 상위 1,000에 돌려 상위 9종목이 점수·달성률·
지표값과 함께 떴고 임계가 USD로 갈렸다. 콘솔 오류 0건. **시험만으로는 배선이 안 잡힌다.**

**표시값 하나가 2배 느려졌고 그것이 이 결정이 산 유일한 퇴행이다.** 원인은 §버린 대안의
푸시다운 항목에 있고, 여는 길과 그 수도 거기 적었다.

**전제 예산 — 이 결정이 고치려던 손해**

| | 참 | 발견 | 판정 불가 | 예산 |
|---|---|---|---|---|
| 시작 | 107 | 12 | **6** | **45초/45초 소진** |
| VACUUM 뒤 | 111 | 14 | **0** | 43초/45초 |
| 최종 | 111 | 14 | **0** | **40초/45초** |

**닫힌 것은 회귀분이다.** 구조적 바닥은 그대로다 — `indicator_value`를 읽는 전제가 **42개**이고
**합계 18.7초**다(2026-09-13 실측 · 스윕 전체 36.9초의 51%). 이 결정은 힙을 줄이지 않았고
링 버퍼 때문에 `shared_buffers`도 그것들을 안 돕는다.

**MUST NOT: 이 42개를 「날짜 술어가 없는 19개」로 가르지 않는다.** 그 분류는 `trade_date`라는
낱말을 문자열로 센 것이라 **상관 서브쿼리의 조인 조건까지 「날짜 술어」로 셌다** — 그래서 최다
소비자 `ADR-0080`(4.00초)이 「밖」으로 분류됐는데, 그 전제의 바깥 스캔에는 날짜 제한이 없다.
**여는 축은 날짜 술어가 아니라 전제마다의 모양이고, 처방이 갈린다** — 집합을 묻는데 행으로
훑는 것은 뜻을 안 바꾸고 재작성되지만(`ADR-0088`: 1.42 → 0.23초), 전 구간을 정말로 봐야 하는
것도 형태를 찾으면 준다 — `ADR-0080`은 그때 시도한 형태로 3,187 → 3,009 ms(6%)였지만
2026-10-03에 거래일 사다리 × (날짜, 짝)별 `EXCEPT`로 **창 전체가 1.2~1.3초**가 됐다. 모집단을 좁히는
것은 감시 범위를 줄이는 별개의 결정이다.

**바꾸기 전 설정값** — `shared_buffers` 128 MB · `effective_cache_size` 4 GB ·
`random_page_cost` 4 · `max_wal_size` 1 GB · `maintenance_work_mem` 64 MB ·
`log_temp_files` −1. 지금은 6 GB · 16 GB · 1.1 · 4 GB · 1 GB · 0이고, 원본은
`postgresql.conf.bak-20260913`과 같은 파일의 주석 블록에 있다. `work_mem`은 4 MB →
**64 MB**이고, `log_temp_files = 0`이 그 근거를 냈다(아래).

**MUST NOT: `pg_stat_database.temp_bytes`의 174 GB를 디스크 사용량으로 읽지 않는다.** 그것은
`stats_reset`이 빈 **생애 누적 카운터**이고, PostgreSQL은 질의가 끝나면 그 파일을 지운다 —
실측으로 `base/pgsql_tmp`가 **0개 · 0 B**다. **지울 것은 없다.**

**주인은 배치가 아니라 전제 스윕이었다.** `log_temp_files = 0`이 잡은 43건이 전부 한 시각에
몰려 있고 문장이 전제 질의다(`WITH cond AS (… FROM screen_condition …)` ·
`WITH act AS (SELECT DISTINCT m.stock_id FROM universe_membership …)`). **스윕 한 판이 43파일 ·
315 MB**를 흘렸고, 23,675 ÷ 43 ≈ 550판이 174 GB와 맞는다.

**`work_mem` 4 → 64 MB가 그것을 닫았다**(실측):

| | 전 | 후 |
|---|---|---|
| 스윕 한 판의 임시 파일 | 43건 · 315 MB | **1건 · 102.6 MB** |
| 스윕 소요 | 40초/45초 | **37초/45초** |
| 판정 | 참 111 · 발견 14 · 무판정 0 | 동일 |

**`log_temp_files = 0`은 진단이 끝났어도 남긴다** — 원인이 밝혀졌으니 걷는 것이 기본이지만
(`cleanup-after-diagnosis`), `work_mem` 64 MB 뒤 **스윕 한 판당 1줄**이라 소음이 없고 그 한 줄이
곧 **「`work_mem`이 다시 작아졌다」의 탐지기**다. 자료가 자라면 스윕이 다시 흘리기 시작한다.

**남은 하나는 `ADR-0050` 펜스의 윈도 함수**(`lag(close) OVER w` · `price_daily`를 술어 없이
윈도잉한다)이고 **107.5 MB**다. **`ADR-0126`이 아니다** — `log_temp_files = 0`이 남긴 줄의
statement가 그 질의이고(2026-09-13 확인), `ADR-0126`의 펜스는 `lag(iv.value) OVER (PARTITION
BY …)`이라 `close`가 없다. **즉 이 spill은 `indicator_value` 축이 아니다.** 닫으려면
`work_mem`이 128 MB 이상이어야 하는데, **세션당 한두 판을 위해 앱까지 걸리는 값을 두 배로
올릴 근거가 약해 64 MB에서 멈췄다.** 더 올릴 일이 생기면 이 수가 기준선이다.

**어려워지는 것**

- **`idx_iv_screen`이라는 이름이 사라졌다.** `ADR-0002` §규칙이 그 이름으로 MUST를 걸고 있었고
  `V2__indicator.sql:71-73`이 *"value까지 인덱스에 있어 범위 술어가 인덱스 안에서 끝난다"*를
  존재 이유로 적는데 **병합 PK는 그 성질을 승계하지 않는다**(value가 키에서 INCLUDE로 내려가
  Index Cond가 아니라 Filter가 된다). 실측 손해는 다조건 화면에서 **없었고**, 그 근거로
  `ADR-0002`를 개정했다. `V2`는 Flyway 체크섬 때문에 못 고치므로 V51의 `COMMENT ON INDEX`가
  그 자리를 진다.
- **`shared_buffers` 6 GB는 온디바이스 앱과 같은 24 GB에서 떼어 온 것이다**(실측 여유:
  `memory_pressure` 90% free · 앱 JVM RSS 446 MB). macOS에 `O_DIRECT`가 없어 페이지가
  OS 캐시에도 사본으로 남으므로 **올린 만큼 새 캐시가 생기는 것이 아니다.**
- **`pg_wal`이 1 GB에서 **3.9 GB**(248세그먼트)가 됐다** — `max_wal_size`를 올린 대가이고,
  **DB가 줄인 5.7 GB 중 2.9 GB를 도로 먹는다.** 데이터 디렉토리 실측은 14 GB(base 10 + wal 3.9)다.
  값은 하는 것으로 보인다 — 설정 변경 뒤 배포·빌드·테스트·백필 세 판 동안 **강제 체크포인트 0**
  (직전 같은 길이의 창은 59였다). **다만 두 창의 작업이 달라 깨끗한 대조가 아니다**(앞쪽에
  REINDEX가 있었다). **멱등 백필로는 못 잰다** — 저장 0행이라 WAL을 거의 안 만들어 체크포인트가
  아예 안 일어난다(실측 timed=0 · req=0).
- **중복 제거를 잃었다**(위 §규칙). 상쇄는 autovacuum 임계이고, 재발 감시는 `ADR-0045`의 펜스다.

**되돌리려면** — 데이터는 안 건드렸다.
```sql
CREATE INDEX idx_iv_screen ON indicator_value (trade_date, indicator_code, value) INCLUDE (stock_id);
CREATE UNIQUE INDEX iv_pk_plain ON indicator_value (trade_date, indicator_code, stock_id);
BEGIN; ALTER TABLE indicator_value DROP CONSTRAINT indicator_value_pkey;
ALTER TABLE indicator_value ADD CONSTRAINT indicator_value_pkey PRIMARY KEY USING INDEX iv_pk_plain; COMMIT;
ALTER TABLE indicator_value RESET (autovacuum_vacuum_scale_factor, autovacuum_vacuum_threshold,
  autovacuum_analyze_scale_factor, autovacuum_vacuum_insert_scale_factor);
```
설정은 `postgresql.conf`의 2026-09-13 블록을 지우고 재기동한다.
