-- stock(id)를 참조하는 표 전수 — 대표 교체가 닿는 저장 행의 소비자
SELECT conrelid::regclass AS tbl, conname FROM pg_constraint WHERE contype='f' AND confrelid='stock'::regclass ORDER BY 1;
