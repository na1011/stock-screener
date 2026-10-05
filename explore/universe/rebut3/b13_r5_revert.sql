-- R5 「10-02 관측 단계 되돌리기」가 사본에서 건드릴 행: 유니버스별 · 열린 날/닫힌 날 = 10-02
SELECT universe_code, origin, 'from_1002' kind, count(*), min(created_at)::timestamp(0), max(created_at)::timestamp(0)
FROM universe_membership WHERE effective_from='2026-10-02' GROUP BY 1,2
UNION ALL
SELECT universe_code, origin, 'to_1002', count(*), min(created_at)::timestamp(0), max(created_at)::timestamp(0)
FROM universe_membership WHERE effective_to='2026-10-02' GROUP BY 1,2
ORDER BY 1,3;
