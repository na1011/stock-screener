---
id: ADR-0135
status: 채택됨
date: 2026-09-09
applies_to:
  - "src/main/resources/db/migration/V50__universe_membership_origin.sql"
  - "src/main/java/com/stockscreener/pipeline/collector/UniverseLedgerRestorer.java"
  - "src/main/java/com/stockscreener/pipeline/batch/UniverseRestoreJob.java"
  - "src/main/java/com/stockscreener/pipeline/collector/JdbcStoredShareCounts.java"
  - "src/main/java/com/stockscreener/screening/query/ScreeningRepository.java"
---

# ADR-0135. 과거 편입을 원장의 둘째 평면으로 복원하고, 스크리닝의 유니버스 술어를 시점화한다

## 결정

**넷이다.**

1. **`universe_membership`에 `origin` 평면을 더한다**(V50). `OBSERVED`는 잡이 실제로 만든 편입,
   `RESTORED`는 그날의 소스로 사후 복원한 편입이다. **복원 행은 절대 열려 있지 않다**를
   `ck_restored_is_closed`로 못박는다.
2. **보존 창(255거래일) 안의 과거 편입을 주간 격자로 복원한다** — `--job=universe-restore`.
   격자는 **그 주의 마지막 거래일**이고, 구간은 **관측 하한에서 끊는다.**
3. **스크리닝의 유니버스 술어를 `[effective_from, effective_to)` 포함 판정으로 바꾼다.**
   `ScreeningRepository.currentlyIn`의 javadoc이 이 처방을 이미 적어 뒀고, 그것이 전제한
   *"과거 시점으로 되돌아가 조회하는 화면이 v1에 없다"*는 **이미 깨져 있다.**
4. **백분위·업종상대의 모집단도 같은 술어로 시점화한다**(사용자 결정, 2026-09-09).

> **사용자가 고른 문장(2026-09-09):** ①에 대해 *"'언제 실행해도 정확한 스크리닝을 보장한다' 의 취지에
> 부합하는 선택지는 무엇인지? 코드의 수정 비용이나 낡은 ADR에 사로잡히지 않고 오로지 정합성만
> 고려한다면?"*, 그리고 미국 미달 시 처리에 대해 *"비용과 타협한 선택은 거부함."*
> **그에 대한 내 해석 — 갈라 적는다:** 데이터가 없는 복원 구성원을 **편입 행으로 넣고 판정불가로 두며
> 그 수를 낸다**(뒤이어 데이터를 채우는 것이 온전한 답이지만, 채울 종목 수는 복원해 봐야 안다).
> **사용자가 닫은 것은 「정합성이 비용을 이긴다」이고**, 격자·저장 구조·합격선은 내 판단이다.
> 사용자가 함께 닫은 것 둘: **파생층 모집단도 시점화한다** · **복원 구간은 보존 창까지**.

## 규칙

- **MUST: 복원은 재무 적재와 기업행위 원장보다 뒤에 돌고, 그 둘이 바뀌면 다시 돈다.**
  **다시 도는 때는 시장별 관문의 한 번이다**(2026-10-04 사용자 결정 「시장별 관문」 · `CLAUDE.md` 「재적재·백필」 —
  복원 뒤 파생층 백필이 전체 백필이라 그 원칙에 묶인다 · 순서는 `docs/reload-gate.md` 10단계). 그 사이에 재무·원장이
  바뀐 것은 아래 펜스가 세고, 관문 전에 따로 다시 돌리지 않는다.
  복원의 순위는 `StoredShareCounts.activeByMarket`이 정하는데 그 질의가
  **`published_at <= asOf`**로 시점을 지키는 대신 **우리 DB에 그 시점 재무가 얼마나 들어와
  있는지**에 그대로 의존한다. 주식수에는 `exp(sum(ln(ca.ratio)))`로 기업행위 보정이 걸려 있어
  **원장이 비어 있으면 보정도 없다.**
  **실측(2026-09-14)**: 2026-09-10 복원 뒤에 미국 재무를 창 11년으로 재적재하고(정정 7,741 ·
  채움 1,623) 역분할 11종목이 원장에 들어온(2026-09-13) 상태에서 다시 돌리자
  **1,740행 중 1,119행(64%)이 달라졌다.** 즉 앞선 복원은 그만큼 부정확했다.
  **MUST NOT: 이것을 「복원이 틀렸다」로 읽지 않는다** — 복원은 그때 가진 것으로 옳게 지었고,
  **가진 것이 늘면 다시 지어야 하는 것**이 이 결정의 성질이다. 순서는
  **재적재 → `corporate-action` 사슬 → 복원 → 파생층 백필**이다.
- **MUST: 복원 뒤 파생층을 다시 만든다.** 백분위·업종상대의 모집단이 편입 명부로 시점화돼
  있으므로(§결정 4) 명부가 바뀌면 그 값이 낡는다. `--job=backfill --from=<보존 하한>
  --tickers=<하나>`가 날짜만 전 구간을 돌아 전 종목 파생층을 다시 낸다.
- **MUST: 복원 구간을 `origin = 'OBSERVED'`의 `min(effective_from)`에서 끊는다.** `origin` 조건이 빠지면
  재실행이 **자기 복원분을 하한으로 읽어** 격자가 통째로 빈다. 이것이 이 컬럼의 두 번째 존재 이유다.
