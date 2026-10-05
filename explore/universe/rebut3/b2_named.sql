SELECT s.ticker, s.market_code, s.is_active, s.name, m.universe_code, m.origin, m.effective_from, m.effective_to, m.created_at::timestamp(0)
FROM stock s JOIN universe_membership m ON m.stock_id=s.id
WHERE s.ticker IN ('280360','092870','950260','GOSS','KOD','IOVA','CVI','INSW','GRAL','TEM','BLLN','BETA')
  AND m.universe_code<>'US_LISTED'
ORDER BY s.ticker, m.effective_from;
