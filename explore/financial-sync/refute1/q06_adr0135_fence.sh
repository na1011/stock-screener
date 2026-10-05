/opt/homebrew/opt/postgresql@16/bin/psql -d stock_screener -At -c "
WITH r AS (
  -- 유니버스마다 복원 시각이 다르다. 하나로 접으면 가장 오래된 것이 전부를 낡은 것으로 만든다.
  SELECT universe_code uc, min(created_at) restored_at FROM universe_membership
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
       AND f.shares_outstanding IS NOT NULL) fin,
    -- 사정거리: 복원 뒤에 생긴 행만 센다. 판정이 옛 행의 상태를 APPLIED로 바꾼 것은 못 센다
    -- (2026-10-05 HON 2026-06-29 병합 0.5 · 국내 인적분할 5행). 그날 HON의 고친 시총 순위는
    -- 168~175이고 경계(1,000위)가 약 55억 달러라 미국 명부는 안 바뀐다. 국내 복원은 원장을
    -- 안 읽는다(원장 보정은 JdbcStoredShareCounts 안에 있고 그것을 읽는 것은 미국 어댑터 하나다).
    (SELECT count(*) FROM corporate_action ca JOIN stock s ON s.id = ca.stock_id
     WHERE s.market_code = mk.mc AND ca.status = 'APPLIED'
       AND ca.ratio IS NOT NULL AND ca.ratio <> 1 AND ca.created_at > r.restored_at) ledger
  FROM r JOIN mk ON mk.uc = r.uc)
SELECT CASE
  WHEN (SELECT count(*) FROM r) = 0 THEN '잴 것이 없었다 — 복원 행이 하나도 없다'
  WHEN (SELECT count(*) FROM stale WHERE fin > 0 OR ledger > 0) = 0
    THEN 'OK 복원 ' || (SELECT count(*) FROM r) || '곳 전부 그 뒤에 들어온 재무·원장이 없다'
  ELSE (SELECT string_agg(uc || '(' || d || ' 뒤 재무 ' || fin || '행·원장 ' || ledger || '건)', ', ' ORDER BY uc)
        FROM stale WHERE fin > 0 OR ledger > 0)
       || ' — 복원의 순위 재료가 그 둘이라 과거 명부가 실제와 갈린다. universe-restore를 '
       || '다시 돌리고 파생층을 백필한다(§규칙).'
  END"