- **MUST: 구간을 메모리에서 완성해 닫힌 채로 INSERT한다.** `ck_restored_is_closed`가 「열어 두고 나중에
  닫기」를 금지한다(PostgreSQL의 CHECK는 `DEFERRABLE`이 안 된다).
- **MUST: 쓰기 트랜잭션의 단위는 「유니버스 하나의 격자 전체」다.** 격자점 단위로 쪼개면 ADR-0008이
  *"부분 적용된 유니버스는 「제외만 되고 편입이 안 된」 중간 상태를 스크리닝에 노출한다"*로 기각한 것의
  **시간 축 판**이 된다 — 중간에 실패하면 일부 주만 복원된 유니버스가 화면에 실린다.
- **MUST: 쓰기를 옵트인으로 둔다(`--apply=true`).** 이 저장소의 CLI가 `Boolean.parseBoolean(value(args, …,
  "false"))`라 **맨 플래그는 `false`가 된다** — `--dry-run`으로 두면 오타 한 번이 되돌릴 수 없는 쓰기가 된다.
  새 불리언은 `BootstrapRunner.BOOLEAN_OPTIONS`에, 잡 이름은 `UNIVERSE_JOBS`에 함께 등록한다.
- **MUST: 페치를 트랜잭션 밖에 둔다.** KR 100콜·US 약 1,060콜(약 15분) 동안 표에 락이 걸리면 토요일 크론과
  겹친다. 그리고 **페치와 쓰기를 같은 프로세스에서 돈다** — KR 어댑터가 후보 전원의 이름을
  `SecurityNameCache`에 넣으므로 같은 프로세스면 `StockRegistrar`의 마스터 조회가 0콜이다.
- **복원이 만드는 `stock` 행의 `is_active`는 재보지 않았고 `true`로 둔다.** `resolveOne`이 `true` 리터럴로
  넣는데, 그중 이미 상장폐지된 종목을 가르려면 「오늘 상장 전종목」을 물어야 하고 **어댑터에 그 면이 없다.**
  **그 면을 만드는 것보다 안 만들고 사실을 적는 쪽을 골랐다** — 세 답이 이렇다:
  **무엇이 읽는가** = 스크리닝 · `StockRegistrar`의 대상 선정 다섯 · `JdbcStoredShareCounts` ·
  `StockReader`의 검색 ·
  **무엇이 틀리는가** = 폐지 종목이 활성으로 표시된다 ·
  **지금 닿는가** = **안 닿는다**(앞의 넷이 전부 편입 술어나 시장 축으로 그 종목을 먼저 뺀다 · 2026-09-09 실측).
  - **결함이 되는 조건**: `stock.is_active`를 **편입 술어 없이** 읽는 경로가 생기는 날. 그때 이 자리를 연다.
  - **MUST NOT: 대신 관례로 `false`를 박지 않는다.** 폐지 여부를 모르는 채 내리면 **아직 상장된 종목을
    비활성으로 만드는 것**이고, 그쪽이 지금 아무도 안 읽는 값을 틀리게 두는 것보다 나쁘다.
- **MUST: as-of 질의에서 `stock.active`를 걸지 않는다.** 편입 구간이 `asOf`를 덮는다는 것은 **그날 상위 N이었다는
  뜻이고, 그러면 그날 상장돼 있었다.** 오늘의 활성 여부로 과거를 거르면 이 결정이 지우려는 편향을 한 줄 뒤에
  그대로 되살린다. `is_active`는 「오늘」의 답으로만 남는다.
- **MUST: 유니버스 술어의 하한을 최신일 조회에서 접는다.** 토요일 리밸런싱이 `effective_from = 금요일`을 찍는데
  그 금요일 지표는 다음 평일에야 생기므로, 그 사이에는 `effective_from > asOf`인 **열린 행**이 있다.
  `effective_from <= asOf`를 그대로 걸면 **신규 편입이 조용히 사라진다**(실측: 지표 최신일이 2026-09-03이었다면
  47행). **펜스와 검증 질의를 양방향으로 만든다** — 한 방향만 세면 이 실패를 원리적으로 못 본다.
- **MUST NOT: 편입 하한 이전 요청에 빈 결과를 주지 않는다.** 400으로 접고 **무엇에 걸렸는지 말한다.**
  기존 예외를 문구째 재사용하면 *"지표는 최근 구간만 보존한다"*가 거짓말이 된다 — 그 경우 지표는 있다.
  슬러그와 `retentionFrom`/`retentionTo`는 유지하되 문구를 「조회 가능 구간」으로 일반화하고
  `boundBy`(`INDICATOR` | `MEMBERSHIP`)를 싣는다.
- **MUST NOT: 백테스트 하네스의 술어를 함께 바꾸지 않는다.** `ConditionReturnTest`의 앵커 여덟 중 **일곱이
  복원 창 밖**이라(2022-04 ~ 2025-10) 바꾸면 그 일곱에서 편입이 0건이 된다.
