SET statement_timeout='60s';

WITH ctrl(t, namepat) AS (VALUES
 ('AAPL','APPLE INC'),('MSFT','MICROSOFT'),('NVDA','NVIDIA'),('GOOGL','ALPHABET'),('AMZN','AMAZON.COM'),
 ('META','META PLATFORMS'),('BRK.B','BERKSHIRE HATHAWAY'),('LLY','ELI LILLY'),('AVGO','BROADCOM'),
 ('JPM','JPMORGAN'),('TSLA','TESLA'),('WMT','WALMART'),('V','VISA INC'),('XOM','EXXON'),
 ('UNH','UNITEDHEALTH'),('MA','MASTERCARD'),('PG','PROCTER'),('JNJ','JOHNSON & JOHNSON'),
 ('COST','COSTCO'),('HD','HOME DEPOT'),('ORCL','ORACLE'),('ABBV','ABBVIE'),('KO','COCA-COLA CO'),
 ('MRK','MERCK'),('BAC','BANK OF AMERICA'),('CVX','CHEVRON'),('PEP','PEPSICO'),('AMD','ADVANCED MICRO'),
 ('ADBE','ADOBE'),('CRM','SALESFORCE'),('NFLX','NETFLIX'),('TMO','THERMO FISHER'),('LIN','LINDE'),
 ('ACN','ACCENTURE'),('MCD','MCDONALD'),('CSCO','CISCO'),('ABT','ABBOTT'),('WFC','WELLS FARGO'),
 ('DIS','WALT DISNEY'),('INTC','INTEL'),('QCOM','QUALCOMM'),('TXN','TEXAS INSTRUMENTS'),
 ('CAT','CATERPILLAR'),('GE','GE AEROSPACE'),('UNP','UNION PACIFIC'),('NKE','NIKE'),('RTX','RTX'),
 ('LOW','LOWE'),('HON','HONEYWELL'),('GS','GOLDMAN SACHS'),('BLK','BLACKROCK'),('DE','DEERE'),
 ('PFE','PFIZER'),('C','CITIGROUP'),('MS','MORGAN STANLEY'),('LMT','LOCKHEED'),('MMC','MARSH'),
 ('PLD','PROLOGIS'),('EQIX','EQUINIX'),('NTRS','NORTHERN TRUST'),('ET','ENERGY TRANSFER'),
 ('TPL','TEXAS PACIFIC LAND'),('CW','CURTISS-WRIGHT'),('RGLD','ROYAL GOLD'),('DLB','DOLBY')),
mem AS (SELECT s.ticker FROM universe_membership m JOIN stock s ON s.id = m.stock_id
        WHERE m.universe_code = 'US_TOP1000' AND m.effective_to IS NULL),
hit AS (SELECT c.t, c.namepat,
          coalesce((SELECT string_agg(s2.ticker, '/') FROM stock s2
                     WHERE s2.market_code = 'US' AND upper(s2.name) LIKE '%' || c.namepat || '%'
                       AND EXISTS (SELECT 1 FROM mem WHERE mem.ticker = s2.ticker)),
                   CASE WHEN EXISTS (SELECT 1 FROM mem WHERE mem.ticker = c.t) THEN c.t END) AS found
        FROM ctrl c),
why AS (SELECT h.t,
          CASE
            WHEN NOT EXISTS (SELECT 1 FROM stock s WHERE s.market_code = 'US' AND s.ticker = h.t)
              THEN '소스에 그 티커가 없다'
            WHEN NOT EXISTS (SELECT 1 FROM financial_statement f JOIN stock s ON s.id = f.stock_id
                              WHERE s.market_code = 'US' AND s.ticker = h.t AND f.is_latest
                                AND f.shares_outstanding IS NOT NULL AND f.shares_outstanding > 0
                                AND f.period_end >= current_date - interval '2 years')
              THEN '주식수결손'
            WHEN (SELECT s.sic_code FROM stock s WHERE s.market_code = 'US' AND s.ticker = h.t)
                 IN ('6798','6770') THEN 'REIT/SPAC'
            ELSE '설명되지 않음'
          END AS reason
        FROM hit h WHERE h.found IS NULL)
SELECT CASE
  WHEN (SELECT count(*) FROM mem) = 0
    THEN '잴 것이 없었다 — US_TOP1000에 편입 종목이 없다'
  WHEN (SELECT count(*) FROM why WHERE reason = '설명되지 않음') > 0
    THEN '대형주 ' || (SELECT count(*) FROM why WHERE reason = '설명되지 않음')
         || '종목이 설명 없이 빠졌다: '
         || (SELECT string_agg(t, ', ' ORDER BY t) FROM why WHERE reason = '설명되지 않음')
         || ' — 배제축(통화·이름·CIK중복·종가)이 사업회사를 먹고 있다. 이 결함은 화면에도 '
         || '로그에도 안 나타나므로 여기가 유일한 신호다'
  ELSE 'OK 대조군 ' || (SELECT count(*) FROM ctrl) || ' 중 ' || (SELECT count(*) FROM hit WHERE found IS NOT NULL)
       || '이 편입됐고, 빠진 ' || (SELECT count(*) FROM why) || '은 전부 설명된다 ('
       || (SELECT string_agg(reason || ' ' || n, ', ' ORDER BY reason)
           FROM (SELECT reason, count(*) n FROM why GROUP BY reason) g) || ')'
END;
