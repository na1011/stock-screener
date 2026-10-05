-- 대조군: 09-28(#683) 현 편입 중 09-19~09-27 공시가 저장된 종목 — #683이 그 사이 공시를 받았는가
SELECT count(DISTINCT f.stock_id) n, string_agg(DISTINCT s.ticker, ',') tickers
FROM financial_statement f JOIN stock s ON s.id=f.stock_id
WHERE s.market_code='US' AND f.published_at BETWEEN '2026-09-19' AND '2026-09-27'
  AND EXISTS (SELECT 1 FROM universe_membership m WHERE m.stock_id=s.id AND m.universe_code='US_TOP1000'
              AND m.effective_from <= '2026-09-28' AND (m.effective_to IS NULL OR m.effective_to > '2026-09-28'));