- **MUST NOT: 「복원했다」를 조건 없이 적지 않는다.** 미국은 `fetchActiveAssets()`가 오늘 목록이라 **폐지 종목이
  안 들어온다**(ADR-0171의 숨은 상장 보완은 오늘 SEC 파일에 남은 폐지 전 티커만 그 날짜 봉으로 받는다 — 결과가 실행 시각
  SEC 상태에 달린다). 「보존 창 안에서는 복원된다 / 그 밖과 미국 폐지 종목은 여전히 불가」로 조건을 명시한다.
  **그 구멍의 크기는 쟀다 — §결과의 22종목이다.**

### 사전등록한 합격선 — 돌리기 전에 박는다

**관측 구간을 복원으로 재생해 실제 원장과 견준다.** 격자가 아니라 **원장이 실제로 쓴 `effective_from`**으로
재생한다 — 그래야 대조가 격자 선택과 무관해져 「복원 오차」만 남는다.

| 축 | 합격선 |
|---|---|
| KR 재현율 `\|복원∩관측\| / \|관측\|` | **≥ 0.95** (소스가 라이브와 같은 1콜이고 그날 상장 전종목을 준다) |
| US 재현율 | **≥ 0.90** (오늘 자산 목록·오늘 재무 커버리지·오늘 CIK 캐시 셋이 양방향으로 샌다) |

**못 넘기면 ⓐ 원인을 갈라 고치고 다시 잰다 → ⓑ 그래도 안 되면 그 유니버스의 복원분을 안 쓴다.**
가르는 축은 **「놓친 종목의 복원 순위」**다 — 1,001~1,020위면 경계 잡음이고, 3,000위면 순위 축이 깨진 것이라
고칠 수 있는 결함이다. **합격선을 낮추는 갈래는 없다.**

**MUST: US 재현율에 표본 크기를 함께 적는다.** 관측 구간이 8일·3점뿐이라 KR의 6점보다 약한 증거다.
**「US가 0.90을 넘었다」를 「복원이 맞다」로 읽지 않는다.**

## 검증

```bash
./gradlew test --tests "*UniverseReconcilerTest*" --tests "*UniverseLedgerRestorerTest*" \
               --tests "*RestoredRowsAreClosedTest*" --tests "*UniverseMembershipAsOfPredicateTest*" \
               --tests "*MembershipPredicateParityTest*" --tests "*JdbcStoredShareCounts*" \
               --tests "*UsShareCountAsOf*" --tests "*ScreeningRepositoryTest*" \
               --tests "*PercentileCalculatorTest*" --tests "*BootstrapArgumentGuard*"
```

```premise 보존 창의 모든 거래일에 편입이 있다 — 복원이 살아 있는가
psql -d stock_screener -At -c "WITH d AS (SELECT u.code, itd.trade_date FROM universe u JOIN market mk ON mk.code = u.market_code JOIN indicator_trade_date itd ON itd.market_code = mk.code WHERE u.is_active), gap AS (SELECT d.code, count(*) n FROM d WHERE NOT EXISTS (SELECT 1 FROM universe_membership m WHERE m.universe_code = d.code AND m.effective_from <= d.trade_date AND (m.effective_to IS NULL OR m.effective_to > d.trade_date)) GROUP BY 1) SELECT CASE WHEN (SELECT count(*) FROM d) = 0 THEN '잴 것이 없었다 — 활성 유니버스와 지표 달력이 겹치는 거래일이 없다' WHEN (SELECT count(*) FROM universe_membership WHERE origin = 'RESTORED') = 0 THEN '잴 것이 없었다 — 복원 행이 하나도 없다(아직 안 돌렸거나 지워졌다)' WHEN (SELECT count(*) FROM gap) = 0 THEN 'OK 보존 창 ' || (SELECT count(*) FROM d) || '칸 전부에 그날의 편입이 있다' ELSE '편입이 0건인 거래일이 있다: ' || (SELECT string_agg(code || ' ' || n || '일', ', ' ORDER BY code) FROM gap) || ' — 과거 기준일 조회가 그 날짜에 빈 결과를 낸다. 복원분이 지워졌거나 보존 창이 복원 격자보다 앞으로 늘어난 것이다' END"
```

```premise 최신일에서 as-of 술어와 「지금 편입」이 같다 — 라이브 불변
psql -d stock_screener -At -c "WITH l AS (SELECT market_code, max(trade_date) t FROM indicator_trade_date GROUP BY 1), asof AS (SELECT m.universe_code, m.stock_id FROM universe_membership m JOIN universe u ON u.code = m.universe_code AND u.is_active JOIN market mk ON mk.code = u.market_code JOIN l ON l.market_code = mk.code WHERE m.effective_from <= l.t AND (m.effective_to IS NULL OR m.effective_to > l.t)), open AS (SELECT m.universe_code, m.stock_id FROM universe_membership m JOIN universe u ON u.code = m.universe_code AND u.is_active WHERE m.effective_to IS NULL), miss AS (SELECT * FROM open EXCEPT SELECT * FROM asof), extra AS (SELECT * FROM asof EXCEPT SELECT * FROM open) SELECT CASE WHEN (SELECT count(*) FROM open) = 0 THEN '잴 것이 없었다 — 활성 유니버스에 열린 편입이 없다' WHEN (SELECT count(*) FROM miss) = 0 AND (SELECT count(*) FROM extra) = 0 THEN 'OK 최신일에서 두 술어가 같다 (' || (SELECT count(*) FROM open) || '행)' ELSE '갈렸다 — as-of가 못 보는 열린 행 ' || (SELECT count(*) FROM miss) || ' · as-of에만 있는 행 ' || (SELECT count(*) FROM extra) || '. 앞의 것은 편입일이 지표 최신일보다 뒤인 경우이고(토요일 리밸런싱 ~ 다음 평일 지표 사이), 그 창에서 신규 편입이 화면에서 사라진다' END"
```

