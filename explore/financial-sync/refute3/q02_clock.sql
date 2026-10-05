-- 0콜 · 운영 SELECT: 0부 재구성(target_count >= 그 시각 열린 활성 편입 수)과 25일 경계(toDays > 25)
-- G #632 → 10-16 14:15 = 25d 22:56 (연체 아님) · FSR #415 → 10-12 14:15 = 28d · 10-09 = 25d
select (timestamptz '2026-10-16 14:15+09' - (select started_at from batch_execution where id=632)) g_1016,
       (timestamptz '2026-10-12 14:15+09' - (select started_at from batch_execution where id=415)) fsr_1012,
       (timestamptz '2026-10-07 14:15+09' - (select started_at from batch_execution where id=191)) fsr_1007_if_415_narrow;
select id, status, target_count, success_count, failure_count, left(note,40) from batch_execution where id in (190,191,410,415,643,645,646,647,648,663,664,683);
