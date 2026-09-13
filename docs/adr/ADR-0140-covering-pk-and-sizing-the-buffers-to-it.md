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
- **MUST: 느려졌으면 최적화 전에 `relallvisible/relpages`와 `n_dead_tup`을 먼저 본다**
  (`ADR-0011` §검증이 가리키는 순서). **VACUUM 한 번이 전제 예산의 회귀분을 통째로 되돌렸다** —
  45초/45초·무판정 6 → 43초/45초·**무판정 0**.
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

**`premise` 펜스를 새로 만들지 않는다.** 전제 예산이 40초/45초이고 **그 예산이 이 결정이 고친
축 자체**다. `ADR-0045`의 블로트 펜스가 이미 이 자리를 재므로 **그것을 고쳐 썼다** — 인덱스
이름 목록을 `pg_indexes_size`로 바꾸고, 기준선을 재생성 직후 실측 **63 MB/백만행**으로,
임계를 그 2.5배인 **155**로 다시 유도했다.

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
| **`loadIndicatorValues`에서 `stock_id` 푸시다운을 뺀다** | 병합 PK에서 `indicator_code`·`stock_id`가 **둘 다 큰 배열**이면 플래너가 Index Cond를 `trade_date` 하나로 줄여 30,302버퍼가 된다. 빼면 4,083버퍼로 지금보다도 낫지만 **격차가 14.6ms 대 3.0ms라 앱을 고칠 근거가 안 된다.** 단일 종목 상세는 배열이 하나뿐이라 **0.149ms로 무해하다.** 수를 들고 넘긴다 |
| **`shared_buffers`만 올리고 스키마는 안 건드린다** | 부피가 1바이트도 안 준다. 그리고 **대형 순차 스캔은 링 버퍼(256 kB)를 써 `shared_buffers`를 안 탄다** — 전제 126개 중 `indicator_value`를 읽는 42개, 그중 날짜 술어가 없는 **19개**가 그 부류다 |
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

**닫힌 것은 회귀분이다.** 구조적 바닥은 그대로다 — 전제 126개 중 42개가 `indicator_value`를
읽고 **그중 19개에 `trade_date` 등치 술어가 없어 힙 3,689 MB를 훑는다.** 이 결정은 힙을
줄이지 않았고 링 버퍼 때문에 `shared_buffers`도 그 19개를 안 돕는다. **여는 것은 전제 질의에
날짜 술어를 넣는 별도 축이다.**

**바꾸기 전 설정값** — `shared_buffers` 128 MB · `effective_cache_size` 4 GB ·
`random_page_cost` 4 · `max_wal_size` 1 GB · `maintenance_work_mem` 64 MB ·
`log_temp_files` −1. 지금은 6 GB · 16 GB · 1.1 · 4 GB · 1 GB · 0이고, 원본은
`postgresql.conf.bak-20260913`과 같은 파일의 주석 블록에 있다. `log_temp_files = 0`은
**임시 파일의 귀속을 아직 못 찾아** 켜 둔 관측기다.

**MUST NOT: `pg_stat_database.temp_bytes`의 174 GB를 디스크 사용량으로 읽지 않는다.** 그것은
`stats_reset`이 빈 **생애 누적 카운터**이고, PostgreSQL은 질의가 끝나면 그 파일을 지운다 —
실측으로 `base/pgsql_tmp`가 **0개 · 0 B**다. **지울 것은 없다.** 그래도 재는 이유는 그 누적이
곧 **낭비된 I/O**이고 `work_mem`을 정할 근거이기 때문이다.

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