**둘 다 양방향이다.** 아래 것은 첫 판이 `EXCEPT` 한 방향이라 주말 창의 실패를 원리적으로 못 봤다 —
`miss`와 `extra`를 갈라 세는 것이 그 수정이다.

**전제 예산이 45/45초라 새로 더하지 않고 갈아끼운다** — `ADR-0071`의 `시점 유니버스가 DB에 여전히 없다`가
이 결정으로 뜻을 잃으므로 그 자리를 위 첫 펜스가 받는다.

```premise 복원이 재무·원장보다 먼저 돌아 낡지 않았다
psql -d stock_screener -At -c "
WITH r AS (
  -- 유니버스마다 복원 시각이 다르다. 하나로 접으면 가장 오래된 것이 전부를 낡은 것으로 만든다.
  -- grid_to는 복원 격자의 끝이다. 그 뒤에 공시·효력이 생긴 재료는 어느 격자 날짜에도 안 보인다
  -- (StoredShareCounts가 published_at과 effective_date를 asOf 이하로 자른다). 안 자르면 실적 시즌의
  -- 새 분기 보고서가 복원과 무관한데도 이 펜스를 붉힌다(2026-10-06 실측: 그날 US 52행은 전부 격자 안 · 빠지는 행 0).
  SELECT universe_code uc, min(created_at) restored_at, max(effective_to) grid_to FROM universe_membership
  WHERE origin = 'RESTORED' GROUP BY 1),
mk AS (SELECT u.code uc, u.market_code mc FROM universe u),
stale AS (
  SELECT r.uc, r.restored_at::date d,
    -- 복원의 순위 재료가 그 시점 재무(StoredShareCounts)와 기업행위 원장(주식수 보정)이다.
    -- 그 둘이 복원 뒤에 늘면 같은 asOf로 다시 지어도 다른 명부가 나온다.
    --
    -- MUST: shares_outstanding이 있는 행만 센다. StoredShareCounts의 질의가
    -- 「f.shares_outstanding IS NOT NULL AND > 0」을 걸고, 국내 재무는 그 컬럼이 전량 NULL이다
    -- MUST NOT: 이 주석에 역따옴표를 쓰지 않는다 — psql 인자가 큰따옴표라 bash가 그 안을
    -- 명령 치환으로 실행한다. 실제로 위 구절의 부등호가 저장소 루트에 0이라는 이름의 파일을
    -- 만들고 있었다(2026-09-23 생성 · 세션마다 다시 잘렸다). 전제 145개 중 이 자리 하나였다.
    -- (2026-09-20 실측 KOSPI 10,175행 중 0). 그래서 안 좁히면 국내가 원리적으로 오탐한다 —
    -- 실제로 2026-09-20에 종목 001820의 FY2015 Q4 한 행이 KOSPI_TOP200을 낡은 것으로 신고했는데,
    -- 그 행은 주식수를 안 주고 복원 격자 상한(2026-08-05)보다 11년 앞이라 순위에 닿을 길이 없었다.
    -- 소비자 전수로도 갈린다: StoredShareCounts를 읽는 유니버스 어댑터는 UsMarketCapUniverseAdapter
    -- 하나이고 국내 MarketCapUniverseAdapter는 0건이다(소스가 시총을 직접 준다).
    (SELECT count(*) FROM financial_statement f JOIN stock s ON s.id = f.stock_id
     WHERE s.market_code = mk.mc AND f.created_at > r.restored_at
       AND f.shares_outstanding IS NOT NULL AND f.published_at <= r.grid_to) fin,
    -- 사정거리: 복원 뒤에 생긴 행만 센다. 재무에 갱신 시각 컬럼이 없어 옛 행을 제자리에서 채운 것(FILL_MISSING이
    -- 주식수를 채운 행)은 못 센다 — 그래서 fin은 하한이다. 판정이 옛 행의 상태를 APPLIED로 바꾼 것도 못 센다
    -- (2026-10-05 HON 2026-06-29 병합 0.5 · 국내 인적분할 5행). 그날 HON의 고친 시총 순위는
    -- 168~175이고 경계(1,000위)가 약 55억 달러라 미국 명부는 안 바뀐다. 국내 복원은 원장을
    -- 안 읽는다(원장 보정은 JdbcStoredShareCounts 안에 있고 그것을 읽는 것은 미국 어댑터 하나다).
    (SELECT count(*) FROM corporate_action ca JOIN stock s ON s.id = ca.stock_id
     WHERE s.market_code = mk.mc AND ca.status = 'APPLIED'
       AND ca.ratio IS NOT NULL AND ca.ratio <> 1 AND ca.created_at > r.restored_at
       AND ca.effective_date <= r.grid_to) ledger,
    -- 미국 관문(V70) 뒤에는 클래스 축 판정도 순위 재료다(보류 · 숨김 · N_L). 복원 뒤에 다시 유도된 판정을 센다. 판정 표가
    -- 없는 DB(관문 전)에서는 0 — query_to_xml이 그 갈래에서만 표 이름을 푼다(없는 표를 글자로 부르면 질의가 통째로 선다).
    CASE WHEN mk.mc <> 'US' OR to_regclass('public.financial_share_class_filing') IS NULL THEN 0
         ELSE ((xpath('/row/c/text()', query_to_xml(format(
             'SELECT count(*) AS c FROM financial_share_class_filing v WHERE v.derived_at > %L AND v.filed <= %L',
             r.restored_at, r.grid_to), false, true, '')))[1])::text::int END cls
  FROM r JOIN mk ON mk.uc = r.uc)
SELECT CASE
  WHEN (SELECT count(*) FROM r) = 0 THEN '잴 것이 없었다 — 복원 행이 하나도 없다'
  WHEN (SELECT count(*) FROM stale WHERE fin > 0 OR ledger > 0 OR cls > 0) = 0
    THEN 'OK 복원 ' || (SELECT count(*) FROM r) || '곳 전부 그 뒤에 들어온 재무·원장·판정이 없다'
  ELSE (SELECT string_agg(uc || '(' || d || ' 뒤 재무 ' || fin || '행·원장 ' || ledger || '건·판정 ' || cls || '행)', ', ' ORDER BY uc)
        FROM stale WHERE fin > 0 OR ledger > 0 OR cls > 0)
       || ' — 복원의 순위 재료(재무 · 원장 · 미국 관문 뒤 클래스 축 판정)가 바뀌어 과거 명부가 실제와 갈린다. 관문 10단계의 복원이 닫는다 — '
       || '관문 전에 universe-restore와 파생층 백필을 따로 돌리지 않는다(§규칙).'
  END"
```

