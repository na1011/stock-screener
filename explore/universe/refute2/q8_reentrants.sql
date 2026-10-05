-- 관측 리밸런싱마다 새 편입 중 「창 안에 앞선 편입이 있던 재편입」 수 (꼬리 FR이 무효화하면 지표 행을 잃는 쪽)
-- 와 「재무 0행이 아니었던」 수(지금 꼬리의 FINANCIAL 축이 안 잡는 쪽)
SELECT m.effective_from AS d, m.universe_code, count(*) AS added,
  count(*) FILTER (WHERE EXISTS (SELECT 1 FROM universe_membership p WHERE p.stock_id=m.stock_id AND p.universe_code=m.universe_code AND p.effective_to IS NOT NULL AND p.effective_to <= m.effective_from AND p.effective_to > '2025-09-26')) AS reentrant_in_window,
  count(*) FILTER (WHERE EXISTS (SELECT 1 FROM financial_statement f WHERE f.stock_id=m.stock_id AND f.created_at < m.created_at)) AS had_fin_before
FROM universe_membership m
WHERE m.origin='OBSERVED' AND m.effective_from > '2026-08-31'
GROUP BY 1,2 ORDER BY 1,2;
