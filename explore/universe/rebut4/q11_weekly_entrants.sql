-- B 정상 상태 부하: 관측 리밸런싱마다 새로 연 행 수(= 그 주 F6 대상의 상한 — 술어상 새 행은 전부 그 행 시작 이후 동기화가 없다)
SELECT effective_from, universe_code, count(*) opened
FROM universe_membership WHERE origin='OBSERVED' AND universe_code IN ('US_TOP1000','KOSPI_TOP200','KOSDAQ_TOP150')
  AND effective_from > (SELECT min(effective_from) FROM universe_membership m2 WHERE m2.universe_code=universe_membership.universe_code AND m2.origin='OBSERVED')
GROUP BY 1,2 ORDER BY 1,2;