## 배경

과거 기준일 조회는 **지표만 그날 것이고 대상은 오늘 명부**였다. `ScreeningRepository.selectStockIds`가 `asOf`를
조건 술어에는 넘기고 유니버스 술어에는 안 넘긴다. 어긋남은 실측돼 있다(ADR-0129 §유니버스 축의 크기) —
2025-09-05 복원 명부 대비 `KOSPI_TOP200` **10.0%** · `KOSDAQ_TOP150` **26.7%**가 어긋났다.

막고 있던 것은 **이력의 길이 하나**였다(KR 2026-08-05 · US 2026-08-31부터). 그리고 그것을 막던 두 서술이
**재보니 틀렸다.**

- **`RETENTION_BAND = 1.1`은 경로 의존을 만들지 않는다.** `MarketCapUniverseAdapter.select()`는 순수 상위 N을
  반환하고 그 상수는 `log.debug` 한 줄에만 쓰인다(도입 커밋 이후 변경 0건). **낡은 게 아니라 처음부터 그랬다.**
  같은 문장이 일곱 곳에 있었고 그중 하나는 **상수 자신의 javadoc**이었다.
- **GiST가 막지 않는다.** `daterange`가 `[)` 반열림이라 `[…, 하한)`과 `[하한, ∞)`은 **인접이지 겹침이 아니다**
  (실제 제약을 건 임시 표로 확인 — 인접 통과 · 1일 겹침 거절). `effective_from`을 뒤로 늘리는 UPDATE도,
  `uq_membership` 키 변경도, ADR-0071 premise 이동도 일어나지 않는다.

**실제 경로 의존은 넷이고 자리가 다르다** — ① 실행일을 `effective_from`으로 찍고 주 1회만 돈다
② `MAX_REMOVAL_RATIO` 거부 ③ 같은 날 재실행 DELETE 갈래 ④ **미국 `fetchActiveAssets()`가 오늘의 목록**이다(숨은 상장
보완도 오늘의 SEC 파일을 본다 · ADR-0171).
①~③은 격자를 원장의 실제 격자에 맞추고 같은 가드를 태워 재현하고, ④는 재현되지 않는다.

## 버린 대안

