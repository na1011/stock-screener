-- B-1 「초기 적재가 종목 성공마다 쓴다」: 초기 적재가 재무 단계를 돌았고(FINANCIAL 단계 성공>0) 그 실행이 이미 재무가 있던 종목을 덮은 흔적(정정>0)
SELECT b.id, b.triggered_by, b.target_count, b.started_at::timestamp(0), p.success_count fin_ok, p.failure_count fin_fail, left(b.note,60) note
FROM batch_execution b JOIN batch_execution_phase p ON p.execution_id=b.id AND p.phase='FINANCIAL'
WHERE b.job_name='initial-load' AND p.success_count>0 ORDER BY b.id;
-- FR 실행 중 연도 창이 8년보다 좁은 것(「종목 성공 = 동기화」로 셀 수 없는 실행)
SELECT id, triggered_by, target_count, started_at::timestamp(0), substring(note from '연도=([0-9]+~[0-9]+)') yrs
FROM batch_execution WHERE job_name='financial-refill' AND note ~ '연도=' 
  AND (substring(note from '연도=([0-9]+)~([0-9]+)'))::int > extract(year from started_at)::int - 8 ORDER BY id;