| 대안 | 왜 버렸나 |
|---|---|
| **복원분을 별도 표에 둔다** | `ex_membership_no_overlap`이 **표 하나 안에서만** 겹침을 막는다. 나누면 두 평면 사이 겹침을 DB가 못 보고, 읽기 술어 사본이 25 → 50이 된다 |
| **표시 없이 같은 표에 넣는다** | ADR-0012·0086 §버린 대안이 「한 코드의 원장에 두 정의를 구분 없이 섞는 것」을 이미 기각했다. 되돌리기도 불가능해진다 |
| **일 단위로 복원한다** | **이 시스템이 한 번도 가진 적 없는 명부를 지어낸다.** 유니버스의 정의가 「우리 리밸런서가 그날 골랐을 상위 N」이고 그 잡은 주 1회 돈다. 정의보다 **정밀한 오답**이고, 관측 하한에 **우리가 만든 회전율 계단**을 남긴다. 콜이 5배인 것은 부수다 |
| **`UniverseReconciler`를 격자마다 부른다** | 그 안의 어려운 부분(「그날 열린 행은 닫지 말고 지운다」)은 **같은 날 재실행**이 만든 것인데 복원은 격자 전체를 미리 안다. 게다가 `ck_restored_is_closed`가 「열어 두고 닫기」를 금지한다. 대신 제외 비율 가드 상수를 열어 **상수 하나에 독자 둘**로 둔다 |
| **`effective_from`을 뒤로 늘리는 UPDATE** | `progress.md`가 처방으로 적어 뒀는데 **전제가 틀렸다**(§배경). 인접은 겹침이 아니다 |
| **미국 재현율이 낮으면 국내도 안 한다** | **대칭은 판단 기준이 아니다.** `CLAUDE.md`가 *"한 결과에 두 시장을 섞지 않는다"*로 답의 단위를 시장별로 못박았으므로 국내 답의 정합성은 미국 답에 의존하지 않는다. 그리고 접는 쪽이 정합성에 **이긴다** — 지금은 틀린 명부로 200을 답하고, 접으면 「그 날짜는 답할 수 없다」고 말한다 |
| **합격선을 낮추고 오차를 함께 싣는다** | 결과를 본 뒤 기준을 옮기는 것이라 `phase-criteria.md`가 **기준 포기**로 부르는 형태다 |
| **`--dry-run` 플래그** | 맨 불리언 플래그가 `false`가 되어 **조용히 쓴다**(`BootstrapRunner`가 그 함정을 이미 적어 뒀다). 되돌릴 수 없는 잡이라 기본을 dry-run으로 두고 쓰기를 옵트인한다 |
| **백테스트 하네스도 함께 시점화한다** | 앵커 여덟 중 **일곱이 복원 창 밖**이라 그 시점들이 0건이 된다. 넘기려면 복원 창을 백테스트 창까지 넓혀야 하고 그것은 P17 축이다 |

**이 결정은 `ADR-0128:112` §버린 대안이 기각한 「유니버스 편입도 시점화한다」를 다시 여는 것이다.**
기각 사유는 **「저장된 이력이 짧아서」 하나**이고 이 결정이 없애는 것이 정확히 그것이다. 그리고 그 칸이
**재개 경로를 스스로 지목했다**(*"MUST NOT: 그것을 「소스가 없다」로 읽지 않는다 — 국내는 `basDt`만 준
전종목 조회 1콜로 그날 명부를 복원할 수 있다"*). **기각이 든 위험은 그대로 유효하다** — 그 문서가 든
「1,000/1,350 종목이 176일자에서 0건」이 실측으로 「보존 창 255일 중 KR 231·US 249일이 편입 0건」이고,
**편입 하한 인터록이 그것을 0건이 아니라 400으로 바꾸는 것이 이 위험에 대한 답이다.**

**`ADR-0036:62`의 MUST가 그 위에 선다** — *"시점 조인으로 바꾸기 전에 `min(effective_from)`이 지표 보존
구간을 덮는지 먼저 확인한다. 지금 바꾸면 검사 범위가 229거래일에서 2거래일로 줄고 그러고도 OK가 뜬다."*
그래서 파생층 시점화는 **복원 뒤**이고, ADR-0036의 premise에 **「검사한 거래일 수」를 넣어** 범위가 줄어드는
것이 세션 시작에 보이게 한다.

## 결과

### as-of 술어가 최신일에서 갈리는 것은 결함이 아니다 (2026-09-20)

「as-of가 못 보는 열린 행 20」이 뜨면 **토요일 리밸런싱과 다음 평일 지표 계산 사이**다.
그 20종목은 **그날 편입이 아니었으므로 그날 지표가 없는 것이 맞다** — 실측으로 편입일 ≤ 최신
지표일인 **1,330종목은 최신일 지표 0행이 0건**이고, 0행인 20은 전부 편입일(09-18)이 최신
지표일(09-17)보다 뒤다.

**MUST NOT: 이것을 결손으로 읽고 그 날짜를 백필하지 마라.** 그날 모집단에 없던 종목의 파생
행을 만드는 것이고, `batch-load` skill의 「주말 리밸런싱 기준일을 백필하지 마라」와 같은 자리다.
**2026-09-20에 나와 독립반증 둘이 이것을 「전원이 프리셋에서 조용히 빠진 상태」로 읽었다** —
수는 셋 다 맞게 쟀고 **시점 조건을 안 걸어 해석이 틀렸다.** 편입 축을 잴 때는
`joined <= hi`를 먼저 건다.


### 재생 대조 — 사전등록한 합격선을 두 시장 다 넘었다 (2026-09-09)

원장이 **실제로 쓴 `effective_from`**을 그대로 재생해 견줬다(KR 6점 × 2 · US 3점 · 약 72콜).

| 유니버스 | 최저 | 평균 | 합격선 | 판정 |
|---|---|---|---|---|
| `KOSPI_TOP200` | 0.9900 | **0.9950** | 0.95 | 통과 |
| `KOSDAQ_TOP150` | 0.9667 | **0.9844** | 0.95 | 통과 |
| `US_TOP1000` | 0.9690 | **0.9873** | 0.90 | 통과 |

**어긋난 것의 성질을 갈랐다.** 국내에서 어긋난 여덟 종목은 시총 **1.37~2.87조**로 진입선
**1.286조** 언저리다 — **경계 잡음이지 순위 축 파손이 아니다**(파손이면 3,000위대가 나온다).
미국은 2026-08-31이 0.9690으로 가장 낮고 2026-09-07이 **1.0000**인데, 그 차가 `AMZN`·`JNJ`·
`ILMN` 같은 이름이라 **ADR-0124가 그 뒤에 연 재무 커버리지가 오늘 기준으로 새어 든 것**이다.
**최신 날짜로 갈수록 사라지므로 복원 오차가 아니다.**

**US 표본이 3점뿐이라는 것을 함께 적는다** — 관측 구간이 8일이다. **「0.90을 넘었다」를
「복원이 맞다」로 읽지 않는다.**

### 미국 폐지 종목 구멍 — 22종목 (2026-09-09)

**「원리적으로 못 한다」가 아니었다.** `/v2/assets`에서 `status`를 빼면 비활성이 함께 오고,
**봉의 존재가 그날 거래됐다는 증거**라 상장 여부는 유도된다. 9콜로 쟀다(`alpaca-universe.md`
§비활성 종목의 봉에 소스 계약과 함정을 옮겼다).

- 비활성 2,164 → 물어볼 수 있는 심볼 1,522(자리표 491 · 지금 활성인 같은 이름 140 제외)
- **보존 창 안에 실제로 거래된 것 22**, 그중 `stock`에 있는 것 **0**
- 거래대금 상위는 `CADE` 14.7B · `IAC` 7.4B · `CIVI` 4.8B이고 **나머지는 1B 미만**이라,
  상위 1,000에 들었을 법한 것은 **셋 안팎**이다

**MUST NOT: 22를 「복원이 놓친 종목 수」로 읽지 않는다** — 그중 몇이 상위 1,000이었는지는
**주식수 없이 못 가른다.** 이것은 **상한**이다.

**MUST: 활성 그림자를 빼고 센다.** 첫 판이 `_DELISTED` 접미만 떼고 세어 **22를 162로 일곱 배**
부풀렸다 — 뗀 이름이 지금 활성인 심볼과 겹치면 그 봉은 **활성 종목의 것**이다.

### 복원이 재무·원장보다 먼저 돌면 명부가 그만큼 틀린다 (2026-09-14)

**실측: 미국 1,740행 중 1,119행(64%)이 달라졌다.** 2026-09-10 복원 뒤에 ①미국 재무를 창
11년으로 재적재하고(정정 7,741 · 채움 1,623) ②역분할 11종목이 원장에 들어온(2026-09-13)
상태에서 같은 잡을 다시 돌린 결과다. 국내도 같은 이유로 다시 돌렸다(KOSPI 301 · KOSDAQ 422).

**왜 그런가 — 복원은 시점을 지키지만 「우리가 가진 것」에 의존한다.**
`JdbcStoredShareCounts`의 질의가 `published_at <= asOf`로 **point-in-time을 제대로 지킨다.**
그런데 그 시점에 공시된 행이 **우리 DB에 들어와 있어야** 보이고, 주식수에 걸린 기업행위 보정
`exp(sum(ln(ca.ratio)))`은 **원장이 비어 있으면 곱할 것이 없다.** 그래서 적재와 원장이 늘면
같은 `asOf`가 다른 순위를 낸다.

**구체 사례 — LNZA.** 1:100 역분할(2025-08-19)이 원장에 들어온 것이 **2026-09-13**인데 복원은
**2026-09-10**에 돌았다. 보정 없는 주식수(231,995,967)로 시총이 100배 부풀어
**2025-09-26~11-07 구간에 `US_TOP1000` 편입**으로 기록됐다. 역분할 후 실제 시총은
2,320,216주 × 약 $13 = **$30M**이라 상위 1000에 들 수 없다.

**MUST NOT: 이것을 「복원 로직의 결함」으로 읽지 않는다.** 복원은 그때 가진 것으로 옳게
지었다. **가진 것이 늘면 다시 지어야 하는 것이 이 결정의 성질**이고, 그 규칙이 §규칙에
없었던 것이 결함이다. 이제 premise가 유니버스마다 그 상태를 잰다.

### 복원과 적재의 실측 (2026-09-10)

| 축 | 값 |
|---|---|
| 복원 구간 | `KOSPI_TOP200` 300 · `KOSDAQ_TOP150` 425 · `US_TOP1000` 1,740 = **2,465** |
| 격자 | KR 50점(`2025-08-22~2026-07-31`) · US 53점(`2025-09-03~2026-08-28`) |
| 보존 창의 「편입 0건」 거래일 | KR 231·231 · US 249 → **전부 0** |
| 복원이 만든 `stock` 행 | **109**(KOSPI 31 · KOSDAQ 78 · 미국 0) |
| 파생층 | 5,405,238 → **5,241,005**(시점화) → **5,333,684**(109종목 적재 뒤) · 시점화 한 판에서 **값이 달라진 행 3,789,445(70%)** |

**생존 편향을 값으로 잴 수 있게 됐다.** 오늘 명부 중 그날에도 편입이던 비율이 창 앞단에서
`KOSPI_TOP200` **91.0%** · `KOSDAQ_TOP150` **71.3%**다(평균 94.6% · 82.9%). 즉 고치기 전에는
2025-08-22 조회의 코스닥 후보 150개 중 **43개가 그날 편입이 아니었고** 진짜 편입 43개가 빠져 있었다.

**결손 구성원을 채웠다 — 예측을 맞혔다.** 109종목의 시세·재무·업종·지표를 넣어 창 앞단
판정불가가 **61 → 10**이 됐다(KOSPI 17→2 · KOSDAQ 44→8). 남는 10은 **시세 이력이 짧아 적재로
안 풀리는 쪽**이고, 그 진단은 채우기 전에 「61 중 51이 복원이 만든 종목」으로 이미 나와 있었다.

**MUST: 그 적재 경로는 `--tickers` 하나만 열었다.** `activeUniverseMemberIds`(일일 갱신 대상)를
같이 넓히면 1,350 → **1,709**가 되고(실측 +27%) 그 비용을 **매일** 문다. 창과 겹치는 편입을
대상으로 삼는 것은 **손으로 티커를 주는 경로**에서만 옳다.

### 화면으로 확인했다 (2026-09-10)

기준일 `2025-12-01` · 대상 `코스닥 시총 상위 150`에서 **결과 142 / 평가 142종목**이 나왔고,
DB의 「그날 편입 150 중 시총 있음 142」와 **정확히 일치**한다. **옛 동작이었다면 146이고
두 후보 집합이 34종목(23%) 다르다** — 시점화가 화면에서 실제로 작동한다.

**두 하한이 맞물린다.** 복원 뒤 편입 하한이 지표 하한과 **같아졌다**(KR·US 각각
`2025-08-22`·`2025-09-03`). 그래서 **편입 하한 인터록은 대기 가드**다 — 단일 유니버스에서는
지표 경계가 먼저 걸린다. **창이 복원 격자보다 앞으로 늘어나는 날 그것이 처음 문다.**

**`universeInfo.memberCount`를 시점화하지 않은 것은 무해하다 — 재서 확인했다.**
세 답: **무엇이 읽는가** = 결과 머리말의 「대상 … (N종목)」 · **무엇이 틀리는가** = 과거 날짜에
오늘의 편입 수가 뜬다 · **지금 닿는가** = **안 닿는다.** 상위 N 유니버스는 편입 수가 언제나 N이라
(보존 창 765칸 전부에서 **N이 아닌 날짜 0**) 오늘 수와 그날 수가 같다.
**결함이 되는 조건**: 편입 수가 N과 달라질 수 있는 유니버스가 생기는 날.

**쉬워지는 것**: 과거 기준일 조회가 그날의 명부로 답한다. 생존 편향의 크기를 **하한이 아니라 값으로**
잴 수 있게 된다(ADR-0071이 요구한 것이고, 복원 원장이 있으면 외부 콜 0으로 나온다).

**어려워지는 것**

- **적재 경로가 복원분에 안 닿는다.** `StockRegistrar`의 다섯 술어와 `IndicatorBackfillJob`의 둘이 전부
  `effective_to IS NULL`이라 **여섯이 다 0건을 낸다.** ADR-0071 (iii)가 같은 사실을 적어 뒀고 **그 문장은 아직
  참이다.** 결정 1의 「사본이 한 줄도 안 바뀐다」는 **이득이자 비용**이다 — 전제 18개를 공짜로 지키는 그
  성질이 적재 경로도 함께 잠근다. 여는 것은 별도 결정이고, 열 때 **일일 갱신 대상이 얼마나 커지는지를
  먼저 실측한다.**
- **판정불가 구성원이 응답에 실린다.** ADR-0087이 그 형태를 경고했다(*"지표가 하나도 없는 종목이 0점·전 조건
  판정불가로 응답에 그대로 실린다"*). **여기서는 의도한 것**이다 — 분모를 정직하게 만드는 값이다. 다만
  **화면이 「N종목 중 M종목만 판정 가능」을 말해야 한다.**
- **화면에 같은 사실이 두 곳에 다르게 그려진다.** 조회 대상 셀렉터는 「오늘 200종목」이고 결과는 「그날 M종목」이다.
  **둘이 다른 것이 사실이지만 나란히 놓이면 하나가 틀린 것으로 읽힌다** — 결과 쪽에 기준일을 붙인다.
- **업종에는 이력이 없다.** `stock.industry_group_key`가 오늘 값이라, 파생층 모집단을 시점화해도 **어느 층에
  묶이는가는 오늘 기준**이다. **크기를 재보지 않았다** — 재분류 이력이 DB에 없어 DB로는 못 재고 소스에 물어야 한다.
  같은 부류로 `stock.ticker`·`market_code`에도 이력이 없다(**재보지 않았다**).
- **상세 화면은 「지표는 그날 것, 지수 뱃지는 오늘 것」을 나란히 그린다.** `StockController`가 이미 `asOf`를
  받는데 `StockReader`의 지수 목록은 안 고쳤다. **결정이지 결함이 아니고, 안 적으면 다음 세션이 결함으로 신고한다.**
- **워치리스트·알림은 안 닿는다**(확인함) — `WatchlistAlertRunner`가 종목별 시장 최신 지표 일자를 쓰고
  유니버스 술어를 안 탄다.

**되돌리려면**: `DELETE FROM universe_membership WHERE origin = 'RESTORED'`를 먼저 돌리고, 그 다음 복원이 만든
`stock` 행(어느 편입에도·시세에도·재무에도·지표에도 안 걸리는 행)을 지운다. **순서를 뒤집으면 FK로 정리가
중단된다.** 읽기 경로와 계산기는 revert이고, 파생층은 창 전체 백필을 한 번 더 돌린다.
